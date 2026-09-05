"""Extract finite stock key registration and ADAS function tables for offline review."""
import zipfile,pathlib,json,re,hashlib
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.analysis.analysis import Analysis
from androguard.decompiler.decompiler import DecompilerDAD
R=pathlib.Path(__file__).resolve().parent
result=[]
for p in [R/'private/XSFInputService.apk',R/'private/ecarx.adaptapi.jar',R.parent.parent/'analysis/mcu_log_route/private/ECarXCarService.apk']:
 for dn in zipfile.ZipFile(p).namelist():
  if not re.fullmatch('classes[0-9]*.dex',dn):continue
  d=DEX(zipfile.ZipFile(p).read(dn));a=Analysis(d);d.set_decompiler(DecompilerDAD(d,a))
  for c in d.get_classes():
   nm=c.get_name()
   if (p.name=='XSFInputService.apk' and (nm.startswith('Lecarx/xsf/inputservice/key/KeyPolicyImpl') or nm.startswith('Lecarx/xsf/inputservice/InputService'))) or (p.name=='ecarx.adaptapi.jar' and nm in ['Lcom/ecarx/xui/adaptapi/car/vehicle/ADAS;','Lcom/ecarx/xui/adaptapi/car/vehicle/Vehicle;','Lcom/ecarx/xui/adaptapi/car/AbsCarFunction;','Lecarx/car/hardware/annotation/DrvrCrsCtrlFctActvReq;','Lecarx/car/hardware/annotation/DrvrCrsCtrlFctSeldTyp;','Lecarx/car/hardware/annotation/DrvrAsscSysBtnPush;']) or (p.name=='ECarXCarService.apk' and re.search('hardkey|hard.key|inputservice|keyevent',nm,re.I) and not nm.startswith(('Landroid/','Lecarx/car/'))):
    target=R/'private'/f'{p.name}.{nm.replace("/","_").replace(";","")}.java.txt'
    try:target.write_text(c.get_source());err=None
    except Exception as ex:err=str(ex)
    methods=[]
    for m in c.get_methods():
     methods.append({'method':m.get_name(),'descriptor':m.get_descriptor(),'constants':[(o,i.get_output()) for o,i in m.get_instructions_idx() if i.get_name().startswith('const')] if m.get_code() else []})
    result.append({'file':p.name,'dex':dn,'class':nm,'output':str(target),'error':err,'methods':methods})
  print(p.name,dn,flush=True)
(R/'private/handlers_index.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
