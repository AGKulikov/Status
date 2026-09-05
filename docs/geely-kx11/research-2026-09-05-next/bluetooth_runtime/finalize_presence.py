import hashlib,json,re
from pathlib import Path
r=Path('audit-work/next-analysis/bluetooth_runtime');p=r/'full_corpus_target_coverage.json';d=json.loads(p.read_text());lookup={}
for x in d['registry_coverage']:
 f=Path('audit-work/originals')/x['name']
 with f.open('rb') as s:sha=hashlib.file_digest(s,'sha256').hexdigest()
 x['local_sha256']=sha;x['matches_registry_sha256']=sha==x['expected_registry_sha256'] if x['expected_registry_sha256'] else None;lookup[x['name']]=sha
for x in d['archives']:x['archive_sha256']=lookup.get(x['archive'])
for x in d['per_target']:
 x['exact_requested_abi_available'] = x['basename']=='ts-dm-service.apk'
 x['collection_disposition']='known_static_apk_metadata_only_unless_hash_changed' if x['exact_requested_abi_available'] else 'missing_requested_file_or_abi'
d['all_registry_sources_hashed']=True;d['registry_hash_mismatches']=[x['name'] for x in d['registry_coverage'] if x['matches_registry_sha256'] is False]
d['limits'][0]='Absence proven for exact basenames in standalone files and all ZIP/TAR nesting discovered by archive-member names; not proof of absence inside raw firmware partitions or APK assets renamed to unrelated basenames.'
p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n');print('hashed',len(lookup),'mismatches',len(d['registry_hash_mismatches']))
rows=[]
patterns={'new_hfp_disconnect':'disconnect new HFP connect','a2dp_log_only':'diconnect new A2DP connect','no_projection':'onDeviceProfileStatusChanged, no projection device.','same_device_ignored':'ignore same device connected HFP and a2dp','profile_timeout':'connect profile timeout, dmDevice:'}
for f in sorted((r/'private').glob('*/DIAGNOSTICS/logcat_bluetooth_slice.txt')):
 b=f.read_bytes();text=b.decode('utf-8','replace');events=[];counts={k:0 for k in patterns}
 for lineno,line in enumerate(text.splitlines(),1):
  if not re.search(r'\b(?:ConnectionManager|BluetoothDeviceManager)\s*:',line):continue
  for key,pat in patterns.items():
   if pat in line:
    counts[key]+=1;stamp=re.match(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)',line)
    events.append({'line':lineno,'timestamp':stamp.group(1) if stamp else None,'event':key})
 rows.append({'archive':f.parts[-3]+'.tar.gz','archive_sha256':lookup[f.parts[-3]+'.tar.gz'],'member':'/'.join(f.parts[-3:]),'member_sha256':hashlib.sha256(b).hexdigest(),'line_count':len(text.splitlines()),'counts':counts,'events':events})
(r/'dm_service_runtime_projection.json').write_text(json.dumps({'scope':'exact five service strings under two known log tags; event types/timestamps only, no raw line or device identifiers','sources':rows},ensure_ascii=False,indent=2)+'\n');print('events',{k:sum(x['counts'][k] for x in rows) for k in patterns})
