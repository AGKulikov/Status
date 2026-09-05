"""Publish selected derived research; originals and private extraction stay outside repo."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

parser=argparse.ArgumentParser(description='Reproduce integration from a private audit-work layout')
parser.add_argument('--work',required=True,type=Path)
WORK=parser.parse_args().work.resolve()
REPO = WORK/'repo'
SRC = WORK/'next-analysis'
DEST = REPO/'docs/geely-kx11/research-2026-09-05-next'
DEST.mkdir(parents=True,exist_ok=True)

def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

cruise_names=['report.md','VALIDATION.json','evidence.json','collection_targets.json','native_tables.json',
              'cruise_dex_xrefs.json','pcap_audit.json','lv_image_inventory.json','source_inventory.json',
              'updater_path_xrefs.json']
cruise_names += [p.name for p in (SRC/'cruise_handler').glob('*.py')]
for name in cruise_names:
    p=DEST/'cruise_handler'/name;p.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(SRC/'cruise_handler'/name,p)

old=REPO/'docs/geely-kx11/research-2026-09-05/corpus/corpus_inventory.json'
reg=json.loads(old.read_text())
records=[]
for item in reg['sources']:
    p=WORK/'originals'/item['name']
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    assert p.stat().st_size==item['bytes'],item['name']
    records.append({'name':item['name'],'bytes':p.stat().st_size,'sha256':h.hexdigest(),
                    'category':item['category'],'available':True,
                    'prior_depth':item.get('current_depth'),
                    'depth_note':'Materialized and content-hashed. Exact semantic coverage is recorded separately in the four domain reports; acquisition alone does not mean every byte is understood.'})
write(DEST/'CORPUS_CHECK.json',{'registered_sources':len(records),'available_sources':len(records),
       'bytes':sum(x['bytes'] for x in records),'missing':[],'size_mismatches':[],
       'additional_checksum_sidecar':'KX11-HU-Route-20260810-070911-29994-1234567.zip.sha256',
       'sources':records})

questions_path=REPO/'docs/geely-kx11/open_questions.json'
q=json.loads(questions_path.read_text());q['version']='1.2'
updates={
 'CRUISE_WRITE':('В 42 выбранных DEX проверены 1406737 методов: внешние ссылки на cruise enum не найдены. 11 проверенных VHAL-статусов имеют invalid outgoing branch; реальные DrvrAsscSysBtnPush относятся к парковке. В QNX proxy шесть callbacks, can_driver — общий transport, не cruise handler.',
                 'Через Mac-сборщик проверить сохранённые пакеты OTA и новые варианты известных компонентов; после появления production ECU image разобрать получатель кнопок. Точный путь этого image пока не установлен.','cruise_handler'),
 'LIMITER_VALUE':('Четыре speed feature ID имеют только ограниченные прямые ссылки в CarLog/пользовательском consumer/support-map; штатный command handler не найден в исследованной области.',
                  'Разобрать новые компоненты OTA, если сборщик их получит. Не переносить setter/readback из статуса лимитера в неизвестный command protocol.','cruise_handler'),
 'VP_IDENTITY':('Linux LV initramfs распакован: mcu_log — UDP logger с каталогом /data/kernellog, swl_update — клиент обновления. Это не подтверждение аппаратной личности peer .1.',
                'Сопоставить состав найденного OTA с production MCU version; установленного Android/QNX alias к LV /data пока нет.','cruise_handler'),
 'CAN_OWNERSHIP':('QNX can_driver open/close меняет глобальное состояние драйвера; обычный бинарный файл можно читать отдельно, открывать /dev/can* для якобы пассивного сбора нельзя.',
                  'Изучать только новые обычные бинарные файлы; ECU-владелец и cruise framing остаются неподтверждёнными.','cruise_handler'),
 'MCU_LOG_ORIGIN':('Во всех 32 выбранных уникальных PCAP найдено 300 входящих MCU-log datagrams; LV имеет отдельный mcu_log receive/write handler. Сходство порта не доказывает тождество процессов или файловых систем.',
                    'Проверить новые firmware components и доказанный доступный LV filesystem; не запускать logger с mount callback.','cruise_handler'),
 'UPDATE_RECOVERY':('Android updater содержит точные /ota_download/udisk_manifest.json и пять update-сегментов; LV /swdl относится к другому namespace. Наличие пакетов на текущей ГУ условно.',
                    'Запустить Mac-сборщик: только шесть точных Android OTA paths; состав и принадлежность частей определять по новым байтам.','cruise_handler'),
 'RUNTIME_SDK':('Все 621 уникальных DEX прошли структурное извлечение; независимый decoder исправляет signed int и float/double initializer. У 75 исходных DEX внутренняя SHA1 signature не совпадает при корректном Adler32; это отмечено отдельно.',
                'Сборщик сверит текущие APK/JAR hashes и package/maps metadata; defining classloader конкретного класса всё ещё требует существующей диагностики внутри его процесса.','sdk_completeness'),
 'DIAG_DTC':('В разобранной SDK-копии DtcManager возвращает локальный набор восьми записей; только WPC обновляется по PA33625. DiagnosticMonitor.setMonitorEnable и ряд BtDebug методов — literal false.',
             'Не выдавать этот API за полный reader ECU DTC. Сверить текущую SDK-копию; реальный диагностический owner пока не установлен.','sdk_completeness'),
 'HVAC_CONTRACT':('Пять runtime источников TempManager показывают LO — 16…28°C — HI, min15.5/max28.5/step0.5. Числовые пределы соответствуют крайним LO/HI в штатном UI. SDK range check не обеспечивает finite/grid: 16.2 и NaN проходят прочитанный validator.',
                   'Собрать отсутствующий XCHvac.apk (ecarx.hvac.app) и разобрать click conversion, guards и связь requested/confirmed по зонам.','comfort_contracts'),
 'HVAC_EVENTS_PREPOST':('Восстановлены три разные REQUEST/CONFIRM карты ответов; public2 не имеет универсальной семантики. PRE/POST notavailable в сохранённых сеансах не означает отсутствия оборудования.',
                       'XCHvac.apk нужен для текста и lifecycle кнопок предложений, корреляции актуального запроса и ответа.','comfort_contracts'),
 'LIGHTING_AMBIENT':('Production Settings пишет bool0/1 в 0x2a010300, тогда как его SDK регистрирует RGB BREATHE_MODE_COLOR и runtime возвращает65326. Это конфликт consumer/SDK, не доказанный рабочий breathing switch.',
                     'Сборщик сверит текущие Settings/JAR/maps. Установить реальную runtime-копию и нижний обработчик до управления этой функцией.','comfort_contracts'),
 'WINDOW_SEAT':('Штатный seat consumer отправляет STOP0 по UP; ACTION_CANCEL идёт в return true. Roof position slider пишет при отпускании; UI-delay не является доказанным ECU watchdog.',
                'Прочитать итоговый lifecycle-разбор с inherited/RxBus цепью; остаются физический STOP при разрыве клиента, ECU watchdog и anti-pinch, которые файловый сбор сам не проверяет.','comfort_contracts'),
 'LOCKS_KEYS':('Digital create/delete/register остаются заглушками в проверенных копиях; physical-discovery CB33254 и PA33853 имеют конкретную карту callback. PA результата нельзя использовать как CB.',
               'Не запрашивать guessed key-service или ключевые БД. Отдельный действующий issuance-service и hardware provisioning пока не установлены.','comfort_contracts'),
 'PROFILE_SCENES':('Profile apply может вернуть true после поглощённого Binder failure; onAdded вызывается даже при FAILED. Семь scene bool используют общий selector, OFF старой сцены может сбросить новый режим.',
                   'Сверить текущую SDK-версию; для физических действий и rollback нужен подтверждённый нижний owner. Автоповтор по return value не обоснован.','comfort_contracts'),
 'BLUETOOTH_COEXISTENCE':('Разобраны все шесть Bluetooth архивов: reason40/0x28 и затем0x100 относятся к GPS client5; Natro client6 выполняет local disconnect/close/unregister. HFP остаётся подключённым. ts-dm-service найден и server arbitration разобран.',
                        'Собрать восемь отсутствующих Bluetooth ELF/APK, metadata текущего Natro и очищенный GATT snapshot. Внутреннее решение Natro требует его существующего diagnostic event без содержимого уведомлений.','bluetooth_runtime'),
 'PHONE_CARPLAY':('TS-DM capability5/6 соответствует HFP/A2DP и использует FoundationManager, дедупликацию запросов, callbacks и таймеры сверки. Это не доказанная ANCS-арбитрация.',
                  'Досбор EcarxBluetoothServiceExtension и native backend продолжает неизвестную нижнюю policy-ветвь.','bluetooth_runtime'),
 'POWER_LIFECYCLE':('PowerSomeIP повторные enable, timeout/Binder errors наблюдаются, но причинная связь со sleep и конкретным обрывом GATT не доказана.',
                    'Сверить новые версии и очищенный runtime snapshot; синхронный AP/QNX power epoch остаётся отдельным отсутствующим наблюдением.','bluetooth_runtime'),
}
for item in q['questions']:
    if item['id'] not in updates:continue
    known,nxt,domain=updates[item['id']]
    item.setdefault('history',[]).append({'version':'1.1','known':item['known'],'next_step':item['next_step'],'missing_evidence':item['missing_evidence']})
    item['known']=known;item['next_step']=nxt
    item['missing_evidence']='Остаточная граница описана в новом отчёте и следующем шаге; ранее закрытые статические звенья не запрашиваются повторно.'
    item['sources'].append('research-2026-09-05-next/'+domain+'/report.md')
    item['collection_plan']='../../tools/geely-macos-collector/collection_plan.json'
write(questions_path,q)

guide_path=REPO/'GEELY_KX11_KNOWLEDGE_RU.md'
guide=guide_path.read_text().replace('Версия 1.1 • расширенный разбор','Версия 1.2 • расширенный разбор',1)
start='## Расширение 1.1: охват систем и новые доказательства'
addition='''## Продолжение 1.2: разбор до конкретных файловых пробелов

Итог этого этапа — [готовый комплект досбора для macOS](tools/geely-macos-collector/README_RU.md),
а также [новые доказательства и границы](docs/geely-kx11/research-2026-09-05-next/README_RU.md).
Все 158 зарегистрированных источников теперь доступны и проверены по размеру/SHA;
это не означает полного понимания каждого ECU. Точная глубина анализа указана по направлениям.

- **Климат:** пять runtime-источников подтверждают штатный ряд LO — 16…28°C — HI.
  Изучены validator/grid/NaN, consumer подсветки, press/release сиденья, ключи и профили.
  Отсутствующий production consumer — `XCHvac.apk`, пакет `ecarx.hvac.app`.
- **Bluetooth:** разобраны все шесть архивов. Ошибки GPS client5 отделены от local teardown
  Natro client6. `ts-dm-service.apk` найден в старом архиве и разобран; нужны восемь других
  native/extension файлов. Причина внутреннего решения Natro ещё не установлена.
- **Круиз:** проверены конечные Android/QNX handlers, 42 выбранных DEX, 32 PCAP и LV initramfs.
  Поддержанный MAIN/SET/RES не найден в этой области; статусы и парковочные buttons не становятся
  командами круиза. Досбор OTA привязан к шести точным условным путям производителя.
- **SDK:** структурно обработаны все 621 уникальных DEX; опубликован типизированный каталог
  с вариантами источников и исправлением signed/floating initializers. Отдельно сохранена
  аномалия внутренних SHA1 75 исходных DEX. Это не аппаратная приёмка API.

Читать новые отчёты до исторических чисел/вопросов ниже. Старые версии и 50 постоянных ID
вопросов сохранены; закрытые статические звенья заменены конкретными остаточными вопросами.
Сборщик имеет hash-baseline, отчёт каждого отказа и офлайн-проверки; реальный запуск Mac/KX11
ещё нужен. `GATE-046`, `GATE-048` и `GATE-049` остаются открытыми.

'''
guide=guide.replace(start,addition+start,1)
guide=guide.replace('физические LO/HI ещё не определены.','runtime LO/HI уточнены в продолжении 1.2; физический эффект крайних режимов этим не подтверждён.')
guide=guide.replace('Обновлены [источники и глубина проверки](docs/geely-kx11/research-2026-09-05/corpus/corpus_inventory.json):158 зарегистрированных источников,33 заново материализованных файла,29 архивов/5134 внешних members. Инвентаризация имён не равна смысловому разбору всего содержимого; пять из шести дополнительных Bluetooth архивов ещё ждут целевого анализа. Полный аппаратный состав автомобиля не получен.',
                    'Исторический снимок 1.1 фиксировал 33 материализованных источника и 29 архивов. В продолжении 1.2 [проверены все158 зарегистрированных источников](docs/geely-kx11/research-2026-09-05-next/CORPUS_CHECK.json), завершён целевой разбор всех шести Bluetooth архивов и расширен SDK/consumer/native pass. Инвентаризация и полный аппаратный состав остаются разными задачами.')
guide_path.write_text(guide)

readme_path=REPO/'docs/geely-kx11/README.md'
readme=readme_path.read_text()
readme=readme.replace('Обновление 1.1 от 5 сентября:',
  'Продолжение 1.2: [Mac-сборщик недостающих файлов](../../tools/geely-macos-collector/README_RU.md),\n[углублённый разбор](research-2026-09-05-next/README_RU.md), [проверка158 источников](research-2026-09-05-next/CORPUS_CHECK.json).\n\nИсторическое обновление 1.1 от 5 сентября:',1)
readme_path.write_text(readme)
print({'corpus':len(records),'questions_preserved':len(q['questions']),'updated_questions':len(updates),'destination':str(DEST)})
