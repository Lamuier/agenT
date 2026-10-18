"""Explicit emulator-only performance verification; never choose a connected phone."""
from datetime import datetime, timezone
from pathlib import Path
import json
import re
import shutil
import subprocess
import argparse
import os


def emulator_environment(sdk, env):
    serial = env.get("AGENT_PERFORMANCE_SERIAL", "")
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("性能检查须显式设置 AGENT_PERFORMANCE_SERIAL=emulator-端口；不选择实体设备")
    adb = Path(sdk) / "platform-tools" / ("adb.exe" if __import__("os").name == "nt" else "adb")
    # ADB needs device routing, never the private Release signing environment.
    device_env = {key: value for key, value in env.items() if not key.startswith("AGENTT_RELEASE_")}
    def read(*args):
        result = subprocess.run([str(adb), *args], env=device_env, capture_output=True, text=True, check=True)
        return result.stdout.strip()
    devices = [line.split()[:2] for line in read("devices").splitlines()[1:] if line.strip()]
    if devices != [[serial, "device"]]:
        raise ValueError("性能检查要求当前 adb 服务仅连接指定任务模拟器；请使用隔离的 ADB_SERVER_SOCKET")
    if read("-s", serial, "shell", "getprop", "ro.kernel.qemu") != "1":
        raise ValueError("指定设备未通过模拟器身份校验")
    if read("-s", serial, "shell", "settings", "get", "global", "airplane_mode_on") != "1" or \
            read("-s", serial, "shell", "settings", "get", "global", "wifi_on") != "0":
        raise ValueError("性能模拟器须开启飞行模式并关闭 Wi-Fi，确保合成夹具不发起外部查询")
    # Android Studio/AGP inherit the same isolated server and exact serial.
    env["ANDROID_SERIAL"] = serial
    return {"serial": serial, "sdk": read("-s", serial, "shell", "getprop", "ro.build.version.sdk"),
            "abi": read("-s", serial, "shell", "getprop", "ro.product.cpu.abi"), "environment": "emulator"}


def collect_reports(root, device, env):
    root = Path(root)
    label = env.get("AGENT_PERFORMANCE_LABEL", "run")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", label):
        raise ValueError("AGENT_PERFORMANCE_LABEL 仅接受字母、数字、下划线和连字符")
    destination = root / "app/build/reports/performance" / (label + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    destination.mkdir(parents=True, exist_ok=False)
    source = root / "performance/build/outputs/connected_android_test_additional_output"
    benchmarks = []
    for path in source.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(source)
        out = destination / "raw" / relative
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, out)
        if path.suffix == ".json":
            payload = json.loads(path.read_text())
            benchmarks.extend(payload.get("benchmarks", []))
    if not benchmarks:
        raise ValueError("性能测试未产生 Macrobenchmark JSON；不把构建启动视为测量完成")
    (destination / "summary.json").write_text(json.dumps({"device": device, "benchmarks": benchmarks,
        "limitation": "Emulator measurements are only comparable on the same host and AVD; they are not phone performance."}, indent=2), encoding="utf-8")
    print("性能报告：" + str(destination), flush=True)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", required=True)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    parser.add_argument("--collect", action="store_true")
    args = parser.parse_args()
    environment = os.environ.copy()
    device = emulator_environment(args.sdk, environment)
    if args.collect:
        collect_reports(args.root, device, environment)
    else:
        print("任务模拟器身份检查通过：" + device["serial"])
