"""Build safety and portability checks; no Android SDK or signing assets needed."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_support as build


class BuildSupportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="agenT build test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.signing = self.root / ".signing"
        self.signing.mkdir()
        self.der = b"test certificate DER"
        self.cert = hashlib.sha256(self.der).hexdigest().upper()
        (self.root / "store with spaces.p12").write_bytes(b"keystore fixture")
        (self.root / "password.txt").write_text(" password with spaces \n", encoding="utf-8")
        self.metadata = {
            "schemaVersion": 2, "storeFile": "store with spaces.p12", "keyAlias": "release",
            "credentialFormat": "text", "credentialFile": "password.txt",
            "keyPasswordSameAsStore": True, "certificateSha256": self.cert,
        }
        self.save_metadata()
        (self.signing / "pinned-certificate.sha256").write_text(self.cert + "\n")

    def save_metadata(self):
        (self.signing / "release-signing.json").write_text(json.dumps(self.metadata))

    def test_mac_and_linux_sdk_defaults(self):
        with patch.object(Path, "home", return_value=self.root):
            self.assertEqual(build.find_sdk(self.root, None, {}, "Darwin"), self.root / "Library/Android/sdk")
            self.assertEqual(build.find_sdk(self.root, None, {}, "Linux"), self.root / "Android/Sdk")

    def test_local_properties_escaped_spaces_and_override_conflict(self):
        sdk = self.root / "Android SDK"
        (self.root / "local.properties").write_text("sdk.dir=" + str(sdk).replace(" ", r"\ ") + "\n")
        self.assertEqual(build.find_sdk(self.root, None, {"ANDROID_HOME": "/other"}, "Linux"), sdk)
        self.assertEqual(build.find_sdk(self.root, str(sdk), {}, "Darwin"), sdk)
        with self.assertRaises(build.BuildError):
            build.find_sdk(self.root, "/other", {}, "Linux")

    def test_java_versions_and_explicit_invalid_jdk(self):
        self.assertEqual(build.java_major('openjdk version "21.0.9" 2026-01-01'), 21)
        self.assertEqual(build.java_major('java version "1.8.0_123"'), 8)
        with self.assertRaises(build.BuildError):
            build.find_java(self.root, "missing JDK", {}, "Linux")

    def test_signing_paths_and_password_preserve_spaces(self):
        env = {}
        self.assertEqual(build.load_signing(self.root, self.signing, env), self.cert)
        self.assertEqual(env[build.SIGNING_KEYS[0]], str(self.root / "store with spaces.p12"))
        self.assertEqual(env[build.SIGNING_KEYS[1]], " password with spaces ")
        self.assertEqual(env[build.SIGNING_KEYS[1]], env[build.SIGNING_KEYS[3]])

    def test_distinct_key_password(self):
        (self.root / "key-password.txt").write_text("another secret\r\n")
        self.metadata.update(keyPasswordSameAsStore=False, keyCredentialFile="key-password.txt")
        self.save_metadata()
        env = {}
        build.load_signing(self.root, self.signing, env)
        self.assertEqual(env[build.SIGNING_KEYS[3]], "another secret")

    def test_missing_pin_bad_pin_and_windows_clixml_stop_release(self):
        pin = self.signing / "pinned-certificate.sha256"
        pin.unlink()
        with self.assertRaises(build.BuildError):
            build.load_signing(self.root, self.signing, {})
        pin.write_text("0" * 64)
        with self.assertRaises(build.BuildError):
            build.load_signing(self.root, self.signing, {})
        pin.write_text(self.cert)
        self.metadata["credentialFormat"] = "clixml"
        self.save_metadata()
        with self.assertRaisesRegex(build.BuildError, "clixml"):
            build.load_signing(self.root, self.signing, {})

    def test_certificate_verification_uses_env_password_and_rejects_other_key(self):
        env = {}
        build.load_signing(self.root, self.signing, env)
        with patch.object(build, "capture", return_value=self.der) as capture:
            build.validate_signing_key(self.root / "JDK with spaces", env, self.cert)
            command = capture.call_args.args[0]
            self.assertIn("-storepass:env", command)
            self.assertIn("-certreq", command)
            self.assertIn("-keypass:env", command)
            self.assertNotIn(env[build.SIGNING_KEYS[1]], command)
        with patch.object(build, "capture", return_value=b"other certificate"):
            with self.assertRaises(build.BuildError):
                build.validate_signing_key(self.root, env, self.cert)

    def test_wrong_private_key_password_fails_preflight(self):
        env = {}
        build.load_signing(self.root, self.signing, env)
        with patch.object(build, "capture", side_effect=[self.der, build.BuildError('private key password failed')]):
            with self.assertRaisesRegex(build.BuildError, 'private key password'):
                build.validate_signing_key(self.root, env, self.cert)

    def test_gradle_check_tasks_and_no_shell_interpolation(self):
        (self.root / "gradle/wrapper").mkdir(parents=True)
        (self.root / "gradle/wrapper/gradle-wrapper.jar").touch()
        args = build.parser().parse_args(["--release", "--clean"])
        command = build.gradle_command(self.root, self.root / "JDK with spaces", args, True)
        self.assertEqual(command[0], str(self.root / "JDK with spaces/bin/java"))
        self.assertLess(command.index(":app:clean"), command.index(":app:testDebugUnitTest"))
        self.assertIn(":app:lintVitalRelease", command)
        self.assertNotIn(":app:lintDebug", command)
        self.assertIn("--offline", command)
        args = build.parser().parse_args([])
        self.assertIn(":app:lintDebug", build.gradle_command(self.root, self.root, args, False))

    def test_unknown_or_conflicting_options_fail(self):
        with patch("sys.stderr"):
            for options in (["--debug", "--release"], ["--offline", "--online"], ["--install"]):
                with self.assertRaises(SystemExit):
                    build.parser().parse_args(options)

    def test_failed_gradle_does_not_export_old_apk_or_retain_signing_env(self):
        config = {"versionName": "1.0.1", "compileSdk": 37, "buildToolsVersion": "37.0.0"}
        sdk = self.root / "SDK"
        (sdk / "platforms/android-37.0").mkdir(parents=True)
        (sdk / "platforms/android-37.0/android.jar").touch()
        tools = sdk / "build-tools/37.0.0"
        tools.mkdir(parents=True)
        for tool in ("aapt", "apksigner", "zipalign"):
            (tools / tool).touch(mode=0o755)
        def fail(command, root, env):
            self.used_env = env
            self.assertNotIn("AGENT_UI_REVIEW_SMOKE", env)
            self.assertNotIn("AGENT_UI_REVIEW_SCENES", env)
            raise build.BuildError("fixture build failed")
        with patch.object(build, "app_config", return_value=config), \
             patch.object(build, "find_java", return_value=(self.root, 21)), \
             patch.object(build, "find_sdk", return_value=sdk), \
             patch.object(build, "validate_signing_key"), \
             patch.object(build, "gradle_command", return_value=[":app:assembleRelease"]), \
             patch.object(build, "run_gradle", side_effect=fail), \
             patch.object(build, "export_release") as export, patch("builtins.print"), \
             patch.dict(os.environ, {"AGENT_UI_REVIEW_SMOKE": "1", "AGENT_UI_REVIEW_SCENES": "overview"}):
            with self.assertRaisesRegex(build.BuildError, "fixture build failed"):
                build.main(["--release"], self.root)
            export.assert_not_called()
        self.assertFalse(any(key in self.used_env for key in build.SIGNING_KEYS))

    def test_existing_artifacts_and_traversal_suffix_are_rejected(self):
        (self.root / "dist").mkdir()
        existing = self.root / "dist/1.0.1.apk"
        existing.write_bytes(b"original published APK")
        with self.assertRaises(build.BuildError):
            build.ensure_destination(self.root, "1.0.1")
        self.assertEqual(existing.read_bytes(), b"original published APK")
        with self.assertRaises(build.BuildError):
            build.artifact_base({"versionName": "1.0.1"}, "../../outside")

    def test_timestamped_and_official_names_use_current_version_and_beijing_time(self):
        instant = datetime(2026, 10, 12, 20, 14, 15, tzinfo=timezone.utc)
        config = {"versionName": "1.1.0"}
        self.assertEqual(build.artifact_base(config, started_at=instant), "1.1.0_20261013_041415")
        self.assertEqual(build.artifact_base(config, "uiux", started_at=instant), "1.1.0_20261013_041415_uiux")
        self.assertEqual(build.artifact_base(config, official_release=True, started_at=instant), "1.1.0")
        with self.assertRaises(build.BuildError):
            build.artifact_base(config, "uiux", official_release=True)
        with self.assertRaises(build.BuildError):
            build.artifact_base({"versionName": "../../outside"}, started_at=instant)
        with self.assertRaises(build.BuildError):
            build.main(["--official-release"], self.root)

    def test_release_export_uses_consistent_names_and_keeps_existing_artifacts(self):
        apk = self.root / "app-release.apk"
        apk.write_bytes(b"verified APK fixture")
        mapping = self.root / "app/build/outputs/mapping/release/mapping.txt"
        mapping.parent.mkdir(parents=True)
        mapping.write_text("fixture mapping")
        config = {"applicationId": "com.lamuier.agenT", "versionName": "1.1.0", "versionCode": 3,
                  "minSdk": 26, "targetSdk": 37}
        instant = datetime(2026, 10, 12, 20, 14, 15, tzinfo=timezone.utc)
        base = build.artifact_base(config, started_at=instant)
        with patch("builtins.print"):
            build.export_release(self.root, apk, config, self.cert, base, [], True, True, instant)
        exported = self.root / "dist" / (base + ".apk")
        checksum = hashlib.sha256(exported.read_bytes()).hexdigest().upper()
        self.assertEqual((self.root / "dist" / (base + ".sha256")).read_text(),
                         checksum + "  " + exported.name + "\n")
        report = build.read_json(self.root / "dist" / (base + "-build.json"))
        self.assertEqual(report["apk"], str(exported))
        self.assertEqual(report["buildStartedAt"], instant.isoformat())
        self.assertEqual(report["artifactTimeZone"], "Asia/Shanghai")
        self.assertFalse(report["officialRelease"])
        with self.assertRaises(build.BuildError):
            build.export_release(self.root, apk, config, self.cert, base, [], True, True, instant)
        self.assertEqual(exported.read_bytes(), b"verified APK fixture")
        with patch("builtins.print"):
            build.export_release(self.root, apk, config, self.cert, "1.1.0", [], True, True,
                                 instant, official_release=True)
        official = self.root / "dist/1.1.0.apk"
        self.assertTrue(official.is_file())
        self.assertTrue(build.read_json(self.root / "dist/1.1.0-build.json")["officialRelease"])
        self.assertTrue(exported.is_file())

    def test_windows_naming_policy_preserves_history_and_uses_same_time_zone(self):
        script = (Path(__file__).resolve().parents[2] / "build.ps1").read_text(encoding="utf-8-sig")
        for required in ('[switch]$OfficialRelease', '[string]$ArtifactSuffix',
                         '[DateTimeOffset]::UtcNow', 'ToOffset([TimeSpan]::FromHours(8))',
                         '"yyyyMMdd_HHmmss"', '"$artifactBaseName.apk"', '"$artifactBaseName.sha256"',
                         'Test-ReleaseDestination -Dist $dist -Base $artifactBaseName'):
            self.assertIn(required, script)
        self.assertNotIn('Remove-Item -LiteralPath $stale.FullName', script)
        self.assertNotIn('Copy-Item -LiteralPath $apk -Destination $destination -Force', script)

    def test_elf_load_segments_require_16k_alignment(self):
        data = bytearray(120)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q", data, 112, 16384)
        self.assertEqual(build.elf_alignments(data, "fixture.so"), [16384])
        struct.pack_into("<Q", data, 112, 4096)
        with self.assertRaises(build.BuildError):
            build.elf_alignments(data, "fixture.so")
        struct.pack_into("<Q", data, 112, 16384)
        struct.pack_into("<Q", data, 80, 4096)
        with self.assertRaises(build.BuildError):
            build.elf_alignments(data, "fixture.so")

    def test_debug_fixtures_rejected_in_release_archive(self):
        apk = self.root / "test.apk"
        elf = bytearray(120)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", elf, 32, 64)
        struct.pack_into("<HH", elf, 54, 56, 1)
        struct.pack_into("<I", elf, 64, 1)
        struct.pack_into("<Q", elf, 112, 16384)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/fixture.so", elf)
            archive.writestr("classes.dex", b"UiReviewActivity")
        with self.assertRaisesRegex(build.BuildError, "Debug"):
            build.verify_archive(apk, True)
        self.assertEqual(build.verify_archive(apk, False), [])

    def test_gradle_output_redacts_passwords(self):
        import io
        command = [sys.executable, "-c", "import os; print(os.environ['AGENTT_RELEASE_STORE_PASSWORD'])"]
        env = os.environ.copy()
        env[build.SIGNING_KEYS[1]] = "fixture-only-password"
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            build.run_gradle(command, self.root, env)
            self.assertEqual(output.getvalue().strip(), "[REDACTED]")

    def test_tool_failure_does_not_echo_secret_output(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"password", b"password")):
            with self.assertRaises(build.BuildError) as error:
                build.capture(["keytool"], {}, "certificate")
            self.assertNotIn("password", str(error.exception))


if __name__ == "__main__":
    unittest.main()
