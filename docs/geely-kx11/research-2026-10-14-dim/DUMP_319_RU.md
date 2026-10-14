# Дамп 3.1.9 от 14.10.2026: FIELD и следующий шаг

Архив Natro-system-0882d46f-9aa2-4202-9bac-13d25fdc4688.zip,
SHA256 b2864b8f65c14312bfa6eb96ad6385b49ea63d8a227d53bca151eaae761931c1.
CRC и все payload SHA256 из manifest проверены. Firmware:
qti/kx11_high/kx11_high:9/20.24.10.23024.41151/539:userdebug/test-keys.
Метка пользователя navigation, не автоматически подтверждённый режим.
Сбор26.876с, su_0,22/22 QNX файла,92 PID,6 mappings. Два отсутствующих
Android module paths — альтернативные пути; dim_dtc.ko получен из vendor.

## Что подтвердилось и что не подтвердилось

Inventory-first устранил прежний обрыв после25 PID. Mappings сохранены:
wfd_be40984=81743, wfd_be40985=85497, screen40986=111804,
openwfd_server184363=175089, cluster_controller1077323=97044,
sc_qnx_vip_proxy1163346=93691 байт. Все ниже256KiB, truncation не происходил.
Строго по числу столбцов 147 строк, начинающихся с0x, имеют не15
полей. Это диагностическая статистика, не число потерянных строк/байтов.
Не все нарушения обнаруживаются только числом полей: встречаются склейки
hex адресов внутри одного поля. Все6 snapshots не проходят новый format gate.

В3.1.9 статус read подтверждал завершение transport read, не CSV-структуру.
SOURCE: добавлен validMappings: точный header,14 hex полей, object_name,
финальный newline, без replacement/control characters. Запятая в имени объекта
допустима. При нарушении read_content_invalid, aggregate partial; bytes сохранены,
обход остальных процессов продолжается. Это исправление ложного успеха,
НЕ исправление первопричины потери данных. Структурно валидный snapshot тоже
не гарантирует полноту, атомарность или отсутствие пропуска целых строк.

## Где искать причину

QnxFileReader.procText задаёт offset=data.size(), запросы по2048байт, учитывает
первое числовое поле ответа. Другие поля reply не сохраняются. exact() переносит
ровно объявленный payload, не trim/regex; transport передаёт AdbStream bytes.
11 общих QNX файлов побайтно совпали с3.1.8. Это аргумент против всеобщей порчи
бинарного транспорта, но не доказательство отсутствия ошибки на procfs.

Повторяются схожие позиции повреждений в нескольких процессах, в частности
32249 у двух wfd_be и screen. Приоритетная гипотеза: позиционирование/генерация
динамического procfs через file service. Ни сохранённый ZIP, ни обычный qcl
GET для статических файлов не доказывают семантику дополнительных reply fields.
Не менять offset на непроверенное поле и не маскировать пропуски склейкой строк.

Следующий диагностический probe должен сохранять bounded request offset,
count, полный control reply и actual payload count; проверить overlap одного
малого участка при разных chunk sizes. Одинаковые данные/совпадение overlap
повышают уверенность, но при изменении процесса не доказывают transport bug.
Нужен отдельный анализ pmap как компактного metadata-кандидата; не читать as/ctl.

## Зацепки для крыльев

В целых строках mappings реально присутствуют:
- wfd_be: libopenwfd.so, libdisplay_shim_util.so, libuhab-qvmhost.so;
- screen: libWFD.so.1, libopenwfd_qnx.so;
- openwfd_server: libdalconfig-sa8155_adp_star_v1.0.0.so, libhalmdputil_s8155.so,
  три ранее исследованных UART библиотеки.

Свежая libdalconfig.so.1 имеет SONAME libdalconfig-sa8155_adp_star_v1.0.0.so:
название runtime не является само по себе доказательством отсутствующего файла.
Побайтовое равенство обоих runtime путей пока не проверено.
libplanedef.so содержит query_plane_def/query_omx_plane_def, вычисления stride,
buffer size и color format. Plane здесь относится к раскладке пиксельного буфера;
это не найденный список визуальных слоёв/крыльев.

Наблюдаемые buffer paths: /dev/shmem/slogger2/display.40984,
display.40985, display.40986, display.184363, screen_QC.40986,
openwfd_server_QC.184363, cluster_controller.1077323, а также варианты ..0.
Это names/mappings, содержимого буферов в ZIP нет. PID нельзя захардкодить
для следующего запуска; требуется bounded directory inventory с проверкой имени.
DLT Android /mnt/userdata/DLT absent_or_inaccessible; это не отсутствие QNX логов.
QNX config и startup используют /userdata/DLT, наличие файлов не проверено.
В92 PID есть dlt_daemon; отдельные DltLogAdapter/usbdatalogger не обнаружены.
Это не исключает иных имён/встроенного механизма.

QNX slog2info документирует -l filename и -d directory для static snapshots.
Предпочтительный следующий путь — получить bounded копии точных buffer files
через file service, отдельно проверить stat/type/size/readability и декодирование.
Не запускать logger, не менять verbosity, не очищать buffers, не блокировать
display driver. Живые buffer bytes могут меняться: нельзя применять требование
двух одинаковых чтений как единственный способ сохранить такие свидетельства.
Возможность чтения и decoder ещё OPEN.

Recovery log141637байт SHA141639d47a7d62b6844d9d9b01c09f64e0b48c961180df92ce632bb7305d9cc8
побайтно совпадает с историческим источником. Четыре UBWC errors display2/3
на ранних boot timestamps не являются новыми событиями нынешнего сбора.
Android layer names не дают alpha/geometry/pixel content.
Единый overlay с прозрачным центром не исключён; renderer/hide flag OPEN.

## Проверки SOURCE

7 tests tools.tests.test_system_file_dump OK, включая production replay.
Новый gate дополнительно применён к каждому из6 реальных mappings: все отвергнуты.
Backup inventory85owners unchanged; git diff --check clean.
Полная Android CI, новый APK и FIELD для этой правки не выполнялись.

## Первичные источники

- https://www.qnx.com/developers/docs/7.1/com.qnx.doc.neutrino.sys_arch/topic/mappings.html
- https://www.qnx.com/developers/docs/7.1/com.qnx.doc.neutrino.utilities/topic/s/slog2info.html
- https://github.com/zayfod/qcl/blob/master/qcl.pl (GET, не доказательство procfs semantics)
