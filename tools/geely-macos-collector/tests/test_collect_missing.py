"""Independent offline tests; FakeADB and authored local subprocesses only."""
from __future__ import annotations
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / 'collect_missing.py'
spec = importlib.util.spec_from_file_location('kx11_collect_missing_tested', MODULE_PATH)
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def metadata(data):
    return 'KX_FILE_V1\n%s\n1700000000\n%s  ignored-file-name\n' % (len(data), digest(data))


class FakeADB:
    def __init__(self, payload=b'installed-ELF-fixture', metadata_text=None, stream_error=None):
        self.payload = payload
        self.metadata_text = metadata_text if metadata_text is not None else metadata(payload)
        self.stream_error = stream_error
        self.text_calls = []
        self.shell_calls = []
        self.executable = 'FAKE_ADB_NEVER_EXECUTED'
        self.serial = 'FAKE_DEVICE'

    def text(self, script, **kwargs):
        self.text_calls.append((script, kwargs))
        return self.metadata_text

    def shell(self, script, **kwargs):
        self.shell_calls.append((script, kwargs))
        stream = kwargs['output']
        stream.write(self.payload)
        if self.stream_error:
            raise self.stream_error
        return b'', len(self.payload)


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.target = {'id': 'bt-native', 'path': '/system/lib64/libbluetooth.so',
                       'reason': 'Resolve native callback status', 'max_bytes': 1024}

    def tearDown(self):
        self.temporary.cleanup()

    def assert_status_raises(self, status, callback, *args, **kwargs):
        with self.assertRaises(core.AcquisitionError) as caught:
            callback(*args, **kwargs)
        self.assertEqual(caught.exception.status, status)

    def test_path_and_package_rejection(self):
        rejected = ['/data/misc/bluedroid/256.key', '/data/user/0/pkg/database.db',
                    '/system/../data/private.apk', '/system//lib/libx.so',
                    '/system/lib/./libx.so', '/system/lib/libx.so\ncat /private',
                    '/system/lib\\libx.so', '/system/credentials/private.json',
                    '/system/keystore/x.xml', '/system/lib/pair.key',
                    '/systemX/lib/libx.so']
        for path in rejected:
            with self.subTest(path=path):
                self.assert_status_raises('rejected', core.safe_file_path, path)
        for package in ['a; touch bad', 'ru.natro.statuswidget$(id)', 'pkg\nsecret', '../x']:
            with self.subTest(package=package):
                self.assert_status_raises('rejected', core.safe_package, package)

    def test_remote_path_metacharacters_are_data_at_both_shell_boundaries(self):
        path = "/system/lib64/a'$(echo FORBIDDEN);`echo FORBIDDEN`.so"
        adb = FakeADB()
        core.file_metadata(adb, path)
        script = adb.text_calls[0][0]
        self.assertEqual(shlex.split(script.splitlines()[0]), ['p=' + path])
        with patch.object(core, 'run_bounded', return_value=(b'', 0)) as bounded:
            real = core.ADB('/fake/adb', 'SERIAL;echo FORBIDDEN')
            real.shell(script)
        argv = bounded.call_args.args[0]
        self.assertEqual(argv[:4], ['/fake/adb', '-s', 'SERIAL;echo FORBIDDEN', 'exec-out'])
        self.assertEqual(shlex.split(argv[4]), ['sh', '-c', script])

    def test_fresh_known_hash_transfers_no_binary(self):
        adb = FakeADB()
        receipt = core.collect_android_file(adb, self.target, self.root, {digest(adb.payload)}, {})
        self.assertEqual(receipt['status'], 'known_duplicate')
        self.assertEqual(len(adb.text_calls), 1)
        self.assertEqual(adb.shell_calls, [])
        self.assertEqual(list(self.root.rglob('*')), [])
        self.assertIn('finished_at', receipt)

    def test_duplicate_within_run_transfers_no_binary(self):
        adb = FakeADB()
        seen = {digest(adb.payload): 'android/files/system/lib64/prior.so'}
        receipt = core.collect_android_file(adb, self.target, self.root, set(), seen)
        self.assertEqual(receipt['status'], 'duplicate_in_run')
        self.assertEqual(receipt['same_content_as'], seen[digest(adb.payload)])
        self.assertEqual(adb.shell_calls, [])

    def test_success_requires_exact_size_and_hash(self):
        adb = FakeADB()
        seen = {}
        receipt = core.collect_android_file(adb, self.target, self.root, set(), seen)
        self.assertEqual(receipt['status'], 'collected')
        saved = self.root / receipt['local_path']
        self.assertEqual(saved.read_bytes(), adb.payload)
        self.assertEqual(receipt['verified_download_sha256'], digest(adb.payload))
        self.assertEqual(seen[digest(adb.payload)], receipt['local_path'])
        self.assertFalse(list(self.root.rglob('*.partial')))

    def test_hash_mismatch_is_failure_and_removes_partial(self):
        adb = FakeADB(payload=b'replaced', metadata_text=metadata(b'original'))
        seen = {}
        receipt = core.collect_android_file(adb, self.target, self.root, set(), seen)
        self.assertEqual(receipt['status'], 'changed_during_read')
        self.assertFalse(list(self.root.rglob('*.partial')))
        self.assertFalse((self.root / 'android/files/system/lib64/libbluetooth.so').exists())
        self.assertEqual(seen, {})
        self.assertNotIn('verified_download_sha256', receipt)

    def test_transfer_exception_removes_partial(self):
        adb = FakeADB(stream_error=core.AcquisitionError('permission_denied', 'safe fixed error'))
        receipt = core.collect_android_file(adb, self.target, self.root, set(), {})
        self.assertEqual(receipt['status'], 'permission_denied')
        self.assertFalse(list(self.root.rglob('*.partial')))
        self.assertFalse((self.root / 'android/files/system/lib64/libbluetooth.so').exists())

    def test_missing_denied_irregular_and_oversized_do_not_transfer(self):
        for body, status in [('KX_MISSING\n', 'missing'), ('KX_DENIED\n', 'permission_denied'),
                             ('KX_NOT_REGULAR\n', 'not_regular'),
                             ('KX_FILE_V1\n999999\n1700000000\n' + 'a' * 64 + '\n', 'oversized')]:
            with self.subTest(status=status):
                adb = FakeADB(metadata_text=body)
                receipt = core.collect_android_file(adb, self.target, self.root, set(), {})
                self.assertEqual(receipt['status'], status)
                self.assertEqual(adb.shell_calls, [])
                self.assertFalse(list(self.root.rglob('*.partial')))

    def test_bad_metadata_never_becomes_empty_success(self):
        cases = ['', 'PRIVATE_RAW_STDOUT', 'KX_FILE_V1\n0\n0\n\n',
                 'KX_FILE_V1\nNaN\n0\n' + 'a' * 64,
                 'KX_FILE_V1\n0\n0\nnot-a-sha', 'KX_FILE_V1\n0']
        for body in cases:
            with self.subTest(body=body):
                self.assert_status_raises('metadata_unavailable', core.parse_metadata, body)

    def test_bounded_stdout_and_stderr(self):
        # Only a tiny, authored Python program executes; no ADB or original artifact runs.
        self.assert_status_raises('oversized', core.run_bounded,
            [sys.executable, '-c', "import sys;sys.stdout.write('x'*5000)"], max_bytes=128)
        self.assert_status_raises('oversized', core.run_bounded,
            [sys.executable, '-c', "import sys;sys.stderr.write('x'*70000)"], max_bytes=128)

    def test_failed_command_does_not_echo_private_stdout(self):
        secret = 'PRIVATE_RAW_STDOUT_PHONE_CONTACT_PAYLOAD'
        with self.assertRaises(core.AcquisitionError) as caught:
            core.run_bounded([sys.executable, '-c',
                "import sys;sys.stdout.write(%r);sys.stderr.write('Permission denied');sys.exit(1)" % secret])
        self.assertEqual(caught.exception.status, 'permission_denied')
        self.assertNotIn(secret, str(caught.exception))

    def test_preflight_zero_multiple_unauthorized_and_explicit_selection(self):
        cases = [('List of devices attached\n', 'no_device'),
                 ('List of devices attached\nA\tunauthorized\n', 'unauthorized'),
                 ('List of devices attached\nA\tdevice\nB\tdevice\n', 'multiple_devices')]
        for raw, status in cases:
            with self.subTest(status=status):
                with patch.object(core, 'run_bounded', return_value=(raw.encode(), len(raw))):
                    self.assert_status_raises(status, core.ADB('/fake/adb').select)
        raw = b'List of devices attached\nA\tdevice\nB\tdevice\n'
        with patch.object(core, 'run_bounded', return_value=(raw, len(raw))):
            adb = core.ADB('/fake/adb')
            self.assertEqual(adb.select('B'), 'B')
            self.assertEqual(adb.serial, 'B')
            self.assert_status_raises('no_device', core.ADB('/fake/adb').select, 'C')

    def test_package_and_maps_projections_do_not_claim_class_origin(self):
        package_text = '''  versionCode=208021310 minSdk=28 targetSdk=28
  versionName=2.7.7
  codePath=/data/app/ru.natro.statuswidget-test/base.apk
  userId=10081
  primaryCpuAbi=arm64-v8a
  callerName=PRIVATE_PERSON
  phoneNumber=PRIVATE_PHONE
  notification=PRIVATE_NOTIFICATION
  signingKey=PRIVATE_KEY
'''
        result = core.package_projection(package_text)
        self.assertEqual(result['versionName'], '2.7.7')
        self.assertEqual(result['versionCode'], '208021310')
        self.assertEqual(result['minSdk'], '28')
        self.assertEqual(result['targetSdk'], '28')
        self.assertNotIn('PRIVATE_', json.dumps(result))
        self.assertNotIn('classloader_origin_proven', result)
        maps = '''1000-2000 r-xp 0000 00:00 0 /system/lib64/libbluetooth.so
2000-3000 r--p 0000 00:00 0 /system/lib64/libbluetooth.so
3000-4000 r--p 0000 00:00 0 /data/user/0/pkg/files/PRIVATE_USER.so
4000-5000 r--p 0000 00:00 0 /system/lib64/private.so (deleted)
5000-6000 rw-p 0000 00:00 0 [anon:PRIVATE_MEMORY]
not-a-map PRIVATE_PHONE
'''
        projected = core.maps_projection(maps)
        self.assertEqual(projected, [{'path': '/system/lib64/libbluetooth.so', 'permissions': ['r--p', 'r-xp']}])
        self.assertNotIn('PRIVATE_', json.dumps(projected))

    def test_package_projection_reads_version_fields_on_one_whitespace_delimited_line(self):
        for separator in (' ', '\t', '  \t '):
            with self.subTest(separator=repr(separator)):
                text = '  ' + separator.join(('versionCode=208021310', 'minSdk=28', 'targetSdk=28')) + '\n'
                self.assertEqual(core.package_projection(text), {
                    'versionCode': '208021310', 'minSdk': '28', 'targetSdk': '28',
                })

    def test_package_receipt_preserves_origin_limit_and_never_saves_raw(self):
        responses = {
            'pm path': 'package:/system/app/Bluetooth/Bluetooth.apk\nPRIVATE_RAW_STDOUT\n',
            'dumpsys package': 'versionName=9\nversionCode=28\nnotification=PRIVATE_NOTIFICATION\n',
            'pidof': '123 456;touchPRIVATE badpid\n',
            'cat /proc/123/maps': '1000-2000 r-xp 0000 00:00 0 /system/lib64/libbluetooth.so\nPRIVATE_USER\n',
        }
        class PackageADB:
            def text(self, script, **kwargs):
                for prefix, value in responses.items():
                    if script.startswith(prefix):
                        return value
                raise AssertionError('Unexpected mock command: ' + script)
        targets, record = core.package_targets(PackageADB(), {'name': 'com.android.bluetooth', 'reason': 'known process'}, self.root)
        self.assertFalse(record['classloader_origin_proven'])
        self.assertEqual(record['running_process_count'], 1)
        self.assertIn('/system/app/Bluetooth/Bluetooth.apk', [t['path'] for t in targets])
        self.assertNotIn('PRIVATE_', json.dumps(record))
        for path in self.root.rglob('*'):
            if path.is_file():
                self.assertNotIn('PRIVATE_', path.read_text())

    def test_absent_candidate_is_recorded_without_error_or_more_commands(self):
        class AbsentADB:
            def __init__(self):
                self.calls = []
            def text(self, script, **kwargs):
                self.calls.append(script)
                return ''
        adb = AbsentADB()
        targets, record = core.package_targets(adb, {'name': 'com.example.optional',
            'reason': 'Observed candidate only', 'presence_probe': True}, self.root)
        self.assertEqual(targets, [])
        self.assertEqual(record['presence_status'], 'not_installed')
        self.assertEqual(record['errors'], [])
        self.assertEqual(adb.calls, ['pm path com.example.optional'])
        saved = json.loads((self.root / 'android/packages/com.example.optional.json').read_text())
        self.assertEqual(saved['presence_status'], 'not_installed')
        self.assertEqual(saved['errors'], [])
        self.assertEqual(saved['apk_paths'], [])
        self.assertFalse(saved['classloader_origin_proven'])

        required_adb = AbsentADB()
        required_targets, required_record = core.package_targets(required_adb, {
            'name': 'com.example.required', 'reason': 'Required installed-code source',
        }, self.root)
        self.assertEqual(required_targets, [])
        self.assertEqual(required_record['presence_status'], 'not_installed')
        self.assertEqual(required_record['errors'], [{'status': 'package_not_found'}])
        self.assertEqual(required_adb.calls, ['pm path com.example.required'])

    def test_sealed_archive_contains_verifiable_manifest(self):
        destination = self.root / 'evidence'
        destination.mkdir()
        (destination / 'safe.json').write_text('{"event":"connected","status":0}\n')
        report = {'status': 'COMPLETED_COLLECTION_PLAN', 'receipts': [{'id': 'bt', 'status': 'projected', 'raw_saved': False}]}
        archive = core.seal(destination, report)
        self.assertTrue(archive.exists())
        expected = archive.with_suffix(archive.suffix + '.sha256').read_text().split()[0]
        self.assertEqual(expected, digest(archive.read_bytes()))
        manifest = json.loads((destination / 'FILES.json').read_text())
        self.assertNotIn('FILES.json', [f['path'] for f in manifest['files']])
        for row in manifest['files']:
            data = (destination / row['path']).read_bytes()
            self.assertEqual(row['bytes'], len(data))
            self.assertEqual(row['sha256'], digest(data))
        with tarfile.open(archive) as tar:
            self.assertIn('evidence/RESULT.json', tar.getnames())
            self.assertIn('evidence/FILES.json', tar.getnames())

    def test_preflight_failure_still_seals_partial_summary(self):
        plan = {'android_files': [], 'packages': [], 'qnx_files': [], 'bluetooth_log_command': 'unused'}
        fixture_root = self.root / 'collector'
        fixture_root.mkdir()
        (fixture_root / 'collection_plan.json').write_text(json.dumps(plan))
        args = argparse.Namespace(output=self.root, adb='/fake/adb', serial=None, no_bluetooth=True, no_qnx=True)
        output = io.StringIO()
        with patch.object(core, 'ROOT', fixture_root), patch.object(core, 'load_known', return_value=set()), \
             patch.object(core, 'find_adb', return_value='/fake/adb'), \
             patch.object(core.ADB, 'select', side_effect=core.AcquisitionError('unauthorized', 'No authorized online ADB device')), \
             contextlib.redirect_stdout(output):
            result = core.collect(args, plan)
        self.assertEqual(result, 3)
        archives = list(self.root.glob('KX11-Missing-Evidence-*.tar.gz'))
        self.assertEqual(len(archives), 1)
        with tarfile.open(archives[0]) as tar:
            member = next(m for m in tar.getmembers() if m.name.endswith('/RESULT.json'))
            report = json.load(tar.extractfile(member))
        self.assertEqual(report['status'], 'PARTIAL')
        self.assertEqual(report['fatal']['status'], 'unauthorized')
        self.assertEqual(report['receipts'], [])
        self.assertNotIn('class origin proven', output.getvalue())


if __name__ == '__main__':
    unittest.main()
