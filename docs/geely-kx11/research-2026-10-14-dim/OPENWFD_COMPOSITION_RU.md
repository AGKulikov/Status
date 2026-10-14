# OpenWFD: прозрачность и состав кадра, 14.10.2026

## Архивная проверка и штатный DLT-путь

Проверены12 ZIP в upload/diagnostics/wings-evidence:1051 entries с расширениями
txt/log/json/csv/tsv/out, лимит64MiB/entry, превышений не было. Точных строк
QDI_LAYER_PROPERTY_BLEND/configurePipelineBlending/iTransparency=/dest_layerid=
нет. Бинарные логи, entries без этих расширений и вложенные архивы этим
текстовым поиском не охвачены. В ZIP wings-evidence не найдено entries с
окончанием .dlt или компонентом /DLT/; отсутствие runtime событий не доказано.

Новый конкретный маршрут найден в исторических источниках:

| Источник | Размер | SHA256 |
|---|---:|---|
| Deep-Evidence: /etc/dlt_logadapter.conf | 2762 | 3014158cff20b81b0df68daacc71caee6f134bccfe018f83309fe997883653c8 |
| Cruise-Trace20260810: vendor_etc_init.logqnx.sh | 809 | fd1e41b82d029e9bc366787fb57a0ef24ca41986f385681947540e18e93a7664 |
| Gap-Evidence20260815: /var/log/display_recovery_info.txt | 141637 | 141639d47a7d62b6844d9d9b01c09f64e0b48c961180df92ce632bb7305d9cc8 |

DLT adapter config: loggerpath=/dev/shmem/slogger2/, allbuffers=true, level5;
включены screen→SCRE, display→DISP, wfd_be→WFDB, openwfd_server→WSER.
Это настройка маршрута логов, не доказательство включённого MDSS log mask bit4.
Android init.logqnx.sh копирует /mnt/userdata/DLT на USB как QNX log export.
Скрипт не исполнялся: он создаёт каталог/маркер и делает sync, не является
нужной нам пассивной проверкой. Для будущего сбора достаточно сначала проверить
существование/доступность и bounded inventory самого каталога штатным ADB.
Не запускать logger, не менять права/маску и не копировать весь каталог без
ограничений. Текущая доступность и соответствие октябрьской прошивке OPEN.

Recovery log реально содержит109 DS_Layer_SetProperty и89 DS_Layer_SetSurface,
но четыре заголовка ошибок относятся к display2/3 (UBWC ERROR), не display1;
это старый boot/error snapshot, не запись переключения навигационного режима.
Он не доказывает причину нынешней белой полосы и не идентифицирует крылья.
В app/tools и текстовых research docs готового DLT collector route не найдено.

## Дополнение: параметры доходят до выходного blending

STATIC путь продолжен до QDI/DS; это не доказательство физического слоя крыльев.
Локальная функция идентифицирована diagnostic name0x15f518 как
WFD_CommitMgr_configurePipelineBlending. В0x5c21c она читает pipeline+0x568,
проверяет bits0/1/2; в0x5c234 и0x5c3d8 читает global alpha+0x56c.
В0x5c250/0x5c42c вызывает WFD_PixelFormatHasAlpha. Таким образом учёт
alpha формата и global alpha не ограничивается хранением значений в setters.

В0x5c270 выбирается property9, в0x5c280 вызывается QDI_Layer_SetProperty.
Diagnostic string0x15fb38 прямо называет QDI_LAYER_PROPERTY_BLEND и поля
src_layer, dest_layerid, iTransparency. QDI wrapper вызывает
DS_Layer_SetProperty0xdfa98 в0xe9714. Это подтверждённая цепочка до DS setter,
но не полный разбор аппаратного смешивания и не команда для машины.

Появился конкретный кандидат пассивного свидетельства: уже существующие
сообщения configurePipelineBlending/iTransparency/QDI_LAYER_PROPERTY_BLEND
могут связать исходный и выходной слой, если записаны при смене режима.
Их наличие зависит от log mask: перед success log проверяется bit4
MDSS_LogMgr_GetLogLevel (0x5c28c–0x5c290). Маска не менялась.
Поиск по доступным распакованным txt/log/md в natro, dim-318-analysis, upload
совпадений не дал; это НЕ полный поиск по ZIP и НЕ доказательство отсутствия
сообщений в QNX runtime. Не выполнять slog/loglevel команды без оценки
конкретного read-only пути и разрешений.

Для повторного поиска ссылок по всей .text необходимо skipdata=True:
обычная линейная Capstone-дизассембляция может остановиться на данных внутри
секции и дать ложный неполный список xrefs. Совпадение offset само по себе
не доказывает одинаковую структуру; приведённые выше места проверены вручную.

## Дополнение: composition query не является готовым пассивным probe

Последующая проверка выявила обязательные условия, меняющие рекомендацию:

- 0x4ef68–0x4ef70 передают type=2 в AuthenticateClientHandle0x56a7c.
  В0x56b1c–0x56b24 тип зарегистрированного клиента сравнивается с требуемым;
  несоответствие не допускает дальнейшее получение состава кадра.
- После UtilityMgr_GetProperty0x4f0d0 проверяется поле состояния+8.
  При нуле переход0x4f0e4→0x4f358 возвращает ошибку0x7518;
  diagnostic string0x15a40c: Driver has not been locked. Exiting.
  Поэтому автоматическое добавление запроса как read-only диагностики отклонено.
  Драйвер не блокировался, тип клиента не подменялся; эффект блокировки
  на обновление изображения не проверен и не должен проверяться наугад.
