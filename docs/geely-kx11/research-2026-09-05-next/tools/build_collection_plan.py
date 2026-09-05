import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
ANALYSIS = Path(__file__).resolve().parents[1]
OUT = REPO/'tools/geely-macos-collector'

def read(path):
    return json.loads(path.read_text())

plan = {
    'schema_version':1, 'collector_version':'1.0.0','vehicle_scope':'Geely KX11 / ECARX; compare the current device with the supplied corpus',
    'date':'2026-09-05', 'android_files':[], 'packages':[], 'qnx_files':[],
    'not_collectable_as_files':[],
    'completion_scope':'Finite file-acquisition plan. This does not certify all vehicle functions or close hardware acceptance.',
    'rules':['Existing authorized ADB only; no root daemon restart, installation, firmware execution or vehicle command.',
             'Only exact installed-code paths and reviewed conditional OTA paths. No network scanning or device-node reads.',
             'Current SHA-256 determines content duplication; matching filenames are insufficient.',
             'Conditional missing files are evidence of current absence only. Permission/transport/format errors remain errors.',
             'QNX stat is available after O_RDONLY open; only pre-reviewed ordinary-file locations are included.'],
}

def add_file(path,reason,proof,id=None,known=None,conditional=False,maximum=256*1024*1024,domain='Android'):
    target={'id':id or domain.upper()+':'+path, 'path':path,'reason':reason,
            'evidence':proof,'max_bytes':maximum,'conditional':conditional}
    if known: target['known_sha256']=known
    plan['qnx_files' if domain=='QNX' else 'android_files'].append(target)

packages={}
def add_package(name,reason,proof,presence_probe=False):
    if name in packages:
        packages[name]['evidence'].append(proof)
        packages[name]['presence_probe'] &= presence_probe
    else:
        packages[name]={'name':name,'reason':reason,'evidence':[proof],'presence_probe':presence_probe}

comfort=read(ANALYSIS/'comfort_contracts/collection_targets.json')
for target in comfort['targets']:
    why='; '.join(target['needed_for'])
    add_file(target['exact_path'],why,target['evidence'],id=target['id'],
             known=[target['available_sha256']] if target.get('available_sha256') else None,
             maximum=512*1024*1024)
    add_package(target['package'],why,target['evidence'])

bt=read(ANALYSIS/'bluetooth_runtime/collection_targets.json')
for target in bt['targets']:
    add_file(target['path'],target['reason'],target['existence_evidence'],
             known=[target['expected_baseline_sha256']] if target.get('expected_baseline_sha256') else None)
    cmd=target.get('package_path_resolution_command','')
    if cmd.startswith('pm path '):
        add_package(cmd[len('pm path '):],target['reason'],target['existence_evidence'])
add_package('com.android.bluetooth','Current Bluetooth package, embedded SDK and mapped native-code variants',
            {'report':'bluetooth_runtime/report.md','basis':'Observed Bluetooth process/package in archived runtime logs'})
add_package('ru.natro.statuswidget','Current Natro code and package version for the local GATT teardown branch',
            {'report':'bluetooth_runtime/report.md','basis':'Observed GATT client6 and package in runtime snapshots'})
add_package('ecarx.bluetooth.service.extension','Current ECARX Bluetooth service extension and mapped vendor dependencies',
            {'archive':'KX11_Bluetooth_Collect_20260815-134614_76337.tar.gz',
             'member':'KX11_Bluetooth_Collect_20260815-134614_76337/DISCOVERY/packages_full_paths.txt','line':11})
add_package('com.ts.dm.service','Compare installed device-management service with the now analyzed archived implementation',
            {'report':'bluetooth_runtime/dm_service_evidence.json',
             'sha256':'b42926770416f0e05778a04aaa69b0b69c5dd9f1572babe88813a253df7aa8c1'})

sdk=read(ANALYSIS/'sdk_completeness/collection_targets.json')
for target in sdk['static_targets']:
    add_file(target['android_path'],target['purpose'],{'report':'sdk_completeness/report.md','target_id':target['target_id']},
             id=target['target_id'],known=target['known_sha256'])
for target in sdk['package_targets']:
    add_package(target['package'],target['purpose'],target.get('presence_evidence',{'basis':target['presence_status']}),True)
plan['not_collectable_as_files'].extend(sdk['runtime_targets'])

cruise=read(ANALYSIS/'cruise_handler/collection_targets.json')
for target in cruise['targets']:
    ota=target['path'].startswith('/ota_download/update-')
    add_file(target['path'],target['reason'],target['source_evidence'],known=target['known_sha256'],
             conditional=target['status'].startswith('conditional'),domain=target['domain'],
             maximum=2*1024*1024*1024 if ota else (512*1024*1024 if target['path'].endswith('.img') else 256*1024*1024))
plan['not_collectable_as_files'].extend(cruise['not_collectable_through_current_android_qnx_filesystem'])
plan['not_collectable_as_files'].append({'id':'CRUISE-PRODUCTION-HANDLER','facts':cruise['missing_fact_without_exact_collectable_path'],
                                        'status':'No verified exact file path; do not substitute device reads or replay.'})
plan['not_collectable_as_files'].append({'id':'ANCS-APP-DECISION','status':'The existing generic Bluetooth log projection does not reveal the internal Natro decision/reason/epoch; requires an existing app diagnostic with no notification payload.'})
plan['not_collectable_as_files'].append({'id':'PHYSICAL-AND-ECU-GATES','status':'Hardware inventory, physical effects, ECU watchdogs, interlocks and unsupported features cannot be verified by file acquisition alone.'})
plan['packages']=list(packages.values())
plan['counts']={key:len(plan[key]) for key in ('android_files','qnx_files','packages','not_collectable_as_files')}
OUT.mkdir(exist_ok=True,parents=True)
(OUT/'collection_plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
print(plan['counts'])
