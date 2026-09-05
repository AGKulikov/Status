from pathlib import Path
import re,json,hashlib,collections,datetime
r=Path(__file__).parent;ss=json.load(open(r/'source_inventory.json'))
def src(archive_hint,member_tail):return next(s for s in ss if archive_hint in s['archive'] and s['member'].endswith(member_tail))
def ref(hint,tail,*lines):
 s=src(hint,tail);return {k:s[k] for k in ('archive','archive_sha256','member','member_sha256')}|{'lines':list(lines)}
def log(hint,*lines):return ref(hint,'DIAGNOSTICS/logcat_bluetooth_slice.txt',*lines)
def dump(hint,*lines):return ref(hint,'DIAGNOSTICS/dumpsys_bluetooth_manager.txt',*lines)
findings=[]
def f(id,namespace,kind,claim,refs,limits=''):
 findings.append(dict(id=id,namespace=namespace,evidence_level=kind,claim=claim,sources=refs,limits=limits))
f('BT01','Android BluetoothManagerService / PowerSomeIP','OBSERVED','Штатный ecarx.powersomeip.service запрашивает enable; все шесть dumpsys сообщают enabled=true/state=ON и Bluetooth crashed 0 times. Два снимка указывают SYSTEM_BOOT, остальные APPLICATION_REQUEST.',[dump(h,2,3,9,10,11,12,13) for h in ('20260814','090005','104207','114556','132904','134614')]+[log('104207',18),log('114556',367),log('132904',20)],'Ноль счётчика crash в отдельных эпохах не исключает иных отказов. RESTARTED не равен crash. Причина sleep/wake и AP status в Bluetooth slice отсутствует.')
f('BT02','Android BluetoothManagerService state machine','OBSERVED','13:24:10.222 waitForOnOff time out, MESSAGE_RESTART_BLUETOOTH_SERVICE и BLE_TURNING_ON→BLE_ON; далее BLE_ON→TURNING_ON. Это реально зарегистрированный сбой ожидания запуска; поздние снимки показывают ON.',[log('132904',24,25,26,35,37,42),dump('132904',2,3)],'Срез не содержит полного вызывающего power request или причин задержки; не утверждается падение Bluetooth или физический сон.')
f('BT03','Android GATT ContextMap / application IDs','OBSERVED','В снимках 10:42 и11:45 ID5=org.astpepper.hwgps; ID6=ru.natro.statuswidget. Natro имеет1 GATT connection, GPS также1. В13:29 и13:46 Natro остаётся лишь записью статистики без Registered, Application ID и Connections; GPS зарегистрирован и подключён.',[dump('104207',68,72,74,75,77,81,83,84),dump('114556',51,55,57,58,60,64,66,67),dump('132904',59,62,67,71,73,74),dump('134614',63,66,71,75,77,78)],'ID действует в соответствующем процессе/эпохе. Один только ID6 из другой эпохи нельзя автоматически считать Natro; в13:29 scanner ID6 принадлежит Navigator.')
f('BT04','BluetoothGatt callback status / native GATT reason','OBSERVED + ATTRIBUTION','Дважды GPS clientIf5 получает status40=0x28, закрывает регистрацию, повторяет connect, отменяет неготовое подключение (connId=null, native reason0x100), затем успешно подключается и завершает services discovery. Восстановление до services:8.182с и8.485с.',[log('104207',9750,9759,9793,9800,9801,9930,11687,11693,11776,11911),log('132904',4915,4924,4942,4943,5004,6006,6012,6410,6522),dump('104207',77,81),dump('132904',67,71)],'Это сбой GPS GATT-клиента, не доказательство ANCS failure. Названная семантика0x28/0x100 в конкретных vendor библиотеках ещё не восстановлена; статический GattService передаёт native status без преобразования.')
f('BT05','Android GATT lifecycle / Natro epoch10:39','OBSERVED','Natro client6 после успешного discovery сам проходит clientDisconnect→close→unregisterApp в10:40:59.494–.500 и10:41:26.347–.348. Примерно через1с native запрашивает HCI disconnect reason0x13; затем link down reason0x16. В10:41:29 происходит новое успешное подключение/discovery и локальная регистрация notification callbacks.',[log('104207',13275,14176,46501,46508,46509,46513,46604,46622,46683,48195,48556,48558,48559,48563,48611,48625,48678,48806,48895,48897,48977),dump('104207',68,72,74)],'Причина решения Natro закрыть GATT отсутствует в срезе. close предшествует native disconnect и не доказывает crash/переключение power. registerForNotification не доказывает remote CCCD success или ANCS-ready.')
f('BT06','HFP client vs BLE ACL','OBSERVED','При разрывах BLE штатный InCallServiceImpl сообщает isHfpConnected=true:10:41:00.516 и10:41:27.400. GATT потеря не означает исчезновение профиля звонка; HFP/A2DP и BLE следует оценивать отдельно.',[log('104207',46683,48678),dump('104207',102,111,114,115)],'isHfpConnected — программное наблюдение штатного клиента; слышимость разговора и физический аудиотракт не проверялись.')
f('BT07','Android GATT / Natro recovery','OBSERVED','11:45:51.969 client6 начинает connect;11:45:52.727 callback status0; MTU185 принят в11:45:52.904; services discovery status0 в11:45:52.930; snapshot показывает Natro registered/1 connection.',[log('114556',9773,9782,9883,9910,9971,9973),dump('114556',51,55,57,58)],'Успешные GATT-этапы не заменяют авторизацию ANCS/CCCD/доставку уведомления. Callback данных и личное содержимое не анализировались.')
f('BT08','Android BLE scan ownership','OBSERVED','У Navigator имеются долгие ongoing BLE scan записи. В13:46 один scanner package содержит два ongoing scans (IDs6,7); у Natro активный GATT client отсутствует. В09:00 Natro history включает scans38.486с,60.021с,49.847с,2.204с,2.242с.',[dump('090005',48,49,53,54,55,56,57,59,65,66,67),dump('134614',49,50,56,57,58,59,66)],'Это счётчики и длительности scanner map, не число BLE links и не доказательство конкуренции, радиоистощения или причины задержки ANCS.')
f('BT09','Android GATT Binder callback','OBSERVED','В13:29:12.234 GattService регистрирует DeadObjectException; после этого есть onScanFilterParamsConfigured(clientIf6,status0). Bluetooth crashed0 в снимке не означает отсутствие Binder callback errors.',[log('132904',82988,82998),dump('132904',13)],'Срез не содержит stack trace/callback recipient. Нельзя атрибутировать DeadObjectException Natro; scanner6 в ближайшем snapshot принадлежит Navigator.')
f('BT10','Android profile role / IBluetoothHeadset','OBSERVED','В корпусе повторяется ошибка bind android.bluetooth.IBluetoothHeadset; это сервис обычной Headset роли, тогда как автомобильные снимки содержат HeadsetClientService. Нельзя по этой строке объявлять HFP client отключённым.',[log('090005',3,4),dump('090005',117),dump('104207',111)],'Полная причина запросов неверной/отсутствующей роли и package caller из среза не установлена.')
# HCI sources and matching primary event
hcis=json.load(open(r/'private/hci_metadata.json'))
for s in hcis:
 for e in s['events']:
  if e['event']=='command_complete_status_nonzero':e['event']='vendor_command_complete_first_return_byte';e['first_return_byte']=e.pop('status')
