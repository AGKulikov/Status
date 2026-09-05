#!/usr/bin/env python3
"""Targeted KX11 acquisition using existing ADB access; Python 3.8+ stdlib."""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import uuid

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'lib'))
VERSION = '1.0.0'
MAX_TEXT = 8 * 1024 * 1024
FILE_ROOTS = ('/system/', '/vendor/', '/product/', '/system_ext/', '/oem/', '/data/app/')
OTA_FILES = frozenset(['/ota_download/udisk_manifest.json'] +
                      ['/ota_download/update-'+name+'.tar.gz' for name in ('swp1','swl3','swl4','swlm','swl2')])
CODE_EXTENSIONS = ('.apk', '.jar', '.so', '.odex', '.vdex', '.art', '.xml', '.json', '.rc', '.properties')
PACKAGE_RE = re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\Z')
HASH_RE = re.compile(r'[0-9a-f]{64}\Z')
BLUETOOTH_LOG_COMMAND = "logcat -d -v threadtime -t 2000 BluetoothManagerService:D BluetoothGatt:D BtGatt.GattService:D bt_btm_sec:D bt_stack:W '*:S'"
MAPPED_VENDOR_PREFIXES = ('libecarx','libECarX','libipcp','libIpCp','libvsomeip','libsomeip',
                         'libbluetooth','libbt','libnfore','libnf','libtcam','libpower',
                         'libkanzi','libKanzi','libSwig_Template')


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


class AcquisitionError(RuntimeError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def redact_error(value):
    value = re.sub(r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}', '[address]', str(value))
    return value[:500]


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def safe_file_path(value):
    if not isinstance(value, str) or not (value.startswith(FILE_ROOTS) or value in OTA_FILES):
        raise AcquisitionError('rejected', 'Path is outside installed-code roots')
    if len(value) > 2048 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise AcquisitionError('rejected', 'Control characters or oversized path')
    p = PurePosixPath(value)
    if '..' in p.parts or str(p) != value or '\\' in value:
        raise AcquisitionError('rejected', 'Non-canonical remote path')
    if re.search(r'(?i)(?:^|/)(?:keystore|credentials|accounts|databases|bluetooth_config)(?:/|\.|$)', value):
        raise AcquisitionError('rejected', 'Sensitive data path')
    if value.endswith(('.key', '.pem', '.p12', '.jks', '.db', '.sqlite', '.btsnoop')):
        raise AcquisitionError('rejected', 'Sensitive file type')
    return value


def safe_package(value):
    if not isinstance(value, str) or not PACKAGE_RE.fullmatch(value):
        raise AcquisitionError('rejected', 'Invalid package name')
    return value


def run_bounded(argv, timeout=30, max_bytes=MAX_TEXT, output=None):
    """No host shell; cap stdout/stderr while draining both to avoid deadlocks."""
    proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, start_new_session=True)
    sel = selectors.DefaultSelector()
    sel.register(proc.stdout, selectors.EVENT_READ, 'out')
    sel.register(proc.stderr, selectors.EVENT_READ, 'err')
    data, err, count = bytearray(), bytearray(), 0
    deadline = time.monotonic() + timeout
    try:
        while sel.get_map():
            if time.monotonic() >= deadline:
                raise AcquisitionError('timeout', 'Command exceeded its time limit')
            for key, _ in sel.select(min(.2, max(0, deadline-time.monotonic()))):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    sel.unregister(key.fileobj)
                    continue
                if key.data == 'err':
                    if len(err) + len(chunk) > 65536:
                        raise AcquisitionError('oversized', 'Command stderr exceeded its bound')
                    err.extend(chunk)
                else:
                    count += len(chunk)
                    if count > max_bytes:
                        raise AcquisitionError('oversized', 'Command output exceeded its bound')
                    if output is not None:
                        output.write(chunk)
                    else:
                        data.extend(chunk)
        remaining = max(.1, deadline-time.monotonic())
        code = proc.wait(timeout=remaining)
        if code:
            text = err.decode('utf-8', 'replace')
            status = 'permission_denied' if 'permission denied' in text.lower() else 'command_failed'
            raise AcquisitionError(status, 'Command failed (exit %s): %s' % (code, redact_error(text)))
        return bytes(data), count
    except subprocess.TimeoutExpired:
        raise AcquisitionError('timeout', 'Command did not terminate')
    finally:
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=1)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except OSError:
                    pass
                proc.wait(timeout=2)
        sel.close()
        proc.stdout.close()
        proc.stderr.close()


