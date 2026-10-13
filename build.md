# agenT 标准构建流程

macOS 和 Linux 共用根目录的 `build.sh`，Windows 使用 `build.ps1`。`build.sh` 使用 Bash 和 Python 标准库调用仓库自带的 Gradle Wrapper，不需要全局 Gradle、PowerShell 或 Unix `gradlew`。从任意工作目录调用脚本均可，含空格的路径也受支持。项目文件职责见 [架构说明](docs/architecture.md)，其他开发主题见 [文档导航](docs/README.md)。

## 1. 准备环境

- macOS / Linux：Bash、Python **3.9+**、完整 **JDK 17+**（推荐 JDK 21，须包含 `java`、`javac`、`keytool`）。macOS 自带 Bash 3.2 即可。
- Android SDK：当前工程需要 Platform **37**（SDK 安装目录可能为 `android-37.0`）和 Build Tools **37.0.0**。实际版本以 `app/build.gradle.kts` 为准。通过 Android Studio 的 SDK Manager 安装即可，脚本不自动安装 SDK。
- 首次构建需要网络下载 Gradle 与 Maven / Robolectric 依赖；已缓存后可离线构建。Gradle Wrapper 的版本、镜像与校验和由 `gradle/wrapper/gradle-wrapper.properties` 固定。
- Windows：Android Studio JBR、Android SDK 37、PowerShell；现有 `build.ps1` 默认 JBR 路径为 `C:\Program Files\Android\Android Studio\jbr`。

JDK 可通过 `--java-home` 或 `JAVA_HOME` 指定；未指定时查找 PATH 的完整 JDK、macOS `java_home`、Android Studio JBR 及常见 JDK 目录。显式设置的 JDK 无效会报错，不会悄悄改用别的版本。

SDK 查找顺序为 `--sdk`、`local.properties` 的 `sdk.dir`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`，最后为 macOS 的 `~/Library/Android/sdk` 或 Linux 的 `~/Android/Sdk`。若 `--sdk` 与 `local.properties` 冲突会停止，避免脚本和 Gradle 使用不同 SDK。

```sh
# 按需指定环境，路径应是自己的 JDK / SDK 根目录
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="$HOME/Library/Android/sdk"  # macOS
# Linux 通常为：export ANDROID_HOME="$HOME/Android/Sdk"

./build.sh --doctor  # 仅检查环境，不构建、不下载依赖
./build.sh --help
```

也可设置本机专用、已被 Git 忽略的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

## 2. Debug 构建与检查

```sh
./build.sh                   # 允许联网，单元测试 → 完整 Debug Lint → Debug APK
./build.sh --offline          # 已有依赖缓存时离线构建
./build.sh --clean --offline  # 先清理 app/build，再执行上述流程
```

默认执行 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。单测包含无需模拟器的 Robolectric / Roborazzi UI 渲染与交互测试。标准入口清除局部预览用的 `AGENT_UI_REVIEW_SMOKE` / `AGENT_UI_REVIEW_SCENES`，Gradle 将场景选择作为测试缓存输入，避免复用局部测试结果。任一任务失败立即停止；成功后再校验 APK 签名、包名、版本、SDK 声明、ZIP 完整性及 16 KiB ZIP 对齐。

产物：`app/build/outputs/apk/debug/app-debug.apk`。完整 Debug Lint 报告为 `app/build/reports/lint-results-debug.html`。需要查看 JVM 界面图库时，在成功构建后运行：

```sh
python3 scripts/ui-review-gallery.py
```

图库位于 `app/build/reports/ui-review/index.html`。脚本不启动模拟器，不调用 adb，不安装到手机。

## 3. 配置统一 Release 签名

本工程按用户在 2026-10-12 的明确要求，统一使用 `../release.keystore`（PKCS12，别名 `release`，与追程和安卓模板一致）。旧独立证书签名的安装包不能直接覆盖升级；原 agenT 独立签名资产已停用并清理。已有 `.signing/release-signing.json` 和统一签名资产时直接复用。Mac / Linux 不会自动生成密钥、绑定 debug.keystore 或重绑证书；升级安装必须使用原密钥。签名元数据、密钥和密码不入库。

私钥 `release.keystore`、私有密码 `release-password.txt` 与公钥证书 `release-cert.cer` 统一保存在工程同级目录；项目元数据与固定指纹放在 `.signing/`。JSON 中的相对路径均相对于**工程根目录**，不相对于 JSON 所在目录；支持绝对路径和 `~`。`--signing-root` 可指定其他元数据目录。

`.signing/release-signing.json` 示例（替换为自己原签名的别名与真实 SHA-256）：

```json
{
  "schemaVersion": 2,
  "storeFile": "../release.keystore",
  "credentialFile": "../release-password.txt",
  "credentialFormat": "text",
  "keyAlias": "release",
  "certificateFile": "../release-cert.cer",
  "certificateSha256": "5342F38CB24B950ED3B7112EC82C151B98A7ACBE67A1FA2EA3D0AA9F79C36FF3",
  "source": "portable-pkcs12",
  "keyPasswordSameAsStore": true
}
```

`.signing/pinned-certificate.sha256` 写入相同的证书 SHA-256（64 位十六进制，允许冒号），末尾可换行。脚本在构建前读取密钥库的真实证书并比对元数据与 pin，另在内存中签名 CSR 验证私钥密码，构建后再比对 APK 证书。

如果需要从**已有原密钥库**导出公钥证书，使用 JDK 的 `keytool`，密码由交互提示输入：

```sh
"$JAVA_HOME/bin/keytool" -exportcert -rfc -alias release \
  -keystore ../release.keystore \
  -file ../release-cert.cer
