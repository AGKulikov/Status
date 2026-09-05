#!/usr/bin/env python3
"""Compact declarations, retaining every occurrence and exact-source provenance."""
import collections,gzip,hashlib,json,pathlib,shutil
R=pathlib.Path(__file__).resolve().parent
def js(x):return json.dumps(x,ensure_ascii=True,sort_keys=True,separators=(',',':'))
def save(p,x):p.write_text(json.dumps(x,ensure_ascii=True,indent=2)+'\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def gzrows(p,rows):
    with p.open('wb') as f:
        with gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as z:
            for r in rows:z.write((js(r)+'\n').encode())
def cat(c):
    if c.endswith('/R;') or '/R$' in c:return 'android_resource_declaration'
    if c.endswith('/BuildConfig;'):return 'build_configuration_declaration'
    if c.startswith('Lcom/ecarx/xui/adaptapi/'):return 'adaptapi'
    if c.startswith('Lecarx/car/'):return 'ecarx_car'
    if c.startswith('Lcom/ecarx/sdk/'):return 'ecarx_sdk'
    if c.startswith('Lcom/ts/'):return 'ts_wrapper_or_application'
    if c.startswith('Landroid/car/'):return 'android_car'
    if c.startswith('Lvendor/ecarx/'):return 'vendor_ecarx'
    return 'other_ecarx_application_or_library'
private_manifest=R/'private/declaration_pages_manifest.json'
if private_manifest.exists():
    manifest=json.loads(private_manifest.read_text())
else:
    manifest=json.loads((R/'pages_manifest.json').read_text())
    if (R/'pages').exists():shutil.move(str(R/'pages'),str(R/'private/declaration_pages'))
    for v in manifest.values():
        for p in v['pages']:p['path']=p['path'].replace('pages/','private/declaration_pages/')
    save(private_manifest,manifest)
sources=sorted(s['source_id'] for s in json.loads((R/'source_inventory.json').read_text())['sources'] if s['kind']=='dex')
sidx={s:i for i,s in enumerate(sources)}
out=R/'catalog';out.mkdir(exist_ok=True)
save(out/'sources.json',{'schema':'catalog-source-table-v1','source_ids':sources,'meaning':'p[0] in each occurrence is a zero-based index in source_ids; SHA256 is the DEX identity, not runtime class origin.'})
stats={};files=[]
def rows(k):
    for page in manifest[k]['pages']:
        yield from json.loads((R/page['path']).read_text())['records']
for kind in ('classes','fields','methods'):
    grouped={};n=0;category=collections.Counter()
    for r in rows(kind):
        n+=1;c=cat(r['class']);category[c]+=1
        key=(r['class'],) if kind=='classes' else (r['class'],r['name'] if kind=='fields' else r['method'],r['descriptor'])
        idx=r['class_idx'] if kind=='classes' else r['field_idx'] if kind=='fields' else r['method_idx']
        prov=[sidx[r['source_id']],idx]
        if kind=='classes':
            v={k:r[k] for k in ('access_flags','superclass','interfaces','field_count','method_count')}
        elif kind=='methods':
            # The exact DEX SHA-256 and method_idx retain every source variant.
            # Per-method hashes are private to keep the declaration index compact;
            # equal metadata never asserts equal method implementation.
            v={k:r[k] for k in ('access_flags','has_bytecode','bytecode_size','instruction_count','scan_complete') if k in r}
            if r.get('unsupported_operand_instructions'):v['unsupported_operand_instructions']=r['unsupported_operand_instructions']
        else:
            v={k:r[k] for k in ('access_flags','namespace','encoded_initializer_present','encoded_value','extractor_encoded_value_raw','vm_type_default_value','vm_initial_value_before_clinit','value_evidence_classification','semantic_role','same_dex_sput_count','same_container_other_dex_sput_count','other_corpus_sput_count','baseline_constant_indices')}
            t=r.get('typed_encoded_initializer')
            if t:
                v['typed_encoded_initializer']={k:t[k] for k in ('value_kind','value_type','value_arg','encoded_hex','typed_value')}
                if t['value_kind']=='string':
                    # Arbitrary embedded strings can include credentials. The raw
                    # DEX string index is sufficient for exact private lookup.
                    withheld={'content_withheld':'resolved_string_initializer','dex_string_index':t['typed_value']['index']}
                    for label in ('encoded_value','extractor_encoded_value_raw','vm_initial_value_before_clinit'):
                        v[label]=withheld
                prov.append(t['encoded_offset'])
            else:prov.append(None)
            prov.append(r['class_initializer_method_idx'])
        vk=js(v);g=grouped.setdefault(key,{'category':c,'variants':{}})
        g['variants'].setdefault(vk,{'v':v,'p':[]})['p'].append(prov)
    def records():
        for key,g in sorted(grouped.items()):
            yield {'key':key,'category':g['category'],'variants':list(g['variants'].values())}
    p=out/(kind+'.jsonl.gz')
    ordered=sorted(records(),key=lambda r:(r['class'],r['parent'],r['source_index'])) if kind=='inheritance' else records()
    gzrows(p,ordered)
    stats[kind]={'source_records':n,'unique_declaration_keys':len(grouped),'variants':sum(len(g['variants']) for g in grouped.values()),'source_records_by_category':dict(category)}
    assert n==manifest[kind]['records']
    files.append({'path':str(p.relative_to(R)),'size':p.stat().st_size,'sha256':sha(p),'records':len(grouped)})
    print(kind,stats[kind],p.stat().st_size,flush=True)
for kind in ('static_writes','family_calls','inheritance','typed_initializer_corrections','value_conflicts'):
    def records():
        for r in rows(kind):
            if 'source_id' in r:r['source_index']=sidx[r.pop('source_id')]
            # Unchecked instruction-flow literal guesses remain private only.
            r.pop('unchecked_linear_literal_candidate',None);r.pop('unchecked_linear_argument_literal_candidates',None)
            for label in ('same_container_source_ids','other_source_ids'):
                if label in r:r[label.replace('_ids','_indices')]=[sidx[s] for s in r.pop(label)]
            if kind=='value_conflicts':
                for variant in r['variants']:
                    if isinstance(variant['initial_value_before_clinit'],str):
                        variant['initial_value_before_clinit']={'content_withheld':'resolved_string_initializer'}
            yield r
    p=out/(kind+'.jsonl.gz');gzrows(p,records())
    files.append({'path':str(p.relative_to(R)),'size':p.stat().st_size,'sha256':sha(p),'records':manifest[kind]['records']})
save(R/'catalog_manifest.json',{'schema':'typed-ecarx-catalog-manifest-v2','counts':stats,'files':files,'source_table':'catalog/sources.json','occurrence_layout':{'classes':['source_index','class_idx'],'methods':['source_index','method_idx'],'fields':['source_index','field_idx','encoded_initializer_offset_or_null','class_initializer_method_idx_or_null']},'variants':'Each variant v is shared declaration/scan metadata; p lists every exact DEX occurrence. No first-copy selection. Equal metadata is not proof of equal implementation; method bodies/hashes stay private and source_id + method_idx is the public exact locator. Resource and application declarations remain explicit categories.','runtime_class_origin_verified':False,'effective_runtime_values_computed':False,'private_lossless_page_manifest':'private/declaration_pages_manifest.json'})
save(R/'pages_manifest.json',{'schema':'sdk-public-pages-replaced-by-compact-catalog-v1','public_catalog':'catalog_manifest.json','private_lossless_pages':'private/declaration_pages_manifest.json','original_record_counts':{k:v['records'] for k,v in manifest.items()}})