class ADB:
    def __init__(self, executable, serial=None):
        self.executable, self.serial = executable, serial

    def argv(self, *parts):
        return [self.executable] + (['-s', self.serial] if self.serial else []) + list(parts)

    def shell(self, script, timeout=30, max_bytes=MAX_TEXT, output=None):
        # ADB receives one quoted remote command. No local shell interprets it.
        command = 'sh -c ' + shlex.quote(script)
        return run_bounded(self.argv('exec-out', command), timeout, max_bytes, output)

    def text(self, script, timeout=30, max_bytes=MAX_TEXT):
        return self.shell(script, timeout, max_bytes)[0].decode('utf-8', 'replace')

    def select(self, requested=None):
        raw, _ = run_bounded([self.executable, 'devices'], timeout=15, max_bytes=65536)
        rows = []
        for line in raw.decode('utf-8', 'replace').splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[1] in ('device','offline','unauthorized','recovery','sideload'):
                rows.append((fields[0], fields[1]))
        if requested:
            rows = [x for x in rows if x[0] == requested]
        online = [x for x in rows if x[1] == 'device']
        if len(online) > 1:
            raise AcquisitionError('multiple_devices', 'Several ADB devices: select the car with --serial')
        if len(online) != 1:
            status = 'unauthorized' if any(x[1]=='unauthorized' for x in rows) else 'no_device'
            raise AcquisitionError(status, 'No authorized online ADB device')
        self.serial = online[0][0]
        return self.serial


def parse_metadata(text):
    for token, status in [('KX_MISSING','missing'),('KX_DENIED','permission_denied'),('KX_NOT_REGULAR','not_regular'),('KX_OVERSIZED','oversized')]:
        if token in text.splitlines():
            raise AcquisitionError(status, token)
    lines = text.splitlines()
    try:
        n = lines.index('KX_FILE_V1')
        size, mtime, digest = lines[n+1:n+4]
        digest = digest.split()[0].lower()
        if not size.isdigit() or not mtime.isdigit() or not HASH_RE.fullmatch(digest):
            raise ValueError('Malformed metadata')
        return {'bytes':int(size),'mtime_epoch':int(mtime),'sha256':digest}
    except (ValueError, IndexError):
        raise AcquisitionError('metadata_unavailable', 'No valid file size/time/SHA-256 receipt')


def file_metadata(adb, path, max_bytes=256*1024*1024):
    p = shlex.quote(safe_file_path(path))
    script = '''p=%s
if [ ! -e "$p" ]; then
  diagnostic=$(LC_ALL=C stat "$p" 2>&1)
  case "$diagnostic" in
    *'Permission denied'*) printf 'KX_DENIED\n' ;;
    *'No such file or directory'*) printf 'KX_MISSING\n' ;;
    *) printf 'KX_METADATA_UNAVAILABLE\n' ;;
  esac
  exit 0
fi
if [ ! -f "$p" ]; then printf 'KX_NOT_REGULAR\n'; exit 0; fi
if [ ! -r "$p" ]; then printf 'KX_DENIED\n'; exit 0; fi
sz=$(stat -c %%s "$p" 2>/dev/null || toybox stat -c %%s "$p" 2>/dev/null)
case "$sz" in ''|*[!0-9]*) printf 'KX_METADATA_UNAVAILABLE\n'; exit 0;; esac
if [ "$sz" -gt %d ]; then printf 'KX_OVERSIZED\n'; exit 0; fi
mt=$(stat -c %%Y "$p" 2>/dev/null || toybox stat -c %%Y "$p" 2>/dev/null)
hs=$(sha256sum "$p" 2>/dev/null || toybox sha256sum "$p" 2>/dev/null)
printf 'KX_FILE_V1\n%%s\n%%s\n%%s\n' "$sz" "$mt" "$hs"
''' % (p,int(max_bytes))
    return parse_metadata(adb.text(script, timeout=120, max_bytes=16384))


