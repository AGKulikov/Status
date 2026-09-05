from pathlib import Path
import json,hashlib,zipfile,re
r=Path('audit-work/next-analysis/bluetooth_runtime'); methods=json.load(open(r/'private/dm_server_methods.json'));byoff={m['code_offset']:m for m in methods};coverage=json.load(open(r/'full_corpus_target_coverage.json'))
source=next(x for x in coverage['per_target'] if x['basename']=='ts-dm-service.apk')['hits'][0]; source2=next(x for x in coverage['per_target'] if x['basename']=='ts-dm-service.apk')['hits'][1]
with zipfile.ZipFile(r/'private/ts-dm-service.apk') as z:
 dexsha=hashlib.sha256(z.read('classes.dex')).hexdigest();manifestsha=hashlib.sha256(z.read('AndroidManifest.xml')).hexdigest()
refs=[];facts=[]
def ref(off):
 m=byoff[off]
 return {**source,'dex_member':'classes.dex','dex_sha256':dexsha,'class':m['class'],'method':m['name'],'descriptor':m['descriptor'],'code_item_offset':off,'instructions_offset_base':'code_item+0x10; relative instruction offsets in original DEX bytes'}
def fact(id,namespace,claim,offs,limit,kind='observation_static_body'):
 facts.append({'id':id,'namespace':namespace,'kind':kind,'claim_ru':claim,'limits_ru':limit,'evidence':[ref(x) for x in offs]})
