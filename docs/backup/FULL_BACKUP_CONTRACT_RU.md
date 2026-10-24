# Полная личная копия Natro: контракт реализации

### Review24.10.2026 — MEDIA gestures / 3.3.6

Рассмотрены ButtonSettingsDraft, VehicleButtonController, MediaButtonController:
новые binding.mediaPrevious/mediaPlayPause/mediaNext.{1,2,3,long} используют
прежний DE vehicle_buttons и те же типизированные поля action/app/command/
package/shortcut. Полный prefs adapter включает их автоматически. Новые
namespace/files/секреты отсутствуют. Single отсутствует→109 ordinary; явный0
остаётсяNONE, остальные отсутствуют→0. Старые prefs не переписываются; при всех
исходных defaults новая обработка выключена. Черновик/Apply/Cancel общий,
таймеры/физические edges/native-qualification толькоRAM, restore их не возобновляет.
Прежний media_buttons/disable_default и installation-bound restore review gate
не меняются. Автоматического применения системного патча из backup нет.
BackupDefaultsMigration разрешает335…368→369 иsame369; explicit/unknown/effective
сохраняются;334/370 отклоняются. Новые поля — target-only defaults, не изменение
прежних эффективных значений. Fingerprints четырёх owners пересмотрены по diff;
новые Android draft/migration tests добавлены, их полный CI ожидается.
Static inventory не доказывает физическое восстановление KX11.


### Review3.3.5 — release defaults

Defaults335…367→368 рассмотрены при неизменных keys/defaults/roots.
Регрессия сохраняет effective/explicit/unknown значения и same368;
334/369 отвергаются. Пересмотрен fingerprint BackupDefaultsMigration.
Реальная установка/restore FIELD OPEN.

### Review23.10.2026 — media delivery / traffic heartbeat

Рассмотрены diff MediaButtonController и NavigationDataRepository: дополнительная
runtime-подписка, диагностический ingress и RAM limiter уведомлений. Новых prefs,
keys/defaults/roots нет. encodeTrafficLights(values) сохраняет прежний JSON с
updatedAt; вариант без timestamp используется только для сравнения в RAM.
Все принятые обновления по-прежнему сохраняют актуальность, clear и schema прежние.
Два fingerprints обновлены после этого review. FIELD backup/restore не проверен.

### Review3.3.4 — выгрузка DIM

DiagnosticsActivity: transient progress/dialogs, явная копия существующего ZIP через
NatroSharedFolder в Download/Natro с прежним разрешением и подтверждением. Новых
keys/defaults/roots нет. DimUartCapture нормализует только доверенный getFilesDir;
защита от ссылок внутри diagnostics остаётся. Формат session/state/terminal/uart.log
версии3.3.3 не меняется, исходные записи не удаляются при выгрузке/обновлении.
Добавлены события в существующий DiagnosticJournal, без сырых данных UART.
Defaults335…366→367 рассмотрены при неизменных defaults; same367 не переписывается,
explicit/unknown/effective сохраняются,334/368 отвергаются отдельной регрессией.
Fingerprints трёх изменённых owners пересмотрены по этому diff; FIELD restore OPEN.

### Review3.3.2 — отдельная задача приборки и подтверждённый OFF

Пересмотрены diff launcher/owner/query/coordinator и AdbShellResult: только RAM,
прежние launch token, диагностический журнал/retention и shell transport.
Новых keys/defaults/roots/adapters нет. Begin-marker и exit status не меняют
семантику существующих потребителей Result.success; строгий новый owner query
проверяет оба маркера. Режим OEM-диалога/настройки прошивки не записываются.
Версия365 требует defaults335…364→365; новая отдельная regression сохраняет
explicit/unknown/effective значения и same-version XML, отклоняет334/366.
Прежние version-specific tests сохранены. Fingerprint обновлён только для
проверенного BackupDefaultsMigration после этого review. Runtime restore FIELD OPEN.


### Review3.3.1 — install-over и совместимость defaults

Новый release code208021364 требует разрешить прежние схемы335…363→364.
Проверено отсутствие новых defaults/keys в исправлении переключателя и collector.
Материализация сохраняет effective defaults, explicit значения и неизвестные ключи;
same364 не переписывает XML,334/365 и несовпадение source/schema отклоняются.
Добавлен отдельный migration test; старые version-specific проверки сохранены.
Пересмотрен только fingerprint BackupDefaultsMigration. Это не физическая
проверка восстановления на KX11.

### Review18.10.2026 — lifecycle переключателя приборки

Проверены launcher/activity/close coordinator: отмена хранится только в RAM,
прежний single-use token отзывается через существующий InstrumentPanelStore.
Новых ключей, defaults, persistent roots, backup adapters или миграций нет.
Read-only ownership использует настроенный PrivilegedShell; его storage API
не меняется. Inventory90owners PASS без изменения fingerprints. Диагностические
incidents используют прежний журнал и его retention; FIELD restore не проверен.

### Review18.10.2026 — полный OEM screencap help с exit1

Пересмотрен diff WingsEvidenceArchive: изменено только признание известного
шестистрочного API28 help с exit0/1; оригинальный exit/output остаётся в manifest.
Это допускает прежний адресный capture вместо default-unmapped fallback.
Пределы170с/15с/1MiB/4дисплея/8MiBPNG, временные shell paths и cleanup,
cache/exports, retention2, canonical guard и явный Download-export сохранены.
Новых prefs/keys/defaults/roots нет, схема и restore payload не меняются;
миграция не нужна. Пересмотрен fingerprint только этого owner после проверки
контракта. Static inventory и collector replay не являются FIELD backup/restore.

### Review3.3.0 — адресный wings ZIP

Рассмотрены DiagnosticsActivity, NatroSharedFolder, WingsEvidenceArchive,
BackupDefaultsMigration. Прежний cache/exports получает отдельный префикс
Natro-wings-, retention2 только собственных ZIP; повторное открытие экрана
обнаруживает последний complete/partial wings ZIP с canonical guard; явный Download экспорт
сохраняет префикс и не перезаписывает пользовательские файлы. Новых preferences,
ключей, defaults и persistent roots нет. ZIP диагностический, в restore payload
не входит. Временные PNG shell /data/local/tmp вне app backup; путь/cleanup
записываются в ZIP, обрыв не выдаётся за удаление. Defaults335…362→363 сохраняют
explicit/unknown/effective keys, будущие схемы отклоняются. Fingerprints обновлены
после review этих4owners; static inventory не доказывает runtime restore.

### Review3.2.9 — касания, текст музыки и отчёт докачки

По diff рассмотрены DiagnosticsActivity, LanTransferService, SystemFileDump,
VehicleEvidenceArchive, QnxFileReader и BackupDefaultsMigration. Новый summary.txt
лежит в прежнем cache/exports ZIP, retention2 и явное сохранение прежние. В UI и
LAN добавлен transient summary; QNX detail добавляет поля в TSV. 6000 последних
logcat records фильтруются в RAM до записи; полный raw log не сохраняется.
Новых storage roots, настроек, ключей и defaults нет. Геометрия виджетов/JSON
не переписывается; очистка title относится лишь к display Snapshot. Raw media
identity и cache не меняются. Рассмотрен переход335…361→362 с сохранением
explicit/unknown keys и отклонением будущих схем; добавлена регрессия. Fingerprints
этих6owners обновлены после review. Это не FIELD полной резервной копии/restore.

### Review3.2.8 — изоляция необязательных каналов

Пересмотрены DiagnosticsActivity, LanTransferService, SystemFileDump,
VehicleEvidenceArchive, BackupDefaultsMigration. Новых storage owners/настроек
нет: прежний cache/exports, retention2, явный Download, partial manifest/ZIP.
Порядок/таймер/тексты не меняют backup adapters/roots. Root-probe и skip reason
пишутся только в приватную диагностику; никаких credentials/remote writes.
Defaults335…360→361 и same361 рассмотрены; explicit/unknown/effective сохраняются,
334/362 отклоняются. Runtime backup/restore FIELD OPEN, inventory лишь tripwire.


### Review3.2.7 — докачка QNX

