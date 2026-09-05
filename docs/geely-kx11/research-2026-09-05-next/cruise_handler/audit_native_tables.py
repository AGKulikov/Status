"""Decode bounded known VHAL switch tables from stored ELF only; no firmware execution."""
from pathlib import Path
import json,hashlib,struct
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
R=Path(__file__).resolve().parent
P=R.parent.parent/'analysis/mcu_log_route/private/vhal_v1_0_net_impl-lib.so'
e=ELFFile(P.open('rb'));md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN)
def rd(a,n):
 s=next(s for s in e.iter_sections() if s['sh_addr']<=a<s['sh_addr']+s['sh_size'])
 return s.data()[a-s['sh_addr']:a-s['sh_addr']+n]
ids={0x214070f4:'AsyALatIndcr',0x214070f5:'AsyALgtIndcr',0x21407160:'TiGapSetForLgtCtrl',0x21407705:'SteerWhlBtnPsd',0x21407a7a:'AdjSpdLimnSts',0x21407a83:'CrsCtrlrSts',0x21407174:'AsyALgtStsAsyALgtSts',0x21407175:'AsyALgtStsChks',0x21407176:'AsyALgtStsCntr',0x21407118:'DrvrAsscSysDisp',0x21407119:'DrvrAsscSysSts',0x21407008:'DrvrAsscSysBtnPush',0x21407009:'DrvrAsscSysParkMod',0x214080d2:'CB_SAP_DrvrAsscSysBtnPush',0x21408000:'CB_ASY_ACC_and_TSR',0x21408001:'CB_ASY_HWA'}
rows=[]
for prop,name in ids.items():
 if prop>=0x21408000:base=0x21408000;table=0x24f4d8;converter=0x199f5c
 elif prop>=0x21407a3d:base=0x21407a3d;table=0x2529d8;converter=0x19c808
 else:base=0x21407000;table=0x250274;converter=0x19c808
 idx=prop-base;off=struct.unpack('<i',rd(table+idx*4,4))[0];target=table+off
 instr=[{'va':hex(i.address),'mnemonic':i.mnemonic,'operands':i.op_str} for i in md.disasm(rd(target,12),target)]
 rows.append({'vhal_property':hex(prop),'config_name':name,'converter_va':hex(converter),'table_va':hex(table),'table_property_base':hex(base),'table_index':idx,'entry_signed32':off,'target_va':hex(target),'first_three_instructions':instr,'classification':'invalid_no_outbound' if target==0x1a0fb8 else 'non_cruise_setting_or_parking_command'})
assert all(r['target_va']=='0x1a0fb8' for r in rows[:11])
(R/'native_tables.json').write_text(json.dumps({'source_sha256':hashlib.sha256(P.read_bytes()).hexdigest(),'algorithm':'target = jump_table_VA + little_endian_signed32(table + 4*(property-base)); base/range verified from converter instructions','scope':'11 original/adjacent read properties plus five actual parking/ADAS-setting entries. These are VHAL property IDs, not CAN IDs.','entries':rows},indent=2))
print('verified',len(rows))
