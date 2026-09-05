#!/usr/bin/env python3
"""Publish declaration pages and cross-source checks without modifying frozen v1."""
import collections,hashlib,json,pathlib
ROOT=pathlib.Path(__file__).resolve().parent;WORK=ROOT.parents[1]
(ROOT/'pages').mkdir(exist_ok=True)
def sha(b):return hashlib.sha256(b).hexdigest()
def dump(name,obj):
    (ROOT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def key(r):return (r['class'],r['name'],r['descriptor'])
def sval(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
PUBLIC_PREFIXES=('Lcom/ecarx/','Lecarx/','Lvendor/ecarx/','Landroid/car/','Lcom/ts/')
def load_inventory(p):
    x=json.loads(p.read_text())
    for group in ('classes','fields','methods'):
        for r in x[group]:r['sdk_namespace_scope']=r['class'].startswith(PUBLIC_PREFIXES)
    return x
class Pager:
    def __init__(self,name):self.name=name;self.rows=[];self.size=0;self.pages=[];self.count=0
    def add(self,row):
        b=json.dumps(row,ensure_ascii=False,separators=(',',':')).encode();n=len(b)+2
        if self.rows and self.size+n>285000:self.flush()
        self.rows.append(row);self.size+=n;self.count+=1
    def flush(self):
        if not self.rows:return
        p=f'pages/{self.name}-{len(self.pages)+1:04d}.json';b=(json.dumps({'schema':'sdk-declaration-page-v2','records':self.rows},ensure_ascii=False,separators=(',',':'))+'\n').encode();(ROOT/p).write_bytes(b)
        self.pages.append({'path':p,'records':len(self.rows),'size':len(b),'sha256':sha(b)});self.rows=[];self.size=0
    def finish(self):self.flush();return {'records':self.count,'pages':self.pages}

inv=json.loads((ROOT/'source_inventory.json').read_text());sources={r['source_id']:r for r in inv['sources']};parents=collections.defaultdict(set)
for s in sources.values():
    for c in s['children']:parents[c['source_id']].add(s['source_id'])
def cohorts(sid):
    out={sid}
    for p in parents[sid]:out.update(c['source_id'] for c in sources[p]['children'] if sources[c['source_id']]['kind']=='dex')
    return out
expected={s for s,r in sources.items() if r['kind']=='dex'}
files=sorted((ROOT/'private/inventories').glob('*.json'));files=[p for p in files if not p.name.endswith('.adapt_bytecode.json')]
present={'sha256:'+p.stem for p in files};assert expected==present,(len(expected),len(present),sorted(expected-present))
old=json.loads((WORK/'materials/Geely_KX11_Knowledge_v1.0/firmware/constants.json').read_text())['constants']
oldkeys={key(r) for r in old};oldvariants={(key(r),sval(r['value'])) for r in old}
oldvalues=collections.defaultdict(list)
for i,r in enumerate(old):oldvalues[key(r)].append(i)
totals=collections.Counter();scope_totals=collections.Counter();sput=collections.defaultdict(list);classmap=collections.defaultdict(list);summaries=[];errs=[]
for p in files:
    x=load_inventory(p);sid=x['source_id'];totals.update(x['counts']);errs.extend(x['errors'])
    for k in ('classes','fields','methods'):scope_totals[k]+=sum(r['sdk_namespace_scope'] for r in x[k])
    for r in x['classes']:classmap[r['class']].append(r)
    for r in x['sput_sites']:sput[(r['target_class'],r['target_name'],r['target_descriptor'])].append(r)
    summaries.append({'source_id':sid,'counts':x['counts'],'sdk_namespace_counts':{k:sum(r['sdk_namespace_scope'] for r in x[k]) for k in ('classes','fields','methods')},'dex_header':x['dex_header'],'private_full_inventory':str(p.relative_to(ROOT)),'private_inventory_sha256':sha(p.read_bytes())})
pages={k:Pager(k) for k in ('classes','fields','methods','static_writes','family_calls','inheritance','value_conflicts','typed_initializer_corrections')}
field_index={};variants=collections.defaultdict(dict);statics=collections.Counter();implicit=collections.Counter();systemzero=[];family=[];inherited=collections.Counter();unrepresented=collections.Counter();normalizations=collections.Counter();baseline_typed_affected=set()
SYS='sha256:8c9f0c1ba3370851c2dd9894adedb92f73fb45fb81ea379040c03263b1e45971'
family_prefix=('Lcom/ecarx/xui/adaptapi/car/diagnostics/','Lcom/ecarx/xui/adaptapi/car/base/','Lcom/ecarx/xui/adaptapi/car/sensor/')
for p in files:
    x=load_inventory(p);sid=x['source_id'];siblings=cohorts(sid);class_initializers={r['class']:r for r in x['methods'] if r['method']=='<clinit>'}
    typed={r['field_idx']:r for r in json.loads((ROOT/'private/typed_initializers'/p.name).read_text())['initializers']}
    for r in x['classes']:
        if not r['sdk_namespace_scope']:continue
        pages['classes'].add(r)
        for parent in ([r['superclass']] if r['superclass'] else [])+r['interfaces']:
            same=[a['source_id'] for a in classmap[parent] if a['source_id'] in siblings];other=[a['source_id'] for a in classmap[parent] if a['source_id'] not in siblings]
            status='same_dex' if sid in same else 'same_container_candidate' if same else 'other_container_candidates_origin_unverified' if other else 'external_class_not_defined_in_corpus'
            inherited[status]+=1
            pages['inheritance'].add({'source_id':sid,'class':r['class'],'parent':parent,'relationship':'interface' if parent in r['interfaces'] else 'superclass','resolution':status,'same_container_source_ids':same,'other_source_ids':other,'runtime_resolution_verified':False})
    for r in x['fields']:
        if not r['sdk_namespace_scope']:
            if sid==SYS and r['static'] and r['final'] and r['descriptor'] in 'ZBCSIJFD' and not r['encoded_initializer_present']:
                ws=sput[key(r)];local=[v for v in ws if v['source_id']==sid];co=[v for v in ws if v['source_id'] in siblings and v['source_id']!=sid]
                systemzero.append({'class':r['class'],'name':r['name'],'descriptor':r['descriptor'],'classification':'outside_public_sdk_namespace','same_dex_sput_count':len(local),'same_container_other_dex_sput_count':len(co),'other_corpus_sput_count':len(ws)-len(local)-len(co)})
            continue
        k=key(r);writes=sput[k];own=[v for v in writes if v['source_id']==sid];co=[v for v in writes if v['source_id'] in siblings and v['source_id']!=sid]
        extractor_value=r['encoded_value'];r['extractor_encoded_value_raw']=extractor_value
        if r['encoded_initializer_present']:
            normalized=typed[r['field_idx']];r['typed_encoded_initializer']=normalized
            if normalized['value_type'] in (0,2,3,4,6,16,17,30,31):r['encoded_value']=normalized['typed_value']
            if sval(extractor_value)!=sval(r['encoded_value']):
                normalizations[normalized['value_kind']]+=1;baseline_typed_affected.update(oldvalues.get(k,[]))
                pages['typed_initializer_corrections'].add({'source_id':sid,'class':r['class'],'name':r['name'],'descriptor':r['descriptor'],'field_idx':r['field_idx'],'extractor_encoded_value_raw':extractor_value,'typed_encoded_initializer':normalized,'baseline_constant_indices':oldvalues.get(k,[])})
        r['vm_type_default_value']=r.pop('vm_initial_value');r['vm_initial_value_before_clinit']=r['encoded_value'] if r['encoded_initializer_present'] else r['vm_type_default_value']
        r['class_initializer_method_idx']=class_initializers.get(r['class'],{}).get('method_idx');r['same_dex_sput_count']=len(own);r['same_container_other_dex_sput_count']=len(co);r['other_corpus_sput_count']=len(writes)-len(own)-len(co)
        r['static_effective_value']=None
        if not r['static']:role='instance_field_declaration'
        elif r['encoded_initializer_present']:role='encoded_static_initializer_with_sput' if own or co else 'encoded_static_initializer_no_sput_in_containers'
        elif own or co:role='implicit_vm_default_assigned_by_bytecode'
        elif writes:role='implicit_vm_default_no_sput_in_containers_other_corpus_writes_exist'
        else:role='implicit_vm_default_no_sput_in_corpus'
        r['value_evidence_classification']=role;r['runtime_class_origin_verified']=False
        r['semantic_role']='state_value_declaration' if r['class']=='Lcom/ecarx/xui/adaptapi/car/vehicle/IVehicle;' and r['name'].startswith(('SETTING_FUNC_DIGITAL_KEY_SUSPENSION_','SETTING_FUNC_DIGITAL_KEY_TERMINATION_','SETTING_FUNC_DIGITAL_KEY_UNPAIR_')) else 'function_named_declaration_unresolved' if '_FUNC_' in r['name'] else 'unclassified_declaration'
        r['baseline_constant_indices']=oldvalues.get(k,[])
        statics[role]+=1
        if r['static'] and not r['encoded_initializer_present']:
            implicit['all_static_fields']+=1;implicit['static_final_primitive_fields']+=r['final'] and r['descriptor'] in 'ZBCSIJFD';implicit['absent_declaration_in_baseline']+=k not in oldkeys
            if r['final'] and r['descriptor'] in 'ZBCSIJFD' and sid==SYS:systemzero.append({'class':r['class'],'name':r['name'],'descriptor':r['descriptor'],'classification':role,'same_dex_sput_count':len(own),'same_container_other_dex_sput_count':len(co),'other_corpus_sput_count':len(writes)-len(own)-len(co)})
        if k not in oldkeys:unrepresented['field_records_absent_from_baseline_declaration_keys']+=1
        if r['static']:
            valkey=(sval(r['vm_initial_value_before_clinit']),r['encoded_initializer_present'],bool(own or co))
            v=variants[k].setdefault(valkey,{'initial_value_before_clinit':r['vm_initial_value_before_clinit'],'encoded_initializer_present':r['encoded_initializer_present'],'direct_sput_in_containers':bool(own or co),'sources':[]})
            v['sources'].append({'source_id':sid,'field_idx':r['field_idx']})
        field_index[(sid,)+k]=(r['encoded_initializer_present'],sval(extractor_value))
        pages['fields'].add(r)
    for r in x['methods']:
        if r['sdk_namespace_scope']:pages['methods'].add(r)
    for r in x['sput_sites']:
        if r['target_class'].startswith(('Lcom/ecarx/','Lecarx/','Lvendor/ecarx/','Landroid/car/')):
            r['unchecked_linear_literal_candidate']=r.pop('local_literal_provenance');r['literal_candidate_scope']='Not path/dominance validated; never interpreted as effective runtime value.'
            pages['static_writes'].add(r)
    for r in x['calls']:
        if r['class'].startswith(family_prefix):
            r['unchecked_linear_argument_literal_candidates']=r.pop('argument_literal_provenance');pages['family_calls'].add(r)

conflicts=0;valueconflicts=0
for k,v in sorted(variants.items()):
    if len(v)>1:
        conflicts+=1
        differing=len({sval(r['initial_value_before_clinit']) for r in v.values()})>1;valueconflicts+=differing
        pages['value_conflicts'].add({'class':k[0],'name':k[1],'descriptor':k[2],'distinct_initial_values':differing,'variants':list(v.values()),'meaning':'Initializer/value evidence differs between exact static copies. No runtime variant chosen.'})

cross=[];cross_errors=[]
oldsi=json.loads((WORK/'analysis/coverage_inventory/source_index.json').read_text())['sources']
def resolve_old(s):
    par=sources.get('sha256:'+s['sha256'])
    if not par:return None
    return next((r['source_id'] for r in par['children'] if r['member']==s['dex_entry']),None)
for i,r in enumerate(oldsi):
    sid=resolve_old(r);cross.append({'baseline_source_ref':i,'baseline':r,'source_id':sid})
    if sid is None:cross_errors.append({'baseline_source_ref':i,'problem':'source unresolved'})
oldchecks=collections.Counter()
for i,r in enumerate(old):
    for s in r['sources']:
        oldchecks['source_declarations_checked']+=1;sid=resolve_old(s)
        found=field_index.get((sid,)+key(r))
        if found==(True,sval(r['value'])):oldchecks['exact_encoded_initializer_matches']+=1
        else:cross_errors.append({'baseline_constant_index':i,'source':s,'resolved_source_id':sid,'new_encoded_initializer':found,'old_value':r['value']})
manifest={k:p.finish() for k,p in pages.items()};dump('pages_manifest.json',manifest);dump('baseline_source_crosswalk.json',{'sources':cross,'checks':dict(oldchecks),'errors':cross_errors})
system_counts={'candidates':len(systemzero),'same_dex_written':sum(r['same_dex_sput_count']>0 for r in systemzero),'same_container_other_dex_written':sum(r['same_container_other_dex_sput_count']>0 for r in systemzero),'same_dex_unwritten':sum(not r['same_dex_sput_count'] for r in systemzero)}
dump('system_zero_revalidation.json',{'source_id':SYS,'counts':system_counts,'records':systemzero})
summary={'schema':'sdk-completeness-extraction-summary-v2','archived_inputs':len(inv['archives']),'unique_code_and_archive_blobs':len(sources),'unique_dexes':len(expected),'dexes_with_sdk_scope':sum(x['sdk_namespace_counts']['classes']>0 for x in summaries),'all_dex_declaration_and_scan_counts':dict(totals),'sdk_namespace_declaration_counts':dict(scope_totals),'sdk_namespace_prefixes':['com.ecarx','ecarx','vendor.ecarx','android.car'],'scope_note':'All classes, fields and methods of every DEX retained in private inventories. Public pages cover the declared namespace prefixes without filtering flags, name, value or first class occurrence. Some prefixes include vendor application classes, not exclusively public SDK.','static_field_value_evidence_counts':dict(statics),'implicit_initializers':dict(implicit),'baseline_comparison':dict(oldchecks),'baseline_unchanged':{'entries':4440,'constants_and_variants':16082,'entry_index_sha256':sha((WORK/'analysis/coverage_inventory/entry_index.json').read_bytes())},'initializer_evidence_conflicts':conflicts,'different_initial_value_conflicts':valueconflicts,'inheritance_resolution_counts':dict(inherited),'sources':summaries,'system_zero_revalidation':system_counts,'runtime_class_origin_verified':False,'extraction_errors':errs,'remaining_limitations':['Disk provenance and cross-container same-class candidates do not establish actual classloader resolution.','Static writes are direct sput only; reflection, JNI and dynamic loading remain runtime questions.','Values before class initialization are not final effective runtime values.','General namespace classification is conservative; method presence, value names and repeated numbers do not imply callable function, installed equipment or physical effect.']}
dump('extraction_summary.json',summary)
summary['typed_initializer_corrections']={'source_field_records_by_kind':dict(normalizations),'affected_baseline_declaration_indices':sorted(baseline_typed_affected),'affected_baseline_declarations':len(baseline_typed_affected),'basis':'Independent raw DEX encoded_value decoding: signed extension for byte/short/int/long; unsigned char; right-zero extension then IEEE-754 reinterpretation for float/double. Original parser and baseline values retained.'}
summary['sdk_namespace_prefixes']=['com.ecarx','ecarx','vendor.ecarx','android.car','com.ts']
dump('extraction_summary.json',summary)
validation={'schema':'sdk-completeness-validation-v2','all_source_dexes_parsed':expected==present,'archive_scan_errors':inv['errors'],'bytecode_decode_errors':errs,'baseline_crosswalk_errors':cross_errors,'field_method_class_count_balance':all(x['counts']['classes']==x['dex_header']['class_defs_size'] for x in summaries),'public_page_counts_match_sdk_inventory':all(manifest[k]['records']==scope_totals[k] for k in ('classes','fields','methods')),'system_305_candidates_reproduced':system_counts['candidates']==305,'system_303_unwritten_2_written_reproduced':system_counts['same_dex_unwritten']==303 and system_counts['same_dex_written']==2,'all_public_page_hashes_valid':all(sha((ROOT/p['path']).read_bytes())==p['sha256'] for g in manifest.values() for p in g['pages']),'all_public_pages_below_300000_bytes':all(p['size']<=300000 for g in manifest.values() for p in g['pages']),'runtime_class_origin_verified':False,'firmware_executed':False,'apk_built':False,'vehicle_commands_sent':False}
dump('VALIDATION.json',validation)
print(json.dumps({k:v for k,v in summary.items() if k not in {'sources','remaining_limitations'}},ensure_ascii=False,indent=2))