def collect_android_file(adb, target, destination, known, seen_hashes):
    receipt = {'id':target['id'],'path':target['path'],'reason':target['reason'],
               'transport':'adb-exec-out','started_at':utc(),
               'conditional':bool(target.get('conditional',False))}
    partial = None
    try:
        path = safe_file_path(target['path'])
        maximum = int(target.get('max_bytes', 256*1024*1024))
        before = file_metadata(adb, path,maximum)
        receipt.update(before)
        if not 0 <= before['bytes'] <= maximum:
            raise AcquisitionError('oversized', 'File is larger than the target bound')
        if before['sha256'] in known:
            receipt['status'] = 'known_duplicate'
            receipt['deduplication_basis'] = 'Fresh remote SHA-256 matches an existing analyzed corpus payload'
            return receipt
        if before['sha256'] in seen_hashes:
            receipt['status'] = 'duplicate_in_run'
            receipt['same_content_as'] = seen_hashes[before['sha256']]
            return receipt
        local = destination / 'android' / 'files' / path.lstrip('/')
        local.parent.mkdir(parents=True, exist_ok=True)
        partial = local.with_name(local.name + '.partial')
        script = 'p=%s; if [ -f "$p" ] && [ -r "$p" ]; then cat "$p"; else exit 45; fi' % shlex.quote(path)
        with partial.open('xb') as stream:
            _, count = adb.shell(script, timeout=300, max_bytes=maximum, output=stream)
        digest = hashlib.sha256()
        with partial.open('rb') as stream:
            for chunk in iter(lambda:stream.read(1024*1024), b''):
                digest.update(chunk)
        if count != before['bytes'] or digest.hexdigest() != before['sha256']:
            raise AcquisitionError('changed_during_read', 'Downloaded bytes do not match fresh remote metadata')
        partial.replace(local)
        receipt.update(status='collected', local_path=str(local.relative_to(destination)),
                       verified_download_sha256=digest.hexdigest())
        seen_hashes[before['sha256']] = receipt['local_path']
    except AcquisitionError as exc:
        receipt.update(status=exc.status,error=redact_error(exc))
    except OSError as exc:
        receipt.update(status='local_io_error',error=redact_error(exc))
    finally:
        if partial is not None and partial.exists():
            partial.unlink()
        receipt['finished_at'] = utc()
    return receipt


def package_projection(text):
    result = {}
    for key in ('versionCode','versionName','codePath','resourcePath','userId','targetSdk','minSdk','primaryCpuAbi'):
        match = re.search(r'(?m)(?:^|\s)'+key+r'=([^\s]+)',text)
        if match:
            result[key] = match.group(1)[:500]
    return result


def maps_projection(text):
    paths = {}
    for line in text.splitlines():
        fields = line.split(None,5)
        if len(fields)!=6 or not re.fullmatch(r'[0-9a-fA-F]+-[0-9a-fA-F]+',fields[0]):
            continue
        path = fields[5]
        if path.endswith(' (deleted)'):
            continue
        try:
            safe_file_path(path)
        except AcquisitionError:
            continue
        if path.endswith(CODE_EXTENSIONS):
            paths.setdefault(path,set()).add(fields[1])
    return [{'path':p,'permissions':sorted(perms)} for p,perms in sorted(paths.items())]


def package_targets(adb, package, destination):
    name = safe_package(package['name'])
    record = {'package':name,'reason':package['reason'],'observed_at':utc(),
              'classloader_origin_proven':False,'errors':[],'apk_paths':[],'maps':[]}
    targets = []
    try:
        text = adb.text('pm path '+shlex.quote(name), max_bytes=65536)
        for line in text.splitlines():
            if not line.startswith('package:'):
                continue
            path = safe_file_path(line[len('package:'):].strip())
            if path.endswith('.apk'):
                record['apk_paths'].append(path)
                targets.append({'id':'package:'+name+':'+str(len(targets)), 'path':path,
                                'reason':package['reason'],'max_bytes':512*1024*1024})
        if not record['apk_paths']:
            if text.strip():
                raise AcquisitionError('package_query_unrecognized','PackageManager returned no validated APK path and nonempty output')
            record['presence_status']='not_installed'
            if not package.get('presence_probe',False):
                record['errors'].append({'status':'package_not_found'})
            save_json(destination/'android'/'packages'/(name+'.json'),record)
            return targets,record
        record['presence_status']='installed'
        info = adb.text('dumpsys package '+shlex.quote(name),max_bytes=4*1024*1024)
        record['installed_metadata'] = package_projection(info)
        pidtext = adb.text('pidof '+shlex.quote(name)+' 2>/dev/null || true',max_bytes=4096)
        pids = [x for x in pidtext.split() if re.fullmatch(r'[1-9][0-9]{0,7}',x)][:8]
        record['running_process_count'] = len(pids)
        for pid in pids:
            try:
                maps = maps_projection(adb.text('cat /proc/'+pid+'/maps',max_bytes=4*1024*1024))
                record['maps'].append({'pid':int(pid),'files':maps})
                # Dependencies already captured are skipped by a fresh hash comparison.
                for item in maps:
                    filename=PurePosixPath(item['path']).name
                    app_dirs=[str(PurePosixPath(p).parent)+'/' for p in record['apk_paths']]
                    relevant=(filename.startswith(MAPPED_VENDOR_PREFIXES)
                              or (filename.endswith('.jar') and filename.startswith(('ecarx','ts-')))
                              or any(item['path'].startswith(d) for d in app_dirs))
                    if not relevant:
                        continue
                    targets.append({'id':'mapped:'+name+':'+str(len(targets)), 'path':item['path'],
                                    'reason':'Mapped installed-code dependency of '+name,
                                    'max_bytes':256*1024*1024})
            except AcquisitionError as exc:
                record['errors'].append({'pid':int(pid),'status':exc.status,'error':redact_error(exc)})
    except AcquisitionError as exc:
        record['errors'].append({'status':exc.status,'error':redact_error(exc)})
    save_json(destination/'android'/'packages'/(name+'.json'),record)
    return targets, record


