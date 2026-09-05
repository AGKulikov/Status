"""Static ELF disassembly. Does not execute source binaries."""
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
from pathlib import Path
import sys
R=Path(__file__).resolve().parent
for nm in ['sc_qnx_vip_proxy','can_driver','cluster_controller','ota-service']:
 p=R/'private'/nm
 with p.open('rb') as f:
  e=ELFFile(f);dyn=e.get_section_by_name('.dynsym');sym=e.get_section_by_name('.symtab') or dyn
  names={x['st_value']:x.name for x in sym.iter_symbols() if x['st_value']}
  plt=e.get_section_by_name('.plt');rels=e.get_section_by_name('.rela.plt')
  if plt and rels:
   for i,x in enumerate(rels.iter_relocations()):names[plt['sh_addr']+32+i*16]=dyn.get_symbol(x['r_info_sym']).name
  def rd(a,n):
   for sec in e.iter_sections():
    if sec['sh_addr']<=a<sec['sh_addr']+sec['sh_size']:return sec.data()[a-sec['sh_addr']:a-sec['sh_addr']+n]
  md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN);ss=[]
  if nm=='can_driver':
   t=e.get_section_by_name('.text');ss=[('text',t['sh_addr'],t['sh_size'])]
  else:
   for x in sym.iter_symbols():
    if x['st_value'] and x['st_info']['type']=='STT_FUNC' and any(k in x.name for k in ['qnxVipProxy4init','qnxVipProxy18onRecvUsb','qnxVipProxy15onRecvHeart','qnxVipProxy9onRecvACK','qnxAndroidEventHandler','IPCLModule12parsePayload','IPCLModule11packPayload','ota_received_cb','init_ota_ipcp']):ss.append((x.name,x['st_value'],x['st_size']))
  for name,addr,size in ss:
   lines=[]
   for i in md.disasm(rd(addr,size),addr):
    extra=''
    if i.mnemonic in ['bl','b']:
     try:extra=names.get(int(i.op_str[1:],16),'')
     except:pass
    lines.append(f'{i.address:08x}: {i.mnemonic:8} {i.op_str} {extra}'.rstrip())
   (R/'private'/f'{nm}.{addr:x}.asm.txt').write_text(name+'\n'+'\n'.join(lines))
