import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools import navigation_identity_352 as pack


class NavigatorIdentity352Test(unittest.TestCase):
    def test_manifest_only_and_unchanged_code_resources_and_assets(self):
        baseline = {"AndroidManifest.xml": b"old", "classes19.dex": b"ui",
                    "classes.dex": b"original", "resources.arsc": b"resources", "assets/data": b"asset"}
        updated = dict(baseline, **{"AndroidManifest.xml": b"exact new manifest"})
        expected = {name: hashlib.sha256(data).hexdigest() for name, data in updated.items()}
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            def compare(entries):
                with zipfile.ZipFile(apk, "w") as archive:
                    for name, data in entries.items():
                        archive.writestr(name, data)
                pack.check_payload(apk, expected)

            compare(updated)
            for key in updated:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    compare(dict(updated, **{key: b"unexpected"}))
            with self.assertRaises(ValueError):
                compare({name: data for name, data in updated.items() if name != "classes.dex"})
            with self.assertRaises(ValueError):
                compare(dict(updated, **{"assets/new": b"extra"}))
