#!/usr/bin/env python3
"""Lossless declaration inventory, exact sput/call locations, no code execution."""
import collections,gc,hashlib,json,math,pathlib,re,sys
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
ROOT=pathlib.Path(__file__).resolve().parent
OUT=ROOT/'private/inventories';OUT.mkdir(exist_ok=True)
PREFIXES=('Lcom/ecarx/','Lecarx/','Lvendor/ecarx/','Landroid/car/')
OLD=('Lcom/ecarx/xui/adaptapi/','Lecarx/car/','Landroid/car/','Lvendor/ecarx/xma/','Lecarx/dimprotocol/','Lecarx/powersomeip/')
def scope(c):return c.startswith(PREFIXES)
def sh(b):return hashlib.sha256(b).hexdigest()
def clean(v):
    if v is None or isinstance(v,(str,bool,int)):return v
    if isinstance(v,float):return v if math.isfinite(v) else {'nonfinite_float':repr(v)}
    if isinstance(v,bytes):return {'bytes_hex':v.hex()}
    if isinstance(v,(list,tuple)):return [clean(x) for x in v]
    if hasattr(v,'get_value'):return {'encoded_value_type':v.get_value_type(),'value':clean(v.get_value())}
    if hasattr(v,'get_values'):return [clean(x) for x in v.get_values()]
    return {'representation':str(v),'python_type':type(v).__name__}
def namespace(c):
    if c.startswith('Lcom/ecarx/xui/adaptapi/'):return 'adaptapi'
    if c.startswith('Lecarx/car/hardware/signal/'):return 'ecarx.car.signal'
    if c.startswith('Lecarx/car/hardware/vehicle/'):return 'ecarx.car.manager'
    if c.startswith('Lecarx/car/hardware/annotation/'):return 'ecarx.car.value_annotation'
    if c=='Landroid/car/VehiclePropertyIds;':return 'android.vehicle_property_id'
    return c.split('/')[0][1:]+'.declaration'