fact('DM01','Android Binder / permission','onBind возвращает конкретный Stub d; connect/connectByRequest/disconnect требуют car.permission.CHANGE_DM_STATE, UID1000 обходится без permission query; прочие callers проходят checkCallingOrSelfPermission либо SecurityException.',['0x61a88','0x657f0','0x65820','0x65850','0x65984'],'exported=true не означает доступность операции стороннему UID.')
fact('DM02','DM capability → foundation profile → Android profile','Controller делегирует ConnectionManager. Capability0 отвергается; 5 и6 идут в BtManager; 5 переводится в foundation profile1/HFP, 6 в profile2/A2DP. Низкоуровневый return1 превращается в DM return0, другие значения в -1.',['0x59cfc','0x59cb0','0x59d58','0x5a870','0x5a918','0x5a9a0','0x5a138','0x5a224','0x5a310'],'Это разные namespace. Android proxy16/11 доказаны отдельно BT-ST-001–003; return0 DM не есть физический connected.')
fact('DM03','FoundationProvider / IBluetoothManger','BluetoothDeviceManager создаётся factory d, запрашивает IBluetoothManger у FoundationProvider; connect/disconnect проходят через этот интерфейс, а не через собственный BluetoothGatt.',['0x5ea20','0x5e304','0x5c650','0x5c678'],'Объявленные uses-library ts.platform.library и ts.dm.foundation.lib согласуются с маршрутом; точный runtime classloader origin одним manifest не доказан.')
fact('DM04','DM local capability state / request admission','Connect возвращает1 для local state3/4 либо уже pending CONNECT; иначе вызывает foundation connectProfile. При return1 сам создаёт ProfileStatus3 callback и ставит 10000ms timer.',['0x5df8c','0x5e508','0x5e2a8','0x5e788'],'Вызов onDeviceProfileStatusChanged может быть синтетическим подтверждением начала; не следует объявлять его независимым аппаратным feedback.')
fact('DM05','Foundation ProfileStatus versus DM capability state','Disconnect для состояния вне3/4 возвращает1 без нового native вызова; pending DISCONNECT тоже дедуплицируется. После принятого запроса синтезируется foundation ProfileStatus2/DISCONNECTING. util.b.q переводит foundation2→DM5,3→3,4→4; default→2.',['0x5e0a4','0x5e5a8','0x61914'],'Число2 нельзя переносить между namespace; constructed IllegalArgumentException в default не бросается, фактический return2.')
fact('DM06','Handler timeout / status reconciliation','Оба timer Runnable через10000ms перечитывают actual foundation getProfileConnectionState, создают callback текущего состояния и удаляют pending token. Оба используют один текст connect profile timeout, включая DISCONNECT timer.',['0x5e508','0x5e5a8','0x5e870','0x5e95c','0x5df70','0x5e1fc'],'В этих Runnable нет повторного connect, adapter reset или удаления пары. Название log-сообщения не устанавливает тип запроса или физический сбой.')
fact('DM07','Device update / pending cancellation','DeviceManager.l сначала вызывает BluetoothDeviceManager.v, затем listeners. v проверяет CONNECT/DISCONNECT для HFP/A2DP; pending CONNECT удаляется после выхода local state из3, DISCONNECT после выхода из5.',['0x5da6c','0x5e7ec','0x5e3d0','0x5d208'],'Cleanup зависит от обновления модели устройства; он не добавляет самостоятельной диагностики причины разрыва.')
fact('DM08','Projection/HFP arbitration','ConnectionManager регистрируется listener. Если обновлённое устройство имеет connected capability1 или2, b(device) перебирает device list и вызывает capability5/HFP disconnect у устройств с другим непустым адресом. Projection update e(...).+0x11a проходит в этот listener через DeviceManager.l.',['0x5aa30','0x5a83c','0x5aad4','0x5aa74','0x5c424','0x5da6c'],'Это статическая политика модели projection; факт её исполнения в конкретной Bluetooth-эпохе пока не найден. Адреса устройств не сохраняются.')
fact('DM09','Projection/HFP callback arbitration','При наличии tracked projection device новый профиль того же устройства игнорируется. Для другого устройства с connected HFP вызывается disconnect(capability5) по +0x92. Соседняя ветка A2DP имеет только log diconnect new A2DP connect и return; вызова disconnect в ней нет.',['0x5ab98','0x5a770','0x5a7d4','0x61618','0x5fb60','0x5adf8'],'Tracked projection reference не равно независимому подтверждению активной физической projection. Лог A2DP не доказывает выполненное отключение.')
fact('DM13','Foundation callback → Handler → model → arbitration','BluetoothDeviceManager передаёт onDeviceProfileStatusChanged слушателям; DeviceManager ставит Runnable на свой Handler. Runnable вызывает c(BluetoothDevice,int): при найденной модели сначала обновляет её, затем рассылает profile callback слушателям, включая ConnectionManager.',['0x5e788','0x5e3b8','0x5dd9c','0x5baa0','0x5daf0','0x5d64c','0x5d538','0x5aa30'],'Та же callback-точка принимает синтетические статусы и timeout reconciliation. Сам callback не устанавливает независимый физический переход; неизвестное модели устройство этой веткой не рассылается. Поток Handler и задержка здесь не измерены.')
metadata=[]
lookup={x['name']:x['local_sha256'] for x in coverage['registry_coverage']}
for p in sorted((r/'private').glob('*/DIAGNOSTICS/dumpsys_package_com.ts.dm.service.txt')):
 b=p.read_bytes();lines=b.decode('utf-8','replace').splitlines();fields={};linenos=[]
 for i,l in enumerate(lines,1):
  for k in ['versionCode','versionName','codePath']:
   m=re.search(r'(?:^|\s)'+k+r'=([^\s]+)',l)
   if m:fields[k]=m.group(1);linenos.append(i)
 metadata.append({'archive':p.parts[-3]+'.tar.gz','archive_sha256':lookup[p.parts[-3]+'.tar.gz'],'member':'/'.join(p.parts[-3:]),'member_sha256':hashlib.sha256(b).hexdigest(),'lines':sorted(set(linenos)),'fields':fields})
