#!/usr/bin/env python3
"""Independent binary class_data counts and DEX integrity checks (no Androguard)."""
import hashlib,json,pathlib,struct,zlib
ROOT=pathlib.Path(__file__).resolve().parent
def uleb(b,p):
    n=0
    for shift in range(0,35,7):
        v=b[p];p+=1;n|=(v&127)<<shift
        if v<128:return n,p
    raise ValueError('ULEB128 overflow')
def verify(p):
    b=p.read_bytes();sid='sha256:'+p.stem
    n,off=struct.unpack_from('<II',b,96)
    totals={'static_fields':0,'instance_fields':0,'direct_methods':0,'virtual_methods':0,'encoded_static_initializers':0,'implicit_static_initializers':0};classes=[]
    for i in range(n):
        cidx,flags,superidx,interfaces,sf,anno,cdata,svalues=struct.unpack_from('<8I',b,off+i*32)
        if not cdata:counts=[0,0,0,0]
        else:
            cp=cdata;counts=[]
            for j in range(4):v,cp=uleb(b,cp);counts.append(v)
            # Consume every encoded_field and encoded_method, validating index chains.
            for j,count in enumerate(counts):
                index=0
                for k in range(count):
                    diff,cp=uleb(b,cp);index+=diff
                    af,cp=uleb(b,cp)
                    if j>=2:co,cp=uleb(b,cp)
        enc=uleb(b,svalues)[0] if svalues else 0
        assert enc<=counts[0]
        for name,c in zip(('static_fields','instance_fields','direct_methods','virtual_methods'),counts):totals[name]+=c
        totals['encoded_static_initializers']+=enc;totals['implicit_static_initializers']+=counts[0]-enc
        classes.append({'class_idx':cidx,'fields':counts[0]+counts[1],'methods':counts[2]+counts[3]})
    x=json.loads((ROOT/'private/inventories'/(p.stem+'.json')).read_text());byidx={c['class_idx']:c for c in x['classes']}
    return {'source_id':sid,'sha256_matches_filename':hashlib.sha256(b).hexdigest()==p.stem,'dex_sha1_signature_valid':hashlib.sha1(b[32:]).digest()==b[12:32],'dex_adler32_checksum_valid':zlib.adler32(b[12:])&0xffffffff==struct.unpack_from('<I',b,8)[0],'raw_counts':totals,'classes_match':n==len(x['classes']),'fields_match':totals['static_fields']+totals['instance_fields']==len(x['fields']),'methods_match':totals['direct_methods']+totals['virtual_methods']==len(x['methods']),'per_class_counts_match':all(c['fields']==byidx[c['class_idx']]['field_count'] and c['methods']==byidx[c['class_idx']]['method_count'] for c in classes),'encoded_static_initializers_match':totals['encoded_static_initializers']==sum(r['static'] and r['encoded_initializer_present'] for r in x['fields']),'implicit_static_initializers_match':totals['implicit_static_initializers']==sum(r['static'] and not r['encoded_initializer_present'] for r in x['fields'])}
rs=[verify(p) for p in sorted((ROOT/'private/dex').glob('*.dex'))]
checks=['sha256_matches_filename','dex_sha1_signature_valid','dex_adler32_checksum_valid','classes_match','fields_match','methods_match','per_class_counts_match','encoded_static_initializers_match','implicit_static_initializers_match']
out={'schema':'raw-dex-independent-verification-v1','dexes':len(rs),'all_checks_pass':all(all(r[k] for k in checks) for r in rs),'failure_counts':{k:sum(not r[k] for r in rs) for k in checks},'sources':rs}
(ROOT/'RAW_DEX_VALIDATION.json').write_text(json.dumps(out,indent=2)+'\n');print({k:v for k,v in out.items() if k!='sources'})
