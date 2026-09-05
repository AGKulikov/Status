"""Find bounded AArch64 ADRP+ADD references to known updater file-path strings."""
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
from pathlib import Path
import re,json,hashlib
R=Path(__file__).resolve().parent
p=R/'private/vendor_bin_smartcore_update_client';e=ELFFile(p.open('rb'));t=e.get_section_by_name('.text');md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN);md.detail=True;regs={};out=[]
for i in md.disasm(t.data(),t['sh_addr']):
 if i.mnemonic=='adrp':
  m=re.fullmatch(r'(x\d+), #0x([0-9a-f]+)',i.op_str)
  if m:regs[m[1]]=(int(m[2],16),i.address)
 elif i.mnemonic=='add':
  m=re.fullmatch(r'(x\d+), (x\d+), #0x([0-9a-f]+)',i.op_str)
  if m and m[2] in regs:
   base,at=regs[m[2]];v=base+int(m[3],16)
   if i.address-at<=64 and v in [0xe29c,0xe2b2,0xe30f,0xe324,0xe351,0xe366,0xe393,0xe3a8,0xe3d5,0xe3ea,0xe417,0xe42c,0xea35]:out.append({'adrp_va':hex(at),'add_va':hex(i.address),'string_va':hex(v),'register':m[1]})
 elif i.mnemonic in ['bl','blr']:regs={}
 else:
  for r in i.regs_access()[1]:regs.pop(i.reg_name(r),None)
(R/'updater_path_xrefs.json').write_text(json.dumps({'source_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'scope':'Only nearby ADRP+ADD references to known path strings; establishes code references, not installed file presence or package ownership.','references':out},indent=2))
assert len(out)==14
