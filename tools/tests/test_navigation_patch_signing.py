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

    def test_native_hook_lanes_preserve_every_other_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); base=root/'base.apk'; out=root/'out.apk'
            paths={}
            with zipfile.ZipFile(base,'w') as z:
                for name in ('classes11.dex','classes15.dex','classes19.dex','classes4.dex','AndroidManifest.xml'):
                    z.writestr(name,b'original '+name.encode())
            for name in ('classes11.dex','classes15.dex','classes19.dex'):
                paths[name]=root/name;paths[name].write_bytes(b'dex\n039\0'+name.encode())
            before,after=replace_bridge(base,paths['classes19.dex'],out,
                {name:paths[name] for name in ('classes11.dex','classes15.dex')})
            self.assertEqual(['classes11.dex','classes15.dex','classes19.dex'],sorted(n for n in before if before[n]!=after[n]))
            self.assertEqual(before['AndroidManifest.xml'],after['AndroidManifest.xml'])
            self.assertEqual(before['classes4.dex'],after['classes4.dex'])
            with self.assertRaisesRegex(ValueError,'reviewed native'):
                replace_bridge(base,paths['classes19.dex'],root/'bad.apk',{'classes4.dex':paths['classes11.dex']})