- GetAllDisplayNodes0x5b304 перечисляет глобальные display records;
  GetAllPipelinesPerDisplay0x5b4f4 обходит все client records и фильтрует
  порты по display/node (0x5b5f4–0x5b610), а не только по вызывающему клиенту.
  Это область данного OpenWFD server, не доказательство видимости внешнего DIM.
- Host handler44 использует node records размером0x18 и pipeline records0x60,
  затем вызывает host_os_utils_shmem_export0x13573c. В этой сборке helper
  лишь заполняет descriptor: type3 в+0x18 и копию входного адреса в+0x10.
  Это не доказанный kernel export или безопасный кросс-процессный снимок.
- Клиент wfdGetDisplayCompositionInfo_User0xd17c отправляет selector0x2c,
  затем вызывает user_os_utils_shmem_import0xd33c. Таким образом ответ не
  следует трактовать как простой автономный JSON/список метаданных.

Следующий безопасный путь: обычные существующие process/module metadata и
статический разбор источников/выхода. Если потребуется активная диагностика
с блокировкой драйвера, она требует отдельной оценки и согласования, а не
включения в автоматический сбор Natro. Никакого runtime вызова не сделано.

Статус: STATIC, без выполнения QNX программ и команд на автомобиле.
Источник: дамп3.1.8, проверенный в DUMP_318_RU.md. openwfd_server SHA256
50f52f0714d828ead04227686ea916d488c79562f313c87146ff991e1f3e086c.
Адреса ниже — виртуальные адреса данного ELF, не адреса процесса после загрузки.

## Что установлено

Обработчик записи0x135a44 выбирает Host handler из таблицы0x1efd90
по selector message+0x2c; косвенный вызов0x135c60. Таблица содержит47
последовательных Host handlers. Индекс33 — wfdSetPipelineAttribi_Host,
индекс44 — wfdGetDisplayCompositionInfo_Host. Это графический обмен;
тождество с UART-командой навигационного режима не установлено.

Путь scalar setter: client wfdSetPipelineAttribi_User0xbca4 → selector33
→ Host0xa8450 → wfdSetPipelineAttribi0x3c144 → vector setter0x3a5b4.
Имена атрибутов ниже установлены по собственным диагностическим строкам
ELF и соответствующим веткам, а не угаданы по значениям.

| Атрибут | Назначение | Ветка ClientMgr setter | Поле записи pipeline |
|---|---|---|---|
| 0x7726 | SOURCE_RECTANGLE | 0x59ec4 | +0x78, четыре int |
| 0x772d | DESTINATION_RECTANGLE | 0x5a004 | +0x88, четыре int |
| 0x772e | TRANSPARENCY_ENABLE | 0x5a24c | +0x98, один int |
| 0x772f | GLOBAL_ALPHA | 0x5a290 | +0x9c, один int |

Таблица переходов0x15c3f4 содержит14 signed16 offsets относительно0x59e98
с множителем4. Публичный setter обновляет transparency/alpha в server
pipeline+0x568/+0x56c и dirty mask0x40 перед вызовом ClientMgr
(0x3bdb4/0x3bf18). WFD_PixelFormatHasAlpha0x68518 проверяет таблицу
форматов через WFD_GetWfdPixelFormatInfo и поле+0x48.
Это подтверждает инфраструктуру прозрачности, но НЕ использование её крыльями.

## Конкретный диагностический кандидат

wfdGetDisplayCompositionInfo0x4eedc вызывает GetAllDisplayNodes,
GetAllPipelinesPerDisplay и GetPipeInputSource. Последний читает источник
из записи pipeline+0x60 — того же поля, куда ValidateBindSourceToPipeline
записывает проверенный источник в0x5a5bc либо ноль в0x5a5c8.

В функции состава кадра подтверждены чтения:

| Вызов getter | Атрибут | Поле результирующей записи |
|---|---|---|
| 0x4fc40 | 0x772e transparency | +0x4c |
| 0x4fca8 | 0x772f global alpha | +0x50 |
| 0x4fd20 | 0x7726 source rectangle | +0x1c |
| 0x4fd90 | 0x772d destination rectangle | дальнейшую упаковку проверить |

Это более конкретный кандидат для исследования состава кадра, чем слепое
отключение pipeline. Не утверждается готовность безопасного runtime probe:
нужно разобрать ABI, client authentication, размеры ответов, область видимости
и жизненный цикл клиента. Функция не вызывалась на машине.

## Ограничения вывода о слоях

В активном XML для display1 найден LA_GVM0x7815/port3/layer16/Z6.
Один настроенный Android pipeline не означает один слой всей физической
приборки и не исключает единый слой двух крыльев с прозрачной серединой.
Композиция внутри источника или последующее наложение во внешнем DIM остаются
открытыми версиями. Ни renderer крыльев, ни независимый hide flag не установлены.

Следующее: разобрать packing handler44 и границы видимости composition query;
проверить путь источника до физического выхода. При необходимости следующего
дампа сначала исправить потерю process inventory/prefix при64KiB mappings cap.
Не менять alpha слоя16 наугад: принадлежность пикселей крыльев ему не доказана.
