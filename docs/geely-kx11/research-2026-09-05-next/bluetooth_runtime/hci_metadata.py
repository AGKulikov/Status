from pathlib import Path
import json,tarfile,hashlib,struct,datetime,collections
root=Path(__file__).parent;srcs=json.load(open(root/'source_inventory.json'));res=[]
for s in srcs:
 if '/FILES/data/misc/bluetooth/logs/btsnoop_' not in s['member']:continue
 with tarfile.open(Path('audit-work/originals')/s['archive']) as t:b=t.extractfile(s['member']).read()
 s['member_sha256']=hashlib.sha256(b).hexdigest();s['inspection']='HCI record framing and whitelisted lifecycle metadata only; ACL/SCO/key/notification payload skipped'
 p=root/'private'/s['archive'].replace('.tar.gz','')/'FILES/data/misc/bluetooth/logs'/s['member'].split('/')[-1];p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);s['local_path']=str(p)
 assert b[:8]==b'btsnoop\0',b[:8]
 version,link=struct.unpack('>II',b[8:16]);off=16;n=0;bad=0;events=[];types=collections.Counter();start=end=None
 while off<len(b):
  record_offset=off
  if off+24>len(b):bad+=1;break
  original,included,flags,drops,ts=struct.unpack('>IIIIQ',b[off:off+24]);off+=24
  if off+included>len(b):bad+=1;break
  pkt=b[off:off+included];off+=included;n+=1
  timestamp=datetime.datetime.fromtimestamp((ts-0x00dcddb30f2f8000)/1e6,datetime.timezone.utc).isoformat();start=start or timestamp;end=timestamp
  if not pkt:continue
  types[pkt[0]]+=1
  fields=None
  # type 4 event: read only fixed scalar fields in these enumerated events.
  if pkt[0]==4 and len(pkt)>=3:
   ev,plen=pkt[1],pkt[2]
   if ev==5 and len(pkt)>=7:fields={'event':'disconnection_complete','hci_event':ev,'status':pkt[3],'handle':int.from_bytes(pkt[4:6],'little'),'reason':pkt[6]}
   elif ev==0x10 and len(pkt)>=4:fields={'event':'hardware_error','hci_event':ev,'error':pkt[3]}
   elif ev==0x3e and len(pkt)>=5 and pkt[3] in (1,3,10):
    sub=pkt[3];fields={'event':{1:'LE_connection_complete',3:'LE_connection_update_complete',10:'LE_enhanced_connection_complete'}[sub],'hci_event':ev,'subevent':sub,'status':pkt[4]}
    if len(pkt)>=7:fields['handle']=int.from_bytes(pkt[5:7],'little')
   elif ev==0x0f and len(pkt)>=7 and pkt[3]!=0:fields={'event':'command_status_failure','status':pkt[3],'opcode':int.from_bytes(pkt[5:7],'little')}
   elif ev==0x0e and len(pkt)>=7 and pkt[6]!=0:fields={'event':'command_complete_status_nonzero','opcode':int.from_bytes(pkt[4:6],'little'),'status':pkt[6]}
  elif pkt[0]==1 and len(pkt)>=4:
   opcode=int.from_bytes(pkt[1:3],'little')
   if opcode==0x0406 and len(pkt)>=7:fields={'event':'disconnect_command','opcode':opcode,'handle':int.from_bytes(pkt[4:6],'little'),'reason':pkt[6]}
  if fields:events.append({'record':n,'record_offset':record_offset,'timestamp_UTC':timestamp,**fields})
 summary={k:s[k] for k in ('archive','archive_sha256','member','member_sha256')};summary.update(version=version,datalink=link,records=n,malformed_records=bad,record_type_counts=dict(types),start_UTC=start,end_UTC=end,events=events);res.append(summary)
 print(s['archive'],version,link,n,start,end,'lifecycle_events',len(events),'errors',bad)
 print(collections.Counter((x['event'],x.get('reason'),x.get('status')) for x in events))
(root/'private/hci_metadata.json').write_text(json.dumps(res,indent=2));(root/'source_inventory.json').write_text(json.dumps(srcs,ensure_ascii=False,indent=2))
