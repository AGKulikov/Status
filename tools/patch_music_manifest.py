#!/usr/bin/env python3
"""Pinned binary XML patch: add only the authenticated provider and mod version identity."""
import hashlib,struct
from pathlib import Path
EXPECTED='4f8250f842f4cccbdc642fbca4467171acc70866a4821fe4a7ff13687d559512'
PROVIDER='ru.natro.music.BootstrapProvider'
AUTHORITY='ru.yandex.music.natro.bootstrap'
VERSION='2026.10.2 #169gpr Natro 1'
U=0xffffffff

def chunks(data):
    pos=8
    while pos<len(data):
        kind,head,size=struct.unpack_from('<HHI',data,pos)
        if size<head or pos+size>len(data):raise ValueError('Invalid chunk')
        yield kind,bytes(data[pos:pos+size])
        pos+=size
    if pos!=len(data):raise ValueError('Invalid end')

def patch(data):
    if hashlib.sha256(data).hexdigest()!=EXPECTED:raise ValueError('Unreviewed Music manifest')
    parts=list(chunks(data)); pool=parts[0][1]
    count,styles,flags,start,style_start=struct.unpack_from('<5I',pool,8)
    if styles or flags or style_start:raise ValueError('Unexpected string pool layout')
    offsets=struct.unpack_from('<'+str(count)+'I',pool,28)
    strings=[]
    for off in offsets:
        n=struct.unpack_from('<H',pool,start+off)[0]
        if n>=0x8000:raise ValueError('Unexpected long string')
        strings.append(pool[start+off+2:start+off+2+n*2].decode('utf-16le'))
    blob=bytearray(pool[start:]); offsets=list(offsets)
    def index(s):
        if s in strings:return strings.index(s)
        offsets.append(len(blob));raw=s.encode('utf-16le')
        blob.extend(struct.pack('<H',len(raw)//2)+raw+b'\0\0');strings.append(s)
        return len(strings)-1
    ns=index('http://schemas.android.com/apk/res/android');provider=index('provider')
    cls=index(PROVIDER);authority=index(AUTHORITY);version=index(VERSION)
    def attr(name,kind,value):
        # Attribute names must already have the correct Android resource IDs.
        if name not in strings[:count]:raise ValueError('Missing framework attribute '+name)
        return struct.pack('<IIIHBBI',ns,index(name),value if kind==3 else U,8,0,kind,value)
    attrs=[attr('name',3,cls),attr('enabled',0x12,U),attr('exported',0x12,U),attr('authorities',3,authority)]
    # Android binary XML attributes are ordered by framework resource ID.
    resource_map=parts[1][1];ids=struct.unpack_from('<'+str((len(resource_map)-8)//4)+'I',resource_map,8)
    attrs.sort(key=lambda a:ids[struct.unpack_from('<I',a,4)[0]])
    opening=struct.pack('<HHIII',0x102,16,36+20*len(attrs),0,U)+struct.pack('<IIHHHHHH',U,provider,20,20,len(attrs),0,0,0)+b''.join(attrs)
    closing=struct.pack('<HHIIIII',0x103,16,24,0,U,U,provider)
    while len(blob)%4:blob.append(0)
    size=28+4*len(strings)+len(blob)
    newpool=struct.pack('<HHI5I',1,28,size,len(strings),0,0,28+4*len(strings),0)+struct.pack('<'+str(len(offsets))+'I',*offsets)+blob
    result=[newpool];inserted=0;versions=0
    for kind,part in parts[1:]:
        if kind==0x102 and strings[struct.unpack_from('<I',part,20)[0]]=='manifest':
            b=bytearray(part);a_start,a_size,a_count=struct.unpack_from('<HHH',b,24)
            for i in range(a_count):
                off=16+a_start+i*a_size;name=strings[struct.unpack_from('<I',b,off+4)[0]]
                if name=='versionName':struct.pack_into('<I',b,off+8,version);struct.pack_into('<I',b,off+16,version);versions+=1
                if name=='versionCode':
                    if struct.unpack_from('<I',b,off+16)[0]!=24026521:raise ValueError('Unexpected version')
                    struct.pack_into('<I',b,off+16,24026522);versions+=1
            part=bytes(b)
        if kind==0x103 and strings[struct.unpack_from('<I',part,20)[0]]=='application':
            result.extend([opening,closing]);inserted+=1
        result.append(part)
    if inserted!=1 or versions!=2:raise ValueError('Unexpected patch counts')
    payload=b''.join(result)
    return struct.pack('<HHI',3,8,len(payload)+8)+payload

if __name__=='__main__':
    import argparse
    a=argparse.ArgumentParser();a.add_argument('input',type=Path);a.add_argument('output',type=Path);v=a.parse_args()
    v.output.write_bytes(patch(v.input.read_bytes()))
