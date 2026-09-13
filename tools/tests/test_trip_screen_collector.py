#!/usr/bin/env python3
"""Synthetic transport and fake-ADB tests; no firmware, user PCAPs or vehicle connection."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/geely-trip2'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


collector = module('trip_screen_collector', SOURCE / 'collect_trip_screens.py')
bundle = module('trip_screen_bundle', SOURCE / 'build_screen_bundle.py')


def synthetic_pcap(count=20):
    # All values are invented test data. Valid Ethernet/IPv4/UDP envelopes, zero UDP checksum.
    result = bytearray(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 1024, 1))
    for i in range(count):
        message = bytearray(728)
        struct.pack_into('>HHI', message, 0, 0x6e, 0xc8, 720)
        struct.pack_into('>4I', message, 16 + 0x30, 1, 600 + i, 0, 0)
        struct.pack_into('>4I', message, 16 + 0x50, 1, 1234567, 0, 0)
        ip = bytearray(20)
        ip[0], ip[8], ip[9] = 0x45, 64, 17
        struct.pack_into('>H', ip, 2, 20 + 8 + len(message))
        ip[12:20] = bytes((198, 18, 34, 1, 198, 18, 34, 15))
        total = sum(struct.unpack('>10H', ip))
        while total >> 16:
            total = (total & 65535) + (total >> 16)
        struct.pack_into('>H', ip, 10, (~total) & 65535)
        frame = bytes(12) + b'\x08\x00' + ip + struct.pack('>4H', 50500, 50335, 736, 0) + message
        result.extend(struct.pack('<4I', 1700000000 + i, 0, len(frame), len(frame)))
        result.extend(frame)
    return bytes(result)


FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, shlex, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['TRIP_TEST_COMMAND_LOG'], 'a') as stream:
    stream.write(json.dumps(args) + '\n')
if args == ['devices']:
    print('List of devices attached')
    print('fake-kx11:5555\tdevice')
    if os.environ.get('TRIP_TEST_MULTIPLE'):
        print('second-device\tdevice')
    raise SystemExit(0)
assert args[:4] == ['-s', os.environ.get('TRIP_TEST_SERIAL', 'fake-kx11:5555'), 'shell', '-T'], args
outer = shlex.split(args[4])
command = outer[-1]
tokens = shlex.split(command)
if command == 'id -u':
    if outer[0] == 'su' and os.environ.get('TRIP_TEST_DENY_ROOT'):
        print('permission denied', file=sys.stderr)
        raise SystemExit(1)
    print('0' if outer[0] == 'su' else '2000')
elif 'natro-trip-stderr-probe' in command:
    sys.stdout.buffer.write(b'\x01\x00\xff\n')
    sys.stdout.buffer.flush()
    if os.environ.get('TRIP_TEST_MERGED_STDERR'):
        print('natro-trip-stderr-probe')
    else:
        print('natro-trip-stderr-probe', file=sys.stderr)
    raise SystemExit(0 if os.environ.get('TRIP_TEST_LOST_EXIT_CODE') else 17)
elif command == 'toybox timeout 2 true':
    pass
elif command.startswith('if [ -x /system/xbin/tcpdump'):
    print('/system/xbin/tcpdump')
elif tokens[0] == '/system/xbin/tcpdump' and '-d' in tokens:
    print('1\n6 0 0 65535')
elif tokens[:2] == ['toybox', 'sha256sum']:
    print(os.environ['TRIP_TEST_DIGEST'] + '  ' + tokens[2])
elif tokens[:3] == ['toybox', 'timeout', '30']:
    assert tokens[3] == '/system/xbin/tcpdump'
    sys.stdout.buffer.write(Path(os.environ['TRIP_TEST_PCAP']).read_bytes())
    sys.stdout.buffer.flush()
    print('synthetic capture', file=sys.stderr)
    raise SystemExit(int(os.environ.get('TRIP_TEST_CAPTURE_EXIT', '0')))
else:
    raise AssertionError(command)
'''


class TripScreenCollectorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='trip screen test ')
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.adb = self.folder / 'fake adb'
        self.adb.write_text(FAKE_ADB, encoding='utf-8')
        self.adb.chmod(0o755)
        self.log = self.folder / 'commands.jsonl'
        self.pcap = self.folder / 'synthetic.pcap'
        self.pcap.write_bytes(synthetic_pcap())
        self.env = dict(os.environ, TRIP_TEST_COMMAND_LOG=str(self.log),
                        TRIP_TEST_DIGEST=collector.EXPECTED_VHAL, TRIP_TEST_PCAP=str(self.pcap))

    def run_collector(self, input_text='\n\n', extra=(), launcher=False):
        entry = (['bash', str(SOURCE / 'Collect-Trip-Screens.command')] if launcher
                 else [sys.executable, str(SOURCE / 'collect_trip_screens.py')])
        run = subprocess.run(entry + ['--adb', str(self.adb), '--output-dir', str(self.folder)] + list(extra),
                             input=input_text, text=True, capture_output=True, env=self.env,
                             cwd=self.folder, timeout=15)
        outputs = list(self.folder.glob('Natro-Trip-Check-*.zip'))
        self.assertEqual(len(outputs), 1, run.stdout + run.stderr)
        archive_path = outputs[0]
        with zipfile.ZipFile(archive_path) as archive:
            self.assertIsNone(archive.testzip())
            prefix = archive_path.stem + '/'
            report = json.loads(archive.read(prefix + 'RESULT.json'))
            files = json.loads(archive.read(prefix + 'FILES.json'))
            for entry in files:
                data = archive.read(prefix + entry['path'])
                self.assertEqual(len(data), entry['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), entry['sha256'])
        return run, report, archive_path

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_both_photos_and_exact_passive_capture_commands(self):
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(report['status'], 'captured_awaiting_stock_photos')
        self.assertEqual([p['label'] for p in report['points']], ['Поездка 2', 'Поездка 1'])
        self.assertEqual(run.stdout.count('СЕЙЧАС'), 2)
        for point in report['points']:
            self.assertEqual(point['status'], 'captured')
            self.assertIsNotNone(point['photo_window_started_utc'])
            self.assertEqual(point['observation']['first']['time_words'], [1, 600, 0, 0])
            self.assertEqual(point['observation']['last']['time_words'], [1, 619, 0, 0])
            self.assertEqual(point['observation']['distance_raw_values'], [1234567])
        commands = [shlex.split(c[-1])[-1] for c in self.commands() if 'shell' in c]
        captures = [shlex.split(c) for c in commands if c.startswith('toybox timeout 30 ')]
        self.assertEqual(len(captures), 2)
        for command in captures:
            self.assertEqual(command, ['toybox', 'timeout', '30', '/system/xbin/tcpdump', '-i', 'eth0',
                                       '-p', '-nn', '-s', '1024', '-U', '-c', '20', '-w', '-', collector.BPF])
        self.assertEqual(collector.BPF, 'udp and src host 198.18.34.1 and dst host 198.18.34.15 '
                         'and src port 50500 and dst port 50335 and udp[8:4] = 0x006e00c8')
        for dangerous in ('pkill', 'killall', 'setprop', 'service call', 'install ', 'push ', 'reboot'):
            self.assertFalse(any(dangerous in c for c in commands), dangerous)

    def test_merged_device_stderr_stops_before_binary_capture(self):
        self.env['TRIP_TEST_MERGED_STDERR'] = '1'
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertIn('отдельный stderr', report['error'])
        self.assertEqual(report['points'], [])
        self.assertNotIn('tcpdump', self.log.read_text())

    def test_missing_remote_exit_status_stops_before_binary_capture(self):
        self.env['TRIP_TEST_LOST_EXIT_CODE'] = '1'
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertIn('код выхода', report['error'])
        self.assertEqual(report['points'], [])
        self.assertNotIn('tcpdump', self.log.read_text())

    def test_unknown_firmware_keeps_bytes_without_decoded_fields(self):
        self.env['TRIP_TEST_DIGEST'] = 'a' * 64
        run, report, archive_path = self.run_collector()
        self.assertEqual(run.returncode, 0)
        self.assertFalse(report['firmware']['decoder_firmware_matches'])
        for point in report['points']:
            self.assertNotIn('observation', point)
            self.assertIn('interpretation_withheld', point)
        with zipfile.ZipFile(archive_path) as archive:
            self.assertEqual(archive.read(archive_path.stem + '/trip2.pcap'), self.pcap.read_bytes())

    def test_timeout_exit_with_partial_packets_is_not_success(self):
        self.pcap.write_bytes(synthetic_pcap(3))
        self.env['TRIP_TEST_CAPTURE_EXIT'] = '124'
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertEqual(report['status'], 'partial')
        self.assertEqual(report['points'][0]['capture_exit_code'], 124)
        self.assertEqual(report['points'][0]['packet_validation']['trip_messages'], 3)

    def test_empty_capture_does_not_signal_photo_or_success(self):
        self.pcap.write_bytes(synthetic_pcap(0))
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertNotIn('СЕЙЧАС', run.stdout)
        self.assertTrue(all(p['status'] == 'partial_or_unavailable' for p in report['points']))

    def test_damaged_tail_is_not_success_despite_twenty_valid_packets(self):
        self.pcap.write_bytes(synthetic_pcap() + b'broken-tail')
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertEqual(report['points'][0]['packet_validation']['unparsed_tail_bytes'], 11)

    def test_denied_root_archives_error_before_any_capture(self):
        self.env['TRIP_TEST_DENY_ROOT'] = '1'
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertIn('permission denied', report['error'])
        self.assertEqual(report['points'], [])
        self.assertNotIn('tcpdump', self.log.read_text())

    def test_multiple_devices_requires_selection_before_remote_read(self):
        self.env['TRIP_TEST_MULTIPLE'] = '1'
        run, report, _ = self.run_collector()
        self.assertEqual(run.returncode, 3)
        self.assertIn('несколько устройств', report['error'])
        self.assertEqual(self.commands(), [['devices']])

    def test_explicit_serial_selects_second_device(self):
        self.env.update(TRIP_TEST_MULTIPLE='1', TRIP_TEST_SERIAL='second-device')
        run, _, _ = self.run_collector(extra=('--serial', 'second-device'))
        self.assertEqual(run.returncode, 0, run.stderr)

    def test_eof_saves_interrupted_archive(self):
        run, report, _ = self.run_collector(input_text='')
        self.assertEqual(run.returncode, 130)
        self.assertEqual(report['status'], 'interrupted')
        self.assertEqual(report['points'], [])

    def test_interrupted_capture_preserves_prefix_and_point_receipt(self):
        class FakeAdb:
            def command(self, script, privileged):
                return ['unused-fake-adb']

        def interrupted(argv, **kwargs):
            kwargs['output'].write(synthetic_pcap(2))
            kwargs['on_chunk'](synthetic_pcap(2))
            raise KeyboardInterrupt()

        with patch.object(collector, 'bounded', side_effect=interrupted), patch('sys.stdout', new=io.StringIO()):
            with self.assertRaises(KeyboardInterrupt):
                collector.capture(FakeAdb(), '/system/xbin/tcpdump', self.folder, 'Поездка 2', True)
        receipt = json.loads((self.folder / 'trip2.pcap.json').read_text())
        self.assertEqual(receipt['status'], 'interrupted')
        self.assertEqual(receipt['packet_validation']['trip_messages'], 2)

    def test_invalid_explicit_adb_does_not_choose_another_executable(self):
        with patch.object(collector.shutil, 'which', return_value=str(self.adb)):
            with self.assertRaises(collector.CollectionError):
                collector.adb_path(str(self.folder / 'does not exist'))

    def test_deadline_and_size_caps_keep_partial_output(self):
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            started = time.monotonic()
            with io.BytesIO() as stream:
                with self.assertRaises(collector.CollectionError):
                    collector.bounded([sys.executable, '-c', 'import sys,time; '
                                       'sys.stdout.buffer.write(b"prefix"); sys.stdout.flush(); time.sleep(30)'],
                                      timeout=1, output=stream)
                self.assertEqual(stream.getvalue(), b'prefix')
            self.assertLess(time.monotonic() - started, 5)
            self.assertIsNone(unrelated.poll())
            with io.BytesIO() as stream:
                with self.assertRaises(collector.CollectionError):
                    collector.bounded([sys.executable, '-c', 'import sys; sys.stdout.buffer.write(b"x"*70000)'],
                                      limit=1024, output=stream)
                self.assertEqual(stream.getvalue(), b'x' * 1024)
            with self.assertRaises(collector.CollectionError):
                collector.bounded([sys.executable, '-c', 'import sys; sys.stderr.buffer.write(b"x"*70000)'])
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)

    def test_launcher_and_deterministic_self_contained_bundle(self):
        first, second = self.folder / 'one.zip', self.folder / 'two.zip'
        bundle.build(first)
        bundle.build(second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            self.assertEqual(len(archive.infolist()), 5)
            prefix = bundle.DIRECTORY + '/'
            self.assertEqual(archive.getinfo(prefix + 'Collect-Trip-Screens.command').external_attr >> 16, 0o100755)
            manifest = json.loads(archive.read(prefix + 'MANIFEST.json'))
            for item in manifest['files']:
                self.assertEqual(hashlib.sha256(archive.read(prefix + item['path'])).hexdigest(), item['sha256'])
            archive.extractall(self.folder / 'extracted with spaces')
        extracted = self.folder / 'extracted with spaces' / bundle.DIRECTORY
        plan = subprocess.run(['bash', str(extracted / 'Collect-Trip-Screens.command'), '--plan'],
                              input='\n', text=True, capture_output=True, cwd=self.folder, timeout=5)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn('Поездка 2 и Поездка 1', plan.stdout)
        self.assertFalse(self.log.exists())
        # Run the launcher through both capture points as well as the dependency-free plan.
        run, report, _ = self.run_collector(input_text='\n\n\n', launcher=True)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(report['status'], 'captured_awaiting_stock_photos')


if __name__ == '__main__':
    unittest.main()
