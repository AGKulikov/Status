"""Read saved PCAP archives only; no socket or replay code."""
from pathlib import Path
import zipfile,hashlib,json,struct,socket,collections,datetime
R=Path(__file__).resolve().parent; O=R.parent.parent/'originals'
def analyze(b):
 magic={b'\xd4\xc3\xb2\xa1':('<',1e6),b'\xa1\xb2\xc3\xd4':('>',1e6),b'\x4d\x3c\xb2\xa1':('<',1e9),b'\xa1\xb2\x3c\x4d':('>',1e9)}
 if len(b)<24 or b[:4] not in magic:return {'unsupported':'not legacy PCAP header'}
 en,scale=magic[b[:4]];link=struct.unpack_from(en+'I',b,20)[0];off=24;n=0;groups=collections.Counter();flows=collections.Counter();trans=[];mcu=[];last=None;trunc=0;frag=0;out=collections.Counter()
 while off+16<=len(b):
  sec,usec,cap,orig=struct.unpack_from(en+'IIII',b,off);off+=16;raw=b[off:off+cap];off+=cap;n+=1
  if len(raw)!=cap:trunc+=1;break
  if link==113:
   if len(raw)<16 or raw[14:16]!=b'\x08\x00':continue
   raw=raw[16:]
  elif link==1:
   if len(raw)<14:continue
   eth=struct.unpack_from('>H',raw,12)[0];eo=14
   while eth in [0x8100,0x88a8] and len(raw)>=eo+4:eth=struct.unpack_from('>H',raw,eo+2)[0];eo+=4
   if eth!=0x800:continue
   raw=raw[eo:]
  elif link not in [101,228]:continue
  if len(raw)<28 or raw[0]>>4!=4 or raw[9]!=17:continue
  ihl=(raw[0]&15)*4
  if len(raw)<ihl+8:continue
  if struct.unpack_from('>H',raw,6)[0]&0x3fff:frag+=1;continue
  src=socket.inet_ntoa(raw[12:16]);dst=socket.inet_ntoa(raw[16:20]);sp,dp,ln,_=struct.unpack_from('>HHHH',raw,ihl);p=raw[ihl+8:ihl+ln]
  flows[f'{src}:{sp}>{dst}:{dp}']+=1
  inbound=(src,sp,dst,dp)==('198.18.34.1',50500,'198.18.34.15',50335);outbound=(dst,dp,src,sp)==('198.18.34.1',50500,'198.18.34.15',50335)
  if len(p)<16 or not(inbound or outbound):continue
  sv,me,ln=struct.unpack_from('>HHI',p);direction='in' if inbound else 'out';groups[f'{direction} {sv:04x}/{me:04x} len={len(p)}']+=1
  ts=sec+usec/scale
  if inbound and (sv,me)==(31,86) and len(p)>=24:
   head=p[16:24].hex()
   if head!=last:trans.append({'packet':n,'utc':datetime.datetime.fromtimestamp(ts,datetime.timezone.utc).isoformat(),'payload_first8':head,'payload_byte2':p[18]});last=head
  if inbound and (sv,me)==(153,200):mcu.append({'packet':n,'payload_bytes':len(p)-16,'sha256':hashlib.sha256(p[16:]).hexdigest()})
  if outbound and len(p)>16:out[f'{sv:04x}/{me:04x} len={len(p)-16} sha256={hashlib.sha256(p[16:]).hexdigest()}']+=1
 return {'packet_records':n,'linktype':link,'truncated_records':trunc,'fragmented_udp_skipped':frag,'unparsed_tail_bytes':max(0,len(b)-off),'udp_flows':dict(flows),'ipcp_groups':dict(groups),'asdm_head_changes':trans,'mcu_incoming':mcu,'outbound_payload_fingerprints':dict(out)}
rows={}
for p in sorted(O.glob('*.zip')):
 if not any(x in p.name for x in ['Cruise','VP-Action','Raw-Ethernet','QNX-IPCP-Evidence','HU-Route','Bus-Capture']):continue
 z=zipfile.ZipFile(p)
 for i in z.infolist():
  if not i.filename.endswith(('.pcap','.pcapng')):continue
  b=z.read(i);h=hashlib.sha256(b).hexdigest()
  if h not in rows:rows[h]={'sha256':h,'bytes':len(b),'sources':[],'analysis':analyze(b)}
  rows[h]['sources'].append({'archive':p.name,'member':i.filename})
 print(p.name,flush=True)
(R/'pcap_audit.json').write_text(json.dumps({'scope':'All legacy-PCAP members of available cruise/VP-action/raw-ethernet/QNX-IPCP/HU/Bus archives, deduplicated by exact bytes. No reconstruction of IPv4 fragments, TCP, or unrecognized linktypes. User cue timestamps are not physical button times. Outbound hashes are observational, not commands.','captures':list(rows.values())},indent=2))
