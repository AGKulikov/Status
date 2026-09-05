#!/usr/bin/env python3
"""Offline, deterministic public schema inventory. Does not execute firmware or contact a car.
Usage: python3 build_coverage_inventory.py BUNDLE OUTPUT
Only schemas, symbols, IDs, hashes and evidence pointers are emitted, never bytecode.
"""
import argparse, collections, hashlib, json, pathlib, re


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('bundle',type=pathlib.Path);ap.add_argument('output',type=pathlib.Path)
    args=ap.parse_args();base=args.bundle;out=args.output;out.mkdir(parents=True,exist_ok=True)
    used={}
    def read(name):
        data=(base/name).read_bytes();used[name]={'sha256':hashlib.sha256(data).hexdigest(),'size_bytes':len(data)}
        return json.loads(data)
    def write(name,obj):
        (out/name).write_text(json.dumps(obj,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    cat=read('firmware/catalog.json');methods=read('firmware/methods.json')
    constants=read('firmware/constants.json')['constants'];conflicts=read('firmware/conflicts.json')['conflicts']
    schemas=read('firmware/schemas.json');obs=read('observations/observed_signals.json')
    routes=read('firmware/mapping/function_routes.json')['functions'];bridge=read('firmware/sdk_to_hal.json')
    archive=read('firmware/sources.json');original=read('provenance/original_sources.json')
    sources=[];source_keys={}
    def sr(s):
        clean={k:s[k] for k in ['member','sha256','dex_entry'] if k in s}
        key=json.dumps(clean,sort_keys=True)
        if key not in source_keys:source_keys[key]=len(sources);sources.append(clean)
        return source_keys[key]
    ci={(x['class'],x['name']):i for i,x in enumerate(conflicts)}
    schema_index={x['class']:i for i,x in enumerate(schemas)}
    ri={x['function_id']:i for i,x in enumerate(routes)}
    by_obs={x['signal_id']:(i,x) for i,x in enumerate(obs['signals'])}
    by_ns_id={};entries=[];grouped=collections.defaultdict(list)
    decl_keys=set();method_keys=set()
    def pointer(file,path):return {'file':file,'json_pointer':path}
    def native_routes(e):
        return [{'direction':r['direction'],'vhal_id':r['sdk_to_hal']['hal_prop'],
                 'vhal_hex':r['sdk_to_hal']['hal_hex'],'type':r['native_property']['type'],
                 'native_access':r['native_property']['access'],
                 'area_category':r['native_property']['area_category'],
                 'change_mode':r['native_property']['changeMode'],
                 'source':pointer('firmware/catalog.json','/native_properties/'+str(r['native_property']['index'])),
                 'sdk_transform_array_pair_index':r['sdk_to_hal']['array_pair_index']} for r in e.get('native_routes',[])]
    for section in ['car_signals','manager_ids','adaptapi']:
        for i,e in enumerate(cat[section]):
            d=e['declaration'];cl=d['class'];ep='/'+section+'/'+str(i)
            z={'entry_key':f'{section}:{i}','namespace':e['namespace'],'name':e['name'],'id':e['id'],'id_hex':e['id_hex'],
               'declaring_class':cl,'declaration_name':d['name'],'source_refs':[sr(s) for s in d['sources']],
               'source':pointer('firmware/catalog.json',ep),'sdk_access':e['sdk_access'] if isinstance(e['sdk_access'],list) else None,
               'sdk_access_scope':'static property-method availability, not runtime authorization',
               'methods':[],'native_routes':native_routes(e),'runtime_support':None,'physical_effect_confirmed':None,
               'type_scope':'ID declaration is int; native payload type is in native_routes',
               'observation':{'recorded':None,'reason':'not included in numeric CarSignal observation corpus'}}
            decl_keys.add((cl,d['name']))
            for j,m in enumerate(e.get('methods',[])):
                method_keys.add((cl,m['name'],m['descriptor']))
                z['methods'].append({'name':m['name'],'descriptor':m['descriptor'],'validators':m.get('validators',[]),
                                     'source':pointer('firmware/catalog.json',ep+'/methods/'+str(j)),
                                     'source_ref':sr(m['source']),
                                     'payload_schema_refs':[pointer('firmware/schemas.json','/'+str(schema_index[t])) for t in sorted(set(re.findall(r'L[^;]+;',m['descriptor']))) if t in schema_index]})
            z['enum_schema_pointer']=pointer('firmware/catalog.json',ep+'/enum_validators') if e.get('enum_validators') else None
            z['conflict_pointer']=pointer('firmware/conflicts.json','/conflicts/'+str(ci[(cl,d['name'])])) if (cl,d['name']) in ci else None
            if section=='car_signals':
                if e['id'] not in by_obs:z['observation']={'recorded':False,'reason':'no entry in all 14 supplied numeric CarSignal exports'}
                else:
                    oi,o=by_obs[e['id']];oo=o['observations']
                    z['observation']={'recorded':True,'source':pointer('observations/observed_signals.json','/signals/'+str(oi)),
                                      'join':'same CarSignal numeric namespace and ID','catalog_name_seen':e['name'] in o['names'],
                                      'recorded_names':o['names'],'exports':len(oo),'events':sum(t.get('events',0) for t in oo),
                                      'changes':sum(t.get('changes',0) for t in oo),'raw_minus_one_only':all(t.get('raw_minus_one_only',False) for t in oo),
                                      'meaning':'A recorder returned raw values; freshness and independent physical meaning are not thereby proven'}
            if section=='adaptapi':
                kind=('sensor_type_metadata' if cl.endswith('/Sensor;') else 'sensor_group_declaration' if cl.endswith('/ISensorGroup;') else
                      'sensor_id_declaration' if cl.endswith('/ISensor;') else 'config_info_declaration' if cl.endswith('/ICarInfo;') else
                      'profile_function_declaration' if cl.endswith('/Profile;') else 'function_id_declaration')
                z['declaration_role']=kind;z['role_basis']='declaring class and literal declaration, not inferred physical hardware'
                if e['id'] in ri:
                    j=ri[e['id']];r=routes[j]
                    z['adapt_route_summary']={'source':pointer('firmware/mapping/function_routes.json','/functions/'+str(j)),
                         'join':'exact Adapt integer ID; declarative aliases retained','route_module':r['module'],
                         'has_setter_flag_in_prior_extraction':r['has_setter'],'has_explicit_callback_route':bool(r['callback_routes']),
                         'read_signal_ids':r['read_signal'],'read_pa_ids':r['read_pa'],'status_pa_ids':r['status_pa'],
                         'not_a_zone_complete_route':True}
                else:z['adapt_route_summary']=None
            by_ns_id[(e['namespace'],e['id'])]=len(entries);grouped[cl].append(len(entries));entries.append(z)
    # Cross references for static Adapt summaries remain role-separated and never imply runtime observation.
    for e in entries:
        r=e.get('adapt_route_summary')
        if r:
            r['lower_layer_entry_keys']={k:[entries[by_ns_id[(ns,i)]]['entry_key'] for i in r[k] if (ns,i) in by_ns_id]
                for k,ns in [('read_signal_ids','ecarx.car.signal'),('read_pa_ids','ecarx.car.manager'),('status_pa_ids','ecarx.car.manager')]}
    adaptation_ids={e['id'] for e in cat['adaptapi']}
    supplemental=[]
    for j,r in enumerate(routes):
        if r['function_id'] not in adaptation_ids:
            cp=[pointer('firmware/constants.json','/constants/'+str(i)) for i,c in enumerate(constants) if c.get('value')==r['function_id'] and c['name'] in r['names']]
            supplemental.append({'id':r['function_id'],'id_hex':r['function_hex'],'names':r['names'],'module':r['module'],
                                 'route_source':pointer('firmware/mapping/function_routes.json','/functions/'+str(j)),
                                 'matching_constant_sources':cp,'reason':'name-based selection of the original 909 declarations excluded this literal used by buildFunctions',
                                 'runtime_support':None})
    # Retain Android standard IDs separately; do not equate standard names to vendor IDs.
    standard=[];standalone={x['prop']:x for x in cat['native_source']['standalone_configs']}
    for i,e in enumerate(cat['android_vehicle_properties']):
        q={k:e[k] for k in ['namespace','name','id','id_hex','encoded_type_bits','encoded_area_bits','encoded_group_bits']}
        q['source']=pointer('firmware/catalog.json','/android_vehicle_properties/'+str(i));q['runtime_support']=None
        q['matching_native_standalone_config']=standalone.get(e['id']);standard.append(q)
    method_by_class={x['class']:(i,x) for i,x in enumerate(methods)}
    constants_by_class=collections.defaultdict(list)
    for i,c in enumerate(constants):constants_by_class[c['class']].append((i,c))
    groups=[]
    for cl,indices in sorted(grouped.items()):
        es=[entries[i] for i in indices];mi=method_by_class.get(cl)
        ac=collections.Counter(a for e in es for a in e['sdk_access'] or [])
        groups.append({'declaring_class':cl,'group_kind':'Java/API declaring class, not physical ECU','entry_indices':indices,
                       'entry_count':len(es),'namespace_counts':dict(collections.Counter(e['namespace'] for e in es)),
                       'sdk_read_entries':ac['read'],'sdk_write_entries':ac['write'],'sdk_access_unresolved_entries':sum(e['sdk_access'] is None for e in es),
                       'native_route_entries':sum(bool(e['native_routes']) for e in es),
                       'native_type_counts':dict(collections.Counter(r['type'] for e in es for r in e['native_routes'])),
                       'native_access_counts':dict(collections.Counter(r['native_access'] for e in es for r in e['native_routes'])),
                       'recorded_numeric_signals':sum(e['observation']['recorded'] is True for e in es),
                       'changing_numeric_signals':sum(e['observation'].get('changes',0)>0 for e in es),
                       'raw_minus_one_only_numeric_signals':sum(e['observation'].get('raw_minus_one_only',False) for e in es),
                       'adapt_route_summary_entries':sum(e.get('adapt_route_summary') is not None for e in es),
                       'id_conflict_entries':sum(e['conflict_pointer'] is not None for e in es),
                       'method_count_in_selected_methods_catalog':len(mi[1]['methods']) if mi else 0,
                       'method_catalog_source':pointer('firmware/methods.json','/'+str(mi[0])) if mi else None,
                       'constant_declarations':len(constants_by_class.get(cl,[])),
                       'runtime_support_per_entry':'not assessed by this inventory','ecu_ownership':None})
    # Full coverage of the already extracted methods and constants, including classes outside 4,440 entries.
    class_inventory=[]
    allclasses=set(constants_by_class)|set(method_by_class)|set(grouped)|set(schema_index)
    for cl in sorted(allclasses):
        mc=method_by_class.get(cl);cs=constants_by_class.get(cl,[]);ms=[]
        if mc:
            for j,m in enumerate(mc[1]['methods']):
                ms.append({'name':m['name'],'descriptor':m['descriptor'],'flags':m['flags'],
                           'pointer':'/'+str(mc[0])+'/methods/'+str(j),'linked_by_exact_class_name_descriptor':(cl,m['name'],m['descriptor']) in method_keys})
        class_inventory.append({'class':cl,'entry_count':len(grouped.get(cl,[])),
                'constants_count':len(cs),'distinct_constant_names':len({c['name'] for i,c in cs}),
                'selected_entry_declaration_count':sum((cl,c['name']) in decl_keys for i,c in cs),
                'constants_pointers':['/constants/'+str(i) for i,c in cs],
                'method_source_ref':sr(mc[1]['source']) if mc else None,'methods':ms,
                'schema_pointer':'/'+str(schema_index[cl]) if cl in schema_index else None})
    # Observable subset counts are actual counts, not an estimate of vehicle completeness.
    summary={'schema':'geely-evidence-coverage-v1','scope':'all entries of the supplied v1.0 extracted catalogs; not all firmware or all vehicle systems',
        'entry_counts':{k:len(cat[k]) for k in ['car_signals','manager_ids','adaptapi']},
        'total_primary_entries':len(entries),'android_standard_declarations_separate':len(standard),
        'declaring_classes':len(groups),'declaring_classes_by_section':{k:len({e['declaration']['class'] for e in cat[k]}) for k in ['car_signals','manager_ids','adaptapi']},
        'adapt_declarations':len(cat['adaptapi']),'adapt_distinct_numeric_values':len(adaptation_ids),
        'adapt_duplicate_numeric_declarations':len(cat['adaptapi'])-len(adaptation_ids),
        'adapt_roles':dict(collections.Counter(e['declaration_role'] for e in entries if e['namespace']=='adaptapi')),
        'adapt_declarations_with_prior_route_summary':sum(e.get('adapt_route_summary') is not None for e in entries),
        'adapt_distinct_ids_with_prior_route_summary':len(adaptation_ids&set(ri)),
        'adapt_declarations_without_prior_route_summary':sum(e['namespace']=='adaptapi' and e.get('adapt_route_summary') is None for e in entries),
        'supplemental_buildFunctions_values_omitted_from_original_catalog':len(supplemental),
        'prior_route_count':len(routes),'prior_route_modules':dict(collections.Counter(r['module'] for r in routes)),
        'prior_route_setter_flag':sum(r['has_setter'] for r in routes),'prior_route_explicit_callback':sum(bool(r['callback_routes']) for r in routes),
        'sdk_entry_read_write_counts':{k:dict(collections.Counter(a for e in cat[k] for a in e['sdk_access'])) for k in ['car_signals','manager_ids']},
        'sdk_to_hal_read_entries':len(bridge['read_map']),'sdk_to_hal_write_entries':len(bridge['write_map']),
        'native_vendor_configs':len(cat['native_properties']),'native_standalone_configs':len(standalone),
        'native_vendor_access_counts':dict(collections.Counter(e['access'] for e in cat['native_properties'])),
        'native_vendor_payload_types':dict(collections.Counter(e['type'] for e in cat['native_properties'])),
        'observation_exports':len(obs['sources']),'observed_numeric_ids':len(obs['signals']),
        'observed_ids_joined_to_CarSignal':sum(e['observation']['recorded'] is True for e in entries),
        'observed_name_not_available':sum(e['observation']['recorded'] is True and not e['observation']['catalog_name_seen'] for e in entries),
        'signals_with_recorded_changes':sum(e['observation'].get('changes',0)>0 for e in entries),
        'signals_only_raw_minus_one':sum(e['observation'].get('raw_minus_one_only',False) for e in entries),
        'unobserved_read_signals':[{'id':e['id'],'name':e['name']} for e in entries if e['namespace']=='ecarx.car.signal' and e['sdk_access']==['read'] and e['observation']['recorded'] is False],
        'methods_catalog_classes':len(methods),'methods_catalog_signatures':sum(len(x['methods']) for x in methods),
        'methods_linked_to_primary_entry_by_exact_signature':sum(m['linked_by_exact_class_name_descriptor'] for c in class_inventory for m in c['methods']),
        'constants_declarations_including_variants':len(constants),'constant_distinct_class_field_pairs':len({(c['class'],c['name']) for c in constants}),
        'constant_declaring_classes':len(constants_by_class),'class_inventory_union_count':len(allclasses),
        'classes_with_no_primary_entry':len(allclasses-set(grouped)),'schema_classes':len(schemas),
        'all_constant_field_conflicts':len(conflicts),'primary_entry_id_conflicts':sum(e['conflict_pointer'] is not None for e in entries),
        'warnings':['Do not infer car hardware presence from shared SDK declarations.','API class grouping does not identify ECU ownership.',
            'All classes outside primary entries remain retained in class_inventory.json.','All CarSignal names remain unclassified by subsystem; no prefix heuristic silently loses records.',
            'All runtime support, live authorization, physical control effects and full operating conditions remain unverified by this inventory.',
            'Source methods.json is an existing selected extraction, not a guarantee of every method in every archived binary.',
            'The original extractor skipped fields whose encoded initializer is absent; 16082 is a frozen extracted-declaration count, not a complete SDK value catalog. See the separate implicit-zero audit.']}
    family_specs=[
      ('Диагностическое наблюдение','Lcom/ecarx/xui/adaptapi/car/diagnostics/IDiagnosticMonitor;',None),
      ('Чтение DTC и подписка на изменения','Lcom/ecarx/xui/adaptapi/car/diagnostics/IDtcManager;',None),
      ('Идентификация компонентов','Lcom/ecarx/xui/adaptapi/car/diagnostics/IPartInfos;',None),
      ('Сервис выполнения shell-команд','Lcom/ecarx/xui/adaptapi/car/diagnostics/IShCommand;',None),
      ('Диагностические режимы Bluetooth','Lcom/ecarx/xui/adaptapi/car/diagnostics/IBtDebug;',None),
      ('Жизненный цикл цифровых ключей','Lcom/ecarx/xui/adaptapi/car/userprofile/ICarKey;',None),
      ('Создание, применение и удаление пользовательских профилей','Lcom/ecarx/xui/adaptapi/car/userprofile/IUserProfile;',None),
      ('Производственные проверки группы1','Lecarx/car/hardware/vehicle/ECarXCarDiagmanufacturing1Manager;',None),
      ('Производственные проверки группы2','Lecarx/car/hardware/vehicle/ECarXCarDiagmanufacturing2Manager;',None),
      ('Проксирование диагностики и статусы шлюза','Lecarx/car/hardware/vehicle/ECarXCarDiagproxyManager;',None),
      ('Питание AP и этапы обновления','Lecarx/car/hardware/vehicle/ECarXCarPowerManager;',None),
      ('Версия интерфейса AP/VP и error report','Lecarx/car/hardware/vehicle/ECarXCarApvppulseManager;',None),
      ('Идентификаторы устройства и project code','Lecarx/car/hardware/vehicle/ECarXCarDeviceManager;',None),
      ('Версия DSP и lifecycle','Lecarx/car/hardware/vehicle/ECarXCarDspManager;',None),
      ('Результат диагностики усилителя','Lecarx/car/hardware/vehicle/ECarXCarExtampctrlManager;',None),
      ('eCall и регулировка звуковых предупреждений','Lecarx/car/hardware/vehicle/ECarXCarAudioradioManager;',None),
      ('Привязка идентификатора лица','Lecarx/car/hardware/vehicle/ECarXCarFaceManager;',None),
      ('Сценарные режимы автомобиля','Lecarx/car/hardware/vehicle/ECarXCarScenemodManager;',None),
      ('Беспроводная зарядка телефона и забытый телефон','Lecarx/car/hardware/vehicle/ECarXCarWpcmodelManager;',None),
      ('Подстаканник: разрешение действия и занятость','Lecarx/car/hardware/vehicle/ECarXCarTchmodelManager;',None),
      ('Данные поездки, сброс и расход энергии','Lcom/ecarx/xui/adaptapi/car/hev/ITripData;',None),
      ('Зарядка и разрядка тяговой батареи — применимость не установлена','Lcom/ecarx/xui/adaptapi/car/hev/ICharging;',None),
      ('Гибридный поток мощности — применимость не установлена','Lcom/ecarx/xui/adaptapi/car/vehicle/IHybrid;',None),
      ('Координация навигации с управлением энергией','Lecarx/car/hardware/vehicle/ECarXCarVfnaviManager;',None),
      ('Календарь и дистанция до обслуживания','Lecarx/car/hardware/vehicle/ECarXCarSensorManager;',None),
      ('Отчёт о panic MCU','Lecarx/car/hardware/vehicle/ECarXCarMculogpanicManager;',None),
      ('Групповые датчики и их tick/interval','Lcom/ecarx/xui/adaptapi/car/sensor/ISensorGroupValue;',None),
      ('Управление DTC отчётами с AP','Lecarx/car/hardware/vehicle/ECarXCarDtcManager;',None)]
    families=[]
    for no,(label,cl,pat) in enumerate(family_specs,1):
        inds=grouped.get(cl,[]);mc=method_by_class.get(cl)
        families.append({'id':'GAP-FAMILY-'+str(no).zfill(2),'label':label,'label_inference':True,
            'label_basis':'human reading of declaring class and symbol names; not proof of installed ECU or behavior',
            'class':cl,'primary_entry_count':len(inds),'primary_entry_keys':[entries[i]['entry_key'] for i in inds],
            'example_declarations':[entries[i]['name'] for i in inds[:6]],
            'method_catalog_source':pointer('firmware/methods.json','/'+str(mc[0])) if mc else None,
            'method_examples':[m['name'] for m in mc[1]['methods'][:8]] if mc else [],
            'status':'not represented as a dedicated question in prior 14-item research queue',
            'missing':['installed-equipment applicability','runtime owner and support','payload semantics and units','operating preconditions','independent readback or execution result'],
            'next_offline_step':'trace concrete methods to implementation and compare model/config gates before requesting additional vehicle data'})
    write('entry_index.json',{'schema':'geely-entry-index-v1','entries':entries})
    write('api_groups.json',{'schema':'geely-api-groups-v1','groups':groups})
    write('class_inventory.json',{'schema':'geely-extracted-class-inventory-v1','constants_file':'firmware/constants.json','methods_file':'firmware/methods.json','schemas_file':'firmware/schemas.json','classes':class_inventory})
    write('android_standard_ids.json',{'schema':'android-standard-declarations-separate-v1','entries':standard})
    write('supplemental_route_ids.json',{'schema':'geely-route-ids-omitted-by-declaration-selector-v1','entries':supplemental})
    write('uncovered_families.json',{'schema':'geely-example-research-gaps-v1','exhaustive':False,'families':families})
    write('source_index.json',{'archive_sha256':archive['archive_sha256'],'sources':sources,'input_artifacts':used,
                              'artifact_pointer_scope':'JSON pointers refer to exact input_artifacts hashes, not latest mutable versions'})
    write('summary.json',summary)
    lines=['# Полная инвентаризация существующих извлечённых каталогов Geely / ECARX','',
      'Это полный проход по указанным входным JSON, а не утверждение о полноте прошивки или всех систем автомобиля. Все каталоги обработаны офлайн. Код прошивки не выполнялся.','',
      '## Исправления прежних представлений','',
      '- **Ограничение полноты значений:** исходный extract_catalog.py пропускал поля с get_init_value()==None. Среди них есть неявные нулевые значения и поля, назначаемые в <clinit>. Поэтому 16082 — число извлечённых деклараций/вариантов v1.0, а не полная карта значений SDK. Дополнительный аудит сохранён отдельно в published/supplemental_zero_constants.json; исходные счётчики и 4440 записей здесь не изменяются.',
      f'- Индекс содержит **{len(entries)} деклараций**:2355 CarSignal,1176 manager IDs и909 AdaptAPI. Отдельно сохранены115 Android property declarations.',
      f'- Это **{len(groups)} классов объявления**:один CarSignalManager,44 менеджера и25 AdaptAPI классов. Это группы Java API, а не70 физических ECU.',
      '- 909 AdaptAPI деклараций содержат808 различных числовых значений:101 повторное объявление/алиас. Четыре записи Sensor.SENSOR_TYPE_* — метаданные типа сенсора, а не самостоятельные команды.',
      '- В прежних110 разобранных buildFunctions находятся96 чисел из Adapt-каталога (108 деклараций с учётом дублей). Ещё14 зарегистрированных значений DRIVE_MODE_SELECTION_* не попали в909 из-за фильтра названий. Они вынесены в supplemental_route_ids.json.',
      '- У801 Adapt-декларации отсутствует прежняя структурированная сводка маршрута в function_routes.json. Это ограничение именно данного структурированного набора, а не утверждение об отсутствии информации в остальных отчётах.',
      '', '## Чтение, запись и наблюдения','',
      '| Набор | Деклараций | SDK чтение | SDK запись | Точный SDK→VHAL | Числовые наблюдения |',
      '|---|---:|---:|---:|---:|---:|',
      '| CarSignal |2355|1908|447|2355|1906|',
      '| Manager CB/PA |1176|657|519|1176|Не входит в корпус CarSignal|',
      '| AdaptAPI |909|По каждой функции не установлено|По каждой функции не установлено|Требуется промежуточный маршрут|Нельзя соединять по одному числу|','',
      'Native VHAL:3532 vendor конфигурации (2563 READ,969 READ_WRITE;2668 INT32,864 BYTES) и одна отдельная стандартная WRITE INT32_VEC. Статическое READ_WRITE не доказывает доступность записи приложению или исполнение команды ECU.','',
      '1906 наблюдавшихся ID строго сопоставлены с namespace CarSignal:1891 также по имени,15 имели в журнале имя <hidden>. У55 ID записаны изменения,998 во всех имеющихся экспортах возвращали только raw=-1. Это не998 отсутствующих устройств. Не наблюдались два SDK read сигнала:32257 IntersectionType и32258 LongitudinalSlop.','',
      '## Область, которую прежний каталог функций не охватывал','',
      f'В methods.json находятся{len(methods)} классов и{summary["methods_catalog_signatures"]} сигнатура;в constants.json —{len(constants)} деклараций/вариантов,{len(constants_by_class)} классов и{summary["constant_distinct_class_field_pairs"]} различных пар class/field. Объединённый class_inventory содержит{len(allclasses)} классов, из которых{summary["classes_with_no_primary_entry"]} не имеют записи среди4440. Они сохранены полностью как схемы, имена, сигнатуры и указатели.','',
      'Наличие метода get/set в имени не используется как доказательство управления. Для3531 SDK ID read/write взяты из уже извлечённых property-operation связей. Для Adapt сохранены только явно имеющиеся summary и ссылки на них.','',
      '## Все группы объявления','',
      '| Класс | ID | SDK R/W | Точная VHAL связь | Adapt summary | Наблюдалось/менялось |',
      '|---|---:|---:|---:|---:|---:|']
    for g in groups:
        lines.append('|'+g['declaring_class'].split('/')[-1].rstrip(';')+'|'+str(g['entry_count'])+'|'+str(g['sdk_read_entries'])+'/'+str(g['sdk_write_entries'])+'|'+str(g['native_route_entries'])+'|'+str(g['adapt_route_summary_entries'])+'|'+str(g['recorded_numeric_signals'])+'/'+str(g['changing_numeric_signals'])+'|')
    lines+=['','R/W=статически связанные SDK методы;0 для Adapt означает отсутствие такого вида данных в этом поле, а не запрет чтения/записи.','',
      '## Примеры семейств, которых не было отдельными вопросами','',
      'Названия ниже — label_inference по символам API. Декларация зарядки/гибрида/цифрового ключа не доказывает наличие соответствующего оборудования на KX11 пользователя.','']
    for f in families:lines.append('- **'+f['label']+'** — `'+f['class'].split('/')[-1].rstrip(';')+'`;'+str(f['primary_entry_count'])+' ID в основном индексе. Для остальных входом служат методы вне каталога ID.')
    lines+=['','## Как воспроизвести и читать','',
      '```bash','python3 build_coverage_inventory.py /path/to/Geely_KX11_Knowledge_v1.0 /path/to/output','```','',
      '`entry_index.json` — все4440 деклараций;`api_groups.json` —70 групп;`class_inventory.json` — все классы выбранных methods/constants/schemas;`source_index.json` — точные хэши входов и источников. Указатели относятся к этим хэшам.','',
      'Для каждого настоящего функционального семейства ещё нужны:применимость комплектации,получатель/владелец,точный маршрут команд,условия работы,семантика payload и независимый feedback. Неизвестное значение хранится как null, а не false.','',
      'Ни один итоговый счётчик не является процентом изученности автомобиля.']
    report='\n'.join(lines)+'\n'
    report=re.sub(r'(?<=[А-Яа-яЁё])(?=\d)|(?<=\d)(?=[А-Яа-яЁё])',' ',report)
    report=re.sub(r'(?<=[,;:])(?=[^\s|])',' ',report)
    (out/'REPORT_RU.md').write_text(report,encoding='utf-8')
    assert len(entries)==4440 and len(standard)==115 and len(groups)==70
    assert summary['observed_ids_joined_to_CarSignal']==1906
    assert len(supplemental)==14
    for p in out.glob('*.json'):json.loads(p.read_text())
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
