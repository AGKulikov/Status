#!/usr/bin/env python3
"""Bounded, read-only Android diagnostics for Natro's two external map surfaces."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import sys
import time
import uuid
import datetime as dt

HERE = Path(__file__).resolve().parent
if not (HERE / 'collect_trip_screens.py').is_file():
    sys.path.insert(0, str(HERE.parent / 'geely-trip2'))
from collect_trip_screens import Adb, CollectionError, adb_path, bounded, finalize, now

VERSION = '1.0.0'
TOTAL_SECONDS = 90
REMOTE_SECONDS = 10
PACKAGES = ('ru.natro.statuswidget', 'ru.yandex.yandexnavi')
EXPECTED_APKS = {
    'ru.natro.statuswidget': 'bcbfd5c0fc044d309ada196cc260d7459f89f9956466005bf98d8e4ca1e1db3b',
    'ru.yandex.yandexnavi': '1bb5ba22cd2a60726414779fa9d8ebb37973ee756f4e9f401f9efc5bc63da4a2',
}
LOG_FILTER = ('NatroHudMap:V', 'NavigationHudEndpoint:V', 'NatroNavigatorHook:V',
              'NatroBackgroundMap:V', 'AndroidRuntime:E', 'libc:F', 'DEBUG:F',
              'BufferQueueProducer:W', 'BufferQueueConsumer:W', 'Surface:W',
              'OpenGLRenderer:W', '*:S')


def capture_task(adb, folder, name, script, deadline, limit=512 * 1024, privileged=False):
    """Preserve partial bytes and a receipt even when one optional diagnostic fails."""
    record = {'name': name, 'host_started_utc': now(), 'status': 'started',
              'max_bytes': limit, 'remote_timeout_seconds': REMOTE_SECONDS}
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        record['status'] = 'skipped_deadline'
        return record, None
    output = folder / (name + '.txt')
    data = None
    try:
        remote = shlex.join(['toybox', 'timeout', str(REMOTE_SECONDS), 'sh', '-c', script])
        with output.open('xb') as stream:
            data, error, code = bounded(adb.command(remote, privileged),
                                        timeout=min(REMOTE_SECONDS + 2, remaining),
                                        limit=limit, output=stream)
        (folder / (name + '.stderr.txt')).write_text(error, encoding='utf-8')
        record.update(status='collected' if code == 0 else 'command_failed', exit_code=code)
    except CollectionError as exc:
        record.update(status='capture_error', error=str(exc))
    except KeyboardInterrupt:
        record['status'] = 'interrupted'
        raise
    finally:
        record['host_ended_utc'] = now()
        if output.exists():
            raw = output.read_bytes()
            record.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        (folder / (name + '.receipt.json')).write_text(
            json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return record, data if record['status'] == 'collected' else None


def package_paths(data):
    """Accept only pm's absolute APK paths; quote each before the remote hash command."""
    paths = []
    for line in data.decode('utf-8', 'strict').splitlines():
        if not line.startswith('package:'):
            raise ValueError('Unexpected pm path response')
        path = line[len('package:'):]
        if not path.startswith(('/data/app/', '/system/', '/product/', '/vendor/')):
            raise ValueError('Unexpected APK directory')
        if not path.endswith('.apk') or any(ord(c) < 32 for c in path) or '..' in Path(path).parts:
            raise ValueError('Invalid APK path')
        paths.append(path)
    if not paths or len(paths) > 32:
        raise ValueError('Unexpected number of APK paths')
    return paths