h=next(s for s in hcis if '134614' in s['archive']);e=next(e for e in h['events'] if e['record']==4516)
href={k:h[k] for k in ('archive','archive_sha256','member','member_sha256')}|{'record':e['record'],'byte_offset':e['record_offset'],'timestamp_UTC':e['timestamp_UTC']}
f('BT11','HCI event0x05 / logcat clock alignment','OBSERVED + CROSS_SOURCE_ALIGNMENT','HCI Disconnection Complete record4516 offset1026715:10:25:01.599616UTC,handle3,status0,reason40. Logcat13:25:01.600 содержит тот же handle/reason и GPS client5. Сдвиг+3часа согласует два независимых источника до0.384мс.',[href,log('132904',4924,4942)],'Согласование часов является выводом по событию, не заявлением о системной timezone всего корпуса. Callback приложения происходит позже native event; это не физическая кнопочная задержка.')
f('BT12','Btsnoop file chronology','OBSERVED','Три btsnoop_hci.log.last имеют144934 целых records:14авг13:36–13:47UTC,14авг18:10–18:15UTC,15авг10:24–10:45UTC. Имя архива не является временем HCI. Второй файл, собранный15авг09:00, содержит события предыдущего дня.',[{k:s[k] for k in ('archive','archive_sha256','member','member_sha256')}|{'record_range':[1,s['records']]} for s in hcis],'Разобраны framing и whitelist lifecycle scalar fields; ACL/SCO/security/key/notification payload исключены. Нет claims об ATT error codes или notification content.')
# Produce public typed timeline: no raw arbitrary log content, device values, UUID, names or keys.
logpat=re.compile(r'(\d\d-\d\d \d\d:\d\d:\d\d.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s*(.*)')
timeline=[];stats=[]
allow_ops=['connect','close','unregisterApp','registerClient','onClientRegistered','clientConnect','clientDisconnect','unregisterClient','onClientConnectionState','discoverServices','onSearchCompleted','onSearchComplete','configureMTU','onConfigureMTU','requestConnectionPriority','onConnectionUpdated','registerForNotification']
for s in ss:
 if not s.get('local_path') or not s['member'].endswith('DIAGNOSTICS/logcat_bluetooth_slice.txt'):continue
 lines=Path(s['local_path']).read_text(errors='replace').splitlines();cnt=collections.Counter();times=[]
 for no,l in enumerate(lines,1):
  m=logpat.match(l)
  if not m:continue
  time,pid,tid,level,tag,msg=m.groups();times.append(time);fields=None;ns=None
  if tag in ['BluetoothGatt','BtGatt.GattService']:
   op=next((op for op in allow_ops if re.search(r'\b'+op+r'\(\)',msg)),None)
   if op:
    fields={'event':op};ns='Android GATT API/callback'
    for key,val in re.findall(r'\b(status|Status|clientIf|mClientIf|connId|mtu|interval|latency|timeout|params)[=: ]+([0-9]+|null)\b',msg):fields[key.lower()]=int(val) if val.isdigit() else val
    for key,val in re.findall(r'\b(auto|isDirect|opportunistic|enable)[=: ]+(true|false)\b',msg):fields[key]=val=='true'
   elif 'DeadObjectException' in msg:fields={'event':'DeadObjectException'};ns='Android GATT Binder'
  if tag=='bt_btm_sec' and 'btm_sec_disconnected clearing pending flag' in msg:
   mm=re.search(r'handle:(\d+) reason:(\d+)',msg)
   if mm:fields={'event':'native_link_disconnected','handle':int(mm[1]),'reason':int(mm[2])};ns='native BTM disconnect reason'
  if tag=='bt_btm' and 'btm_sec_send_hci_disconnect' in msg:
   mm=re.search(r'handle:0x([0-9a-f]+), reason=0x([0-9a-f]+)',msg)
   if mm:fields={'event':'native_send_hci_disconnect','handle':int(mm[1],16),'reason':int(mm[2],16)};ns='native HCI disconnect command'
  if tag=='bt_stack' and 'bta_gattc_conn_cback' in msg:
   vals=dict(re.findall(r'(cif|connected|conn_id|reason)=([0-9a-fx]+)',msg))
   fields={'event':'native_gatt_connection_callback',**{k:int(v,16) if v.startswith('0x') else int(v) for k,v in vals.items()}};ns='native GATT callback reason'
  if tag=='BluetoothManagerService':
   if 'enable(ecarx.powersomeip.service)' in msg:fields={'event':'enable_request','caller_package':'ecarx.powersomeip.service','state':msg.split('mState =')[-1].strip()};ns='Android BluetoothManagerService'
   elif 'waitForOnOff time out' in msg:fields={'event':'waitForOnOff_timeout'};ns='Android BluetoothManagerService'
   elif 'MESSAGE_BLUETOOTH_STATE_CHANGE:' in msg:
    mm=re.search(r'CHANGE: ([A-Z_]+) > ([A-Z_]+)',msg)
    if mm:fields={'event':'adapter_state_change','from':mm[1],'to':mm[2]};ns='Android BluetoothManagerService'
  if tag=='XCBTPhone3' and 'ACTION_ACL_DISCONNECTED' in msg:
   mm=re.search(r'isHfpConnected = (true|false) , reason = (\d+)',msg)
   if mm:fields={'event':'stock_phone_acl_disconnect','isHfpConnected':mm[1]=='true','reason':int(mm[2])};ns='Android ACL broadcast / stock HFP state'
  if fields:
   cnt[fields['event']]+=1;timeline.append({'source':{k:s[k] for k in ('archive','archive_sha256','member','member_sha256')}|{'line':no},'time_logcat':time,'pid':int(pid),'tid':int(tid),'tag':tag,'namespace':ns,'level':'OBSERVED',**fields})
 negatives={}
 for key,pat in [('power_ap_callback',r'apStatusChange|ScPwIVIMngrReqState|bt_pre_state|lastAPStatus|wifi_pre_state'),('insufficient_key_text',r'(?i)encryption key size|insufficient.*key|key.*insufficient'),('invalid_handle_text',r'(?i)handle is invalid|invalid handle|GATT_INVALID_HANDLE'),('Natro_ANCS_phase_tag',r'\s(?:AncsClient|AncsManager|ANCS|NatroAncs)\s*:'),('descriptor_write_callback',r'onDescriptorWrite|onCharacteristicWrite')]:negatives[key]={'matches':sum(bool(re.search(pat,l)) for l in lines),'pattern':pat}
 stats.append({'archive':s['archive'],'archive_sha256':s['archive_sha256'],'member':s['member'],'member_sha256':s['member_sha256'],'lines':len(lines),'start':times[0],'end':times[-1],'typed_event_counts':dict(cnt),'negative_searches':negatives})
