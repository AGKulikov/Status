#!/usr/bin/env python3
"""Give the verified Navigator 3.4.2 mod a monotonic updater identity; do not change its code."""
import argparse,copy,json,re,struct,zipfile
from pathlib import Path
from patch_music_manifest import chunks
from sign_natro_candidate import CERT,payload_hashes,run,sha256,signature_entry
from verify_kx11_navigation_pair import verify_signer,verify_zipalign
BASE='1e8e3ecaefcc3f205e06eff95d99bfca9a023bf7e9d41bbd7b60df472409f5bb'
OLD_CODE=739564630
NEW_CODE=739564631
NEW_NAME='30.3.0 Natro 3.4.9'

def identity(data):
    parts=list(chunks(data));pool=parts[0][1]
    count,styles,flags,start,style_start=struct.unpack_from('<5I',pool,8)
    if styles or flags!=256 or style_start or start!=28+4*count:raise ValueError('Unreviewed Navigator string pool')
    offsets=list(struct.unpack_from('<'+'I'*count,pool,28));strings=[]
    def length(pos):
        first=pool[pos];pos+=1
        if first&128:return ((first&127)<<8)|pool[pos],pos+1
        return first,pos
    for off in offsets:
        _,pos=length(start+off);size,pos=length(pos);strings.append(pool[pos:pos+size].decode('utf-8'))
    raw=NEW_NAME.encode('utf-8');assert len(raw)<128
    blob=pool[start:];offsets.append(len(blob));blob+=bytes([len(NEW_NAME),len(raw)])+raw+b'\0'
    blob+=b'\0'*((-len(blob))%4)
    newpool=struct.pack('<HHI5I',1,28,28+4*(count+1)+len(blob),count+1,0,flags,28+4*(count+1),0)+struct.pack('<'+'I'*(count+1),*offsets)+blob
    result=[newpool];changed=[]
    for kind,part in parts[1:]:
        if kind==0x102 and strings[struct.unpack_from('<I',part,20)[0]]=='manifest':
            b=bytearray(part);attr_start,attr_size,attr_count=struct.unpack_from('<HHH',b,24)
            for i in range(attr_count):
                pos=16+attr_start+i*attr_size;name=strings[struct.unpack_from('<I',b,pos+4)[0]]
                if name=='versionCode':
                    if b[pos+15]!=0x10 or struct.unpack_from('<I',b,pos+16)[0]!=OLD_CODE:raise ValueError('Unexpected old versionCode')
                    struct.pack_into('<I',b,pos+16,NEW_CODE);changed.append(name)
                if name=='versionName':
                    if b[pos+15]!=3 or strings[struct.unpack_from('<I',b,pos+16)[0]]!='30.3.0':raise ValueError('Unexpected old versionName')
                    struct.pack_into('<I',b,pos+8,count);struct.pack_into('<I',b,pos+16,count);changed.append(name)
            part=bytes(b)
        result.append(part)
    if sorted(changed)!=['versionCode','versionName']:raise ValueError('Version attributes missing/duplicated')
    payload=b''.join(result);return struct.pack('<HHI',3,8,len(payload)+8)+payload

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('previous','candidate','keystore','password-file','output'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--source-commit',required=True);p.add_argument('--source-tree',required=True)
    a=p.parse_args();candidate=json.loads((a.candidate/'candidate.json').read_text())
    if candidate['sourceCommit']!=a.source_commit or candidate['sourceTree']!=a.source_tree or candidate['versionName']!='3.4.9':raise ValueError('Wrong source candidate')
    if sha256(a.previous)!=BASE:raise ValueError('Pinned Navigator 3.4.2 required')
    sdk=a.candidate/'tools';signer,align,aapt=[sdk/n for n in ('apksigner','zipalign','aapt')]
    before_sign=verify_signer(signer,a.previous,('v2','v3'))
    old_badging=run(aapt,'dump','badging',a.previous).stdout
    if "name='ru.yandex.yandexnavi' versionCode='739564630' versionName='30.3.0'" not in old_badging:raise ValueError('Wrong baseline identity')
    a.output.mkdir(parents=True,exist_ok=False)
    unsigned=a.output/'navigator-identity-unsigned.apk';aligned=a.output/'navigator-identity-aligned.apk';signed=a.output/'Navigator-30.3.0-Natro-3.4.9-signed.apk'
    with zipfile.ZipFile(a.previous) as src,zipfile.ZipFile(unsigned,'w') as dst:
        bridge=__import__('hashlib').sha256(src.read('classes19.dex')).hexdigest()
        if bridge!=candidate['navigator']['patchSha256'] or sha256(a.candidate/'classes19.dex')!=bridge:raise ValueError('CI bridge changed: cannot reuse old Navigator code')
        dst.comment=src.comment
        for entry in src.infolist():
            if signature_entry(entry.filename):continue
            raw=src.read(entry)
            if entry.filename=='AndroidManifest.xml':raw=identity(raw)
            dst.writestr(copy.copy(entry),raw)
    old=payload_hashes(a.previous);expected=payload_hashes(unsigned)
    changed=sorted(k for k in old if old[k]!=expected.get(k))
    if set(old)!=set(expected) or changed!=['AndroidManifest.xml']:raise ValueError('Payload changed outside manifest')
    old_xml=run(aapt,'dump','xmltree',a.previous,'AndroidManifest.xml').stdout
    new_xml=run(aapt,'dump','xmltree',unsigned,'AndroidManifest.xml').stdout
    def without_versions(s):return '\n'.join(line for line in s.splitlines() if not re.search(r'android:version(?:Code|Name)\(',line))
    if without_versions(old_xml)!=without_versions(new_xml):raise ValueError('Manifest semantic changes outside version')
    run(align,'-f','-P','16','4',unsigned,aligned)
    run(signer,'sign','--ks',a.keystore,'--ks-key-alias','status-widget-ha','--ks-pass','file:'+str(a.password_file),'--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--out',signed,aligned)
    verification=verify_signer(signer,signed,('v2','v3'));verify_zipalign(align,signed);run(signer,'verify','--min-sdk-version','28','--max-sdk-version','28',signed)
    if payload_hashes(signed)!=expected:raise ValueError('Signing changed payload')
    badging=run(aapt,'dump','badging',signed).stdout
    if badging!=old_badging.replace("versionCode='739564630'","versionCode='739564631'").replace("versionName='30.3.0'","versionName='"+NEW_NAME+"'"):raise ValueError('Unexpected APK identity/SDK/ABI/label changes')
    report=dict(apk=signed.name,sha256=sha256(signed),size=signed.stat().st_size,sourceCommit=a.source_commit,sourceTree=a.source_tree,ciRunId=candidate['ciRunId'],previousSha256=BASE,versionName=NEW_NAME,versionCode=NEW_CODE,previousVersionCode=OLD_CODE,certificateSha256=CERT,signatureV2=True,signatureV3=True,exactApi28=True,zipalign16KiB=True,changedPayload=changed,unchangedPayloadEntries=len(old)-1,manifestSemanticsOnlyVersionChanged=True,ciBridgeSha256=bridge,physicalKx11Verification='pending')
    (a.output/'navigator-release-report.json').write_text(json.dumps(report,indent=2)+'\n')
    (a.output/'navigator-signature-verification.txt').write_text(verification+'\nPrevious Navigator:\n'+before_sign)
    Path(str(signed)+'.idsig').unlink(missing_ok=True);print(json.dumps(report))
if __name__=='__main__':main()
