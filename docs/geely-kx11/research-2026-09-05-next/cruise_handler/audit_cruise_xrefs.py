"""Scan all unique corpus DEX bytes for exact cruise enum and feature-id instruction references."""
from pathlib import Path
import re,json,hashlib,struct,sys
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
R=Path(__file__).resolve().parent
D=R.parent/'sdk_completeness/private/dex'
needles=[b'DrvrCrsCtrlFctActvReq',b'DrvrCrsCtrlFctSeldTyp']
ids={537067776,537068032,537068800,537069056}
rows=[]
if '--resume' in sys.argv and (R/'cruise_dex_xrefs.json').exists():
 rows=json.loads((R/'cruise_dex_xrefs.json').read_text())['dex']
known={r['dex_sha256'] for r in rows}
for p in sorted(D.glob('*.dex')):
 if p.stem in known:continue
 b=p.read_bytes()
 if not any(q in b for q in needles) and not any(struct.pack('<I',i) in b for i in ids):continue
 d=DEX(b);row={'dex_sha256':p.stem,'bytes':len(b),'class_count':len(d.get_classes()),'methods_scanned':0,'external_enum_instruction_references':[],'feature_id_instruction_references':[],'cruise_enum_fields':{}}
 out=[]
 for c in d.get_classes():
  cn=c.get_name()
  if any(q.decode() in cn for q in needles):
   row['cruise_enum_fields'][cn]=[{'name':f.get_name(),'value':f.get_init_value().get_value() if f.get_init_value() else 0,'implicit_zero':f.get_init_value() is None} for f in c.get_fields()]
  for m in c.get_methods():
   if not m.get_code():continue
   row['methods_scanned']+=1
   found=[]
   for off,i in m.get_instructions_idx():
    op=i.get_name();s=i.get_output()
    if any(q.decode() in s for q in needles) and not any(q.decode() in cn for q in needles):
     item={'class':cn,'method':m.get_name(),'descriptor':m.get_descriptor(),'offset':off,'op':op,'operand':s};row['external_enum_instruction_references'].append(item);found.append(item)
    if op.startswith('const') and op not in ['const-string','const-string/jumbo','const-class']:
     mt=re.search(r', (-?\d+)',s)
     if mt and int(mt[1]) in ids:
      item={'class':cn,'method':m.get_name(),'descriptor':m.get_descriptor(),'offset':off,'id':int(mt[1])};row['feature_id_instruction_references'].append(item);found.append(item)
   if found:
    out.append('\nMETHOD '+cn+'->'+m.get_name()+m.get_descriptor()+'\n'+'\n'.join(f'{off:x}: {i.get_name()} {i.get_output()}' for off,i in m.get_instructions_idx()))
 (R/'private'/(p.stem+'.cruise_xrefs.txt')).write_text('\n'.join(out));rows.append(row)
 print(p.stem,len(row['external_enum_instruction_references']),len(row['feature_id_instruction_references']),flush=True)
(R/'cruise_dex_xrefs.json').write_text(json.dumps({'scope':'All unique DEX files supplied by full corpus magic scan, preselected when either cruise enum byte name or one of four full 32-bit feature IDs exists. IDs have nonzero low16 bits; const/high16 cannot declare them directly. Instruction refs outside enum, not annotation/metadata refs. Arithmetic/reflective construction is outside this scan.','pattern':needles[0].decode()+'|'+needles[1].decode(),'feature_ids':sorted(ids),'dex':rows},indent=2))
