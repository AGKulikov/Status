"""Offline DEX inspection. Does not load or execute vehicle code."""
import zipfile,pathlib,re,json,hashlib
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
R=pathlib.Path(__file__).resolve().parent
paths=list((R/'private').glob('*.jar'))+list((R/'private').glob('*.apk'))+[R.parent.parent/'analysis/mcu_log_route/private/ECarXCarService.apk']
needle=re.compile(r'cruise|speed.?limit|speed.?control|CrsCtrl|AdjSpdLimn|SteerWhlBtn|AsyALgt|TiGapSet|DrvrAsscSysBtn|ACCandTSR',re.I)
result=[]
for p in paths:
 selected=[]; classes=methods=0; strings=[]
 for name in zipfile.ZipFile(p).namelist():
  if not re.fullmatch(r'classes\d*\.dex',name):continue
  d=DEX(zipfile.ZipFile(p).read(name)); ds=[]
  strings.extend(str(s) for s in d.get_strings() if needle.search(str(s)))
  for c in d.get_classes():
   classes+=1
   for m in c.get_methods():
    methods+=1
    if not m.get_code():continue
    ins=[(off,i.get_name(),i.get_output()) for off,i in m.get_instructions_idx()]
    matches=[(off,op,s) for off,op,s in ins if needle.search(s)]
    if needle.search(c.get_name()+' '+m.get_name()) or matches:
     item={'dex':name,'class':c.get_name(),'method':m.get_name(),'descriptor':m.get_descriptor(),'matched_instructions':matches,'code_units':m.get_code().get_insns_size()}
     selected.append(item);ds.append('\nMETHOD '+c.get_name()+'->'+m.get_name()+m.get_descriptor()+'\n'+'\n'.join(f'{off:04x}: {op} {s}' for off,op,s in ins))
  (R/'private'/(p.name+'.'+name+'.selected.txt')).write_text('\n'.join(ds))
 result.append({'file':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'class_count':classes,'method_count':methods,'matched_strings':sorted(set(strings)),'selected_methods':selected})
 print(p.name,classes,methods,len(selected),flush=True)
(R/'private/java_scan.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
