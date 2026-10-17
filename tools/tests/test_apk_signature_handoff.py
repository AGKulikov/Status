import copy
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools import apk_signature_handoff as handoff


class SignatureHandoff(unittest.TestCase):
    def fixture(self, root):
        before, after = root / 'unsigned.apk', root / 'signed.apk'
        for path in (before, after):
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', b'not a real APK' * 10000, compress_type=zipfile.ZIP_DEFLATED)
                archive.writestr('assets/large', b'payload\0' * 200000)
                if path == after:
                    archive.writestr('META-INF/TEST.SF', b'fixture signature, not crypto proof')
        return before, after

    def test_exact_roundtrip_and_no_payload_in_literals(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = self.fixture(Path(directory))
            patch = handoff.create(before, after)
            self.assertEqual(handoff.reconstruct(before.read_bytes(), patch), after.read_bytes())
            self.assertLess(patch['literalBytes'], 2000)
            self.assertEqual(handoff.payload(before), handoff.payload(after))

    def test_rejects_modified_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = self.fixture(Path(directory))
            with zipfile.ZipFile(after, 'a') as archive:
                archive.writestr('assets/extra', b'unreviewed')
            with self.assertRaises(ValueError):
                handoff.create(before, after)

    def test_wrong_input_hash_output_hash_size_and_ranges_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = self.fixture(Path(directory))
            patch = handoff.create(before, after)
            with self.assertRaises(ValueError):
                handoff.reconstruct(b'wrong input', patch)
            for field, value in [('signedSha256', '0' * 64), ('signedSize', 1),
                                 ('literalBytes', 0), ('schema', 2)]:
                bad = copy.deepcopy(patch)
                bad[field] = value
                with self.assertRaises(ValueError):
                    handoff.reconstruct(before.read_bytes(), bad)
            bad = copy.deepcopy(patch)
            bad['ops'][1] = {'offset': -1, 'length': 100}
            with self.assertRaises(ValueError):
                handoff.reconstruct(before.read_bytes(), bad)


if __name__ == '__main__':
    unittest.main()
