"""Offline extraction of embedded gzip/newc image; never boot/mount or execute it."""
from pathlib import Path
import zlib,hashlib,json,re
R=Path(__file__).resolve().parent;p=R/'private/linux-lv.img';b=p.read_bytes();off=19406128
d=zlib.decompressobj(16+zlib.MAX_WBITS);cpio=d.decompress(b[off:],200_000_000)
assert d.eof and cpio[:6]==b'070701'
rows=[];pos=0;root=R/'private/lv-root';root.mkdir(exist_ok=True)
while pos+110<=len(cpio):
 assert cpio[pos:pos+6] in [b'070701',b'070702']
 fields=[int(cpio[pos+6+8*i:pos+14+8*i],16) for i in range(13)]
 ino,mode,uid,gid,nlinks,mtime,size,_,_,_,_,namesize,check=fields
 name=cpio[pos+110:pos+110+namesize-1].decode();start=(pos+110+namesize+3)&~3;data=cpio[start:start+size];pos=(start+size+3)&~3
 if name=='TRAILER!!!':break
 assert not name.startswith('/') and '..' not in Path(name).parts
 row={'path':name,'mode':oct(mode),'bytes':size,'sha256':hashlib.sha256(data).hexdigest()}
 if (mode&0o170000)==0o100000:
  out=root/name;out.parent.mkdir(exist_ok=True,parents=True);out.write_bytes(data)
  pats=[p.decode(errors='replace') for p in [b'DrvrCrsCtrlFct',b'Cruise',b'cruise',b'McuLog',b'20.24.10.23024.41141',b'198.18.34.1'] if p in data]
  if pats:row['byte_patterns']=pats
 elif (mode&0o170000)==0o120000:row['link_target']=data.decode(errors='replace')
 rows.append(row)
(R/'lv_image_inventory.json').write_text(json.dumps({'image_sha256':hashlib.sha256(b).hexdigest(),'format':'AArch64 raw Linux Image with embedded gzip newc initramfs','gzip_offset':off,'gzip_bytes':len(b)-off-len(d.unused_data),'cpio_bytes':len(cpio),'cpio_sha256':hashlib.sha256(cpio).hexdigest(),'entries':rows},indent=2))
print(len(rows),'entries',sum(x['bytes'] for x in rows),'bytes')
for r in rows:
 if 'byte_patterns' in r or re.search('update|vp|mcu|firmware|vehicle|can|fsu|manifest',r['path'],re.I):print(r)