def extract(path):
    h=path.stem;dest=OUT/(h+'.json')
    if dest.exists():
        try:
            previous=json.loads(dest.read_text())
            if '--redo-errors' not in sys.argv or not previous['errors']:return
        except json.JSONDecodeError:pass
    data=path.read_bytes();assert sh(data)==h
    d=DEX(data);sid='sha256:'+h
    result={'source_id':sid,'dex_header':{'file_size':d.header.file_size,'class_defs_size':d.header.class_defs_size,'field_ids_size':d.header.field_ids_size,'method_ids_size':d.header.method_ids_size},'classes':[],'fields':[],'methods':[],'sput_sites':[],'calls':[],'errors':[]}
    fullbyte=[]
    for c in d.get_classes():
        cn=c.get_name();sdk=scope(cn)
        result['classes'].append({'source_id':sid,'class':cn,'class_idx':c.get_class_idx(),'access_flags':c.get_access_flags(),'flags':c.get_access_flags_string(),'superclass':c.get_superclassname(),'interfaces':c.get_interfaces(),'sdk_namespace_scope':sdk,'old_namespace_scope':cn.startswith(OLD),'field_count':len(c.get_fields()),'method_count':len(c.get_methods())})
        for f in c.get_fields():
            v=f.get_init_value();desc=f.get_descriptor();static=bool(f.get_access_flags()&8)
            result['fields'].append({'source_id':sid,'class':cn,'name':f.get_name(),'descriptor':desc,'field_idx':f.get_field_idx(),'access_flags':f.get_access_flags(),'flags':f.get_access_flags_string(),'sdk_namespace_scope':sdk,'old_namespace_scope':cn.startswith(OLD),'namespace':namespace(cn),'encoded_initializer_present':v is not None,'encoded_value_type':None if v is None else v.get_value_type(),'encoded_value':None if v is None else clean(v.get_value()),'vm_initial_value':None if not static or desc.startswith(('L','[')) else False if desc=='Z' else 0.0 if desc in {'F','D'} else 0,'static':static,'final':bool(f.get_access_flags()&16)})
        for m in c.get_methods():
            mn=m.get_name();desc=m.get_descriptor();code=m.get_code()
            locator={'source_id':sid,'class':cn,'method':mn,'descriptor':desc,'method_idx':m.get_method_idx()}
            row={**locator,'access_flags':m.get_access_flags(),'flags':m.get_access_flags_string(),'sdk_namespace_scope':sdk,'old_namespace_scope':cn.startswith(OLD),'has_bytecode':code is not None,'bytecode_sha256':None if code is None else sh(code.get_bc().get_raw()),'bytecode_size':0 if code is None else len(code.get_bc().get_raw()),'instruction_count':0,'scan_complete':True}
            result['methods'].append(row)
            if code is None:continue
            regs={};byte=[]
            try:
                for off,ins in m.get_instructions_idx():
                    op=ins.get_name();args=ins.get_operands()
                    if args is None:
                        row.setdefault('unsupported_operand_instructions',[]).append({'offset_bytes':off,'operation':op})
                        args=[]
                    r=[x[1] for x in args if x[0]==0];literal=[x[1] for x in args if x[0]==1]
                    row['instruction_count']+=1
                    # Only straight-line literal provenance, not effective-value inference.
                    if op.startswith('const') and not op.startswith(('const-string','const-class')) and r and literal:regs[r[0]]={'value':literal[0],'offset':off}
                    elif op.startswith('move') and not op.startswith(('move-result','move-exception')):
                        if len(r)>1 and r[1] in regs:regs[r[0]]=regs[r[1]]
                        elif r:regs.pop(r[0],None)
                    elif op.startswith(('if-','goto','packed-switch','sparse-switch')):regs.clear()
                    if op.startswith('sput'):
                        ids=[x[1] for x in args if x[0]==258]
                        if len(ids)!=1:raise ValueError('unrecognized sput operand schema')
                        fc,fd,fn=d.get_cm_field(ids[0])
                        result['sput_sites'].append({**locator,'offset_bytes':off,'operation':op,'target_class':fc,'target_name':fn,'target_descriptor':fd,'target_field_idx':ids[0],'local_literal_provenance':regs.get(r[0]) if r else None,'in_class_initializer':mn=='<clinit>'})
                    if sdk and op.startswith('invoke-'):
                        ids=[x[1] for x in args if x[0]==256]
                        for idx in ids:
                            target=d.get_cm_method(idx)
                            result['calls'].append({**locator,'offset_bytes':off,'operation':op,'target_class':target[0],'target_method':target[1],'target_descriptor':''.join(target[2]) if isinstance(target[2],list) else target[2],'argument_literal_provenance':{str(x):regs[x] for x in r if x in regs}})
                    if cn.startswith('Lcom/ecarx/xui/adaptapi/'):
                        byte.append({'offset_bytes':off,'operation':op,'operands':ins.get_output()})
                    if args and args[0][0]==0 and not op.startswith(('const','move','if-','goto','packed-switch','sparse-switch','sput','invoke-','return','throw','iput','aput','monitor-')):regs.pop(args[0][1],None)
                if byte:fullbyte.append({**locator,'instructions':byte})
            except Exception as e:
                row['scan_complete']=False;result['errors'].append({**locator,'error':type(e).__name__+': '+str(e)})
    result['counts']={k:len(result[k]) for k in ['classes','fields','methods','sput_sites','calls','errors']}
    tmp=dest.with_suffix('.json.tmp');tmp.write_text(json.dumps(result,ensure_ascii=True,separators=(',',':'))+'\n');tmp.replace(dest)
    if fullbyte:(OUT/(h+'.adapt_bytecode.json')).write_text(json.dumps(fullbyte,ensure_ascii=True,separators=(',',':'))+'\n')
    print(h,result['counts'],flush=True)
    del d,result,fullbyte;gc.collect()

for p in sorted((ROOT/'private/dex').glob('*.dex')):extract(p)
