"""Real ZIP payload checks for offline signing, using small non-installable fixtures."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from tools.sign_navigation_patch_candidate import replace_bridge
from tools.sign_natro_candidate import payload_hashes


class NavigationPatchSigningTest(unittest.TestCase):
    def test_only_bridge_and_signatures_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);base = root / 'base.apk';out = root / 'out.apk';dex = root / 'classes19.dex'
            dex.write_bytes(b'dex\n039\0' + b'new bridge' * 100)
            with zipfile.ZipFile(base, 'w') as apk:
                apk.comment = b'preserve comment'
                for name, data in {'classes19.dex': b'old bridge', 'classes4.dex': b'original hook',
                                   'AndroidManifest.xml': b'manifest', 'lib/arm64-v8a/map.so': b'native',
                                   'META-INF/NOTICE': b'license', 'META-INF/nested/data.SF': b'payload',
                                   'META-INF/CERT.RSA': b'old signature', 'META-INF/CERT.SF': b'old digest',
                                   'META-INF/MANIFEST.MF': b'old manifest'}.items():
                    apk.writestr(name, data)
            before, expected = replace_bridge(base, dex, out)
            self.assertEqual(payload_hashes(out), expected)
            self.assertEqual(['classes19.dex'], [n for n in before if before[n] != expected[n]])
            self.assertEqual(hashlib.sha256(dex.read_bytes()).hexdigest(), expected['classes19.dex'])
            with zipfile.ZipFile(out) as apk:
                self.assertEqual(b'preserve comment', apk.comment)
                self.assertIn('META-INF/NOTICE', apk.namelist())
                self.assertIn('META-INF/nested/data.SF', apk.namelist())
                self.assertNotIn('META-INF/CERT.RSA', apk.namelist())
                self.assertEqual(b'manifest', apk.read('AndroidManifest.xml'))

    def test_invalid_or_newer_dex_is_rejected_before_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);base = root / 'base.apk';out = root / 'out.apk';dex = root / 'classes19.dex'
            with zipfile.ZipFile(base, 'w') as apk: apk.writestr('classes19.dex', b'old')
            for header in (b'dex\n040\0', b'not-a-dex', b'dex\nabc\0'):
                dex.write_bytes(header)
                with self.assertRaisesRegex(ValueError, 'compatible with API 28'):
                    replace_bridge(base, dex, out)
                self.assertFalse(out.exists())