for s in hcis:
 for e in s['events']:
  timeline.append({'source':{k:s[k] for k in ('archive','archive_sha256','member','member_sha256')}|{'record':e['record'],'byte_offset':e['record_offset']},'namespace':'HCI packet scalar metadata','level':'OBSERVED',**e})
f('BT13','bounded log search / negative evidence','NEGATIVE','В25366? строках не обобщается отсутствие всех ошибок: точный корпус248366 строк проверен на конкретные AP callback, insufficient key и invalid handle тексты, ANCS phase tags и descriptor-write callbacks; совпадений по этим шаблонам нет.',[{k:s[k] for k in ('archive','archive_sha256','member','member_sha256')}|{'lines':[1,s['lines']]} for s in stats],'Это отрицательный результат только сохранённого Bluetooth slice; иных буферов и Natro private diagnostic нет. Строки с ANCS в friendly device metadata не являются событиями ANCS.')
findings[-1]['claim']=f"В {sum(s['lines'] for s in stats)} строках шести Bluetooth slices отсутствуют конкретные AP callback, insufficient-key/invalid-handle тексты, выбранные ANCS phase tags и descriptor-write callbacks (точные шаблоны и нули в log_windows)."
f('BT14','native GATT cache I/O','OBSERVED','Missing GATT cache file messages are followed by successful services discovery; missing-cache error alone does not establish corrupted bond.',[log('090005',29195,29799,43568,44363,49957,50336),log('104207',11765,11911,13259,14176,47943,48195),log('114556',1825,3741),log('132904',6399,6522)],'Cache contents and pairing data were not examined; no deletion or repair is justified by these lines.')
f('BT15','captured firmware/config identity','OBSERVED','All six build_fingerprint.txt, current nForeBluetooth.properties, and bt_stack.conf members respectively have identical SHA256.',[ref(h,tail,1) for h in ('20260814','090005','104207','114556','132904','134614') for tail in ('FILES/system/etc/bluetooth/nForeBluetooth.properties','FILES/system/etc/bluetooth/bt_stack.conf','META/build_fingerprint.txt')],'Same selected bytes do not prove identical full firmware, unchanged Natro APK or pristine factory configuration.')
(r/'evidence.json').write_text(json.dumps({'scope':'offline original corpus; no execution/build/vehicle operations','findings':findings,'log_windows':stats,'hci_sources':hcis},ensure_ascii=False,indent=2))
(r/'timeline.json').write_text(json.dumps({'clock_policy':'logcat local wall time retained; HCI UTC retained; only BT11 establishes +3h alignment for its window','privacy':'typed allowlist projection only; no raw messages, MAC, UUID, device names, keys, notification/call/contact payload','events':timeline},ensure_ascii=False,indent=2))
print('findings',len(findings),'typed_events',len(timeline),'log_lines',sum(s['lines'] for s in stats),'hci_records',sum(s['records'] for s in hcis))
