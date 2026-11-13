#!/usr/bin/env python3
"""Prepare pinned Music Natro4 payload; signing is a separate mandatory release step."""
import argparse, hashlib, json, os, struct, subprocess, zipfile
from pathlib import Path
from patch_music_manifest import chunks
BASE='61be448f22cf3852a4a0ea12452b0e07adec60337dde5b8c1d423d459f1bbe9e'
def identity(data):
    old='2026.10.2 #169gpr Natro 3'.encode('utf-16le')
    if data.count(old)!=1:raise ValueError('Unexpected manifest identity')
    data=data.replace(old,'2026.10.2 #169gpr Natro 4'.encode('utf-16le'))
    pool=list(chunks(data))[0][1]
    count,styles,flags,start,style_start=struct.unpack_from('<5I',pool,8)
    if styles or flags or style_start:raise ValueError('Unexpected pool')
    strings=[]
    for off in struct.unpack_from('<'+str(count)+'I',pool,28):
        n=struct.unpack_from('<H',pool,start+off)[0]
        strings.append(pool[start+off+2:start+off+2+n*2].decode('utf-16le'))
    result=[];found=0
    for kind,part in chunks(data):
        if kind==0x102 and strings[struct.unpack_from('<I',part,20)[0]]=='manifest':
            b=bytearray(part);a_start,a_size,a_count=struct.unpack_from('<HHH',b,24)
            for i in range(a_count):
                pos=16+a_start+i*a_size
                if strings[struct.unpack_from('<I',b,pos+4)[0]]=='versionCode':
                    if struct.unpack_from('<I',b,pos+16)[0]!=24026524:raise ValueError('Wrong previous code')
                    struct.pack_into('<I',b,pos+16,24026525);found+=1
            part=bytes(b)
        result.append(part)
    if found!=1:raise ValueError('Missing version')
    payload=b''.join(result);return struct.pack('<HHI',3,8,len(payload)+8)+payload

def main():
    p=argparse.ArgumentParser()
    for name in ('previous','bridge','tool-dir','out'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args()
    if hashlib.sha256(a.previous.read_bytes()).hexdigest()!=BASE:raise ValueError('Unreviewed Music APK')
    a.out.mkdir(parents=True,exist_ok=False)
    cp=os.pathsep.join([str(a.tool_dir)]+[str(x) for x in sorted(a.tool_dir.glob('*.jar'))])
    with zipfile.ZipFile(a.previous) as src:
        before=a.out/'native-before.dex';after=a.out/'native-after.dex'
        before.write_bytes(src.read('classes.dex'))
        subprocess.run(['java','-cp',cp,'PatchMusicWave',str(before),str(after)],check=True)
        changed={};unchanged=0
        with zipfile.ZipFile(a.out/'Music-Natro4-UNSIGNED.apk','w') as dst:
            for info in src.infolist():
                if info.filename.startswith('META-INF/') and (info.filename.endswith(('.RSA','.SF','.MF','.DSA','.EC'))):continue
                data=src.read(info)
                if info.filename=='classes.dex':data=after.read_bytes()
                elif info.filename=='classes7.dex':data=a.bridge.read_bytes()
                elif info.filename=='AndroidManifest.xml':data=identity(data)
                if data!=src.read(info):changed[info.filename]=hashlib.sha256(data).hexdigest()
                else:unchanged+=1
                dst.writestr(info,data)
    if set(changed)!={'classes.dex','classes7.dex','AndroidManifest.xml'}:raise ValueError('Unexpected payload changes')
    report={'previous_sha256':BASE,'changed':changed,'unchanged_entries':unchanged,
            'versionCode':24026525,'versionName':'2026.10.2 #169gpr Natro 4',
            'signed':False,'field_verified':False}
    (a.out/'payload-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
if __name__=='__main__':main()
