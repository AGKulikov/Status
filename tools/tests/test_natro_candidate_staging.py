"""Packaging checks use a synthetic APK; they do not replace real CI or signing."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools import sign_natro_candidate, stage_natro_adb_candidate


class CandidateStagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="natro-stage-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.previous_cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.previous_cwd)
        self.environment = patch.dict(os.environ, {
            "VERSION_NAME": "2.9.9", "VERSION_CODE": "208021332",
            "RUNNER_TEMP": str(self.root), "ANDROID_HOME": str(self.root / "sdk"),
            "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.apk = self.root / "app/build/outputs/apk/geely/release/app-unsigned.apk"
        self.apk.parent.mkdir(parents=True)
        self.write_apk()
        self.report = self.root / "app/build/test-results/testGeelyDebugUnitTest/TEST-example.xml"
        self.report.parent.mkdir(parents=True)
        self.report.write_text('<testsuite tests="1906" failures="0" errors="0" skipped="0"/>')
        sdk = self.root / "sdk/build-tools/36.0.0"
        for name in ("aapt", "apksigner", "zipalign", "dexdump", "lib/apksigner.jar", "lib64/libc++.so"):
            target = sdk / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(name.encode())
        self.out = self.root / "natro-2.9.9-candidate"

    def write_apk(self, missing=None):
        with zipfile.ZipFile(self.apk, "w") as apk:
            for asset in ("lan/index.html", "lan/client.js", "lan/client.css", "natro-wechat-compat.apk"):
                if asset != missing:
                    apk.writestr("assets/" + asset, "test fixture")

    def stage(self):
        with patch.object(stage_natro_adb_candidate.subprocess, "check_output", return_value="b" * 40 + "\n"), contextlib.redirect_stdout(io.StringIO()):
            stage_natro_adb_candidate.main()

    def test_complete_provenance_and_exact_copies(self):
        self.stage()
        manifest = json.loads((self.out / "candidate.json").read_text())
        self.assertEqual("a" * 40, manifest["sourceCommit"])
        self.assertEqual("b" * 40, manifest["sourceTree"])
        self.assertEqual(1906, manifest["unitTests"]["tests"])
        self.assertEqual(0, manifest["unitTests"]["failures"])
        self.assertEqual("pending", manifest["physicalKx11Verification"])
        self.assertFalse(manifest["navigator"]["pairRequired"])
        self.assertEqual(self.apk.read_bytes(), (self.out / "Natro-2.9.9-unsigned.apk").read_bytes())
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), manifest["unsignedSha256"])
        self.assertEqual(b"lib/apksigner.jar", (self.out / "tools/lib/apksigner.jar").read_bytes())
        self.assertTrue((self.out / "test-results/TEST-example.xml").is_file())

    def test_failed_tests_stop_publication(self):
        self.report.write_text('<testsuite tests="1906" failures="1" errors="0"/>')
        with self.assertRaisesRegex(ValueError, "Full test suite must pass"):
            self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_missing_asset_stops_publication(self):
        self.write_apk(missing="natro-wechat-compat.apk")
        with self.assertRaises(KeyError):
            self.stage()
        self.assertFalse((self.out / "candidate.json").exists())

    def test_streaming_hashes_support_python_310(self):
        payload = self.root / "hash-fixture"
        content = b"streamed" * 300000
        payload.write_bytes(content)
        expected = hashlib.sha256(content).hexdigest()
        self.assertEqual(expected, stage_natro_adb_candidate.sha256(payload))
        self.assertEqual(expected, sign_natro_candidate.sha256(payload))


if __name__ == "__main__":
    unittest.main()
