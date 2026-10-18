#!/usr/bin/env python3
"""Standard-library implementation of build.sh; keep signing secrets out of argv/logs."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SIGNING_KEYS = (
    "AGENTT_RELEASE_STORE_FILE", "AGENTT_RELEASE_STORE_PASSWORD",
    "AGENTT_RELEASE_KEY_ALIAS", "AGENTT_RELEASE_KEY_PASSWORD",
)


class BuildError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise BuildError(message)


def read_json(path):
    require(path.is_file(), "缺少文件：" + str(path))
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (ValueError, UnicodeError):
        raise BuildError("JSON 格式无效：" + str(path)) from None


def resolve_path(value, root):
    path = Path(value).expanduser()
    return (path if path.is_absolute() else root / path).resolve()


def app_config(root):
    source = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    result = {}
    for key in ("applicationId", "versionName", "buildToolsVersion"):
        match = re.search(r'\b' + key + r'\s*=\s*"([^"\n]+)"', source)
        require(match, "无法从 app/build.gradle.kts 读取 " + key)
        result[key] = match.group(1)
    for key in ("versionCode", "minSdk", "targetSdk", "compileSdk"):
        match = re.search(r"\b" + key + r"\s*=\s*(\d+)\b", source)
        require(match, "无法从 app/build.gradle.kts 读取 " + key)
        result[key] = int(match.group(1))
    require(re.fullmatch(r"[A-Za-z0-9._-]+", result["versionName"]), "versionName 不适合用作产物文件名")
    try:
        names = ET.parse(root / "app/src/main/res/values/strings.xml").getroot().findall("string[@name='app_name']")
    except (OSError, ET.ParseError):
        raise BuildError("无法从默认 strings.xml 读取 App 名称 app_name") from None
    require(len(names) == 1, "默认 strings.xml 必须提供唯一的 app_name")
    result["appName"] = "".join(names[0].itertext()).strip()
    return result


def property_sdk(root):
    path = root / "local.properties"
    if not path.is_file():
        return None
    match = re.search(r"^\s*sdk\.dir\s*[:=]\s*(.*?)\s*$", path.read_text(encoding="utf-8"), re.M)
    if not match:
        return None
    # Android Studio writes Java-properties escaping (spaces, colons, backslashes).
    value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), match.group(1))
    value = re.sub(r"\\(.)", r"\1", value)
    return resolve_path(value, root)


def find_sdk(root, explicit, env, host):
    local = property_sdk(root)
    if explicit:
        sdk = resolve_path(explicit, root)
        require(not local or local == sdk, "--sdk 与 local.properties 的 sdk.dir 不一致；请先统一配置")
        return sdk
    if local:
        return local
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if env.get(key):
            return resolve_path(env[key], root)
    suffix = "Library/Android/sdk" if host == "Darwin" else "Android/Sdk"
    return Path.home() / suffix


def java_major(output):
    match = re.search(r'(?:openjdk|java) version "(\d+)(?:\.(\d+))?', output)
    require(match, "无法识别 java -version 的结果")
    return int(match.group(2)) if match.group(1) == "1" else int(match.group(1))


def find_java(root, explicit, env, host):
    configured = explicit or env.get("JAVA_HOME")
    candidates = []
    if configured:
        candidates.append(resolve_path(configured, root))
    else:
        # PATH covers package-manager JDKs on Linux and many macOS installations.
        binary = shutil.which("javac", path=env.get("PATH"))
        if binary:
            candidates.append(Path(binary).resolve().parent.parent)
        if host == "Darwin":
            java_home = Path("/usr/libexec/java_home")
            if java_home.is_file():
                probe = subprocess.run([str(java_home), "-v", "17+"], capture_output=True, text=True)
                if probe.returncode == 0:
                    candidates.append(Path(probe.stdout.strip()))
            candidates.extend([
                Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home"),
                Path.home() / "Applications/Android Studio.app/Contents/jbr/Contents/Home",
            ])
            candidates.extend(sorted((Path.home() / ".jdks").glob("*/Contents/Home"), reverse=True))
            candidates.extend(sorted(Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home"), reverse=True))
        else:
            candidates.extend([Path("/opt/android-studio/jbr"), Path.home() / "android-studio/jbr"])
            candidates.extend(sorted(Path("/usr/lib/jvm").glob("*"), reverse=True))
            candidates.extend(sorted((Path.home() / ".jdks").glob("*"), reverse=True))
    for home in candidates:
        if not all((home / "bin" / tool).is_file() for tool in ("java", "javac", "keytool")):
            continue
        probe = subprocess.run([str(home / "bin/java"), "-version"], capture_output=True, text=True)
        if probe.returncode == 0:
            major = java_major(probe.stdout + probe.stderr)
            if major >= 17:
                return home.resolve(), major
    raise BuildError("需要完整 JDK 17+（推荐 21）；请设置 JAVA_HOME 或 --java-home，见 build.md")


def fingerprint(value):
    require(isinstance(value, str), "签名证书 SHA-256 格式无效")
    value = value.strip().replace(":", "").upper()
    require(re.fullmatch(r"[0-9A-F]{64}", value), "签名证书 SHA-256 格式无效")
    return value


def load_signing(root, signing_root, env):
    metadata = read_json(signing_root / "release-signing.json")
    require(isinstance(metadata, dict) and metadata.get("schemaVersion") == 2,
            "签名元数据须为 schemaVersion=2，见 build.md")
    for key in ("storeFile", "keyAlias", "certificateSha256"):
        require(isinstance(metadata.get(key), str) and metadata[key].strip(), "签名元数据缺少 " + key)
    pin_file = signing_root / "pinned-certificate.sha256"
    require(pin_file.is_file(), "缺少证书指纹 pin：" + str(pin_file))
    certificate = fingerprint(metadata["certificateSha256"])
    require(certificate == fingerprint(pin_file.read_text(encoding="utf-8-sig")), "签名元数据与 pinned-certificate.sha256 不一致")
    store = resolve_path(metadata["storeFile"], root)
    require(store.is_file(), "签名密钥库不存在：" + str(store))
    require(metadata.get("credentialFormat") == "text", "Mac / Linux 需要 text 密码文件；Windows DPAPI/clixml 无法跨平台读取，见 build.md")

    def password_file(key):
        require(isinstance(metadata.get(key), str) and metadata[key], "签名元数据缺少 " + key)
        path = resolve_path(metadata[key], root)
        require(path.is_file(), "密码文件不存在：" + str(path))
        password = path.read_text(encoding="utf-8").rstrip("\r\n")
        require(password and "\x00" not in password and "\n" not in password and "\r" not in password,
                "密码文件须包含一行非空密码：" + str(path))
        return password

    password = password_file("credentialFile")
    same_password = metadata.get("keyPasswordSameAsStore", True)
    require(isinstance(same_password, bool), "keyPasswordSameAsStore 须为布尔值")
    key_password = password if same_password else password_file("keyCredentialFile")
    env.update(dict(zip(SIGNING_KEYS, (str(store), password, metadata["keyAlias"], key_password))))
    return certificate


def capture(command, env, label, binary=False):
    result = subprocess.run([str(x) for x in command], env=env, capture_output=True)
    # Tool failures never echo credentials or raw signing configuration.
    require(result.returncode == 0, label + "失败（退出码 " + str(result.returncode) + "）")
    return result.stdout if binary else (result.stdout + result.stderr).decode("utf-8", errors="replace")


def validate_signing_key(java_home, env, certificate):
    der = capture([java_home / "bin/keytool", "-exportcert", "-alias", env[SIGNING_KEYS[2]],
                   "-keystore", env[SIGNING_KEYS[0]], "-storepass:env", SIGNING_KEYS[1]],
                  env, "读取密钥库证书", binary=True)
    require(hashlib.sha256(der).hexdigest().upper() == certificate, "密钥库证书与固定指纹不一致；构建已停止")
    capture([java_home / "bin/keytool", "-certreq", "-alias", env[SIGNING_KEYS[2]],
             "-keystore", env[SIGNING_KEYS[0]], "-storepass:env", SIGNING_KEYS[1],
             "-keypass:env", SIGNING_KEYS[3]], env, "原私钥解密与签名校验", binary=True)


def gradle_command(root, java_home, args, offline):
    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    require(wrapper.is_file(), "缺少 Gradle Wrapper JAR，请恢复仓库文件")
    variant = "Release" if args.release else "Debug"
    command = [str(java_home / "bin/java"), "-classpath", str(wrapper),
               "org.gradle.wrapper.GradleWrapperMain", "--no-daemon", "--console=plain"]
    if offline:
        command.append("--offline")
    if args.clean:
        command.append(":app:clean")
    if not args.skip_checks:
        command.extend([":app:testDebugUnitTest", ":app:lintVitalRelease" if args.release else ":app:lintDebug"])
    if args.generate_baseline_profile:
        command.extend([":app:generateReleaseBaselineProfile", "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile"])
    command.append(":app:assemble" + variant)
    if args.performance_review:
        command.extend([":performance:connectedBenchmarkReleaseAndroidTest", "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark"])
    if args.performance_review or args.generate_baseline_profile:
        command.append("-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR")
    return command


def run_gradle(command, root, env):
    secrets = {env[key] for key in (SIGNING_KEYS[1], SIGNING_KEYS[3]) if env.get(key)}
    with subprocess.Popen(command, cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, errors="replace") as process:
        try:
            for line in process.stdout:
                for secret in secrets:
                    line = line.replace(secret, "[REDACTED]")
                print(line, end="", flush=True)
            code = process.wait()
        except KeyboardInterrupt:
            process.terminate()
            process.wait()
            raise
    require(code == 0, "Gradle 构建失败（退出码 " + str(code) + "），不会导出产物")


def elf_alignments(data, name):
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", "无效的 arm64 ELF：" + name)
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    require(size >= 56 and offset + size * count <= len(data), "ELF 程序头越界：" + name)
    alignments = []
    for i in range(count):
        p = offset + i * size
        if struct.unpack_from("<I", data, p)[0] == 1:  # PT_LOAD
            file_offset, address = struct.unpack_from("<QQ", data, p + 8)
            alignment = struct.unpack_from("<Q", data, p + 48)[0]
            require(alignment >= 16384 and alignment & (alignment - 1) == 0
                    and (address - file_offset) % 16384 == 0, "ELF 不满足 16 KiB 对齐：" + name)
            alignments.append(alignment)
    require(alignments, "ELF 缺少加载段：" + name)
    return alignments


def verify_archive(apk, release):
    elf = []
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, "APK ZIP CRC 校验失败")
        if release:
            libraries = [x for x in archive.namelist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", x)]
            require(libraries and {x.split("/")[1] for x in libraries} == {"arm64-v8a"}, "Release 须仅包含 arm64-v8a 原生库")
            for name in libraries:
                elf.append({"library": name, "loadSegmentAlignments": elf_alignments(archive.read(name), name)})
            for name in archive.namelist():
                if re.fullmatch(r"classes\d*\.dex", name):
                    data = archive.read(name)
                    require(not any(x in data for x in (b"UiReviewActivity", b"ReviewFixture", b"PerformanceFixtureActivity", b"org/robolectric/", b"androidx/benchmark/", b"androidx/test/runner/")),
                            "Release 混入 Debug 验收代码或测试运行库")
    return elf


def verify_application_profiles(root, apk):
    if not (root / "performance/build.gradle.kts").is_file():
        return None
    folder = root / "app/src/release/generated/baselineProfiles"
    sources = {}
    for name in ("baseline-prof.txt", "startup-prof.txt"):
        path = folder / name
        require(path.is_file() and path.stat().st_size > 0, "缺少应用编译配置，请显式运行 --generate-baseline-profile：" + str(path))
        source = path.read_text(encoding="utf-8")
        require("Lcom/lamuier/agenT/MainActivity;" in source, "编译配置未覆盖应用启动入口：" + name)
        require("Lcom/lamuier/agenT/qa/" not in source, "应用编译配置混入验收夹具")
        sources[name] = hashlib.sha256(path.read_bytes()).hexdigest().upper()
    with zipfile.ZipFile(apk) as archive:
        sizes = {}
        for name, magic in (("baseline.prof", b"pro\0"), ("baseline.profm", b"prm\0")):
            path = "assets/dexopt/" + name
            require(path in archive.namelist(), "Release 缺少编译配置资产：" + path)
            data = archive.read(path)
            require(len(data) > 8 and data.startswith(magic), "Release 编译配置资产格式错误：" + path)
            sizes[name] = len(data)
    return {"sourceSha256": sources, "assetBytes": sizes}


def verify_apk(root, apk, tools, env, config, release, certificate):
    require(apk.is_file(), "未生成 APK：" + str(apk))
    signing = capture([tools / "apksigner", "verify", "--verbose", "--print-certs", apk], env, "APK 签名校验")
    certificates = re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", signing)
    require(certificates, "无法读取 APK 签名证书")
    if release:
        require(len(certificates) == 1 and fingerprint(certificates[0]) == certificate, "APK 未使用固定的原签名证书")
    capture([tools / "zipalign", "-c", "-P", "16", "-v", "4", apk], env, "APK 16 KiB ZIP 对齐校验")
    badging = capture([tools / "aapt", "dump", "badging", apk], env, "APK 包信息校验")
    package = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    version = config["versionName"] + ("" if release else "-debug")
    for key, value in (("name", config["applicationId"]), ("versionCode", config["versionCode"]), ("versionName", version)):
        require(key + "='" + str(value) + "'" in package, "APK " + key + " 与源码声明不一致")
    for key, value in (("sdkVersion", config["minSdk"]), ("targetSdkVersion", config["targetSdk"])):
        require(key + ":'" + str(value) + "'" in badging, "APK " + key + " 与源码声明不一致")
    if release:
        permissions = re.findall(r"uses-permission: name='([^']+)'", capture([tools / "aapt", "dump", "permissions", apk], env, "APK 权限校验"))
        allowed = {"android.permission." + name for name in (
            "INTERNET", "ACCESS_NETWORK_STATE", "USE_BIOMETRIC", "USE_FINGERPRINT",
            "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "POST_PROMOTED_NOTIFICATIONS",
        )} | {config["applicationId"] + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
        require(set(permissions) <= allowed, "Release 出现未审核权限：" + ", ".join(sorted(set(permissions) - allowed)))
        require({"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"} <= set(permissions), "Release 缺少网络权限")
        manifest = capture([tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml"], env, "APK Manifest 校验")
        require(not re.search(r"android:debuggable[^\n]*(?:0xffffffff|0x1\b)", manifest), "Release APK 可调试")
        require(not any(x in manifest for x in ("UiReviewActivity", "PreviewActivity", "PerformanceFixtureActivity")), "Release Manifest 混入 Debug 入口")
        for attribute in ("allowBackup", "usesCleartextTraffic"):
            require(re.search(r"android:" + attribute + r"[^\n]*0x0\b", manifest), "Release " + attribute + " 须为 false")
        source = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        require('android:networkSecurityConfig="@xml/network_security_config"' in source
                and "android:networkSecurityConfig" in manifest, "缺少网络安全配置")
        for qualifier in ("xml", "xml-v37"):
            path = root / "app/src/main/res" / qualifier / "network_security_config.xml"
            require(path.is_file() and not re.search(r'cleartextTrafficPermitted\s*=\s*"true"', path.read_text(encoding="utf-8")), "网络配置允许明文或不存在：" + str(path))
    output = read_json(apk.parent / "output-metadata.json")
    elements = output.get("elements", [])
    require(output.get("applicationId") == config["applicationId"] and len(elements) == 1
            and elements[0].get("versionCode") == config["versionCode"] and elements[0].get("versionName") == version
            and elements[0].get("outputFile") == apk.name, "APK 输出元数据与源码不一致")
    if release:
        verify_application_profiles(root, apk)
    return verify_archive(apk, release)


def artifact_base(config, suffix="", official_release=False, started_at=None):
    name = config.get("appName")
    require(isinstance(name, str) and name == name.strip() and name and name not in (".", "..")
            and not re.search(r'[\x00-\x1f\x7f<>:"/\\|?*]', name) and not name.endswith((".", " ")),
            "App 名称不适合用作产物文件名")
    version = config["versionName"]
    require(re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?", version), "版本号不符合安全的 SemVer 文件命名规则")
    require(not suffix or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", suffix), "--artifact-suffix 仅允许字母、数字、点、下划线与短横线")
    require(not official_release or not suffix, "正式包不接受 --artifact-suffix；文件名使用 App 名称-版本号")
    base = name + "-" + version
    if official_release:
        return base
    started_at = started_at or datetime.now(timezone.utc)
    timestamp = started_at.astimezone(timezone(timedelta(hours=8))).strftime("%Y%m%d_%H%M%S")
    return base + "_" + timestamp + ("_" + suffix if suffix else "")


def ensure_destination(root, base):
    paths = [root / "dist" / (base + tail) for tail in (".apk", ".sha256", "-mapping.txt", "-build.json")]
    require(not any(path.exists() for path in paths), "dist/ 已有同名产物，拒绝覆盖；普通包稍后重试或添加 --artifact-suffix，正式包请先保留已有产物")


def export_release(root, apk, config, certificate, base, elf, checks, offline,
                   started_at=None, official_release=False):
    mapping = root / "app/build/outputs/mapping/release/mapping.txt"
    require(mapping.is_file(), "缺少 R8 mapping.txt，无法完成 Release 导出")
    ensure_destination(root, base)
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    sha = hashlib.sha256(apk.read_bytes()).hexdigest().upper()
    destination = dist / (base + ".apk")
    report = {
        "appName": config["appName"], "packageName": config["applicationId"], "versionCode": config["versionCode"],
        "versionName": config["versionName"], "apk": str(destination), "apkSha256": sha,
        "bytes": apk.stat().st_size, "certificateSha256": certificate,
        "builtAt": datetime.now(timezone.utc).isoformat(), "variant": "release",
        "buildStartedAt": (started_at or datetime.now(timezone.utc)).isoformat(),
        "officialRelease": official_release, "artifactTimeZone": "Asia/Shanghai",
        "minSdk": config["minSdk"], "targetSdk": config["targetSdk"],
        "platform": platform.system(), "offline": offline,
        "checks": {"unitTests": checks, "lintVitalRelease": checks, "signature": True,
                   "zipalign16KiB": True, "crc": True, "arm64Only": True,
                   "debugFixturesAbsent": True, "manifestAndPermissions": True}, "elf": elf,
    }
    profiles = verify_application_profiles(root, apk)
    if profiles is not None:
        report["applicationProfiles"] = profiles
        report["checks"]["applicationProfiles"] = True
    # Stage all files after successful verification; failed builds never copy stale APKs.
    with tempfile.TemporaryDirectory(prefix=".build-", dir=dist) as staging:
        stage = Path(staging)
        shutil.copy2(apk, stage / destination.name)
        shutil.copy2(mapping, stage / (base + "-mapping.txt"))
        (stage / (base + ".sha256")).write_text(sha + "  " + destination.name + "\n", encoding="utf-8")
        (stage / (base + "-build.json")).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for path in stage.iterdir():
            path.replace(dist / path.name)
    print("Release 校验通过，导出：" + str(destination), flush=True)
    print("SHA-256：" + sha)


def parser():
    result = argparse.ArgumentParser(prog="./build.sh", description="agenT Mac / Linux 标准构建入口（详见 build.md）")
    mode = result.add_mutually_exclusive_group()
    mode.add_argument("--debug", action="store_true", help="Debug 构建（默认）")
    mode.add_argument("--release", action="store_true", help="签名 Release 构建、校验与导出")
    result.add_argument("--official-release", action="store_true", help="正式发版命名：App 名称-版本号，不带日期时间；须同时使用 --release")
    network = result.add_mutually_exclusive_group()
    network.add_argument("--offline", action="store_true", help="离线依赖解析")
    network.add_argument("--online", action="store_true", help="允许下载依赖（Debug 默认；Release 需显式启用）")
    result.add_argument("--doctor", action="store_true", help="只检查环境；加 --release 检查签名配置")
    result.add_argument("--clean", action="store_true", help="先执行 :app:clean")
    result.add_argument("--skip-checks", action="store_true", help="显式跳过单元测试和 Lint；APK 校验仍执行")
    result.add_argument("--java-home", help="指定 JDK 17+ 根目录")
    result.add_argument("--sdk", help="指定 Android SDK 根目录（须与 local.properties 一致）")
    result.add_argument("--signing-root", default=".signing", help="签名元数据目录；默认 .signing")
    result.add_argument("--artifact-suffix", default="", help="普通 Release 的时间戳后追加标识；正式包不支持")
    result.add_argument("--generate-baseline-profile", action="store_true", help="显式在指定任务模拟器生成编译配置；须同时使用 --release")
    result.add_argument("--performance-review", action="store_true", help="显式在指定任务模拟器测量冷启动和滚动；须同时使用 --release")
    return result


def main(argv=None, root=ROOT):
    args = parser().parse_args(argv)
    require(sys.version_info >= (3, 9), "需要 Python 3.9+")
    host = platform.system()
    require(host in ("Darwin", "Linux"), "build.sh 支持 macOS / Linux；Windows 请使用 build.ps1")
    require(args.release or not args.artifact_suffix, "--artifact-suffix 仅用于 --release")
    require(args.release or not args.official_release, "--official-release 须同时使用 --release")
    require(args.release or not (args.performance_review or args.generate_baseline_profile), "性能参数须同时使用 --release")
    require(not (args.performance_review and args.generate_baseline_profile), "生成编译配置与性能测量须分次运行")
    config = app_config(root)
    started_at = datetime.now(timezone.utc)
    base = artifact_base(config, args.artifact_suffix, args.official_release, started_at) if args.release else None
    env = os.environ.copy()
    # Always use the selected metadata; partial/stale caller signing env must not affect Debug.
    for key in SIGNING_KEYS:
        env.pop(key, None)
    # Standard builds always cover the complete UI matrix, even after a local preview run.
    for key in ("AGENT_UI_REVIEW_SMOKE", "AGENT_UI_REVIEW_SCENES"):
        env.pop(key, None)
    java_home, major = find_java(root, args.java_home, env, host)
    sdk = find_sdk(root, args.sdk, env, host)
    env.update(JAVA_HOME=str(java_home), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    compile_sdk = config["compileSdk"]
    require(any((sdk / "platforms" / ("android-" + version) / "android.jar").is_file()
                for version in (str(compile_sdk), str(compile_sdk) + ".0")),
            "缺少 Android SDK Platform " + str(compile_sdk) + "：" + str(sdk))
    tools = sdk / "build-tools" / config["buildToolsVersion"]
    for tool in ("aapt", "apksigner", "zipalign"):
        path = tools / tool
        require(path.is_file() and os.access(path, os.X_OK), "缺少可执行 SDK 工具：" + str(path))
    offline = args.offline or (args.release and not args.online)
    command = gradle_command(root, java_home, args, offline)
    print("JDK：" + str(java_home) + "（" + str(major) + "）", flush=True)
    print("Android SDK：" + str(sdk), flush=True)
    certificate = None
    try:
        if args.release:
            certificate = load_signing(root, resolve_path(args.signing_root, root), env)
            validate_signing_key(java_home, env, certificate)
            print("Release 原签名证书校验通过", flush=True)
        if args.doctor:
            print("环境检查通过；未执行构建或下载依赖", flush=True)
            return 0
        performance_device = None
        if args.performance_review or args.generate_baseline_profile:
            from performance_support import emulator_environment
            performance_device = emulator_environment(sdk, env)
        if args.release:
            ensure_destination(root, base)
        if args.skip_checks:
            print("已显式跳过单元测试和 Lint；APK 产物校验仍执行", flush=True)
        print("流程：" + ", ".join(x for x in command if x.startswith(":app:"))
              + ("（离线）" if offline else "（允许联网）"), flush=True)
        run_gradle(command, root, env)
        if args.performance_review:
            from performance_support import collect_reports
            collect_reports(root, performance_device, env)
        variant = "release" if args.release else "debug"
        apk = root / "app/build/outputs/apk" / variant / ("app-" + variant + ".apk")
        elf = verify_apk(root, apk, tools, env, config, args.release, certificate)
        if args.release:
            export_release(root, apk, config, certificate, base, elf, not args.skip_checks, offline,
                           started_at, args.official_release)
        else:
            print("Debug 构建与 APK 校验通过：" + str(apk), flush=True)
        return 0
    finally:
        for key in SIGNING_KEYS:
            env.pop(key, None)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n构建已中断", file=sys.stderr)
        sys.exit(130)
    except (BuildError, OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        print("构建失败：" + str(error), file=sys.stderr)
        sys.exit(1)