Пересмотрены DiagnosticsActivity, LanTransferService, QnxFileReader,
VehicleEvidenceArchive, BackupDefaultsMigration. Прежний cache/exports,
retention2/явный Download, mutex/remote_commands/pairing сохранены. QnxExclusions
читает неизменяемый packaged resource (977fingerprints), не пишет настройки,
новый persistent cache/resume index не вводится. Режим полного повторения только
RAM. Пропуски с историческим SHA записываются в существующий TSV и не становятся
restore payload. Старые пользовательские архивы не удаляются. Native lstat/
readlink не пишет QNX. Новые metadata/logs остаются приватной диагностикой.
Defaults335…359→360 и same360 рассмотрены явно, keys/effective/explicit/unknown
сохраняются;334/361 отвергаются тестом. Settings/defaults/migrations форматов
не меняются. Fingerprints пересмотрены только после этого diff review.
Runtime backup/restore и новая докачка на машине FIELD OPEN.

### Review 3.2.6 / 208021359 — Android9 updater

Пересмотрен NatroUpdater: прежний cache/updates, временный APK удаляется при
отказе, candidate/progress остаются только RAM. Проверка архива запрашивает оба
certificate flags; trust pin/ключи/идентичности не меняются. Добавлены сообщения
в существующий ограниченный DiagnosticJournal, без новых файлов/настроек.
APK не импортируется из backup и перед installIntent повторно проверяется.
Legacy certificate/history не заменяют current signer. Новый storage owner
не появляется. BackupDefaultsMigration явно разрешает335…358→359 и same359,
сохраняет прежние effective defaults/explicit/unknown keys;334/360 отвергаются.
Тесты миграции добавлены; статический inventory не доказывает runtime restore.

### Review3.2.5 — уведомление режима вождения HUD

Новая пользовательская настройка `showDriveModeNotification` находится внутри
прежнего `hudPanelConfigJson`, schema6 не повышается (NAV_MAP marker сохраняется).
Существующий prefs adapter переносит весь JSON; explicit false round-trip
сохраняется. Отсутствующее поле получает true по запросу новой возможности,
HUD включается только прежним hudPanelEnabled. Остальные настройки/раскладка
не пересоздаются. Смена режима/таймер/подписка только RAM, после восстановления
первый sample молчит. Новых файлов/ключей/SDK identities нет.
Пересмотрены HudPanelSettingsActivity (прежние prefs/SAF font), HudCanvasView
(прежнее чтение внешнего шрифта); новый Canvas banner не читает ресурсов APK.
Проверки JSON/реального runtime и native-render добавлены; runtime полного
backup/restore на машине этим не сертифицирован.

### Review 3.2.5 / 208021358 — полный архив QNX и каналы связи

Требования17.10 15:21–15:25 заменяют прежний запрет массового download только
для явно выбранных QNX roots. VehicleEvidenceArchive — новый владелец временных
cache/exports Natro-vehicle-*.zip, manifest/queue/per-file spool; хранит два
последних архива, .part удаляется после завершения/ошибки. Это не managed backup
и не источник firmware restore. DiagnosticsActivity выбирает последний из двух
видов ZIP. Кнопка Download/Natro создаёт отдельную пользовательскую копию без
размерного лимита, не перезаписывает файлы и не очищает их автоматически.

QnxFileReader читает только обычные файлы выбранных roots; symlinks отклоняются
NOFOLLOW, special objects получают metadata, динамический /proc/PID не выбран.
Scope и точные ограничения live snapshot описаны в FULL_VEHICLE_ARCHIVE_RU.md.
LanTransferService использует прежние pairing/remote_commands и общий mutex.
Новый download grant — только RAM: случайная cookie256bit, HttpOnly/SameSite,
10min на начало HTTP-запроса, привязка к owner+export, отзыв pairing проверяется
во время передачи. В URL нет bearer/nonce. Диапазоны long, архив не грузится
в RAM Safari целиком. Grant не переносится/не восстанавливается из backup.
LanHttpServer ограничивает бездействие, а не длительность активной выгрузки.

Новых preferences/defaults/постоянных идентичностей нет. Явно разрешены
schemas335…357 →358 и same-schema358; старые effective defaults, explicit values
и неизвестные keys сохраняются. 334/359 отвергаются. Пересмотрены только владельцы
DiagnosticsActivity, QnxFileReader, VehicleEvidenceArchive, LanTransferService,
NatroSharedFolder, BackupDefaultsMigration и AdbTransport (новый GET-only entry
использует прежние transport/key storage, без новых идентичностей). Статический inventory не является
доказательством полного runtime restore на KX11.

### Review 17.10.2026 — дополнительные DIM evidence files

QnxFileReader добавляет девять точных путей из полученного namespace inventory:
конфигурацию DIM, display libraries/tools, статический PSD resource и текущий
QNX DIM_Slog.log. Чтение остаётся потоковым в прежний cache/exports spool/ZIP;
S_IFREG, два чтения, stat и локальные SHA сохранены. Меняющийся журнал получает
changed_during_read, сохранённые байты не объявляются атомарным снимком.
Новых managed roots, preferences, defaults, адаптеров или firmware restore нет.
Полный каталог не превращён в автоматический download произвольных /dev, /proc
или найденных файлов. InstrumentDisplayOwner использует только RAM-разбор;
SystemProcessEvent добавляет plus.monjaro в прежний allowlist журнала lifecycle.
Контракт cache/exports и архивная политика диагностики проверены; fingerprint
QnxFileReader обновлён после review этого diff. Runtime backup/restore не принят.

### Review 3.2.4 / 208021357

Явно разрешена миграция defaults335…356 →357 и same-schema357. Основные
effective defaults/ключи/типы не изменены: новые LAN flags в отдельном CE
namespace false/empty и installation-bound по review ниже. Материализация
сохраняет старые effective defaults, explicit values и неизвестные keys.
334/358 отклоняются; добавлен Robolectric-тест всех предшественников,
same-schema и отказа будущей схемы. Результат этого теста ожидает полного CI,
не объявляется проверкой KX11 restore. Fingerprint пересмотрен только для
изменённого BackupDefaultsMigration; остальные policies не изменены.

### Review 17.10.2026 — LAN trust, экспорты и updater (SOURCE, без APK/FIELD)

Проверены восемь изменённых/новых владельцев хранения. LanTrustStore открывает
CE `natro_lan_trust_v1` через BackupPreferences: `hashes` StringSet SHA-256 до
4 элементов (default empty), `auto_start` и `remote_commands` Boolean (false).
Commit синхронный в worker, ошибка закрывает доступ. Это локальная идентичность
и разрешение: BackupStorage boundStores архивирует импортированный XML целиком
в restored-device-records и сохраняет действующий локальный, либо удаляет
импортированный активный. Неизвестные keys остаются в архиве. Импорт не доверяет
чужому телефону, не включает сервер или ADB. Переустановка/очистка данных не
обещает сохранения доверия. Существующие namespace/defaults не изменены;
defaultsSchema нового APK потребует отдельного versionCode/migration review.

LanTransferService: входящие файлы остаются на прежнем пути; jobs/bearer/статус
экспорта только RAM, не являются настройкой и не повторяются после restore.
DiagnosticJournal/DiagnosticBundle: те же allowlists/журналы, только уникальное
имя временного cache/exports TXT/ZIP. Одновременный локальный экспорт не
перезаписывает удалённый. Исходные diagnostic files по-прежнему включаются в
полную копию; cache export не является резервной копией и не импортируется.
NatroUpdater: только cache/updates APK, candidate/progress в RAM; ни настроек,
ни токенов GitHub, ни автоматической установки из backup. APK перед каждой
установкой проверяется заново. Cache может очищаться системой; это явная ошибка,
не основание запускать другой файл.

Также рассмотрены уже опубликованные QnxFileReader (uint64 stat identities,
те же временные cache файлы) и InstrumentOemController (только описание
диагностической причины, прежние prefs/ownership). Их storage-политики не
изменились. Fingerprints обновляются после этой ревизии, не вместо неё.
JVM trust replay проверяет сохранение флагов, отзыв и отказ commit на doubles;
это не физический Android boot/restore и не сертификат полноты backup.

### Review3.2.3 — потоковый каталог QNX и снятие квот

