#!/usr/bin/env python3
"""Inventory archived code offline, deduplicate by bytes, preserve every source path."""
import argparse, hashlib, io, json, pathlib, tarfile, zipfile

ROOT=pathlib.Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--originals',type=pathlib.Path,default=ROOT.parents[1]/'originals')
ORIGINALS=parser.parse_args().originals
(ROOT/'private/dex').mkdir(parents=True,exist_ok=True)
blobs={}; occurrences=[]; archives=[]; errors=[]; scanned=[]
if (ROOT/'source_inventory.json').exists():
    old=json.loads((ROOT/'source_inventory.json').read_text())
    blobs={x['sha256']:x for x in old['sources']};occurrences=old['occurrences'];archives=old['archives'];errors=old['errors']

def sha(data): return hashlib.sha256(data).hexdigest()
def file_sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(8*1024*1024),b''):h.update(b)
    return h.hexdigest()

def visit(data, chain, parent=None):
    h=sha(data); sid='sha256:'+h
    occurrences.append({'source_id':sid,'path_chain':chain,'parent_source_id':parent})
    if h in blobs:return
    magic=data[:8]
    kind='dex' if magic.startswith(b'dex\n') else 'compact_dex' if magic.startswith(b'cdex') else 'jvm_class' if magic.startswith(b'\xca\xfe\xba\xbe') else 'zip' if zipfile.is_zipfile(io.BytesIO(data)) else 'unrecognized_candidate'
    blobs[h]={'source_id':sid,'sha256':h,'size':len(data),'kind':kind,'children':[]}
    if kind=='dex':
        (ROOT/'private/dex'/f'{h}.dex').write_bytes(data)
    elif kind=='jvm_class':
        (ROOT/'private/jvm').mkdir(exist_ok=True)
        (ROOT/'private/jvm'/f'{h}.class').write_bytes(data)
    elif kind=='zip':
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for info in z.infolist():
                if info.is_dir():continue
                name=info.filename
                with z.open(info) as f:magic=f.read(8)
                jvm_sdk=name.endswith('.class') and name.startswith(('com/ecarx/','ecarx/','vendor/ecarx/','android/car/'))
                wanted=jvm_sdk or name.lower().endswith(('.dex','.apk','.jar','.zip','.aar')) or magic.startswith((b'dex\n',b'cdex',b'PK\x03\x04'))
                if wanted:
                    child=z.read(info); ch=sha(child)
                    blobs[h]['children'].append({'member':name,'source_id':'sha256:'+ch})
                    visit(child,chain+[name],sid)
    return sid

def scan(p):
    before=len(occurrences); sh=file_sha(p)
    if any(x['filename']==p.name and x['sha256']==sh for x in archives):return
    rec={'filename':p.name,'local_path':str(p),'sha256':sh,'size':p.stat().st_size,'members_scanned':0,'candidate_members':0}
    try:
        if p.suffix.lower() in {'.dex','.jar','.apk','.aar'}:
            rec['format']='standalone';visit(p.read_bytes(),[p.name],'archive-sha256:'+sh)
        elif zipfile.is_zipfile(p):
            rec['format']='zip'
            with zipfile.ZipFile(p) as z:
                for info in z.infolist():
                    if info.is_dir():continue
                    rec['members_scanned']+=1
                    with z.open(info) as f:magic=f.read(8)
                    if info.filename.lower().endswith(('.dex','.apk','.jar','.zip','.aar')) or magic.startswith((b'dex\n',b'cdex',b'PK\x03\x04')):
                        rec['candidate_members']+=1
                        visit(z.read(info),[p.name,info.filename],'archive-sha256:'+sh)
        elif p.name.lower().endswith(('.tar','.tar.gz','.tgz')):
            rec['format']='tar'
            with tarfile.open(p,mode='r|*') as t:
                for info in t:
                    if not info.isfile():continue
                    rec['members_scanned']+=1
                    f=t.extractfile(info);magic=f.read(8)
                    if info.name.lower().endswith(('.dex','.apk','.jar','.zip','.aar')) or magic.startswith((b'dex\n',b'cdex',b'PK\x03\x04')):
                        rec['candidate_members']+=1
                        visit(magic+f.read(),[p.name,info.name],'archive-sha256:'+sh)
        else:return
        rec['occurrences_added']=len(occurrences)-before
    except Exception as e:
        errors.append({'file':p.name,'error':str(e)})
    archives.append(rec)
    print(p.name,rec['members_scanned'],rec['candidate_members'],flush=True)

for p in sorted(ORIGINALS.iterdir()):
    if p.is_file() and not '.openai-download-' in p.name:scan(p)
result={'schema':'sdk-corpus-source-inventory-v2','source_id_rule':'sha256:<full SHA-256 of exact bytes>; outer archive IDs use archive-sha256 prefix','archives':archives,'sources':list(blobs.values()),'occurrences':occurrences,'errors':errors,'runtime_class_origin_verified':False,'limitations':['Archive path is disk provenance only. Duplicate container children refer to the canonical source graph; expand children for every duplicate occurrence.','DEX in encrypted, inaccessible or unsupported compressed containers is not inferred. Unknown candidates are retained.']}
(ROOT/'source_inventory.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('sources',len(blobs),'dex',sum(x['kind']=='dex' for x in blobs.values()),'errors',errors,flush=True)
