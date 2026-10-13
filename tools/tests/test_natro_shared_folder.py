import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
class SharedFolderTest(unittest.TestCase):
    def test_production_folder(self):
        with tempfile.TemporaryDirectory() as work:
            sources = [ROOT/'app/src/main/java/dezz/status/widget/transfer/NatroSharedFolder.java',
                       ROOT/'tools/tests/java/NatroSharedFolderReplay.java']
            run = subprocess.run(['java','com.sun.tools.javac.Main','-d',work,*map(str,sources)],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)
            run = subprocess.run(['java','-cp',work,'dezz.status.widget.transfer.NatroSharedFolderReplay',work],capture_output=True,text=True,timeout=30)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
    def test_auth_and_ui_contract(self):
        service=(ROOT/'app/src/main/java/dezz/status/widget/transfer/LanTransferService.java').read_text()
        self.assertLess(service.index('pairing.authorized(request.token()'),service.index('case "/api/natro-files"'))
        self.assertIn('sharedFolder.open(request.path.substring(16))',service)
        self.assertEqual(len('/api/natro-file/'),16)
        js=(ROOT/'app/src/main/assets/lan/client.js').read_text()
        self.assertIn("Authorization:'Bearer '+token",js)
        self.assertIn('label.textContent=file.name',js)
        self.assertNotIn('innerHTML',js)
        self.assertNotIn('?token=',js)
        self.assertIn('blob.size!==Number',js)
        self.assertIn('Download/Natro',(ROOT/'app/src/main/assets/lan/index.html').read_text())

if __name__ == '__main__': unittest.main()
