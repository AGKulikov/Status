import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('backup_inventory', Path(__file__).resolve().parents[1] / 'check_backup_inventory.py')
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)

class BackupInventoryTest(unittest.TestCase):
    def test_new_store_in_new_module_is_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'future-module/src/main/java/Store.java'
            path.parent.mkdir(parents=True)
            path.write_text('class Store { void f(){ context.getSharedPreferences("new", 0); } }')
            self.assertIn('UNREVIEWED', audit.compare({}, audit.discover(root))[0])

    def test_new_parameter_or_default_in_existing_owner_requires_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'app/src/main/java/Store.java'
            path.parent.mkdir(parents=True)
            path.write_text('class Store { Object p = c.getSharedPreferences(NAME, 0); String NAME="old"; }')
            before = audit.discover(root)
            path.write_text(path.read_text().replace('"old"', '"new"'))
            self.assertIn('CHANGED', audit.compare(before, audit.discover(root))[0])

    def test_removed_owner_requires_migration_review(self):
        self.assertIn('REMOVED', audit.compare({'old': {}}, {})[0])

    def test_file_secret_database_and_uri_owners_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent = root / 'app/src/main/java'
            parent.mkdir(parents=True)
            for name, text in {'File': 'new FileOutputStream(path)', 'Secret': 'AndroidKeyStore',
                               'Db': 'extends RoomDatabase', 'Uri': 'openOutputStream(uri)'}.items():
                (parent / (name + '.java')).write_text(text)
            self.assertEqual(4, len(audit.discover(root)))

    def test_test_fixtures_are_not_production_stores(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent = root / 'app/src/test/java'
            parent.mkdir(parents=True)
            (parent / 'Fixture.java').write_text('new FileOutputStream(path)')
            self.assertEqual({}, audit.discover(root))
