#!/usr/bin/env python3
"""Inventory locally supplied research archives. Never connects to a vehicle."""
import argparse,collections,hashlib,json,re,tarfile,zipfile
from pathlib import Path

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def archive_members(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return [{'path':x.filename,'bytes':x.file_size,'compressed_bytes':x.compress_size,'crc32':f'{x.CRC:08x}'} for x in archive.infolist() if not x.is_dir()]
    if path.name.endswith(('.tar','.tar.gz','.tgz')):
        with tarfile.open(path,'r:*') as archive:
            return [{'path':x.name,'bytes':x.size,'kind':'file' if x.isfile() else 'link_or_special'} for x in archive if not x.isdir()]
    return []

def category(name):
    if re.search(r'backup|keystore|password|signing',name,re.I):return 'protected_excluded'
    if re.search(r'\.(png|jpe?g|webp|gif|mp4|mov)$',name,re.I):return 'visual_asset'
    if re.search(r'^(ecarx-hud-system|ecarx-hud-dump\.tar|hudlab-cluster|status-action-session|Monjaro HUD log|KX11-(Cruise-Trace|QNX-(?!IPCP-Collector)|VP-.*Evidence|Raw-Ethernet-Evidence|Update-Package-Evidence|BOOT-FAILURE|Agent-Review|HU-Route|Bus-Capture|stock-launcher-phone)|KX11_Bluetooth_Collect_20)',name,re.I) and not name.endswith('.sha256'):
        return 'firmware_or_runtime_evidence'
    if re.search(r'^MConfig.*\.(apk|zip)$',name,re.I):return 'reference_application'
    if re.search(r'Research-Agent|collector|capture\.sh|trace\.sh|Collect.*\.(sh|command|zip)$|patch|Activation_ADB|ecarx_hud_dump_mconfig',name,re.I):return 'research_tool_or_prior_procedure'
    return 'other_project_artifact'

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--baseline',type=Path,required=True)
    ap.add_argument('--candidates',type=Path,required=True)
    ap.add_argument('--originals',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    baseline=json.loads(args.baseline.read_text())['sources']
    candidates=json.loads(args.candidates.read_text())
    by_id={x['library_file_id']:x for x in baseline}
    candidate_rows=[];category_counts=collections.Counter()
    local={};archives=[]
    for p in sorted(args.originals.iterdir()):
        if not p.is_file() or '.openai-download-' in p.name:continue
        sha=digest(p);members=archive_members(p)
        entry={'name':p.name,'bytes':p.stat().st_size,'sha256':sha,'outer_member_count':len(members),'scope':'Top-level archive member inventory; targeted semantic analysis is reported separately.'}
        local[p.name]=entry
        if members:
            archives.append({**entry,'members':members})
    seen=set()
    for x in candidates['items']:
        ident=x.get('library_file_id') or x['id'];seen.add(ident)
        cat=category(x['name']);category_counts[cat]+=1
        if cat not in {'firmware_or_runtime_evidence','reference_application','research_tool_or_prior_procedure'}:continue
        previous=by_id.get(ident);present=local.get(x['name'])
        row={'name':x['name'],'bytes':x.get('size_bytes'),'category':cat,'source_id':ident,'prior_v1_0_corpus':previous is not None,'available_locally_this_pass':present is not None,'current_depth':'metadata_only'}
        if previous:row.update({'sha256':previous['sha256'],'prior_depth':previous.get('coverage'),'current_depth':'prior_analysis_retained'})
        if present:
            row.update({'sha256':present['sha256'],'outer_member_count':present['outer_member_count'],'current_depth':'outer_archive_inventory_or_text_available'})
            if previous and previous['sha256']!=present['sha256']:raise ValueError('Original hash differs from baseline: '+x['name'])
        candidate_rows.append(row)
    for x in baseline:
        if x['library_file_id'] in seen:continue
        candidate_rows.append({'name':x['original_name'],'bytes':x['size_bytes'],'category':'prior_baseline_reference','source_id':x['library_file_id'],'prior_v1_0_corpus':True,'available_locally_this_pass':x['original_name'] in local,'sha256':x['sha256'],'current_depth':'prior_analysis_retained','prior_depth':x.get('coverage')})
    hash_groups=collections.defaultdict(list)
    for x in candidate_rows:
        if x.get('sha256'):hash_groups[x['sha256']].append(x['name'])
    output={'schema_version':1,'date':'2026-09-05','scope':{'filename_screening_complete_for_list_snapshot':candidates['list_complete'],'matched_filename_items':len(candidates['items']),'discovery_limit':'Name-based discovery is not content review and can miss generically named archives. No claim that every Library item or vehicle ECU was semantically analyzed.','prior_corpus_sources':len(baseline),'materialized_files_this_pass':len(local),'category_counts':dict(category_counts)},'sources':candidate_rows,'same_sha256_groups':[{'sha256':k,'names':v} for k,v in hash_groups.items() if len(v)>1]}
    for name,data in [('corpus_inventory.json',output),('archive_member_inventory.json',{'date':'2026-09-05','scope':'Outer members only; nested payloads are not automatically counted as decoded.','archives':archives})]:
        (args.output/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'scope':output['scope'],'registered_sources':len(candidate_rows),'archives':len(archives),'outer_members':sum(len(x['members']) for x in archives),'hash_duplicate_groups':len(output['same_sha256_groups'])},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
