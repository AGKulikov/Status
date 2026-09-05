#!/usr/bin/env python3
"""Decode DEX encoded_value independently, including sign and right-zero extension."""
import collections,json,math,pathlib,struct
ROOT=pathlib.Path(__file__).resolve().parent
OUT=ROOT/'private/typed_initializers';OUT.mkdir(exist_ok=True)
def uleb(b,p):
    n=0
    for shift in range(0,35,7):
        v=b[p];p+=1;n|=(v&127)<<shift
        if v<128:return n,p
    raise ValueError('ULEB128 overflow')
TYPES={0:'byte',2:'short',3:'char',4:'int',6:'long',16:'float',17:'double',21:'method_type',22:'method_handle',23:'string',24:'type',25:'field',26:'method',27:'enum',28:'array',29:'annotation',30:'null',31:'boolean'}
def value(b,p):
    start=p;head=b[p];p+=1;t=head&31;arg=head>>5;n=arg+1
    if t in (0,2,3,4,6,16,17,21,22,23,24,25,26,27):
        raw=b[p:p+n];p+=n
        if t in (0,2,4,6):v=int.from_bytes(raw,'little',signed=True)
        elif t==3:v=int.from_bytes(raw,'little')
        elif t in (16,17):
            width=4 if t==16 else 8;bits=int.from_bytes(raw,'little')<<((width-n)*8);v=struct.unpack('<f' if t==16 else '<d',bits.to_bytes(width,'little'))[0]
            if not math.isfinite(v):v={'nonfinite_float':repr(v),'bits_hex':hex(bits)}
        else:v={'index_kind':TYPES[t],'index':int.from_bytes(raw,'little')}
    elif t==28:
        count,p=uleb(b,p);v=[]
        for i in range(count):r,p=value(b,p);v.append(r)
    elif t==29:
        ti,p=uleb(b,p);count,p=uleb(b,p);v={'type_idx':ti,'elements':[]}
        for i in range(count):ni,p=uleb(b,p);r,p=value(b,p);v['elements'].append({'name_idx':ni,'value':r})
    elif t==30:v=None
    elif t==31:v=bool(arg)
    else:raise ValueError('Unknown encoded value type '+str(t))
    return {'value_type':t,'value_kind':TYPES[t],'value_arg':arg,'encoded_offset':start,'encoded_size':p-start,'encoded_hex':b[start:p].hex(),'typed_value':v},p
def extract(path):
    dest=OUT/(path.stem+'.json')
    if dest.exists():return json.loads(dest.read_text())['counts']
    b=path.read_bytes();num,off=struct.unpack_from('<II',b,96);rows=[]
    for ci in range(num):
        classidx,_,_,_,_,_,cdata,svalues=struct.unpack_from('<8I',b,off+ci*32)
        if not svalues:continue
        p=cdata;counts=[]
        for i in range(4):n,p=uleb(b,p);counts.append(n)
        fids=[];fid=0
        for i in range(counts[0]):delta,p=uleb(b,p);fid+=delta;flags,p=uleb(b,p);fids.append(fid)
        n,p=uleb(b,svalues);assert n<=len(fids)
        for i in range(n):
            r,p=value(b,p);r.update({'class_idx':classidx,'field_idx':fids[i]});rows.append(r)
    counts=dict(collections.Counter(r['value_kind'] for r in rows))
    dest.write_text(json.dumps({'source_id':'sha256:'+path.stem,'counts':counts,'initializers':rows},ensure_ascii=True,separators=(',',':'))+'\n');return counts
if __name__=='__main__':
    counts=collections.Counter()
    for p in sorted((ROOT/'private/dex').glob('*.dex')):counts.update(extract(p))
    print(dict(counts))
