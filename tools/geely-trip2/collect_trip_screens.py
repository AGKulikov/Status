#!/usr/bin/env python3
"""Two bounded passive KX11 trip snapshots, paired with photos of the stock instrument panel.

Python 3.8+ standard library. No APK, vehicle commands, resets, process-wide kills or downloads.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import selectors
import shlex
import shutil
import signal
import subprocess
import sys
import time
import uuid
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_saved_pcaps import audit_capture

VERSION = '1.0.0'
EXPECTED_VHAL = 'b6feed82f777bfa8773c03b78c1f17038e122f492ef85126c095354a29c16756'
VHAL_PATHS = ('/system/vendor/lib64/vhal_v1_0_net_impl-lib.so',
              '/vendor/lib64/vhal_v1_0_net_impl-lib.so')
BPF = ('udp and src host 198.18.34.1 and dst host 198.18.34.15 '
       'and src port 50500 and dst port 50335 and udp[8:4] = 0x006e00c8')
SAMPLES = 20
REMOTE_LIMIT = 30


class CollectionError(RuntimeError):
    pass


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def bounded(argv, timeout=12, limit=65536, output=None, on_chunk=None):
    """Drain both pipes with deadlines and byte limits; kill only this local ADB process group."""
    proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, start_new_session=True)
    selected = selectors.DefaultSelector()
    selected.register(proc.stdout, selectors.EVENT_READ, 'stdout')
    selected.register(proc.stderr, selectors.EVENT_READ, 'stderr')
    data, error = bytearray(), bytearray()
    deadline = time.monotonic() + timeout
    try:
        while selected.get_map():
            if time.monotonic() >= deadline:
                raise CollectionError('Истекло время ожидания ответа магнитолы')
            for key, _ in selected.select(min(.2, max(0, deadline - time.monotonic()))):
                chunk = os.read(key.fileobj.fileno(), 32768)
                if not chunk:
                    selected.unregister(key.fileobj)
                    continue
                target = data if key.data == 'stdout' else error
                remaining = (limit if key.data == 'stdout' else 65536) - len(target)
                overflow = len(chunk) > remaining
                chunk = chunk[:remaining]
                target.extend(chunk)
                if key.data == 'stdout':
                    if output is not None:
                        output.write(chunk)
                        output.flush()
                    if on_chunk is not None:
                        on_chunk(bytes(data))
                if overflow:
                    raise CollectionError('Ответ превысил установленный предел размера')
        try:
            code = proc.wait(timeout=max(.05, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise CollectionError('Команда не завершилась после закрытия потока')
        return bytes(data), error.decode('utf-8', 'replace'), code
    finally:
        selected.close()
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=1)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                if proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=2)
        proc.stdout.close()
        proc.stderr.close()


def adb_path(requested):
    candidates = [requested] if requested else [shutil.which('adb'),
                  str(Path.home() / 'Library/Android/sdk/platform-tools/adb'),
                  '/opt/homebrew/bin/adb', '/usr/local/bin/adb',
                  str(Path.home() / 'Downloads/platform-tools/adb')]
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return str(Path(candidate).resolve())
    raise CollectionError('Не найден adb. Используйте прежний platform-tools или параметр --adb /путь/к/adb')


class Adb:
    def __init__(self, executable, requested=None):
        self.executable = executable
        self.serial = None
        raw, err, code = bounded([executable, 'devices'])
        if code:
            raise CollectionError('ADB не запустился: ' + err[:300])
        devices = []
        for line in raw.decode('utf-8', 'replace').splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1] == 'device':
                devices.append(parts[0])
        if requested:
            devices = [d for d in devices if d == requested]
        if not devices:
            raise CollectionError('Нет подключённой магнитолы. Подключите её через ADB, как для прежнего сборщика, и запустите снова')
        if len(devices) != 1:
            raise CollectionError('Подключено несколько устройств. Выберите магнитолу параметром --serial из adb devices')
        self.serial = devices[0]
        self.root = False

    def command(self, script, privileged=False):
        # The entire remote shell program is one ADB argument; all variable tokens are quoted.
        prefix = 'su 0 sh -c ' if privileged and not self.root else 'sh -c '
        return [self.executable, '-s', self.serial, 'exec-out', prefix + shlex.quote(script)]

    def text(self, script, privileged=False, timeout=12):
        data, err, code = bounded(self.command(script, privileged), timeout)
        if code:
            raise CollectionError('Магнитола не выполнила чтение: ' + err.strip()[:300])
        return data.decode('utf-8', 'replace').strip()

    def preflight(self):
        self.root = self.text('id -u') == '0'
        if self.text('id -u', True) != '0':
            raise CollectionError('Недоступен прежний root-доступ для пассивного tcpdump')
        self.text('toybox timeout 2 true', True)
        tcpdump = self.text(
            'if [ -x /system/xbin/tcpdump ]; then echo /system/xbin/tcpdump; '
            'elif [ -x /system/bin/tcpdump ]; then echo /system/bin/tcpdump; fi', True)
        if tcpdump not in ('/system/xbin/tcpdump', '/system/bin/tcpdump'):
            raise CollectionError('Штатный tcpdump не найден по ранее известным путям')
        # Compile this exact filter before asking for photos; do not start or stop other captures.
        self.text(shlex.join([tcpdump, '-i', 'eth0', '-p', '-nn', '-d', BPF]), True)
        native = []
        for path in VHAL_PATHS:
            try:
                raw = self.text('toybox sha256sum ' + shlex.quote(path), True, timeout=20)
                digest = raw.split()[0] if raw.split() else ''
                if len(digest) == 64 and all(c in '0123456789abcdef' for c in digest):
                    native.append({'path': path, 'sha256': digest})
                    break
            except CollectionError:
                continue
        return tcpdump, {'native_files': native, 'decoder_firmware_matches':
                         any(v['sha256'] == EXPECTED_VHAL for v in native),
                         'expected_vhal_sha256': EXPECTED_VHAL}


def capture(adb, tcpdump, folder, label, firmware_matches):
    receipt = {'label': label, 'status': 'started', 'host_started_utc': now(),
               'host_timezone': str(dt.datetime.now().astimezone().tzinfo),
               'requested_packets': SAMPLES, 'remote_timeout_seconds': REMOTE_LIMIT,
               'photo_window_started_utc': None, 'decoder_firmware_matches': firmware_matches}
    photo_notice = False

    def progress(data):
        nonlocal photo_notice
        if not photo_notice and audit_capture(data).get('counts', {}).get('trip_messages', 0):
            photo_notice = True
            receipt['photo_window_started_utc'] = now()
            print('\aСЕЙЧАС сфотографируйте всю строку «' + label +
                  '» на приборке. Оставьте этот экран до окончания записи.', flush=True)

    argv = ['toybox', 'timeout', str(REMOTE_LIMIT), tcpdump, '-i', 'eth0', '-p', '-nn',
            '-s', '1024', '-U', '-c', str(SAMPLES), '-w', '-', BPF]
    filename = 'trip2.pcap' if label == 'Поездка 2' else 'trip1.pcap'
    destination = folder / filename
    try:
        with destination.open('xb') as stream:
            data, error, code = bounded(adb.command(shlex.join(argv), True),
                                        timeout=REMOTE_LIMIT + 8, limit=256 * 1024,
                                        output=stream, on_chunk=progress)
        receipt['capture_exit_code'] = code
        (folder / (filename + '.stderr.txt')).write_text(error, encoding='utf-8')
        analysis = audit_capture(data)
        counts = analysis.get('counts', {})
        bad = any(value for key, value in counts.items() if key not in ('packet_records', 'trip_messages'))
        complete = code == 0 and counts.get('trip_messages') == SAMPLES and not bad
        receipt['status'] = 'captured' if complete else 'partial_or_unavailable'
    except CollectionError as exc:
        receipt['status'] = 'capture_error'
        receipt['error'] = str(exc)
    except KeyboardInterrupt:
        receipt['status'] = 'interrupted'
        raise
    finally:
        receipt['host_ended_utc'] = now()
        if destination.exists():
            receipt['capture_bytes'] = destination.stat().st_size
            data = destination.read_bytes()
            receipt['capture_sha256'] = hashlib.sha256(data).hexdigest()
            analysis = audit_capture(data)
            receipt['packet_validation'] = analysis.get('counts', {})
            if firmware_matches:
                receipt['observation'] = analysis
            else:
                receipt['interpretation_withheld'] = 'Native hash differs or could not be read; saved bytes need a matching decoder'
        save_json(folder / (filename + '.json'), receipt)
    return receipt


def finalize(folder, report):
    report['finished_utc'] = now()
    save_json(folder / 'RESULT.json', report)
    files = [{'path': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in sorted(folder.iterdir()) if p.is_file() and p.name != 'FILES.json']
    save_json(folder / 'FILES.json', files)
    output = folder.with_suffix('.zip')
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(folder.iterdir()):
            if path.is_file():
                archive.write(path, folder.name + '/' + path.name)
    return output


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb')
    parser.add_argument('--serial')
    parser.add_argument('--output-dir', type=Path, default=Path.home() / 'Desktop')
    parser.add_argument('--plan', action='store_true', help='Показать план без подключения')
    args = parser.parse_args(argv)
    if args.plan:
        print('Поездка 2 и Поездка 1: по 20 входящих сообщений 006e/00c8 и фото штатной строки.\n'
              'Штатный tcpdump, eth0, без promiscuous mode; предел 30 секунд на снимок.\n'
              'Только id, проверка tcpdump/timeout, SHA native-файла и пассивная запись.\n'
              'Без сбросов, настроек автомобиля, установки APK, остановки чужих процессов и выгрузки прошивки.')
        return 0
    args.output_dir.mkdir(parents=True, exist_ok=True)
    name = 'Natro-Trip-Check-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    folder = args.output_dir / name
    folder.mkdir()
    report = {'version': VERSION, 'started_utc': now(), 'status': 'partial', 'points': [],
              'purpose': 'Match PA candidate with stock Trip 1/Trip 2; distance unit and reset remain unverified',
              'trip2_reset': 'automatic_only_user_confirmed_2026-09-13', 'filter': BPF,
              'collector_sources': {name: hashlib.sha256((Path(__file__).resolve().parent / name).read_bytes()).hexdigest()
                                    for name in ('collect_trip_screens.py', 'audit_saved_pcaps.py')}}
    exit_code = 3
    try:
        print('Проверка данных поездки. Машина должна стоять. Счётчики не сбрасывайте.\n'
              '«Поездка 2» сбрасывается автоматически; здесь записываются её текущие показания.\n'
              'Нужны фото штатной приборки, на которых видны название поездки, пробег, время и единицы.', flush=True)
        adb = Adb(adb_path(args.adb), args.serial)
        tcpdump, firmware = adb.preflight()
        report['firmware'] = firmware
        for label in ('Поездка 2', 'Поездка 1'):
            answer = input('\nОткройте «' + label + '» на штатной приборке. Enter — начать, s — пропустить: ').strip().lower()
            if answer == 's':
                report['points'].append({'label': label, 'status': 'skipped'})
                continue
            print('Ожидаю первый пакет. После сигнала сделайте фото…', flush=True)
            point = capture(adb, tcpdump, folder, label, firmware['decoder_firmware_matches'])
            report['points'].append(point)
            print('Запись завершена: ' + ('данные получены.' if point['status'] == 'captured'
                                           else 'есть пропуск; причина сохранена в отчёте.'), flush=True)
        if all(p['status'] == 'captured' for p in report['points']):
            report['status'] = 'captured_awaiting_stock_photos'
            exit_code = 0
    except (CollectionError, OSError) as exc:
        report['error'] = str(exc)
        print('\nНе удалось завершить: ' + str(exc), flush=True)
    except (KeyboardInterrupt, EOFError):
        report['status'] = 'interrupted'
        exit_code = 130
        print('\nСбор остановлен. Сохраняю доступный результат.', flush=True)
    # An interruption inside capture() still writes that point before returning here.
    labels = {p['label'] for p in report['points']}
    for path in sorted(folder.glob('trip*.pcap.json')):
        point = json.loads(path.read_text(encoding='utf-8'))
        if point['label'] not in labels:
            report['points'].append(point)
    output = finalize(folder, report)
    print('\nАрхив результата: ' + str(output.resolve()), flush=True)
    print('Пришлите этот ZIP и фото «Поездки 2» и «Поездки 1», сделанные во время записи.\n'
          'Даже при ошибке архив содержит причину. Исходники сборщика повторно присылать не нужно.', flush=True)
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
