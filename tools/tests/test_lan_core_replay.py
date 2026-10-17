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

    def test_boot_permissions_and_restore_boundaries(self):
        base = ROOT / 'app/src/main/java/dezz/status/widget'
        receiver = (base / 'transfer/LanBootReceiver.java').read_text()
        self.assertIn('isUserUnlocked()', receiver)
        self.assertIn('.autoStart()', receiver)
        self.assertNotIn('ACTION_LOCKED_BOOT_COMPLETED', receiver)
        service = (base / 'transfer/LanTransferService.java').read_text()
        self.assertIn('remoteCommands=trust.commandsAllowed()', service)
        self.assertIn('trust.setCommandsAllowed(value)', service)
        self.assertIn('if (!remoteCommands)', service)
        self.assertIn('if (!session.connected()) session.connect()', service)
        startup = service.split('@Override public void onCreate()', 1)[1].split('public String status()', 1)[0]
        self.assertNotIn('daemonRoot(', startup)
        self.assertNotIn('session.command(', startup)
        backup = (base / 'backup/BackupStorage.java').read_text()
        bound = backup.split('Set<String> boundStores=', 1)[1].split('Set<String> identityKeys=', 1)[0]
        self.assertIn('natro_lan_trust_v1.xml', bound)

    def test_browser_persistence_and_offline_do_not_replay_commands(self):
        source = (ROOT / 'app/src/main/assets/lan/client.js').read_text()
        self.assertIn('localStorage.getItem', source)
        self.assertIn('result.remembered?localStorage:sessionStorage', source)
        self.assertIn("await api('/api/unpair',{})", source)
        self.assertIn('if(response.status===401){clearToken()', source)
        self.assertNotIn("localStorage.setItem('natroJob'", source)
        result = subprocess.run(['node','--check',str(ROOT / 'app/src/main/assets/lan/client.js')],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)

if __name__ == "__main__": unittest.main()
