from pathlib import Path
import json,zipfile,tarfile,hashlib
root=Path('audit-work/next-analysis/bluetooth_runtime');originals=Path('audit-work/originals');registry=json.load(open('audit-work/repo/docs/geely-kx11/research-2026-09-05/corpus/corpus_inventory.json'))
target_document=json.load(open(root/'collection_targets.json'));targets=list(target_document['targets'])
# Recheck the recovered APK too, even though it no longer needs collection.
for available in target_document.get('already_available_do_not_recollect',[]):
 if isinstance(available,dict) and available.get('path','').endswith('/ts-dm-service.apk') and not any(t['path']==available['path'] for t in targets):
  targets.append({'path':available['path'],'priority':'P1'})
names={Path(t['path']).name for t in targets};files=[p for p in sorted(originals.iterdir()) if p.is_file() and '.openai-download-' not in p.name]
archives=[];hits=[];nonarchive=[];failed=[];nested=[]
for p in files:
 found=[];members=[];kind=None
 try:
  if zipfile.is_zipfile(p):
   kind='zip'
   with zipfile.ZipFile(p) as z:members=[(m.filename,m.file_size) for m in z.infolist() if not m.is_dir()]
  elif p.name.endswith(('.tar','.tar.gz','.tgz')):
   kind='tar'
   with tarfile.open(p) as t:members=[(m.name,m.size) for m in t if m.isfile()]
  if kind:
   for name,size in members:
    if Path(name).name in names:found.append({'archive':p.name,'member':name,'bytes':size,'basename':Path(name).name})
    if name.endswith(('.zip','.tar','.tar.gz','.tgz','.7z','.rar')):nested.append({'archive':p.name,'member':name,'bytes':size})
   record={'archive':p.name,'format':kind,'member_count':len(members),'target_hits':len(found),'file_bytes':p.stat().st_size};archives.append(record);hits.extend(found)
  else:nonarchive.append({'file':p.name,'bytes':p.stat().st_size,'is_target_basename':p.name in names})
 except (OSError,zipfile.BadZipFile,tarfile.TarError) as exc:failed.append({'file':p.name,'error_type':type(exc).__name__})
 print(p.name,kind or 'nonarchive',len(members),len(found),flush=True)
# SHA only archives with hits, so provenance is available without rehashing all firmware images.
for a in {h['archive'] for h in hits}:
 sha=hashlib.file_digest(open(originals/a,'rb'),'sha256').hexdigest()
 for h in hits:
  if h['archive']!=a:continue
  h['archive_sha256']=sha
  if a.endswith(('.tar','.tar.gz','.tgz')):
   with tarfile.open(originals/a) as t:h['member_sha256']=hashlib.sha256(t.extractfile(h['member']).read()).hexdigest()
  else:
   with zipfile.ZipFile(originals/a) as z:h['member_sha256']=hashlib.sha256(z.read(h['member'])).hexdigest()
registry_coverage=[]
for s in registry['sources']:
 p=originals/s['name'];registry_coverage.append({'name':s['name'],'source_id':s.get('source_id'),'present_by_name':p.is_file(),'expected_registry_sha256':s.get('sha256'),'local_bytes':p.stat().st_size if p.is_file() else None,'inspection':'outer archive filenames/sizes only' if any(x['archive']==s['name'] for x in archives) else 'standalone source; no content scan'})
per=[]
for t in targets:
 th=[h for h in hits if h['basename']==Path(t['path']).name]
 per.append({'target_path':t['path'],'priority':t['priority'],'basename':Path(t['path']).name,'hits':th,'outer_inventory_absent':not th,'scope':'all current local source files excluding transient partial-download files; registry presence mapped individually','nested_payload_absence_proven':False if nested else True})
result={'registry_source_count':len(registry['sources']),'registry_present_by_name':sum(x['present_by_name'] for x in registry_coverage),'local_files_scanned':len(files),'archives_scanned':len(archives),'archive_members_scanned':sum(a['member_count'] for a in archives),'failed':failed,'archives':archives,'nonarchives':nonarchive,'per_target':per,'registry_coverage':registry_coverage,'nested_archives_not_expanded':nested,'limits':['Basename/path inventory is not semantic analysis or proof of absence inside raw firmware partitions, embedded archives, or APK assets with unrelated names.','Matching 32-bit binder basename is not the missing /system/lib64 ABI.','No raw notification/contact/pairing payload interpreted; files not executed.']}
(root/'full_corpus_target_coverage.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('FINAL',len(registry_coverage),sum(x['present_by_name'] for x in registry_coverage),len(archives),sum(a['member_count'] for a in archives),len(hits),len(nested),failed,flush=True)