"$JAVA_HOME/bin/keytool" -printcert -file ../release-cert.cer
```

以输出的 SHA256 填写元数据与 pin。密码文件须为 UTF-8 单行原密码，脚本只去除行尾 CR/LF，保留密码中的空格。可以在工程根目录通过隐藏输入创建：

```sh
mkdir -p .signing
chmod 700 .signing
(
  umask 077
  python3 -c 'import getpass,pathlib; p=pathlib.Path("../release-password.txt"); p.write_text(getpass.getpass("原密钥库密码：")+"\n",encoding="utf-8")'
)
chmod 600 ../release-password.txt
```

若密钥密码与库密码不同，将 `keyPasswordSameAsStore` 设为 `false`，并增加 `keyCredentialFile` 指向另一个同样格式的密码文件。Windows DPAPI 的 `clixml` 密码文件无法在 Mac / Linux 解密；须使用原密码制作 `text` 文件和相应元数据，保持密钥与证书不变。现有便携 PKCS12 配置可直接跨平台复用。

密码只传入构建子进程的 `AGENTT_RELEASE_*` 环境变量，不写入命令行、构建报告或日志。脚本以所选元数据为准，清除调用者遗留的签名环境变量；结束或失败时清除子进程环境中的签名值。

## 4. Release 打包与导出

```sh
./build.sh --release --doctor    # 先检查 SDK / JDK 与原签名，不执行构建
./build.sh --release             # 默认离线，测试 → Vital Lint → 签名、校验、时间戳包
./build.sh --release --online    # 首次构建或缺少缓存时允许下载依赖

