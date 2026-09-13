"""Fake-ADB transport tests for a read-only capture; no vehicle or private log fixtures."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/geely-map-check'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


collector = module('map_check_collector', SOURCE / 'collect_maps.py')
bundle = module('map_check_bundle', SOURCE / 'build_bundle.py')

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, shlex, sys
args = sys.argv[1:]
with open(os.environ['MAP_TEST_COMMANDS'], 'a') as out:
    out.write(json.dumps(args) + '\n')
if args == ['devices']:
    print('List of devices attached\nfake-kx11\tdevice')
    if os.environ.get('MAP_TEST_MULTIPLE'): print('second\tdevice')
    raise SystemExit(0)
assert args[:4] == ['-s', 'fake-kx11', 'shell', '-T'], args
outer = shlex.split(args[4]); command = outer[-1]
if command == 'id -u':
    if outer[0] == 'su' and os.environ.get('MAP_TEST_NO_ROOT'):
        print('root denied', file=sys.stderr); raise SystemExit(1)
    print('0' if outer[0] == 'su' else '2000'); raise SystemExit(0)
if 'natro-map-stderr-probe' in command:
    sys.stdout.buffer.write(b'\x01\x00\xff\n'); sys.stdout.buffer.flush()
    print('natro-map-stderr-probe', file=sys.stdout if os.environ.get('MAP_TEST_MERGED') else sys.stderr)
    raise SystemExit(17)
tokens = shlex.split(command)
assert tokens[:5] == ['toybox', 'timeout', '10', 'sh', '-c'], tokens
command = tokens[5]; tokens = shlex.split(command)
if tokens[:2] == ['pm', 'path']:
    print('package:' + ('/data/local/tmp/unrelated.apk' if os.environ.get('MAP_TEST_BAD_PATH')
          else '/data/app/' + tokens[2] + '/base.apk'))
elif tokens[:2] == ['toybox', 'sha256sum']:
    for path in tokens[2:]:
        digest = ('bcbfd5c0fc044d309ada196cc260d7459f89f9956466005bf98d8e4ca1e1db3b'
                  if 'ru.natro.statuswidget' in path else
                  '1bb5ba22cd2a60726414779fa9d8ebb37973ee756f4e9f401f9efc5bc63da4a2')
        print(digest + '  ' + path)
elif tokens[:3] == ['dumpsys', 'window', 'windows'] and os.environ.get('MAP_TEST_WINDOW_FAIL'):
    print('synthetic unavailable snapshot', file=sys.stderr); raise SystemExit(3)
else:
    print('Synthetic diagnostic output for ' + tokens[0])
'''


class MapCheckCollectorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='map check test ')
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.adb = self.folder / 'fake adb'
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(0o755)
        self.commands = self.folder / 'commands.jsonl'
        self.env = dict(os.environ, MAP_TEST_COMMANDS=str(self.commands))

    def run_collector(self, entry=None):
        command = entry or [sys.executable, str(SOURCE / 'collect_maps.py')]
        result = subprocess.run(command + ['--adb', str(self.adb), '--output-dir', str(self.folder)],
                                env=self.env, input='\n', capture_output=True, text=True, timeout=15)
        archives = list(self.folder.glob('Natro-Map-Check-20*.zip'))
        self.assertEqual(len(archives), 1, result.stdout + result.stderr)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertIsNone(archive.testzip())
            prefix = archives[0].stem + '/'
            report = json.loads(archive.read(prefix + 'RESULT.json'))
            for item in json.loads(archive.read(prefix + 'FILES.json')):
                data = archive.read(prefix + item['path'])
                self.assertEqual(len(data), item['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), item['sha256'])
        return result, report

    def test_exact_installed_hashes_and_only_read_commands(self):
        result, report = self.run_collector()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report['status'], 'collected_awaiting_analysis')
        self.assertTrue(report['transport_probe_passed'])
        self.assertEqual(len(report['captures']), 19)
        for package in collector.PACKAGES:
            self.assertTrue(report['installed_apks'][package]['contains_expected_base'])
        commands = [json.loads(line) for line in self.commands.read_text().splitlines()]
        scripts = [shlex.split(c[-1])[-1] for c in commands if 'shell' in c]
        for forbidden in ('setprop', 'force-stop', 'killall', 'pkill', ' install ', 'logcat -c',
                          'settings put', 'input keyevent', 'tcpdump', 'pm clear'):
            self.assertFalse(any(forbidden in s for s in scripts), forbidden)

    def test_optional_snapshot_failure_keeps_later_logs_and_manifest(self):
        self.env['MAP_TEST_WINDOW_FAIL'] = '1'
        result, report = self.run_collector()
        self.assertEqual(result.returncode, 3)
        states = {r['name']: r['status'] for r in report['captures']}
        self.assertEqual(states['window-state'], 'command_failed')
        self.assertEqual(states['map-logcat-final'], 'collected')

    def test_without_root_collects_shell_diagnostics_and_skips_private_journal(self):
        self.env['MAP_TEST_NO_ROOT'] = '1'
        result, report = self.run_collector()
        self.assertEqual(result.returncode, 3)
        self.assertFalse(report['root_available'])
        states = {r['name']: r['status'] for r in report['captures']}
        self.assertEqual(states['natro-journal'], 'skipped_no_root')
        self.assertEqual(states['map-logcat'], 'collected')
        self.assertNotIn('journal.log', self.commands.read_text())

    def test_transport_failure_archives_error_before_reading_logs(self):
        self.env['MAP_TEST_MERGED'] = '1'
        result, report = self.run_collector()
        self.assertEqual(result.returncode, 3)
        self.assertEqual(report['status'], 'failed')
        self.assertEqual(report['captures'], [])
        self.assertNotIn('logcat', self.commands.read_text())

    def test_unexpected_pm_path_is_not_read_or_interpreted_as_expected_apk(self):
        self.env['MAP_TEST_BAD_PATH'] = '1'
        result, report = self.run_collector()
        self.assertEqual(result.returncode, 3)
        self.assertEqual(report['status'], 'partial')
        for package in collector.PACKAGES:
            self.assertIn('interpretation_error', report['installed_apks'][package])
        self.assertNotIn('sha256sum', self.commands.read_text())

    def test_multiple_devices_archives_error_without_remote_commands(self):
        self.env['MAP_TEST_MULTIPLE'] = '1'
        result, report = self.run_collector()
        self.assertEqual(result.returncode, 3)
        self.assertEqual(report['captures'], [])
        self.assertEqual([json.loads(l) for l in self.commands.read_text().splitlines()], [['devices']])

    def test_deadline_and_partial_size_limit_keep_truthful_receipt(self):
        class LocalCommand:
            def command(self, script, privileged):
                return [sys.executable, '-c', "print('x' * 8192)"]

        record, output = collector.capture_task(LocalCommand(), self.folder, 'limited', 'unused',
                                                time.monotonic() + 10, limit=1024)
        self.assertEqual(record['status'], 'capture_error')
        self.assertIsNone(output)
        self.assertEqual((self.folder / 'limited.txt').stat().st_size, 1024)
        receipt = json.loads((self.folder / 'limited.receipt.json').read_text())
        self.assertEqual(receipt['bytes'], 1024)
        record, _ = collector.capture_task(LocalCommand(), self.folder, 'expired', 'unused',
                                           time.monotonic() - 1)
        self.assertEqual(record['status'], 'skipped_deadline')
        self.assertFalse((self.folder / 'expired.txt').exists())

    def test_packaged_launcher_in_path_with_spaces_is_standalone_and_repeatable(self):
        first, second = self.folder / 'one.zip', self.folder / 'two.zip'
        bundle.build(first); bundle.build(second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            self.assertIsNone(archive.testzip())
            prefix = bundle.DIRECTORY + '/'
            manifest = json.loads(archive.read(prefix + 'MANIFEST.json'))
            for item in manifest['files']:
                data = archive.read(prefix + item['path'])
                self.assertEqual(data, bundle.sources()[item['path']].read_bytes())
                self.assertEqual(hashlib.sha256(data).hexdigest(), item['sha256'])
            self.assertEqual((archive.getinfo(prefix + 'Collect-Maps.command').external_attr >> 16) & 0o777,
                             0o755)
            archive.extractall(self.folder / 'unpacked with spaces')
        launcher = self.folder / 'unpacked with spaces' / bundle.DIRECTORY / 'Collect-Maps.command'
        result, report = self.run_collector(['bash', str(launcher)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(report['status'], 'collected_awaiting_analysis')