QnxFileReader — новый обнаруживаемый storage-owner: временные qnx-work-*.part
для очереди и артефактов. Production Receiver создаёт их только в прежнем
приватном cache/exports; finally удаляет после упаковки/ошибки. Это временная
диагностика, не новый managed root, настройки, секреты или firmware restore.
Прерывание процесса может оставить временный cache-файл до очистки кэша;
он не считается завершённым ZIP и не предлагается для импорта. Архивирование
потоковое, но требует места одновременно под spool и ZIP; при ошибке записи
полнота не объявляется. SystemFileDump сохраняет retention2/явную выдачу ZIP.
DiagnosticsActivity меняет объяснение/прогресс, не defaults/постоянные ключи.
AdbTransport сохраняет RSA adb_key/adb_key.pub и endpoint; новый каталог
не пишет на QNX и не запускает команды/службы. Общий таймер dump отключён,
консоль60с и отдельные I/O deadlines/отмена сохранены.

BackupDefaultsMigration: явно рассмотрены335…355→356 и same-schema356;
ключи, форматы, effective defaults и приоритет explicit values прежние.
334/357 отвергаются новым тестом. Fingerprints этих5 owners обновляются
после данного review; runtime restore на KX11 этим не подтверждается.


### Review3.2.2 — выпуск wide mappings

Defaults/ключи/adapters не меняются. BackupDefaultsMigration явно разрешает
354 как источник,355 как цель;335…354→355 и same-schema355 рассмотрены.
Новый тест отвергает334/356. Fingerprint изменён после проверки совместимости;
прежние проверки explicit values сохраняются, runtime restore FIELD OPEN.

### Review16.10.2026 — lib64 и независимая попытка mappings

SystemFileDump меняет только README внутри существующего приватного ZIP.
Пути хранения, retention2, backup roots, defaults и адаптеры не изменяются.
QnxFileReader добавляет4 allowlisted файла и вторую ограниченную попытку
mappings в тот же экспорт; ожидаемый хвост и спорный ответ сохраняются как
hex в существующей trace. Эти данные остаются диагностикой, не настройками
и не источником восстановления firmware. Fingerprint SystemFileDump пересмотрен
после проверки diff; runtime backup/restore этим не сертифицируется.

### Review3.2.1 — QNX reader,16.10.2026

QnxFileReader не вводит persistent storage, preferences, секретов или adapters;
вывод остаётся в прежнем приватном диагностическом ZIP. BackupDefaultsMigration
явно добавляет353 как источник и354 как цель:335…353→354, same-schema354;
334/355 отвергаются. Defaults, ключи, типы, explicit/unknown значения сохраняются.
Пересмотрен fingerprint единственного изменённого storage-owner после проверки
контракта; runtime backup/restore на KX11 этим не сертифицируется.

### Review 3.1.9 — expanded graphics evidence

SystemFileDump сохраняет прежний cache/exports ZIP и retention2. GraphicsEvidence
не создаёт отдельного persistent storage: callback передаёт ограниченные metadata
и DLT ranges внутрь того же диагностического ZIP. Журналы могут содержать личные
данные; UI предупреждает, автоматической публикации/restore/активации нет.
DiagnosticsActivity добавляет только RAM user label, не preference/default.
QnxFileReader использует прежний транспорт и GET, не пишет на QNX; секреты и
RSA-файлы AdbTransport не меняются. BackupDefaultsMigration явно разрешает
335…351→352 при прежних keys/types/defaults;334/353 отклоняются. Пересмотрены
три изменённых storage owners (UI/export/migration); новый reader не владеет
новыми roots. Fingerprints обновляются после этой ревизии. FIELD restore OPEN.

### Review 3.1.8 — целевой graphics dump

SystemFileDump сохраняет прежние cache/exports ZIP и политику двух последних
архивов; новые files/metadata входят только в приватный диагностический экспорт,
не managed backup roots. DiagnosticsActivity меняет объяснение состава/600с.
AdbTransport использует прежние RSA-файлы/endpoint, не добавляет секретов или
настроек; новые операции только read-only qconn GET. Эти три storage owners
пересмотрены без изменения правил восстановления. BackupDefaultsMigration
явно разрешает335…350→351 с прежними effective defaults/explicit values;
334/352 отвергаются. Новых preferences/defaults нет. Fingerprints этих четырёх
владельцев обновляются после review. Runtime restore gate остаётся OPEN.

### Review 3.1.7 — источник заряда и мягкое скрытие OEM overlay

WidgetService сохраняет прежние два persistent low-battery latch и их правила
backup. Добавлена только RAM-строка диагностического последнего источника;
новых ключей/файлов/defaults нет. Точный источник влияет на срабатывание и
перевооружение предупреждения, а не на формат или перенос состояния.
InstrumentOemController сохраняет hide_oem_white_bar/oem_white_bar_owned и
прежнюю политику локального владения; AppOp ignore — внешний runtime effect,
не новое переносимое поле. Начальная запись заменяет старый deny, при выходе
по-прежнему allow. Изменений адаптеров/roots/секретов нет.
BackupDefaultsMigration явно добавляет рассмотренный источник349 и цель350;
335…349→350 сохраняет effective defaults и explicit values, неизвестная334 и
будущая351 отвергаются. Same-schema350 принимается как прежде. Fingerprints
обновлены у двух обнаруживаемых storage owners (controller/migration); WidgetService
дополнительно проверен как пользователь существующих preferences. Runtime backup/
restore на KX11 этим не сертифицируется.

### Review 3.1.6 — диагностика отказа QNX и настройки

BackupDefaultsMigration:335…348→349 допускаются явно, неизвестные334/350
отклоняются. Ключи, типы, defaults и алгоритм materialize не изменены.
SystemFileDump: добавлен bounded qnx-diagnostics.txt внутрь прежнего cache/exports
ZIP; это диагностический экспорт, не новый managed root. Firmware не восстанавливается
и автоматически не переносится в Download/Natro. Диагностика содержит только
служебный ответ, этап, allowlisted путь и счётчики, не бинарное содержимое файлов.
DiagnosticsActivity меняет только объяснение и вызов ограниченной dump-сессии:
180 секунд вместо60; обычная консоль остаётся60 секунд. AdbTransport использует
прежние RSA adb_key/adb_key.pub и тот же endpoint, открывает отдельный read-only
qconn stream на каждый разрешённый файл. Новых ключей, managed roots, секретов,
write-операций и адаптеров восстановления нет. Все четыре изменённых storage owner
пересмотрены; физический restore gate остаётся открытым.

### Review 13.10 — расширенный дамп и нижний градиент

Кандидат3.1.5/208021348: явно допускаются335…347→348, неизвестные334/349
отвергаются; typed defaults и пользовательские значения сохраняют прежний смысл.
BackupDefaultsMigration пересмотрен отдельно, добавлен тест допуска схем.

SystemFileDump/DiagnosticsActivity сохраняют прежний cache/exports и явную копию
в Download/Natro; добавляются только диагностические payloads QNX и UART context.
Новых managed roots, автоматического восстановления firmware и секретов нет.
AdbTransport сохраняет прежние RSA файлы, новый qconn транспорт не пишет на QNX.
Эти три storage-owner пересмотрены по указанному составу, а не ради scanner.
bottomFadeEnabled хранится в существующем NAV_MAP.options: отсутствующее=true
сохраняет прежнее поведение, false переносится вместе с полным JSON, copy и
mergeUserOptions. Namespace/схема документа неизменны. Test покрывает false при
миграции пресета и повторной сериализации. Внешний новый backup format не вводится.
SettingsEditSession меняет подписи/компоновку footer, не commit/rollback semantics.

### Review 13.10.2026 — отдельный системный дамп

SystemFileDump и DiagnosticsActivity используют только cache/exports/Natro-system-*
для явного read-only экспорта. Это чувствительные временные диагностические файлы,
не настройки и не восстанавливаемая прошивка. Два последних завершённых ZIP,
частичный .part удаляется при ошибке записи. Никаких новых preferences/defaults,
секретов, CE/DE данных или автоматических команд восстановления. Исходные системные
файлы не записываются. Эти два новых storage-owner пересмотрены; приватные исходные
бинарники не включаются в публичный Git. Полнота backup не переутверждается.

### Review 3.1.4 — номер схемы выпуска

BackupDefaultsMigration явно допускает335…346→347 при прежних ключах, типах и
defaults. Явные значения/неизвестные пользовательские ключи сохраняются; будущая
348 и неизвестная334 отклоняются. Добавлен Android migration test. Fingerprint
пересмотрен по этому изменению; физический restore gate остаётся отдельным.

### Review 13.10 — явный экспорт в Download/Natro и чтение с iPhone

