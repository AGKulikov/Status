import pathlib,json,re,collections,hashlib
root=pathlib.Path(__file__).parent
sources=json.loads((root/'source_inventory.json').read_text())
macs={}
def scrub(s):
 s=re.sub(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}',lambda m:macs.setdefault(m[0].lower(),'device'+str(len(macs)+1)),s)
 s=re.sub(r'(?i)(?:name|alias|title|body|messageText|number|contact|phoneNumber|addressString)\s*[=:].*','[personal-field-omitted]',s)
 s=re.sub(r'(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b','[uuid]',s)
 s=re.sub(r'(?<![a-zA-Z_0-9])\+?\d{6,}(?![a-zA-Z_0-9])','[long-number]',s)
 return s
pat=re.compile(r'(?i)(ancs|encryption key size|handle is invalid|onConnectionStateChange|onClientConnectionState|onServerConnectionState|onSearchComplete|onDescriptorWrite|onCharacteristicWrite|[ _]status[ =:]|disconnec.{0,20}reason|[ _]reason[ =:]|apStatusChange|ScPwIVIMngrReqState|PowerSomeIP|bt_pre_state|lastAPStatus|wifi_pre_state|STATE_(?:ON|OFF|TURNING)|setBluetoothEnabled|enableBLE|disableBLE|disable\(|enable\(|stateChangeCallback|onBluetoothStateChange|BluetoothStateChange|onProfileStateChanged|BTA_DM_LINK_DOWN_EVT|BTM_SEC_LINK_KEY|BTA_GATTC_(?:OPEN|CLOSE)_EVT|btm_sec_disconnected)')
# Key-producing messages, personal contact and content lines are never copied.
exclude=re.compile(r'(?i)(link.?key|ltk|irk|csrk|(?:^|\W)pin(?:\W|$)|vcard|phonebook|contact|calllog|at[+]c|notification.{0,12}(?:data|text|title)|characteristicchanged|handlevalue|payload|\bhex\b)')
selected=[]; summaries=[]
for src in sources:
 if not src.get('local_path') or 'logcat_bluetooth_slice.txt' not in src['member']:continue
 lines=pathlib.Path(src['local_path']).read_text(errors='replace').splitlines(); types=collections.Counter(); n=0;times=[]
 for no,line in enumerate(lines,1):
  m=re.match(r'(\d\d-\d\d \d\d:\d\d:\d\d.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s*(.*)',line)
  if not m:continue
  times.append(m[1]);msg=m[6];tag=m[5]
  if pat.search(msg+' '+tag) and not exclude.search(msg):
   selected.append(dict(source_archive=src['archive'],archive_sha256=src['archive_sha256'],member=src['member'],member_sha256=src['member_sha256'],line=no,time=m[1],pid=int(m[2]),tid=int(m[3]),level=m[4],tag=tag,message=scrub(msg)));types[tag]+=1;n+=1
 summaries.append(dict(archive=src['archive'],total_lines=len(lines),start=times[:1],end=times[-1:],projected_events=n,projected_tag_counts=dict(types)))
(root/'private/projected.json').write_text(json.dumps(selected,ensure_ascii=False,indent=2));(root/'private/log_summaries.json').write_text(json.dumps(summaries,indent=2))
for s in summaries:print(s['archive'],s['total_lines'],s['start'],s['end'],s['projected_events'])
print('projection events',len(selected))
