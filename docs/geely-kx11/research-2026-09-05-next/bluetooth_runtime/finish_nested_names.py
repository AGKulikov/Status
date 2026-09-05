import zipfile,tarfile,io,json,hashlib
from pathlib import Path
p=Path('audit-work/next-analysis/bluetooth_runtime/full_corpus_target_coverage.json');d=json.loads(p.read_text()); rows=[];cache={}
for x in d['nested_archives_not_expanded']:
 with zipfile.ZipFile(Path('audit-work/originals')/x['archive']) as z:b=z.read(x['member'])
 with zipfile.ZipFile(io.BytesIO(b)) as z:b=z.read(x['inner_member'])
 sha=hashlib.sha256(b).hexdigest()
 if sha not in cache:
  try:
   with tarfile.open(fileobj=io.BytesIO(b)) as t:names=[m.name for m in t.getmembers()]
   cache[sha]={'tar_valid':True,'member_count':len(names),'names':names}
  except tarfile.TarError: cache[sha]={'tar_valid':False,'member_count':None}
 rows.append({**x,'inner_sha256':sha,**cache[sha]})
d['deep_nested_checks']=rows;d['nested_archives_not_expanded']=[];d['deep_nested_unique_hashes']=len(cache)
assert all(x['tar_valid'] and x['member_count']==0 for x in rows)
for x in d['per_target']:x['nested_payload_absence_proven']=True;x['deep_nested_container_count']=len(rows)
p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n');print('deep',len(rows),'unique',len(cache),'all_valid_empty',True)
