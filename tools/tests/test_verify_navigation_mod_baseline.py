#!/usr/bin/env python3

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "verify_navigation_mod_baseline.py"
SPEC = importlib.util.spec_from_file_location("navigation_baseline_verifier", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class NavigationBaselineVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.baseline = self.root / "baseline.apk"
        self._write_zip(
            self.baseline,
            {
                "AndroidManifest.xml": b"manifest",
                "resources.arsc": b"resources",
                "classes4.dex": b"original activity",
                "classes14.dex": b"working passport boundary",
                "classes18.dex": b"working 30.3 additions",
                "assets/map/data": b"offline map assets",
                "lib/arm64-v8a/libmaps-mobile.so": b"native mapkit",
                "res/drawable/icon.xml": b"original compiled resource",
            },
        )
        self.config = {
            "baseline_apk_sha256": MODULE.sha256_file(self.baseline),
            "mutable_entries": ["AndroidManifest.xml", "classes4.dex"],
            "allowed_new_entries": ["classes19.dex"],
            "release_required_changed_entries": [
                "AndroidManifest.xml", "classes4.dex"
            ],
            "release_required_new_entries": ["classes19.dex"],
            "release_entry_sha256": {
                "AndroidManifest.xml": MODULE._sha256_bytes(b"patched manifest"),
                "classes4.dex": MODULE._sha256_bytes(
                    b"activity plus one reviewed hook"
                ),
            },
            "critical_entries": [
                "resources.arsc",
                "classes14.dex",
                "classes18.dex",
            ],
            "critical_prefixes": ["assets/", "lib/", "res/"],
        }

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def _write_zip(path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            for name, value in entries.items():
                archive.writestr(name, value)

    def _candidate(self, *, protected=b"working passport boundary", extra=None):
        path = self.root / f"candidate-{len(list(self.root.glob('candidate-*')))}.apk"
        entries = {
            "AndroidManifest.xml": b"patched manifest",
            "resources.arsc": b"resources",
            "classes4.dex": b"activity plus one reviewed hook",
            "classes14.dex": protected,
            "classes18.dex": b"working 30.3 additions",
            "classes19.dex": b"new isolated Natro bridge",
            "assets/map/data": b"offline map assets",
            "lib/arm64-v8a/libmaps-mobile.so": b"native mapkit",
            "res/drawable/icon.xml": b"original compiled resource",
        }
        if extra:
            entries.update(extra)
        self._write_zip(path, entries)
        return path

    def test_reviewed_dex_patch_preserves_every_other_entry(self):
        changed, new_entries, protected = MODULE.verify_zip_contents(
            self.config, self.baseline, self._candidate(), release=True
        )
        self.assertEqual(
            {"AndroidManifest.xml", "classes4.dex"}, changed
        )
        self.assertEqual({"classes19.dex"}, new_entries)
        self.assertGreaterEqual(protected, 4)

    def test_resource_table_rebuild_is_rejected(self):
        with self.assertRaisesRegex(MODULE.VerificationError, "protected entries"):
            MODULE.verify_zip_contents(
                self.config,
                self.baseline,
                self._candidate(extra={"resources.arsc": b"rebuilt resource table"}),
                release=True,
            )

    def test_passport_or_native_drift_is_rejected(self):
        with self.assertRaisesRegex(MODULE.VerificationError, "protected entries"):
            MODULE.verify_zip_contents(
                self.config,
                self.baseline,
                self._candidate(protected=b"rewritten protection logic"),
                release=True,
            )

        with self.assertRaisesRegex(MODULE.VerificationError, "protected entries"):
            MODULE.verify_zip_contents(
                self.config,
                self.baseline,
                self._candidate(
                    extra={"lib/arm64-v8a/libmaps-mobile.so": b"patched native mapkit"}
                ),
                release=True,
            )

    def test_unreviewed_file_is_rejected(self):
        with self.assertRaisesRegex(MODULE.VerificationError, "unreviewed entries"):
            MODULE.verify_zip_contents(
                self.config,
                self.baseline,
                self._candidate(extra={"classes20.dex": b"hidden extra code"}),
                release=True,
            )

    def test_duplicate_zip_entry_is_rejected(self):
        duplicate = self.root / "duplicate.apk"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(duplicate, "w") as archive:
                archive.writestr("classes4.dex", b"one")
                archive.writestr("classes4.dex", b"two")
        with self.assertRaisesRegex(MODULE.VerificationError, "duplicate ZIP entry"):
            MODULE.zip_entry_digests(duplicate)


if __name__ == "__main__":
    unittest.main()
