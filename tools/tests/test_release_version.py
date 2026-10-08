import unittest
from tools.check_release_version import validate_release_version


class ReleaseVersionTest(unittest.TestCase):
    def test_project_patch_limit(self):
        for version in ("3.0.9", "3.1.0", "2.6.0"):
            self.assertEqual(len(validate_release_version(version)), 3)
        for version in ("3.0.10", "3.1.00", "3.1", "03.1.0", "3.1.0-debug"):
            with self.assertRaises(ValueError):
                validate_release_version(version)