NatroSharedFolder владеет только общей папкой Download/Natro: сервер читает прямые
обычные файлы, исключает ссылки, скрытые файлы и незаконченные .part. Список ID
только в RAM. Локальный явный экспорт создаёт уникальный .part, затем переносит
в новый ZIP без REPLACE_EXISTING; исходный приватный ZIP и пользовательские файлы
не удаляются. Общая папка не становится managed root настроек и не импортируется
автоматически вместе с ними. Это выбранный пользователем экспорт/обмен, не новый
источник секретов или настроек; содержимое требуется хранить/переносить отдельно.
LanTransferService сохраняет старый app-specific Natro-Incoming и его backup policy.
DiagnosticsActivity добавляет только явную копию cache/exports → Download/Natro.
Новых preferences, defaults, схемы, автостарта или сохранённых pairing secrets нет.
Fingerprint этих владельцев пересмотрен по указанным путям/политике, а не ради
зелёного scanner. Полнота аппаратного backup/restore этой проверкой не утверждается.

### Дополнение Review 3.1.3 / 10.10.2026 17:08 МСК

InstrumentOemController меняет только RAM lifecycle подписки и условие read-only
task probe. Имена hide_oem_white_bar/oem_white_bar_owned, DE namespace, defaults,
адаптеры и RuntimePreferenceWriter сохранены; новых данных нет. Пересмотрен один
fingerprint. ANCS WAL сохраняет тот же snapshot и обязательный commit/readback;
диагностика не экспортирует его содержимое и не изменяет формат/restore policy.

### Review 3.1.3 / 10.10.2026

BackupDefaultsMigration: явное335…345→346; defaults, документы и неизвестные
ключи прежние. MediaAutoResumeController сохраняет те же DE keys, target/token/
manual guard; меняются только ранний unlock gate и RAM-порядок dispatch.
AndroidCentralTransportV2 добавляет capability flags в прежнюю диагностику,
enrollment/SecretStore/файлы неизменны. Иконка хранит только последний Drawable
в карточке, не создаёт persistent cache. Пересмотрены три storage fingerprints;
это не сертификация аппаратной полноты backup/restore.


### Review 3.1.2 / 10.10.2026

BackupDefaultsMigration: явный переход335…344→345 без новых defaults или схемы
пользовательских документов; explicit/unknown значения сохраняются, будущие
версии запрещены. DIM owner только RAM/application context, не новое хранилище.
HUD block.XXXXXX — временные файлы прежней внешней служебной папки, не настройки;
при импорте не применяются. Allowlist и оригиналы системных файлов прежние.
HudLcaPatchActivity сохраняет тот же last-report.txt также при отказе shell;
путь, чувствительность и правила backup не меняются. Пересмотрены fingerprints
этих двух изменённых владельцев после ревизии, не как доказательство полноты.

### Дополнение review3.1.1, web app18:39

AutomationStateStore сохраняет прежние preferences/ключи/сериализацию; только
проверка длины icon допускает namespace phone-app: с bounded512 ANCS ID. Нового
хранилища/миграции нет; прежние значения читаются без изменения. Валидация обычных
полей не ослабляется. Owner пересмотрен, runtime backup-приёмка отдельно.

### Дополнение review3.1.1 после17:53

AndroidCentralTransportV2 меняет теперь и завершение proven GATT: точный таймер
ожидания disconnect, close+settle, сохранение lease при исключении. Все поля и таймер
только в памяти; enrollment records, SecretStore, ключи и восстановление не меняются.
Никакой GATT/timer не восстанавливается из backup. Повторно пересмотрен этот owner.
Предыдущее описание «только пассивная телеметрия» относится первому диагностическому
коммиту и заменено этим дополнением. TSR/Nav новые состояния также только в памяти.

## Review 3.1.1 / 09.10.2026

DiagnosticJournal добавляет только ограниченные архивные operations.log и
operations-hud.log в прежнюю diagnostics; эти файлы не являются настройками,
не запускают запись/команды при восстановлении и удаляются общей очисткой.
DiagnosticBundle экспортирует только фиксированный allowlist с прежней редактирующей
обработкой. HudLcaPatchActivity не меняет существующий last-report.txt, но пишет
этапы и отказ через диагностическую очередь; полный вывод ошибки туда не сохраняется.
AndroidCentralTransportV2 меняет только пассивную телеметрию и временную метку
ожидания disconnect; существующее enrollment-хранилище и восстановление не меняются.
BackupDefaultsMigration явно разрешает 335…343 → 344 без новых ключей/defaults;
неизвестные и будущие схемы по-прежнему отвергаются. Эти пять владельцев пересмотрены.
Статическая инвентаризация не является аппаратной приёмкой backup/restore.

## Review 3.1.0 / 08.10.2026

HudPanelSettingsActivity сохраняет boolean options.hideDuringVolume в существующем
документе каждого HUD-элемента; отсутствующее значение=false. JSON/copy/import
сохраняют options целиком. Новых namespaces и файлов нет. HudCanvasView только
читает эту настройку; состояние громкости остаётся временным и не экспортируется.
HudLcaPatchActivity сохраняет прежний last-report.txt; DiagnosticJournal удерживает
короткий структурированный отказ в прежнем incidents.log, без shell output/секретов.
DiagnosticBundle меняет только подпись возраста исторического action-tail.
BackupDefaultsMigration принимает335…342→343, сохраняет прежние defaults и запрет
неизвестных/будущих схем. Пересмотрены эти шесть владельцев; остальные fingerprints
не меняются. ANCS и Helper заморожены. Runtime/KX11 backup-приёмка отдельно.

## Ревизия 05.10 — HOME пассажира и главное меню

Preferences добавляет boolean passengerLauncherAutoStart в прежний основной DE
namespace, default false. Полный typed snapshot и PortableBackupDefaults получают
его автоматически; отдельного списка экспортируемых полей нет. Старые эффективные
значения/неизвестные ключи сохранены; добавлен явный переход 335…338 → 339 (3.0.6).
Новый отсутствующий ключ остаётся false, явное true сохраняется. Ни preview, ни
чтение/проверка архива не запускают HOME. Решение принимается один раз основным
процессом после unlock из durable application-context, не из черновика редактора.

SettingsHubActivity меняет выбор layout и пояснение Apply/Cancel, не свои файловые операции.
StatusWidgetApplication добавляет процессный запуск сервиса, crash/archive пути
не меняются. VehicleButtonController различает закрытие устаревшего reader и
реальный отказ текущего; namespace/buttons/policies не меняются. BackupDefaultsMigration
меняет только перечисленные допустимые схемы; пути/порядок materialize прежние.
Fingerprint этих владельцев пересмотрен по этой ревизии, не как доказательство
работы на машине или полноты фактического архива.

Дата: 03.10.2026. Основание: аудит 16.09 и требование пользователя автоматически
включать все новые параметры. Статус: подготовлены инвентаризация, защита от
непроверенных изменений источников хранения и контракт. Новый архиватор,
координатор записи и аварийное восстановление ЕЩЁ НЕ реализованы. Старый JSON
не стал полной копией. APK не собирался. Утверждение «всё восстановимо» запрещено
до прохождения перечисленных ниже испытаний.

## Граница полноты

Включаются все постоянные настройки Natro, вложенные документы целиком, значения
по умолчанию, ID, порядок, ссылки, все сохранённые варианты, пользовательские
ресурсы, доступные секреты и собственные данные нашего модуля Navigator.
27 имён preferences Natro и одно имя модуля Navigator перечислены в
[storage-inventory.json](storage-inventory.json). Список имён не равен списку
настроек. Каждый источник экспортирует все ключи с исходным типом, а не вручную
отобранные параметры. Новое поле документа сохраняется вместе с документом.
Неизвестные поля не отбрасываются при проверке и преобразовании.

Сохранение исходных байтов документа само по себе не доказывает его пригодность:
каждый действующий декодер должен иметь строгий проверочный режим, который
сообщает о пропуске, fallback, дубликате ID или замене defaults. Например,
LauncherBackdropStore.load сейчас пропускает непригодные элементы и очищает
результат при ошибке; такой путь нельзя использовать как доказательство полноты.

Системные разрешения, Bluetooth-bond, Android HOME, AppOps и действительность
внешних токенов не гарантируются архивом приложения. Их сохранённое описание
и повторная проверка обязательны. Чужие очереди команд/идентичности не активируются.
Состояния сторонних приложений, включая частные данные Яндекса, не входят в
обещание «все настройки Natro». Отчёт показывает эту границу до восстановления.

