"""Real socket, disk and shell framing checks; does not claim iPhone/vehicle hardware QA."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

class LanCoreReplay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-lan-test-")
        cls.classes = pathlib.Path(cls.temp.name)
        base = ROOT / "app/src/main/java/dezz/status/widget"
        sources = [base / (path + ".java") for path in (
            "transfer/LanHttpServer", "transfer/LanFileStore", "transfer/LanPairing",
            "adb/AdbShellResult", "adb/AdbCommandCatalog")]
        compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        result = subprocess.run([*compiler, "-encoding", "UTF-8", "-d", str(cls.classes),
            *map(str, sources), str(ROOT / "tools/tests/java/LanCoreReplay.java")], text=True, capture_output=True)
        if result.returncode:
            cls.temp.cleanup()
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, suite):
        with tempfile.TemporaryDirectory(prefix="natro-lan-data-") as root:
            result = subprocess.run(["java", "-cp", str(self.classes), "LanCoreReplay", suite, root],
                text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS", result.stdout)

    def test_pairing_expiry_limits_and_revoke(self): self.replay("pairing")
    def test_file_integrity_quota_abort_and_traversal(self): self.replay("files")
    def test_http_origin_boundaries_and_shutdown(self): self.replay("http")
    def test_real_shell_exit_framing(self): self.replay("shell")

if __name__ == "__main__": unittest.main()