# 用户明确正式发版时：文件名只保留当前版本
./build.sh --release --official-release
```

默认执行 `:app:testDebugUnitTest`、`:app:lintVitalRelease`、`:app:assembleRelease`，开启 R8 与资源压缩。**Release Vital Lint 与完整 Debug Lint 是不同检查**；打包通过不能代替完整 Debug Lint，功能修改后应先完成第 2 节流程。

导出前校验：签名与固定证书、包名 / 版本 / minSdk / targetSdk、权限白名单、Manifest 的禁止调试 / 备份 / 明文传输、网络安全配置、ZIP CRC、仅 arm64 原生库、16 KiB ZIP 与 ELF 加载段对齐、Debug 验收代码和测试运行库未进入 Release、Gradle 输出元数据以及 R8 映射文件。失败不会将旧 APK 当作本次成功产物导出。

全部通过后，在 `dist/` 生成：

| 文件 | 用途 |
| --- | --- |
| `<版本>_YYYYMMDD_HHMMSS.apk` / 正式 `<版本>.apk` | 原签名 Release 安装包 |
| `<产物名>.sha256` | APK SHA-256 校验文件 |
| `<产物名>-mapping.txt` | 本次 R8 混淆映射 |
| `<产物名>-build.json` | 包信息、指纹、校验结果、时间与联网模式；不含密码 |

普通 Release 文件名按编译启动时刻生成，时间为北京时间（Asia/Shanghai，UTC+08:00），例如 `1.1.0_20261012_203000.apk`。正式发版使用 `--official-release` / `-OfficialRelease`，例如 `1.1.0.apk`；这只是命名开关，不授权升版本、归档、提交或推送。

同次构建的 APK、校验文件、R8 映射和构建报告共用一个产物名；报告记录启动 / 完成时间和命名模式。两端均保留历史包、拒绝同名覆盖、不自动清理 `dist/`。普通包同秒冲突时稍后重试，或用 `--artifact-suffix` / `-ArtifactSuffix` 在时间戳后追加标识；正式包不接受附加标识。同名正式包存在时须先保留已有文件。

## 5. 参数与 Windows 对照

| Mac / Linux | Windows | 含义 |
| --- | --- | --- |
| `./build.sh` | `.\build.ps1` | Debug 测试、Lint、构建 |
| `--release` | `-Release` | Release 测试、Vital Lint、签名、校验、导出 |
| `--offline` / `--online` | `-Offline` / `-Online` | 依赖解析模式；Unix 两者互斥 |
| `--clean` | `-Clean` | 先清理本工程构建输出 |
| `--skip-checks` | `-SkipChecks` | 显式跳过测试和 Lint，仍校验 APK |
| `--signing-root <目录>` | `-SigningRoot <目录>` | 指定签名元数据位置 |
| `--java-home <目录>` / `--sdk <目录>` | Windows 脚本内 JBR / SDK 配置 | 指定本机工具路径 |
| `--official-release`（须与 `--release` 同用） | `-OfficialRelease`（须与 `-Release` 同用） | 正式发版命名，不带编译日期时间 |
| `--artifact-suffix <标识>` | `-ArtifactSuffix <标识>` | 普通包时间戳之后追加标识，正式包禁用 |
| `--doctor` | 无对应参数 | Unix 环境自检 |

Windows 使用 `build.ps1 -SetupSigning` 配置共享 `release.keystore` 与私有密码文件，同样核验固定证书和私钥签名；不再生成独立密钥、使用 Windows clixml 或自动绑定 Debug 密钥。Windows Release 与 Unix 使用同一命名和历史保留规则。助手不得默认重绑签名或使用 `-Install` 操作实体手机。

`--offline` 控制 Gradle 的依赖解析，不代替首次准备：Wrapper 本体与 Robolectric 的测试 SDK 也须提前缓存。遇到缓存缺失时显式使用 `--online` 准备，再离线重试；不通过跳过检查来掩盖构建失败。

需代理时，在用户自己的 `~/.gradle/gradle.properties`（Windows 为 `%USERPROFILE%\.gradle\gradle.properties`）设置 `systemProp.https.proxyHost` / `proxyPort` 等属性，不把密码或代理私密信息写进仓库。工具路径缺失先用 `--doctor` 检查，不手工改 Gradle Wrapper 版本或绕过证书校验。

## 6. 构建工具的回归检查

工作流程、每次修改后的 Release 交付、设备验证和版本 / Git 边界统一见本地 `AGENTS.md`。本文维护构建入口与检查命令；不在不同文档重复扩写执行约定。

构建脚本修改后先完成独立回归，再按标准入口验证实际构建与导出：

```sh
bash -n build.sh
python3 -B -m unittest discover -s scripts/tests -v
```

文档或目录调整后运行 `python3 scripts/check_docs.py`（Windows 可用 `python`），核对链接、锚点、源码引用、版本和平台声明。检查结果只能证明覆盖的范围，不能代替应用行为验证或 Android 测试、Lint。