## Автоматическое включение новых данных

1. Единый интерфейс постоянного хранилища принимает namespace, schemaVersion и
   типизированные значения. Все новые ключи попадают в архив автоматически.
   Неизвестное значение или тип — ошибка, не пропуск. Секретность всего архива
   обязательна: новое поле не должно случайно оказаться в незашифрованном JSON.
2. У ресурса есть категория: постоянная настройка, переносимый секрет,
   привязанное к установке состояние возврата, архивный журнал или временный кэш.
   Добавление постоянного параметра не требует изменения экспортного списка.
   Изменение смысла/формата требует версии и миграции, даже если поле уже попало
   в архив автоматически.
3. Существующие хранилища подключаются адаптерами без переименования текущих ключей.
   У каждого адаптера строгие snapshot/validate/stage/readBack и правила активации.
   Нельзя временно считать новую область включённой только из-за записи в реестр.
4. Управляемые пользовательские файлы включаются рекурсивно. URI импортируется
   как файл с собственной ссылкой архива; ссылка на исходный документ недостаточна.
5. Проверка исходников tools/check_backup_inventory.py отслеживает новые,
   изменённые и удалённые владельцы хранения во всех production Java/Kotlin
   модулях. Изменение requires review; автоматического обновления baseline нет.
   Это сигнал для ревизии, НЕ доказательство исчерпывающего анализа: native-код,
   reflection, новые API и команды shell требуют отдельного контроля.
6. При создании копии дополнительно инвентаризируются фактические области CE/DE:
   shared_prefs, files, no_backup, databases, app-specific external storage,
   зарегистрированные общие каталоги и URI. Каждая находка получает политику.
   Неучтённый объект блокирует статус полной копии; его нельзя молча пропускать
   или автоматически активировать при восстановлении.
7. Не исключать файлы по одному расширению .tmp/.bak: такой файл может быть
   единственным оригиналом или частью незавершённой операции. Решает владелец.

## Файловые группы и внешние владельцы

| Группа | Обязательное действие |
|---|---|
| user_presets | Все файлы, названия, порядок при наличии; отделить личный снимок от визуального профиля |
| adb_key / adb_key.pub | Зашифрованный архив пары, проверка соответствия; отдельно проверка авторизации сервера |
| APNs metadata + .p8 | Строгая расшифровка с имеющимся Keystore, экспорт секрета, повторное шифрование новым локальным ключом |
| MQTT/Sprut/HA и Intent | Все секреты и определения правил; никакого исполнения при проверке |
| spruthub_catalog_v1.json и MQTT catalog | Метаданные/связи сохраняются, показания не считаются свежими |
| ANCS-icons | Каталог и фактические значки из выбранного внутреннего/общего места |
| customFontUri | Байты шрифта, тип/размер/хеш, перепривязка URI; недоступность — неполнота |
| Natro-Incoming | Завершённые файлы; незавершённые передачи явно отмечены, не выдаются за целые |
| diagnostics | Отдельная архивная часть; active marker не возобновляет запись/команды |
| navigation_graphics, artwork, runtime HUD | Кэш/диагностика с явной политикой, не активное состояние |
| natro_floating_window_v3 | Собственная геометрия окна Navigator через согласованный протокол снимка |
| MediaInputPatch.PATH + .natro-backup | Внешний системный патч и оригинал: учёт доступности/хеша; обычное восстановление их не устанавливает |
| HUD/LCA patch | После внедрения: оригинал, версия/хеш, выбранный режим и журнал операции; отдельное применение |

Последние две строки нельзя подменять сохранением checkbox. Пользователь
подтвердил, что приложенный HUD/LCA-патч скрывал машинку на его автомобиле;
это подтверждение эффекта на его машине, а не универсальная совместимость.

## Значения по умолчанию и типы

Хранить одновременно явно записанный набор и эффективный снимок настроек с
версией схемы/defaults. Отсутствующий ключ отличается от явного false/0/пустой
строки. После обновления прежний эффективный выбор сохраняется либо изменение
показывается в плане миграции. Схема 03.10 принудительно переводит HUD/CLUSTER
в ночь: это объявленная миграция, а не побитовое совпадение со старым архивом.

Boolean, int32, int64, float32, String и StringSet маркируются явно. Long со
значением 1 не превращается в Integer. Float хранится без потери битового
представления. Порядок StringSet незначим, порядок массивов/панелей значим.
Unicode, пустые значения и неизвестные старые ключи сохраняются. Неизвестная
будущая схема не активируется. Удалённое поле остаётся в исходном архиве.

## Согласованный снимок и применение

Снимок требует координации всех писателей и процессов, а не простого обхода
каталогов: flush pending writes, граница ревизии, блокировка изменений на короткое
время или согласованное поколение. Неостанавливаемый писатель делает снимок
непроверенным. Внешний Navigator участвует в том же протоколе; отсутствие модуля
не замалчивается. Старые настройки читаются через адаптеры, без запуска сервисов.

Восстановление: расшифровать → проверить манифест/состав → развернуть отдельно →
строго декодировать/мигрировать → сравнить с ожидаемым результатом → создать и
проверить точку возврата → переключить поколение → проверить чтение → запустить
разрешённые службы. Не выполнять прошлые команды/ADB/патчи и не запускать музыку
как побочный эффект импорта. Все listeners до активации изолированы.

Нельзя обещать атомарность простого набора SharedPreferences.commit(). Для
согласованного переключения нужен общий корень поколений/указатель и перевод
всех читателей/писателей на него либо журнал транзакции с доказанным recovery.
Замена XML под живым SharedPreferences оставляет старый RAM-кэш и не подходит.
Аварийный экран должен открываться до обычных служб и уметь выбрать предыдущий
проверенный набор. Патчи системных библиотек не часть этой транзакции.

## Формат и проверка сохранения

Переносимый зашифрованный контейнер содержит манифест версий, разделы с исходными
типами, файлы, сведения об исходной установке и список исключений/ограничений.
Пароль/ключ восстановления независим от единственного Keystore магнитолы.
Аутентификация покрывает манифест и данные; формат/KDF/лимиты должны пройти
отдельную проверку совместимости Android 9 и тестирование повреждений.

После сохранения закрыть поток и повторно открыть именно внешний файл через
провайдер. Проверить расшифровку, каждый файл и полноту восстановления на чистом
назначении. Share-sheet не доказательство сохранения. До начала проверить
свободное место для staging, архива и точки возврата. Не хранить пароль в логах.
Защита импорта: ограничения размера/числа файлов/распаковки/глубины JSON,
запрет path traversal, абсолютных путей, symlink и дублирующихся путей/ключей.

## Отчёт и критерии готовности

Статусы отдельно: файл сохранён и перечитан; состав полный/неполный; восстановление
данных проверено/не проверено; подключения требуют проверки. Один общий зелёный
индикатор не должен скрывать отсутствие секрета, шрифта или Navigator-блока.
Показывать пользовательские названия разделов и конкретные отсутствующие данные.

Обязательные испытания до принятия нового механизма:
- Пустое назначение с новым локальным ключом; все настройки, пять вариантов
  приборки, сценарии/Intent/кнопки, ссылки, порядок и файлы сравниваются целиком.
- Новый произвольный параметр и файл автоматически входят в копию и возвращаются.
- Новое непокрытое хранилище блокирует полноту; новый модуль требует адаптера.
- Ошибки секретов различаются с отсутствием: текущий APNs.load возвращает null
  и при повреждении; SecretStore может вернуть пустую строку. Для архива нужны
  строгие методы, запрещающие такую подмену результата.
- Неверный пароль, повреждение, нехватка места, ошибка commit, недоступный URI,
  незавершённая передача, отсутствующий Navigator, параллельная запись.
- Прерывание процесса на каждой стадии; после запуска целое старое или целое
  новое состояние, никакого смешанного набора и повторного исполнения команд.
- Старый JSON импортируется только как явно неполный источник с предварительным
  планом; отсутствующие разделы не удаляются. Профили меняют лишь визуальную область.
- Android-тесты CE/DE, Keystore, lifecycle, нескольких процессов, URI и recovery.
  Затем отдельно KX11. JVM/source проверки не заменяют аппаратное испытание.

Подготовка контракта и пять тестов исходного реестра завершены. Все перечисленные
испытания реального нового архива пока OPEN. Нельзя переименовывать их в пройденные
на основании совпадения хешей исходников или количества зарегистрированных мест.

