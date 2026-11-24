import hashlib
import importlib.util
import json
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('requirements_reader', ROOT/'tools/requirements.py')
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class RequirementsReaderTest(unittest.TestCase):
    def test_archive_is_intact_and_original_ledger_is_preserved(self):
        directory = ROOT/'docs/archive'
        hashes = json.loads((directory/'documentation-before-reorganization-2026-11-24.sha256.json').read_text())
        with zipfile.ZipFile(directory/'documentation-before-reorganization-2026-11-24.zip') as archive:
            self.assertEqual(set(hashes), set(archive.namelist()))
            for path, sha in hashes.items():
                self.assertEqual(hashlib.sha256(archive.read(path)).hexdigest(), sha)
            self.assertTrue(reader.LEDGER.read_bytes().startswith(archive.read('PROJECT_REQUIREMENTS_RU.md')))

    def test_all_canonical_rows_have_one_module_and_sections_cover_every_byte(self):
        source = reader.LEDGER.read_text()
        generated = reader.generated(source)
        original = [line for line in source.splitlines() if reader.ROW.match(line)]
        rows = [line for name, text in generated.items() if name.startswith('modules/')
                for line in text.splitlines() if reader.ROW.match(line)]
        self.assertGreaterEqual(len(original), 448)
        self.assertCountEqual(rows, [reader.relocate(line) for line in original])
        self.assertEqual(''.join(s['text'] for s in reader.sections(source)), source)
        for name, text in generated.items():
            self.assertEqual((reader.OUT/name).read_text(), text)
        ids = [reader.ROW.match(line)[1] for line in rows]
        self.assertEqual(len(ids), len(set(ids)))

    def test_common_keeps_all_release_rule_formulations(self):
        source = reader.LEDGER.read_text()
        common = reader.generated(source)['COMMON_RU.md']
        for line in source.splitlines():
            if re.match(r'^\| REL-\d{3} \|', line):
                rid, rule = [c.strip() for c in line.split('|')[1:3]]
                self.assertIn(f'**{rid}**', common)
                self.assertIn(reader.relocate(rule).replace('../../../', '../../'), common)

    def test_prose_only_amendments_remain_discoverable(self):
        chunks = reader.sections(reader.LEDGER.read_text())
        for term in ('CARD-026', 'UPDATE-CENTER-349', 'MUSIC-WAVE-DIAG', 'сохранить автоматическую настройку'):
            self.assertTrue(any(term.casefold() in s['text'].casefold() for s in chunks), term)


if __name__ == '__main__':
    unittest.main()
