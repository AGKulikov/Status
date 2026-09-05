"""Offline complete member byte screening and ELF dependency inventory, no execution."""
import pathlib,zipfile,tarfile,hashlib,json,io,re,sys
from elftools.elf.elffile import ELFFile
R=pathlib.Path(__file__).resolve().parent
O=R.parent.parent/'originals'
patterns=[b'DrvrCrsCtrlFct',b'CrsCtrlrSts',b'AdjSpdLimnSts',b'SteerWhlBtnPsd',b'20.24.10.23024.41141',b'MAIN/SET/RES',b'198.18.34.1',b'CruiseMain',b'CRUISE_MAIN',b'SPEED_CONTROL_MODE']
rows=[]
if '--append-missing' in sys.argv and (R/'source_inventory.json').exists():
 rows=json.loads((R/'source_inventory.json').read_text())['sources']
known={row['name'] for row in rows}
for p in sorted(O.iterdir()):
 if not p.is_file() or '.openai-download-' in p.name or p.name.endswith('.sha256'):continue
 if p.name in known:continue
 src={'name':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'members':[]}
 if zipfile.is_zipfile(p):
  z=zipfile.ZipFile(p); entries=((i.filename,z.open(i)) for i in z.infolist() if not i.is_dir())
 elif tarfile.is_tarfile(p):
  z=tarfile.open(p); entries=((i.name,z.extractfile(i)) for i in z if i.isfile())
 else:entries=[(p.name,p.open('rb'))]
 for name,f in entries:
  b=f.read();f.close(); h=hashlib.sha256(b).hexdigest(); ent={'path':name,'bytes':len(b),'sha256':h}
  hits={q.decode():[m.start() for m in re.finditer(re.escape(q),b)][:500] for q in patterns if q in b}
  if hits:ent['byte_pattern_hits']=hits
  if b.startswith(b'\x7fELF'):
   try:
    e=ELFFile(io.BytesIO(b));dyn=e.get_section_by_name('.dynamic')
    ent['elf']={'machine':e['e_machine'],'type':e['e_type'],'class':e.elfclass,'dependencies':[tag.needed for tag in dyn.iter_tags() if tag.entry.d_tag=='DT_NEEDED'] if dyn else [],'dyn_functions':sum(1 for s in e.get_section_by_name('.dynsym').iter_symbols() if s['st_info']['type']=='STT_FUNC' and s['st_value']) if e.get_section_by_name('.dynsym') else 0}
   except Exception as ex:ent['elf_error']=str(ex)
  src['members'].append(ent)
 src['member_count']=len(src['members']);src['member_bytes']=sum(m['bytes'] for m in src['members']);rows.append(src)
 print(p.name,src['member_count'],src['member_bytes'],flush=True)
(R/'source_inventory.json').write_text(json.dumps({'scope':'All current original archives/member bytes screened using exact patterns. This is not complete semantic disassembly. Nested archive compressed members are not recursively screened; selected APK DEX analyzed separately.','patterns':[p.decode() for p in patterns],'sources':rows},ensure_ascii=False,indent=2))