## Дополнение 03.10 — пользовательские иконки и желаемые загрузки

Новый постоянный ресурс files/phone-icon-overrides целиком включается в будущую
полную копию: catalog.json и все соответствующие PNG; пользовательский приоритет
не должен теряться. ANCS-icons/catalog.json теперь содержит source_url; отдельный
automaticDownloadManifest содержит полный перечень ранее найденных приложений.
Нужно сохранить и перечень, и доступные автоматические файлы. После активации
восстановления очередь недостающих загрузок запускается без новых уведомлений.
Без сети остаются записи для повторной попытки; свои отсутствующие файлы означают
неполноту. Подробнее: ../PHONE_NOTIFICATION_ICONS_RU.md. Runtime backup ещё OPEN.

Ревизия scanner: добавлено обнаружение openInputStream; это дополнительно выявило
LiveActivityApnsSettingsActivity (входной .p8), чтение импорта SettingsHubActivity
и чтение графики MediaBroadcastRepository. Это существующие пути импорта, их
назначения уже описаны; импортёр и хранилище новых значков добавлены отдельно.

## 03.10.2026 — пассажирские панели

Все passengerPanel*, passengerFavorites*, passengerAllApps* и
passengerSystemAppsDefaultApplied принадлежат существующему основному prefs.
Включать динамические документы избранного, включая сохранённые после удаления
из коллекции, новые scope PASSENGER внутри сценариев и эффективные defaults.
Никаких новых исключений. Локально проверен JSON round-trip новых значений;
общая полнота архива от этого не считается доказанной. Активация UI только после
успешного завершения восстановления. Изменённые PresetsActivity/SettingsHubActivity
добавляют пересогласование пассажирской службы; формат источника не меняют.

## 03.10.2026 — отдельный HOME пассажира

Ревизия Preferences: новый профиль автоматически отображает собственные launcher*
ключи в passengerLauncher.* в том же DE preferences. Глобальные player/auto-resume
и system-bar политики сохраняют старые ключи. Defaults собственного HOME совпадают
с основным; данные основного профиля не копируются и не перезаписываются.
Профильный migration marker и legacy snapshot также раздельны; пассажирский запуск
не потребляет перенос основного launcherClimateConfigJson в floatingClimateConfigJson.
Архивировать все новые ключи, неизвестные будущие параметры и документы целиком,
включая TapAction.PASSENGER_HOME и сценарный scope PASSENGER_LAUNCHER. Это добавочные
значения существующих документов; старые значения и ID не переименовываются.
JVM проверяет типы, независимость, существующий JSON round-trip и этот случай
миграции. Изменение fingerprint Preferences принято после этой ревизии; ни оно,
ни тест JSON не закрывают общий gate полного runtime backup/restore.

## Реализация 3.0.2: ревизия владельцев хранения

Новые BackupFiles/Archive/Transaction/Maintenance/Storage и FullBackupActivity
владеют только зашифрованным экспортом, приватным staging и журналом
DE/no_backup/natro-backup. Последний исключён из рекурсивного снимка во избежание
самокопирования; исходные rollback поколения сохраняются отдельно. Рекурсивные
CE/DE namespaces включают новые пользовательские файлы и типы автоматически.
PortableBackupSecrets экспортирует расшифрованные секреты только внутрь
зашифрованного контейнера, PortableBackupDefaults читает defaults без записи.
RuntimeSnapshotPreferences получил явный flush; bootstrap проверяет журнал до
контроллеров. Старые telemetry/startup snapshot при импорте архивируются как история.
SettingsAppearance хранит natro_settings_ui_v1 (theme/textSp), автоматически
включаемое typed-адаптером. Остальные изменённые редакторы получили общий UI-host,
ключи и defaults их панелей не изменены. HudLcaPatchActivity сохраняет только отчёт
в files/hud-lca-patch; системные оригиналы остаются отдельным внешним владельцем.
NavigatorBackupBridge владеет только natro_floating_window_v3 и своим AtomicFile
журналом; FloatingWindowController останавливает запись геометрии на период операции.
Новый протокол не читает и не экспортирует частные данные Яндекса.

Статус реализации/испытаний уточняется по результату Android CI. Исторические
утверждения «ещё не реализовано» выше относятся к состоянию до команды собирать.
Сверка fingerprints ниже выполнена после этой ревизии, а не заменяет проверку
декодеров, Keystore, URI, crash recovery и устройства KX11.

Ревизия 04.10: Navigator считает AtomicFile .bak частью незавершённой транзакции,
схема окна и defaults не меняются. Maintenance отвергает процесс без имени вместо
неопределённого доступа. Typed-адаптер запрещает неявное приведение JSON типов;
это ужесточение проверки формата 1 до первого выпуска. Новых исключений нет.

Точка отката теперь связывает снимок участника Navigator с UUID конкретного
файлового поколения. Отдельный общий navigator-rollback.json не используется: он
мог рассогласоваться при повторном откате. Новые generation metadata остаются
в исключённом maintenance-каталоге; экспортируемые данные и defaults не изменены.

### 04.10 — черновики редакторов и журнал «Применить»

SettingsPreferences не переименовывает ключи и не переносит XML. Черновики находятся
только в памяти: существующие runtime-читатели получают временное превью, а рабочие
SharedPreferences меняются по явному «Применить». InstrumentPanelStore сохраняет
свои прежние DE namespace и поля; вложенные изменения включаются в родительский
черновик. Поздние callbacks закрытого/пересозданного редактора отвергаются.

SettingsApplyJournal использует существующую исключённую область
DE/no_backup/natro-backup/settings-apply-v1.json и тот же maintenance.lock. Перед
изменением рабочего набора записываются типизированные старые/новые значения только
затронутых ключей; PREPARED восстанавливает старые значения, APPLIED сохраняет новые.
Отсутствие ключа не подменяется false/0/пустой строкой. Recovery запускается до
контроллеров и перед снимком backup; XML живого SharedPreferences не заменяется.
Неожиданное значение/схема блокирует восстановление, не угадывается и не теряется.
Постоянные темы, размер текста, выбранные разделы и прокрутка остаются в прежнем
natro_settings_ui_v1 и включаются целиком. Шрифты пользовательских панелей не меняются.
Это ревизия политики новых владельцев, не заявление о пройденном crash/KX11 gate.

Recovery журнала Apply выбирает CE через application context и DE через
createDeviceProtectedStorageContext, без скрытого Android API. Приложение с DE
в качестве default context отвергается явно, как в BackupStorage. Имена, типы,
defaults и формат журнала не изменены. HUD/LCA сохраняет тот же last-report.txt;
новые сообщения ошибок не означают наличие резервного оригинала на устройстве.

FIELD-006: встроенный LocalImagePicker только читает выбранный PNG/JPEG; исходная
папка/URI не становится постоянной зависимостью и не архивируется целиком. Итоговые
байты по-прежнему нормализует PhoneIconImporter и сохраняет прежний override-store
files/phone-icon-overrides. Отказ/отмена выбора ничего туда не записывает. Новых
ключей, defaults или формата каталога нет; общая полнота backup остаётся OPEN.

### 04.10 — ревизия продолжения и выбор архива без DocumentsUI

Сверены владельцы с последней принятой inventory-ревизией 25bef97:

| Владельцы | Данные / решение |
|---|---|
| Preferences, InstrumentPanelStore | Прежние DE names/keys/types; SettingsPreferences направляет изменения в RAM-черновик, затем в явный Apply. Новых defaults и миграций нет. |
| SettingsApplyJournal, BackupMaintenance | Типизированный журнал Apply и recovery до снимка в уже исключённом DE/no_backup/natro-backup. Никакого нового исключения постоянных настроек. |
| HudPanelSettingsActivity | Общий черновик и preview, deferred внешние команды по root Apply; имена документов и defaults прежние. Ответы команд используют application context. |
| LiveActivityApnsSettingsActivity, PhoneConnectorSettingsActivity | Общая оболочка диалогов; пути .p8, шифрование, назначения и формат сохранённых полей не менялись. |
| SettingsAppearance, SettingsHubActivity | Навигация по вложенным формам, выделение/прокрутка; прежний natro_settings_ui_v1, без новых исключений. |
| HudLcaPatchActivity | Прежний files/hud-lca-patch/last-report.txt; различение ошибок UI не доказывает наличие системного оригинала. |
| LocalImagePicker | Читает выбранное изображение; полный источник/папка не включаются в архив, нормализованный override сохраняется прежним импортёром. |
| FullBackupActivity, BackupFilePicker, BackupLocalFiles | Внешний зашифрованный экспорт в Natro-Backups выбранного носителя и чтение выбранного .natrobackup. Это выход архиватора, вне managed roots, без рекурсивного включения прошлых копий. |