facts.append({'id':'DM10','namespace':'Package identity / provenance','kind':'observation_metadata','claim_ru':'APK 4.5.9.0/versionCode28 имеется в двух Cruise-архивах с одним SHA; все шесть Bluetooth dumpsys имеют те же package version fields.','limits_ru':'Совпадение versionCode/versionName не доказывает байтовую идентичность APK августа14–15; свежий SHA проверяется перед решением о повторном переносе.','evidence':[source,source2,{'member':'AndroidManifest.xml','container_member':source['member'],'container_sha256':source['member_sha256'],'member_sha256':manifestsha,'binary_xml_attributes':{'package':'com.ts.dm.service','versionCode':28,'versionName':'4.5.9.0','exported':True,'uses_library':['ts.platform.library','ts.dm.foundation.lib']}},*metadata]})
runtime=json.load(open(r/'dm_service_runtime_projection.json'))
facts.append({'id':'DM11','namespace':'Runtime selected DM log tags','kind':'observation_scoped_negative','claim_ru':'Пять точных DM arbitration/timeout-сообщений под tags ConnectionManager/BluetoothDeviceManager не встречены в шести сохранённых Bluetooth slices.','limits_ru':'Срезы фильтрованные; отсутствие строки не доказывает неисполнение кода или причину Natro teardown.','evidence':[{'archive':x['archive'],'archive_sha256':x['archive_sha256'],'member':x['member'],'member_sha256':x['member_sha256'],'line_range':[1,x['line_count']],'counts':x['counts']} for x in runtime['sources']]})
ble=[]
for m in methods:
 for i in m['instructions']:
  if any(v in i[2] for v in ['BluetoothGatt','connectGatt','ANCS','Ancs','ancs']):ble.append([m['class'],m['name']])
facts.append({'id':'DM12','namespace':'DEX com/ts/dm scoped route search','kind':'observation_scoped_negative','claim_ru':'В инструкциях сохранённых com/ts/dm классов нет BluetoothGatt/connectGatt/ANCS/Ancs/ancs references.','limits_ru':'Это отрицание только указанного namespace/набора references, не доказательство отсутствия всех косвенных native возможностей или shared-library кода.','evidence':[{**source,'dex_member':'classes.dex','dex_sha256':dexsha,'method_bodies_scanned':len(methods),'reference_patterns':['BluetoothGatt','connectGatt','ANCS','Ancs','ancs'],'matches':ble}]})
assert not ble
out={'schema_version':1,'scope':'Offline actual ts-dm-service server DEX and source presence correction; no physical acceptance','source':source,'duplicate_source':source2,'dex_sha256':dexsha,'findings':facts}
(r/'dm_service_evidence.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
for p in [r/'collection_targets.json',r/'static/collection_targets.json']:
 d=json.loads(p.read_text());removed=[x for x in d['targets'] if x['path'].endswith('/ts-dm-service.apk')];d['targets']=[x for x in d['targets'] if not x['path'].endswith('/ts-dm-service.apk')]
 for x in d['targets']:
  c=next(c for c in coverage['per_target'] if c['target_path']==x['path'])
  x['corpus_status']='Checked all158 registered sources/102 outer archives plus all discovered ZIP/TAR nesting; requested file or lib64 ABI absent.'
  x['coverage_evidence']='full_corpus_target_coverage.json';x['same_basename_other_abi_hits']=c['hits']
 d['already_available_do_not_recollect']=[x for x in d.get('already_available_do_not_recollect',[]) if not (isinstance(x,dict) and x.get('path','').endswith('/ts-dm-service.apk'))]
 d['already_available_do_not_recollect'].append({'path':'/system/app/ts-dm-service/ts-dm-service.apk','sha256':source['member_sha256'],'bytes':source['bytes'],'package':'com.ts.dm.service','versionCode':28,'versionName':'4.5.9.0','source':source,'duplicate_source':source2,'inspection':'Actual service-side DEX connect/disconnect, request dedup, timeouts and projection/HFP arbitration completed','safe_next_step':'Fresh pm path/package metadata and SHA only; collect binary only if SHA differs from known baseline','evidence':'dm_service_evidence.json'})
 d['coverage_correction']={'previous_target_count':9,'remaining_missing_target_count':8,'source_registry_count':158,'reason':'Earlier29-archive inventory omitted two Cruise APK members; ts-dm-service is now found and statically analyzed.'}
 p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
inv=json.load(open(r/'source_inventory.json'))
for s in [source,source2]:
 if not any(x.get('archive')==s['archive'] and x.get('member')==s['member'] for x in inv):inv.append({**s,'inspection':'full DEX method inventory plus targeted actual service-side arbitration/timeout bodies','dex_sha256':dexsha})
(r/'source_inventory.json').write_text(json.dumps(inv,ensure_ascii=False,indent=2)+'\n')
print('facts',len(facts),'binary_method_refs',sum(len(x.get('evidence',[])) for x in facts[:9]),'missing',8)
