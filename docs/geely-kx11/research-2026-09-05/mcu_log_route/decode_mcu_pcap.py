#!/usr/bin/env python3
"""Read a local classic PCAP and decode ECARX PA_McuLog_Panic payloads.
No sockets, subprocesses, vehicle commands or network access.
"""
import argparse, collections, datetime, hashlib, json, pathlib, socket, struct

def udp_packets(data):
    fmts={b'\xd4\xc3\xb2\xa1':('<',1e6),b'\xa1\xb2\xc3\xd4':('>',1e6),b'\x4d\x3c\xb2\xa1':('<',1e9),b'\xa1\xb2\x3c\x4d':('>',1e9)}
    if data[:4] not in fmts or len(data)<24: raise ValueError('Only classic PCAP supported')
    endian,scale=fmts[data[:4]]; link=struct.unpack_from(endian+'I',data,20)[0]; off=24; num=0
    if link not in (1,113,101,228): raise ValueError(f'Unsupported PCAP link type {link}')
    while off+16<=len(data):
        sec,frac,cap,orig=struct.unpack_from(endian+'IIII',data,off); off+=16;raw=data[off:off+cap];off+=cap;num+=1
        if len(raw)!=cap: raise ValueError('Truncated PCAP packet')
        if link==113:
            if len(raw)<16 or raw[14:16]!=b'\x08\x00':continue
            raw=raw[16:]
        elif link==1:
            if len(raw)<14:continue
            eth=struct.unpack_from('>H',raw,12)[0];eo=14
            while eth in (0x8100,0x88a8) and len(raw)>=eo+4:eth=struct.unpack_from('>H',raw,eo+2)[0];eo+=4
            if eth!=0x800:continue
            raw=raw[eo:]
        if len(raw)<28 or raw[0]>>4!=4 or raw[9]!=17:continue
        # Never decode an IP fragment as a complete datagram.
        if struct.unpack_from('>H',raw,6)[0]&0x3fff:continue
        ihl=(raw[0]&15)*4
        if ihl<20 or len(raw)<ihl+8:continue
        sp,dp,ln,_=struct.unpack_from('>HHHH',raw,ihl)
        if ln<8 or len(raw)<ihl+ln:continue
        yield num,sec+frac/scale,socket.inet_ntoa(raw[12:16]),sp,socket.inet_ntoa(raw[16:20]),dp,raw[ihl+8:ihl+ln]

def analyze(path):
    data=path.read_bytes();records=[];flows=collections.Counter()
    for num,ts,src,sp,dst,dp,p in udp_packets(data):
        if len(p)<16:continue
        service,method,ln=struct.unpack_from('>HHI',p)
        if (service,method)!=(0x99,0xc8):continue
        flow=f'{src}:{sp} -> {dst}:{dp}';flows[flow]+=1
        r={'packet':num,'unix_timestamp':ts,'utc':datetime.datetime.fromtimestamp(ts,datetime.timezone.utc).isoformat(),'flow':flow,'ipcp_header_hex':p[:16].hex(),'ipcp_length':ln,'udp_payload_length':len(p),'message_type':p[14],'payload_sha256':hashlib.sha256(p[16:]).hexdigest()}
        raw=p[16:]
        if ln!=len(p)-8:r['length_warning']='IPCP length does not describe this whole UDP datagram; not decoded'
        elif len(raw)==0:r['payload_empty']=True
        elif len(raw)!=0x588:r['length_warning']='Expected 1416 bytes; not decoded'
        else:
            # Wire offsets proven by the July/August ELF SHA b6feed82... converter.
            field=raw[8:0x580];terminator=field.find(b'\0');s=field if terminator<0 else field[:terminator]
            r.update(availability=struct.unpack_from('>I',raw,0)[0],unknown_offset_4_hex=raw[4:8].hex(),format=struct.unpack_from('>I',raw,0x580)[0],status=struct.unpack_from('>I',raw,0x584)[0],text=s.decode('utf-8',errors='replace'),text_length=len(s),nul_terminated=terminator>=0)
        records.append(r)
    return {'file':path.name,'sha256':hashlib.sha256(data).hexdigest(),'service':'0x0099','method':'0x00C8','packet_count':len(records),'flows':dict(flows),'records':records}

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('pcap',type=pathlib.Path);ap.add_argument('--output',type=pathlib.Path);a=ap.parse_args();out=json.dumps(analyze(a.pcap),ensure_ascii=False,indent=2)
    if a.output:a.output.write_text(out+'\n',encoding='utf-8')
    else:print(out)