def find_adb(explicit):
    candidates = [explicit] if explicit else [shutil.which('adb'),'/opt/homebrew/bin/adb','/usr/local/bin/adb',
              str(Path.home()/'Library/Android/sdk/platform-tools/adb'), str(ROOT/'platform-tools/adb')]
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate,os.X_OK):
            return str(Path(candidate).resolve())
    raise AcquisitionError('adb_missing','ADB was not found; see README_RU.md')


def load_known():
    path=ROOT/'known_files.json.gz'
    with gzip.open(path,'rt',encoding='utf-8') as stream:
        result=json.load(stream)
    hashes=set(result['sha256'])
    if not all(isinstance(x,str) and HASH_RE.fullmatch(x) for x in hashes):
        raise AcquisitionError('invalid_baseline','Invalid known-files baseline')
    return hashes


def seal(destination, report):
    report['finished_at']=utc()
    save_json(destination/'RESULT.json',report)
    files=[]
    for path in sorted(destination.rglob('*')):
        if path.is_file():
            digest=hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b''):
                    digest.update(chunk)
            files.append({'path':str(path.relative_to(destination)),'bytes':path.stat().st_size,'sha256':digest.hexdigest()})
    save_json(destination/'FILES.json',{'files':files,'note':'This manifest excludes itself.'})
    archive=destination.with_suffix('.tar.gz')
    with tarfile.open(archive,'w:gz') as tar:
        tar.add(destination,arcname=destination.name,recursive=True)
    digest=hashlib.sha256()
    with archive.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):
            digest.update(chunk)
    archive.with_suffix(archive.suffix+'.sha256').write_text(digest.hexdigest()+'  '+archive.name+'\n')
    return archive


