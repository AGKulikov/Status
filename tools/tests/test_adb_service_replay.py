"""Runs production console and recovery policy against bounded Android/daemon test doubles."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[2]

class AdbServiceReplay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-policy-")
        source = ROOT / "app/src/main/java/dezz/status/widget"
        files = list((ROOT / "tools/tests/runtime_stubs").rglob("*.java"))
        files += [source / (name + ".java") for name in (
            "adb/AdbConsoleSession", "adb/AdbShellResult", "servicemode/ServiceModeJournal",
            "servicemode/AppsToHideStorage", "servicemode/AlwaysIgnoreAppResolver", "servicemode/PinStorage")]
        files += [ROOT / "tools/tests/java" / name for name in ("AdbSessionReplay.java", "ServiceModeRecoveryReplay.java")]
        compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        result = subprocess.run([*compiler, "-encoding", "UTF-8", "-d", cls.temp.name, *map(str, files)], text=True, capture_output=True)
        if result.returncode:
            cls.temp.cleanup()
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def replay(self, name):
        result = subprocess.run(["java", "-cp", self.temp.name, name], text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS", result.stdout)
    def test_root_single_request_and_200_shutdown_races(self): self.replay("AdbSessionReplay")
    def test_service_journal_restore_and_pin(self): self.replay("dezz.status.widget.servicemode.ServiceModeRecoveryReplay")
    def test_discovery_stops_with_screen_and_never_scans_stale_hosts(self):
        source = (ROOT / "app/src/main/java/dezz/status/widget/servicemode/ShellExecutor.java").read_text()
        ui = (ROOT / "app/src/main/java/dezz/status/widget/servicemode/MainActivity.java").read_text()
        self.assertIn("connectionScan.cancel(true)", ui)
        self.assertIn("port <= LAST_PORT && !Thread.currentThread().isInterrupted()", source)
        self.assertIn("if (!states.containsKey(e.host)) return;", source)
        self.assertNotIn("states.putIfAbsent(e.host", source)

if __name__ == "__main__": unittest.main()
