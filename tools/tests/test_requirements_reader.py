import contextlib
import hashlib
import io
import importlib.util
import json
import re
import tempfile
import unittest
from unittest import mock
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
        expected = {'CARD-026': 'FIELD 3.5.6 отрицательный',
                    'UPDATE-CENTER-349': 'единый раздел обновлений',
                    'MUSIC-WAVE-DIAG': 'native transition reasons',
                    'сохранить автоматическую настройку': 'сохранить автоматическую настройку'}
        for term, title in expected.items():
            hits = [s for s in chunks if reader.matching_lines(s, [term])]
            self.assertTrue(any(title in s['title'] for s in hits), term)
        journal = next(s for s in chunks if s['title'] == '12. Журнал изменений реестра')
        self.assertTrue(reader.matching_lines(journal, ['CARD-018']))


class ReaderBehaviorTest(unittest.TestCase):
    SAMPLE = ("# Реестр\n\n## Правила\n"
              "| REL-001 | Сохранить подпись | Открыто |\n\n"
              "## Журнал\nОбщее условие для вложенной записи.\n"
              "### Уточнение\nЗнак манёвра CARD-017/018 должен оставаться видимым.\n"
              "Ещё одно уточнение о маневре.\n"
              "### Решение пользователя\nАвтоматический доступ сохраняется.\n")

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        out = root/'docs/requirements'
        out.mkdir(parents=True)
        (out/'README_RU.md').write_text('[REL](modules/REL.md)\n')
        ledger = root/'PROJECT_REQUIREMENTS_RU.md'
        ledger.write_text(self.SAMPLE)
        patcher = mock.patch.multiple(reader, ROOT=root, OUT=out, LEDGER=ledger)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.run_cli('build')

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                reader.main(list(args))
                code = 0
            except SystemExit as error:
                code = error.code
        return code, out.getvalue(), err.getvalue()

    def reference(self, title):
        text = reader.LEDGER.read_text()
        section = next(s for s in reader.sections(text) if s['title'] == title)
        return section['key']+'@'+reader.revision(text)

    def test_search_prose_under_generic_title_shows_snippet_and_paginates(self):
        code, output, _ = self.run_cli('search', 'уточнение', 'доступ', '--limit', '1')
        self.assertEqual(code, 0)
        self.assertIn('2 matching sections', output)
        self.assertIn('first L', output)
        self.assertIn('REMAINING:', output)
        code, output, _ = self.run_cli('search', 'уточнение', 'доступ', '--limit', '1', '--offset', '1')
        self.assertEqual(code, 0)
        self.assertIn('Автоматический доступ', output)
        self.assertNotIn('REMAINING:', output)

    def test_matches_reads_all_offsets_and_parent_context(self):
        reference = self.reference('Уточнение')
        code, output, _ = self.run_cli('matches', reference, 'маневр', '--limit', '1')
        self.assertEqual(code, 0)
        self.assertIn('2 matching lines', output)
        self.assertIn(self.reference('Журнал'), output)
        self.assertIn('REMAINING:', output)
        code, output, _ = self.run_cli('matches', reference, 'маневр', '--limit', '1', '--offset', '1')
        self.assertEqual(code, 0)
        self.assertIn('Ещё одно уточнение', output)
        self.assertNotIn('REMAINING:', output)
        code, output, _ = self.run_cli('read', reference, '--limit', '1')
        self.assertEqual(code, 0)
        self.assertIn('REMAINING: use read --offset 1', output)

    def test_search_accepts_e_yo_and_family_finds_abbreviated_id(self):
        for query in ('МАНЕВР', 'манёвр', 'CARD'):
            code, output, _ = self.run_cli('search', query)
            self.assertEqual(code, 0)
            self.assertIn('1 matching sections', output)
            self.assertIn('CARD-017/018', output)

    def test_stale_or_tampered_view_blocks_lookup(self):
        path = reader.OUT/'modules/REL.md'
        path.write_text(path.read_text().replace('Сохранить', 'Изменить'))
        code, _, error = self.run_cli('search', 'REL')
        self.assertNotEqual(code, 0)
        self.assertIn('STALE or missing', error)

    def test_changed_ledger_requires_build_and_invalidates_old_ref(self):
        reference = self.reference('Уточнение')
        reader.LEDGER.write_text(reader.LEDGER.read_text()+'\nНовое решение.\n')
        self.assertNotEqual(self.run_cli('read', reference)[0], 0)
        self.assertEqual(self.run_cli('build')[0], 0)
        code, _, error = self.run_cli('read', reference)
        self.assertNotEqual(code, 0)
        self.assertIn('STALE or unversioned', error)
        self.assertEqual(self.run_cli('read', self.reference('Уточнение'))[0], 0)

    def test_unversioned_unknown_ref_and_bad_pages_fail_explicitly(self):
        for args in [('read', 'S003'), ('read', 'S999@'+reader.revision(self.SAMPLE)),
                     ('read', self.reference('Уточнение'), '--offset', '1000'),
                     ('search', 'CARD', '--offset', '-1'), ('search', 'CARD', '--offset', '99'),
                     ('matches', self.reference('Уточнение'), 'CARD', '--offset', '99'), ('search', ''),
                     ('search', 'CARD', '--limit', '0')]:
            with self.subTest(args=args):
                self.assertNotEqual(self.run_cli(*args)[0], 0)

    def test_no_match_is_not_treated_as_absence_of_requirement(self):
        code, output, _ = self.run_cli('search', 'missing-term')
        self.assertEqual(code, 0)
        self.assertIn('NO MATCH is not proof of absence', output)

    def test_new_family_requires_reading_route(self):
        reader.LEDGER.write_text(reader.LEDGER.read_text()+'\n| NEW-001 | Новое требование | Открыто |\n')
        self.assertEqual(self.run_cli('build')[0], 0)
        code, _, error = self.run_cli('check')
        self.assertNotEqual(code, 0)
        self.assertIn('No reading route', error)
        route = reader.OUT/'README_RU.md'
        route.write_text(route.read_text()+'[NEW](modules/NEW.md)\n')
        self.assertEqual(self.run_cli('check')[0], 0)


if __name__ == '__main__':
    unittest.main()