Встроенный выбор используется при отсутствии системного DocumentsUI. Экспорт
не предлагает app-specific каталог, удаляемый вместе с приложением. Чтение папок
в фоне ограничено 20 000 записей; канонические пути не выходят за выбранный корень.
CREATE_NEW запрещает перезапись старой копии и конечной символической ссылки;
подмена каталога Natro-Backups ссылкой отвергается. Перед экспортом по-прежнему
проверяется расшифровка приватного архива, после записи перечитываются внешние
байты и сравнивается SHA-256. Частичный локальный файл удаляется при ошибке;
невозможность удаления остаётся ошибкой, а не успешным сохранением.

Ввод пароля, выбор файла и его проверка не применяют восстановление. Отмена и
отказ разрешения очищают ожидающий пароль; результат чтения закрытого диалога
игнорируется. Формат/defaults/ключи не изменены. Текущий validate по-прежнему
отклоняет архив другой defaultsSchema; совместимость между версиями требует
отдельной миграции и не заявляется этой ревизией. Полнота runtime-снимка,
Keystore, участие Navigator и аппаратное восстановление KX11 остаются OPEN.

В этой же ревизии SettingsAppearance корректирует только цвет подписи
CompoundButton в теме настроек; пользовательские renderers исключены прежним
PREVIEW_TAG. HudLcaPatchActivity блокирует запись до полного подтверждения
совместимости; путь/назначение last-report.txt и внешний оригинал не меняются.
Новых namespaces, ключей, defaults или экспортных исключений эти изменения не вводят.

### 04.10 — результат проверки HUD/LCA в общем журнале

HudLcaPatchActivity добавляет к уже существующему DiagnosticJournal этап, exit,
truncation, режим и класс ошибки. Источник/назначение last-report.txt, системные
оригиналы и политика их восстановления прежние; shell output в общий журнал
не копируется. Fingerprint этого владельца пересмотрен по этому изменению.


### 04.10 — черновики подключений и полный путь копии, кандидат 3.0.4

Эта ревизия заменяет приведённое выше ограничение «только одна defaultsSchema»
и статус отсутствия runtime-адаптера; прежние записи сохранены как история.
Схемы 3.0.2/208021335 и 3.0.3/208021336 явно принимаются версией 3.0.4/208021337.
Старые отсутствующие main/passenger keys материализуются из declaredMainDefaults;
явные и неизвестные значения имеют приоритет, новые поля получают свои defaults.
Неизвестная/будущая схема и несовпадение sourceVersionCode/defaultsSchema запрещены.
Форматы документов, namespace, значения defaults и пути действующих настроек
этой ревизией не переименованы. PortableBackupDefaults исправляет Object-методы
изолированного proxy: сбор defaults больше не падает при регистрации в WeakHashMap.

Ревизия владельцев хранения перед обновлением fingerprints:

| Владельцы | Данные и политика |
|---|---|
| Preferences, RuntimeSnapshotPreferences, StartupWorkCoordinator, AutomationStateStore, AutoHoldStateRepository, ScreenReservationStateStore, DriveModeSettings, InstrumentPanelStore/InstrumentOemController | Все прежние CE/DE имена и типы. Открытие через BackupPreferences сохраняет runtime-снимки/историю и добавляет общий барьер записи. |
| MediaAutoResumeController, MediaPlaybackHistoryStore, NavigationCollectionDemand, MqttShortcutCatalogStore, PackageReplaceBleRecoveryGate, LiveActivityProvisioningStore, Geely HudModeFallbackStore/BootReceiver | Та же замена способа открытия; defaults/назначения не изменены. Исторические таймеры и показания не становятся свежими. |
| servicemode AppsToHideStorage/ConnectionStorage/ExcludeAppsStorage/NatroSelfVisibility/PinStorage/ServiceModeJournal, shell ConnectionStorage | Прежние endpoints/PIN/журналы владения; барьер без переименования. Правила устройства и запрет считать endpoint разрешением прежние. |
| ButtonRouteRestorer, VehicleButtonController, MediaButtonController, новый ButtonSettingsDraft | Существующие vehicle_buttons/media_buttons целиком; UI пишет черновик, Apply обновляет только затронутые настройки runtime. drive.selected и другие независимые записи сохраняются. |
| PhoneConnectorSettingsActivity, LiveActivityApnsSettingsActivity, ApnsCredentialStore | Те же поля телефона и CE metadata/APNs blob; ввод не меняет связь. Ключ и метаданные фиксируются вместе по root Apply, отмена не удаляет исходный файл. |
| SettingsApplyJournal, новый SettingsFileChange | Schema 2 принимает schema 1 при recovery. Журнал хранит до 64 малых частных ресурсов вместе с touched preference keys. APNs payload в журнале уже зашифрован, plaintext туда не попадает. Все paths предварительно проверены, maintenance-root запрещён. |
| SettingsAppearance, BackupPreferences, BackupMaintenance | Прежние настройки вида; реестр открытых stores включает ещё не созданные XML. Общий барьер удерживается до остановки процесса, ошибки снимают его. Прямой обход барьера в новом production-коде блокирует source gate. |
| BackupStorage, BackupDefaultsMigration, BackupExternalState, FullBackupActivity | Перенос всей файловой области и строгих typed keys; явная миграция старых defaults. Только известные внешние пути патчей читаются для availability/size/SHA-256; доступные оригиналы копируются в ce_files/restored-system-originals с хешем в имени. Они никогда не устанавливаются в системный путь при импорте. Недоступность/символьные ссылки отмечены явно. |

RuntimePreferenceWriter перед общим барьером сбрасывает принятую очередь;
поздние события удерживаются отдельно, при неудаче обслуживания очередь продолжается.
RuntimeSnapshotPreferences checkpoint выполняется до захвата общего барьера,
чтобы не инвертировать порядок diskLock/write-lock. Фактические процессы затем
останавливаются прежним протоколом same-UID/lease; snapshot сверяет состав и хеши.
Отсутствие DocumentsUI для иконок и архивов покрывает ранее добавленный выбор файлов.

APNs encryption теперь получает случайный IV от Cipher/Keystore, а не передаёт
свой IV при randomizedEncryptionRequired. При удалении удаляются файл и метаданные;
AES alias без APNs signing key сохраняется для возможного rollback зашифрованного
поколения. Путь .p8 и версия encrypted blob остаются прежними. Keystore-адаптер
полной копии переносит секрет и шифрует его новым локальным ключом.

После restore отдельный локальный маркер запрещает автоматический повтор
системных операций MEDIA/SRC/VA/DM/POWER/STAR из импортированных flags. Параметры
не стираются: каждый маршрут снова допускается только после его явного успешного
применения. Ручной запуск музыки не снимает этот запрет. Маркеры проверки находятся
в уже исключённом maintenance-root и сбрасываются новым восстановлением.

LOCAL: исполняемые проверки охватывают ключ+metadata на каждом durable boundary,
удаление/ошибку/conflict, отмену/вложенные ресурсы/старый экран, новые namespaces,
очередь runtime, типы/Unicode/неизвестные значения, миграцию defaults, SHA-256
системного оригинала и отсутствие записи в исходный системный файл. В полном
archive -> decrypt -> validate -> stage -> apply тесте исходные Keystore-ключи
удалены: секреты, APNs, CE/DE файлы и URI восстановлены под новыми тестовыми ключами.
Ключи тестового AndroidKeyStore находятся только в памяти; AES-GCM и EC выполняет
JCE. Это не приёмка аппаратного Keystore/согласования процессов/IPC Navigator на KX11.
Прежние JVM crash-recovery/participant-проверки сохраняются. Финальный CI и
физическое восстановление учитываются отдельно, статический inventory не служит
сертификатом полноты данных на конкретной магнитоле.

## 04.10.2026 — ревизия диагностики 3.0.5

Проверены изменённые владельцы хранения: DiagnosticJournal, ActionRecorder,
PhoneConnectionJournal, StatusWidgetApplication, FullBackupActivity,
SettingsApplyJournal, InstrumentOemController и VehicleButtonController.
У последних четырёх изменены только диагностические точки; prefs, WAL, секреты,
порядок применения, права, vehicle setters и восстановление не расширены.

