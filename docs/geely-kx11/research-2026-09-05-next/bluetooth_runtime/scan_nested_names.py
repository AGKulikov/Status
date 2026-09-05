from pathlib import Path
import json,zipfile,tarfile,io,hashlib
r=Path('audit-work/next-analysis/bluetooth_runtime');p=r/'full_corpus_target_coverage.json';d=json.load(open(p));targets={x['basename'] for x in d['per_target']};results=[];hits=[];deeper=[];cache={}
for item in d['nested_archives_not_expanded']:
 with zipfile.ZipFile(Path('audit-work/originals')/item['archive']) as outer:b=outer.read(item['member'])
 sha=hashlib.sha256(b).hexdigest()
 if sha in cache:entries=cache[sha]
 elif zipfile.is_zipfile(io.BytesIO(b)):
  with zipfile.ZipFile(io.BytesIO(b)) as z:entries=[(m.filename,m.file_size) for m in z.infolist() if not m.is_dir()]
  cache[sha]=entries
 else:entries=[]
 row={**item,'nested_member_sha256':sha,'members':len(entries),'zip_valid':bool(entries),'target_hits':[]}
 for name,size in entries:
  if Path(name).name in targets:row['target_hits'].append({'member':name,'bytes':size,'basename':Path(name).name});hits.append({**item,'inner_member':name,'bytes':size,'basename':Path(name).name})
  if name.endswith(('.zip','.tar','.tar.gz','.tgz','.7z','.rar')):deeper.append({**item,'inner_member':name,'bytes':size})
 results.append(row)
d['nested_archive_checks']=results;d['nested_archives_not_expanded']=deeper;d['nested_archive_target_hits']=hits;d['nested_archive_unique_hashes']=len(cache)
for target in d['per_target']:
 target['nested_hits']=[h for h in hits if h['basename']==target['basename']];target['nested_payload_absence_proven']=not deeper;target['scanned_nested_container_count']=len(results)
p.write_text(json.dumps(d,ensure_ascii=False,indent=2));print('nested',len(results),'unique',len(cache),'inner_members',sum(x['members'] for x in results),'hits',hits,'deeper',deeper)