def collect(adb, folder, report, deadline):
    adb.root = adb.text('id -u') == '0'
    try:
        root_available = adb.text('id -u', True) == '0'
    except CollectionError:
        root_available = False
    report['root_available'] = root_available
    probe = "printf '\\001\\000\\377\\012'; printf 'natro-map-stderr-probe\\n' >&2; exit 17"
    data, error, code = bounded(adb.command(probe, root_available), timeout=10)
    if data != b'\x01\x00\xff\n' or error != 'natro-map-stderr-probe\n' or code != 17:
        raise CollectionError('ADB не сохраняет отдельные потоки и код выхода; сбор остановлен')
    report['transport_probe_passed'] = True
    report['installed_apks'] = {}

    def task(name, script, limit=512 * 1024, privileged=False):
        record, output = capture_task(adb, folder, name, script, deadline, limit, privileged)
        report['captures'].append(record)
        print(name + ': ' + record['status'], flush=True)
        return output

    # Read the already available failure first, before slower package/display snapshots.
    task('map-logcat', shlex.join(['logcat', '-d', '-v', 'threadtime', '-t', '10000',
                                  *LOG_FILTER]), 2 * 1024 * 1024, root_available)
    if root_available:
        journal = '/data/user/0/ru.natro.statuswidget/files/diagnostics/journal.log'
        fallback = '/data/data/ru.natro.statuswidget/files/diagnostics/journal.log'
        task('natro-journal', 'if [ -f ' + shlex.quote(journal) + ' ]; then cat '
             + shlex.quote(journal) + '; else cat ' + shlex.quote(fallback) + '; fi',
             1600 * 1024, True)
    else:
        report['captures'].append({'name': 'natro-journal', 'status': 'skipped_no_root'})
    task('android-version', 'getprop ro.build.version.sdk; getprop ro.build.fingerprint; '
         'getprop ro.product.cpu.abilist; toybox date -u; cat /proc/uptime', 16384)
    for package in PACKAGES:
        short = 'natro' if package == PACKAGES[0] else 'navigator'
        path_data = task(short + '-apk-paths', shlex.join(['pm', 'path', package]), 32768)
        if path_data is not None:
            try:
                paths = package_paths(path_data)
                raw = task(short + '-apk-sha256', shlex.join(['toybox', 'sha256sum', *paths]),
                           32768, root_available)
                if raw is not None:
                    hashes = []
                    for line in raw.decode('utf-8', 'strict').splitlines():
                        match = re.fullmatch(r'([0-9a-f]{64})\s+(.+)', line)
                        if match is None or match[2] not in paths:
                            raise ValueError('Invalid APK hash response')
                        hashes.append({'path': match[2], 'sha256': match[1]})
                    if sorted(v['path'] for v in hashes) != sorted(paths):
                        raise ValueError('Incomplete APK hashes')
                    report['installed_apks'][package] = {
                        'files': hashes, 'expected_base_sha256': EXPECTED_APKS[package],
                        'contains_expected_base': any(v['sha256'] == EXPECTED_APKS[package]
                                                      for v in hashes)}
            except (UnicodeError, ValueError) as exc:
                report['installed_apks'][package] = {'interpretation_error': str(exc)}
        task(short + '-package', shlex.join(['dumpsys', 'package', package]))
        task(short + '-services', shlex.join(['dumpsys', 'activity', 'services', package]))
        task(short + '-activities', shlex.join(['dumpsys', 'activity', 'activities', package]))
        task(short + '-graphics', shlex.join(['dumpsys', 'gfxinfo', package]))
    task('display-state', 'dumpsys display')
    task('window-state', 'dumpsys window windows')
    task('surface-list', 'dumpsys SurfaceFlinger --list', 256 * 1024)
    task('map-logcat-final', shlex.join(['logcat', '-d', '-v', 'threadtime', '-t', '10000',
                                        *LOG_FILTER]), 2 * 1024 * 1024, root_available)
    hashes_complete = len(report['installed_apks']) == len(PACKAGES) and all(
        'interpretation_error' not in value for value in report['installed_apks'].values())
    report['status'] = ('collected_awaiting_analysis' if hashes_complete and all(
        r['status'] == 'collected' for r in report['captures']) else 'partial')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    parser.add_argument('--output-dir', type=Path, default=Path.home() / 'Desktop')
    parser.add_argument('--plan', action='store_true')
    args = parser.parse_args(argv)
    if args.plan:
        print('Снимок текущего отказа карт HUD/приборки: журналы Natro/MapKit, версии и SHA APK,\n'
              'состояние служб, Activity, окон, дисплеев и графики. До 90 секунд.\n'
              'Только чтение Android. Карты и приложения не перезапускаются, настройки не меняются.\n'
              'APK, пароли, настройки аккаунтов и прошивка не скачиваются. Результат — ZIP на рабочем столе.')
        return 0
    args.output_dir.mkdir(parents=True, exist_ok=True)
    name = 'Natro-Map-Check-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    folder = args.output_dir / name
    folder.mkdir()
    report = {'version': VERSION, 'started_utc': now(), 'status': 'started', 'captures': [],
              'purpose': 'Locate the remaining HUD/cluster map startup failure after Navigator mapfix',
              'total_deadline_seconds': TOTAL_SECONDS,
              'collector_sources': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in [Path(__file__), Path(sys.modules['collect_trip_screens'].__file__),
                                              Path(sys.modules['audit_saved_pcaps'].__file__)]}}
    deadline = time.monotonic() + TOTAL_SECONDS
    code = 0
    try:
        print('Оставьте Natro и Навигатор в состоянии, когда обе карты не запускаются.\n'
              'Снимаю текущие журналы и состояние Android…', flush=True)
        adb = Adb(adb_path(args.adb), args.serial)
        collect(adb, folder, report, deadline)
        if report['status'] == 'partial':
            code = 3
    except KeyboardInterrupt:
        report['status'] = 'interrupted'
        code = 130
    except (CollectionError, OSError, ValueError) as exc:
        report.update(status='failed', error=str(exc))
        code = 3
    finally:
        output = finalize(folder, report)
        print('\nГотов архив: ' + str(output.resolve()) + '\nПришлите этот ZIP в разговор.', flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