Новые journal-hud.log, incidents[-hud].log и crash[-hud].log относятся к
существующей архивной группе files/diagnostics. После restore они остаются
архивными свидетельствами и не активируют команды/идентичности. Действующая
action-session marker по-прежнему локальная; журнал событий имеет producer time
и отдельную sequence сессии, рабочий формат JSONL совместим (добавлены поля).
DiagnosticFileSnapshot — ограниченный читатель полных строк, не новый storage.
DiagnosticBundle создаёт только временный cache/exports/Natro-diagnostics-*.zip
из явного списка диагностических файлов; настройки, WAL, APNs, ключи и backup
архивы в него не включаются. Это отдельный отчёт, не полная резервная копия.
При перегрузке/отказе диска утраты отражены счётчиками, атомарность снимка двух
процессов не обещается. Для HUD экспорт явно сообщает отсутствие flush-барьера.

3.0.5 не меняет эффективных defaults. defaultsSchema следует versionCode;
после проверки добавлен разрешённый переход 208021337 → 208021338, сохранены
ранее рассмотренные 335/336. Неизвестные будущие версии отклоняются. Изменения
fingerprint внесены после этой ревизии; аппаратные backup gates остаются OPEN.


## Review 3.0.7 / 06.10.2026

Проверены изменённые владельцы AdbTransport, InstrumentOemController и
ButtonRouteRestorer. Новых ключей/preferences/файлов пользователя, defaults,
миграций и адаптеров нет: RSA adb_key/adb_key.pub и их правила остаются прежними;
hide_oem_white_bar/oem_white_bar_owned и сохранённые disable_default читаются и
пишутся прежними адаптерами. Streaming shell не создаёт временных файлов на машине.
Новые очереди, поколения, наблюдённый DIM priority и счётчики существуют только
в RAM и не являются переносимыми настройками. Fingerprints обновлены после этой
проверки, не в качестве доказательства runtime backup/restore.

## Review 3.0.8 / 07.10.2026

AdbTransport сохраняет прежние adb_key/adb_key.pub и адаптеры; исходник AdbCrypto
теперь включён из прежней зависимости1.3 и пишет ровно переданные файлы, новых
roots/ключей нет. HudLcaPatchActivity сохраняет прежний last-report.txt, добавляет
этап диагностики. AndroidCentralTransportV2 меняет только lifecycle mapping.
HudRuntimeStatusStore пишет прежний DE hud-runtime-status-v1.txt одним асинхронным
писателем, с заменой ожидающего snapshot. Это прежняя исключённая runtime-диагностика,
не активируемая после restore; пользовательских данных в очереди нет.
BackupDefaultsMigration явно разрешает рассмотренные335…340→341 при неизменном
формате/defaults3.0.7. При переносе сохраняются effective defaults и explicit keys;
будущие/неизвестные схемы отвергаются. Fingerprints пересмотрены для этих шести
владельцев; статический inventory не подтверждает аппаратное восстановление.

## Review 3.0.9 / 07.10.2026

PackageReplaceBleRecoveryGate использует прежние DE namespace и markElapsed.
Ранняя проверка PackageInfo не вводит ключей; duplicate broadcast не продлевает
процессный барьер. Метка остаётся монотонным временным состоянием, прежний отказ
при reboot/stale сохранён. InstrumentOemController добавляет read-only task probe
и RAM-флаги, прежние hide_oem_white_bar/oem_white_bar_owned и адаптеры сохранены.
BackupDefaultsMigration принимает рассмотренные335…341→342 при прежних defaults
и формате; новые/будущие схемы запрещены. Пользовательские иконки/каталоги не
меняются: renderer только читает их в фоне. Пересмотрены fingerprints именно
этих трёх storage owners; это не доказательство полного runtime restore.


## Review 2026-10-14 — full composition, slog2 и proc trace

Пересмотрены DiagnosticsActivity, SystemFileDump и AdbTransport по diff.
DiagnosticsActivity передаёт лимит вывода через новый Shell adapter; preference,
defaults и места сохранения не меняются. SystemFileDump добавляет bounded entries
в тот же cache/exports ZIP (retention2); исходные системные файлы и восстановление
не затрагиваются. AdbTransport добавляет одно GET-соединение для существующих
slog2; RSA adb_key/adb_key.pub и endpoint прежние. Новых storage owners, managed
roots, persistent settings, секретов и defaults migration нет. Fingerprints
обновлены только у этих трёх рассмотренных owners. Runtime restore gate OPEN.


### Review 3.2.0 — версия defaults

BackupDefaultsMigration явно допускает335…352→353 с прежними keys/types/defaults.
Неизвестная334 и будущая354 отвергаются; тест проверяет перенос explicit values.
Новых settings и storage roots нет; fingerprint migration пересмотрен.
Аппаратная приёмка restore остаётся OPEN.


## Review18.10.2026 — DimUartCapture / DimUartRecording

Пересмотрены DiagnosticsActivity и два новых storage owners.
files/diagnostics/dim-uart/session-* содержит только архивные свидетельства:
metadata/state/terminal/uart.log; не prefs, не executable queue. На старте нет
чтения marker для запуска: восстановленные данные не активируют ADB/команды.
Прежняя группа files/diagnostics включается рекурсивно в архив по действующему
контракту; новых adapters/keys/defaults/migrations нет. Макс3×64МиБ, ручное
удаление. cache/exports Natro-dim-uart-* хранит2ZIP; в restore payload не входит.
Экспорт блокируется активным сбором; symlink не читается. Transport ключи прежние.
Fingerprints обновлены только после этого review. Runtime backup FIELD OPEN.


## Review3.3.3 — defaults и непрерывный журнал DIM

Разрешён переход335…365→366 при прежних keys/defaults. Same366 сохраняет XML,
explicit/unknown/effective значения сохраняются;334/367 и source/schema mismatch
по-прежнему отклоняются. Добавлена отдельная regression333, прежние тесты
сохранены. Пересмотрен fingerprint только BackupDefaultsMigration после review.
Запись DIM использует ранее рассмотренную архивную diagnostics, не автозапуск
после restore. Физическая приёмка backup/restore OPEN.


Review24.10: media_like/navigator_alice — два новых значения существующего
shortcut target, без новых prefs/files/defaults. LauncherShortcutStore хранит
их прежним JSON, полный adapter сохраняет неизвестные/новые target. Проверены
изменения LauncherMediaController и LauncherHomeSurface: новый путь вызова,
новых persistent данных нет. Runtime backup FIELD OPEN.

Review3.3.7: разрешён явный переход335…369→370 при прежних keys/types/defaults.
Тест проверяет effective/unknown values и отклонение334/371; предыдущие тесты
сохранены. Fingerprint обновлён только BackupDefaultsMigration после review.
Новые actions сохраняются прежним shortcut JSON. Runtime restore FIELD OPEN.


Review3.3.8: MediaAutoResumeController добавляет только отмену RAM-продолжения
музыки после ручной команды; его prefs/keys/defaults/boot policy не меняются.
Alice request/session tokens и таймер2s — толькоRAM, не backup/restore state.
BackupDefaultsMigration335…370→371 и same371 рассмотрены: explicit/effective/unknown
сохранены,334/372 отклоняются. Новая отдельная regression, прежние сохранены.
Fingerprints пересмотрены только этих двух owners после diff review.
Статический inventory не является FIELD полной резервной копии/restore.

Review BOOT-338: yandexReceiverAttempts(int,0) добавлен в прежний
launcher_media_auto_resume_state, reset при новом capture/plan; не разрешает
восстановление старого plan/foreign token. Общий prefs adapter включает ключ;
историческое runtime state после restore не запускает команды. Остальные
ключи/defaults/target/manual guard прежние. LauncherHomeSurface/MediaResumeCommand/
Browser resolver не добавляют storage roots. Fingerprint MediaAutoResumeController
пересмотрен повторно по diff; FIELD backup OPEN.


Review3.3.8 CI40094274695: migration regression выявила отсутствующий source370
в явном allowlist (target371 уже был). Добавлен только335…370→371, формат/keys
не меняются, будущие схемы по-прежнему запрещены. Fingerprint пересмотрен
после проверки условия; прежние migration tests остаются обязательными.
