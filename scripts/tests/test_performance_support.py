import os
from pathlib import Path
import subprocess
import sys
import unittest
import tempfile
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import performance_support as performance
import build_support as build


class PerformanceSupportTest(unittest.TestCase):
    def test_release_requires_own_startup_rules_and_valid_profile_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "performance").mkdir(); (root / "performance/build.gradle.kts").write_text("fixture")
            profile = root / "app/src/release/generated/baselineProfiles"
            profile.mkdir(parents=True)
            apk = root / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/dexopt/baseline.prof", b"pro\0" + b"profile fixture")
                archive.writestr("assets/dexopt/baseline.profm", b"prm\0" + b"metadata fixture")
            with self.assertRaises(build.BuildError): build.verify_application_profiles(root, apk)
            for name in ("baseline-prof.txt", "startup-prof.txt"):
                (profile / name).write_text("HSPLcom/lamuier/agenT/MainActivity;->onCreate()V\n")
            self.assertEqual(2, len(build.verify_application_profiles(root, apk)["assetBytes"]))
            (profile / "baseline-prof.txt").write_text("Landroidx/compose/OnlyLibrary;\n")
            with self.assertRaises(build.BuildError): build.verify_application_profiles(root, apk)

    def test_never_selects_a_phone_or_unspecified_device(self):
        for serial in ("", "phone123", "emulator-5554;echo unsafe"):
            with self.assertRaises(ValueError):
                performance.emulator_environment(Path("sdk"), {"AGENT_PERFORMANCE_SERIAL": serial})

    def test_other_connected_devices_abort_before_instrumentation(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0,
            "List of devices attached\nemulator-5554\tdevice\nphone123\tdevice\n", "")):
            with self.assertRaises(ValueError):
                performance.emulator_environment(Path("sdk"), {"AGENT_PERFORMANCE_SERIAL": "emulator-5554"})

    def test_qemu_identity_and_exact_serial_are_required(self):
        results = ["List of devices attached\nemulator-5582\tdevice\n", "1", "1", "0", "37", "arm64-v8a"]
        env = {"AGENT_PERFORMANCE_SERIAL": "emulator-5582", "AGENTT_RELEASE_STORE_PASSWORD": "test-only-private-value"}
        with patch.object(subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0, out, "") for out in results]) as calls:
            result = performance.emulator_environment(Path("sdk"), env)
        self.assertEqual(result["environment"], "emulator")
        self.assertEqual(env["ANDROID_SERIAL"], "emulator-5582")
        self.assertEqual(env["AGENTT_RELEASE_STORE_PASSWORD"], "test-only-private-value")
        self.assertTrue(all(not any(key.startswith("AGENTT_RELEASE_") for key in call.kwargs["env"]) for call in calls.call_args_list))
        self.assertEqual(calls.call_args_list[1].args[0][1:3], ["-s", "emulator-5582"])

    def test_standard_builds_do_not_start_device_tests(self):
        args = build.parser().parse_args(["--release"])
        commands = build.gradle_command(Path(__file__).resolve().parents[2], Path("JDK"), args, True)
        self.assertFalse(any("connected" in part or "generateReleaseBaseline" in part for part in commands))
        args = build.parser().parse_args(["--release", "--generate-baseline-profile"])
        commands = build.gradle_command(Path(__file__).resolve().parents[2], Path("JDK"), args, True)
        self.assertLess(commands.index(":app:generateReleaseBaselineProfile"), commands.index(":app:assembleRelease"))


if __name__ == "__main__":
    unittest.main()