def collect(args, plan):
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d-%H%M%S')
    destination=args.output.expanduser().resolve()/('KX11-Missing-Evidence-'+stamp+'-'+uuid.uuid4().hex[:6])
    destination.mkdir(parents=True,mode=0o700)
    report={'collector_version':VERSION,'started_at':utc(),'receipts':[], 'package_records':[],
            'plan_sha256':hashlib.sha256((ROOT/'collection_plan.json').read_bytes()).hexdigest(),
            'constraints':{'vehicle_commands':False,'apk_installation':False,'service_restart':False,
                           'raw_bluetooth_private_payload':False,'runtime_class_origin_inferred_from_maps':False}}
    stop=False
    try:
        known=load_known()
        adb=ADB(find_adb(args.adb))
        serial=adb.select(args.serial)
        report['device_reference_sha256']=hashlib.sha256(serial.encode()).hexdigest()
        report['known_corpus_hashes']=len(known)
        print('Подключение ADB подтверждено. Проверяю версии и состав файлов.',flush=True)
        build={}
        for prop in ('ro.product.model','ro.product.device','ro.build.display.id','ro.build.fingerprint','ro.build.version.sdk','ro.build.version.release'):
            try:
                build[prop]=adb.text('getprop '+shlex.quote(prop),max_bytes=8192).strip()[:2048]
            except AcquisitionError as exc:
                build[prop]={'status':exc.status}
        save_json(destination/'android'/'build.json',build)
        targets=list(plan['android_files'])
        for package in plan['packages']:
            print('Пакет: '+package['name'],flush=True)
            found,record=package_targets(adb,package,destination)
            targets.extend(found)
            report['package_records'].append({'package':package['name'],'errors':record['errors'],
                                             'presence_status':record.get('presence_status','unknown')})
        seen_paths=set();seen_hashes={}
        for target in targets:
            if target['path'] in seen_paths:
                continue
            seen_paths.add(target['path'])
            print('Файл: '+target['path'],flush=True)
            report['receipts'].append(collect_android_file(adb,target,destination,known,seen_hashes))
            save_json(destination/'checkpoint.json',report)
        if not args.no_bluetooth:
            from bt_projection import project_dumpsys,project_logcat
            for label,command,projector in [
                ('gatt_state','dumpsys bluetooth_manager',project_dumpsys),
                ('bluetooth_events',BLUETOOTH_LOG_COMMAND,project_logcat),
            ]:
                try:
                    text=adb.text(command,max_bytes=MAX_TEXT)
                    projected=projector(text)
                    del text
                    save_json(destination/'android'/(label+'.json'),projected)
                    report['receipts'].append({'id':label,'status':'projected','raw_saved':False})
                except AcquisitionError as exc:
                    report['receipts'].append({'id':label,'status':exc.status,'error':redact_error(exc)})
        else:
            report['receipts'].append({'id':'bluetooth','status':'skipped_by_user'})
        if not args.no_qnx and plan['qnx_files']:
            from qnx_acquire import acquire
            print('Проверяю файловый канал QNX через Android.',flush=True)
            report['receipts'].extend(acquire(adb.executable,adb.serial,plan['qnx_files'],destination,known,timeout=120))
        elif args.no_qnx:
            report['receipts'].append({'id':'qnx','status':'skipped_by_user'})
        report['not_collectable_as_files']=plan.get('not_collectable_as_files',[])
    except KeyboardInterrupt:
        report['fatal']={'status':'interrupted','message':'Сбор остановлен пользователем; уже собранные результаты сохранены.'}
        stop=True
    except (AcquisitionError,OSError,ValueError,ImportError) as exc:
        report['fatal']={'status':getattr(exc,'status','error'),'message':redact_error(exc)}
    good={'collected','known_duplicate','duplicate_in_run','known_duplicate_discarded','duplicate_discarded','projected'}
    counts={}
    for receipt in report['receipts']:
        key=receipt.get('status','unknown');counts[key]=counts.get(key,0)+1
    report['counts']=counts
    failures=[r for r in report['receipts'] if r.get('status') not in good
              and not (r.get('conditional') and r.get('status')=='missing')]
    package_failures=any(r['errors'] for r in report['package_records'])
    report['status']='PARTIAL' if failures or package_failures or report.get('fatal') else 'COMPLETED_COLLECTION_PLAN'
    report['status_scope']='Completion of the finite acquisition plan; not proof that all vehicle systems are understood.'
    save_json(destination/'COLLECTION_PLAN.json',plan)
    archive=seal(destination,report)
    print('\nРезультат: '+report['status'],flush=True)
    print('Пришлите этот архив:\n'+str(archive),flush=True)
    if report.get('fatal'):
        print('Причина: '+report['fatal']['message'],flush=True)
    return 130 if stop else (0 if report['status']=='COMPLETED_COLLECTION_PLAN' else 3)


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description='Адресный досбор файлов Geely KX11 с Mac через уже доступное ADB.')
    parser.add_argument('--output',type=Path,default=Path.home()/'Desktop',help='Папка результата; по умолчанию Рабочий стол')
    parser.add_argument('--adb',help='Путь к существующему adb')
    parser.add_argument('--serial',help='Конкретное ADB-устройство, если подключено несколько')
    parser.add_argument('--plan',action='store_true',help='Показать план без подключения к автомобилю')
    parser.add_argument('--no-qnx',action='store_true',help='Пропустить файловый канал QNX')
    parser.add_argument('--no-bluetooth',action='store_true',help='Пропустить очищенные Bluetooth-снимки')
    args=parser.parse_args()
    plan=json.loads((ROOT/'collection_plan.json').read_text(encoding='utf-8'))
    for target in plan['android_files']:
        safe_file_path(target['path'])
    for package in plan['packages']:
        safe_package(package['name'])
    if args.plan:
        print(json.dumps(plan,ensure_ascii=False,indent=2))
        return 0
    return collect(args,plan)


if __name__=='__main__':
    sys.exit(main())
