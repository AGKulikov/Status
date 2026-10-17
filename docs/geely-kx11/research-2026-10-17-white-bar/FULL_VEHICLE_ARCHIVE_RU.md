# Полный архив выбранных QNX каталогов и каналов связи

Требования пользователя17.10.2026 15:21–15:25 Europe/Moscow: скачать содержимое
обсуждавшихся каталогов для будущих разработок; Android целиком исключён;
расширить исследование на другие каналы. SOURCE кандидат3.2.5/208021358.
FIELD сбор на магнитоле ещё не выполнялся; файлы удалённых блоков не получены
самим фактом добавления кнопки. Это не полный образ всех ЭБУ автомобиля.

## Содержимое

| Источник | Что собирается |
| --- | --- |
| QNX `.2:8000` через прежний ADB/qconn | Все обычные файлы `/bin`, `/sbin`, `/usr`, `/lib`, `/lib64`, `/etc`, `/scripts`, `/persist`, `/proc/boot`, `/userdata/DLT`, `/var/log`; отдельно `/ramdisk/linux.config` |
| QNX metadata | Полный доступный namespace inventory, процессы, полные mappings выбранных графических процессов, существующие display/cluster slog2 |
| Android как шлюз к блокам | Снимок интерфейсов/VLAN, IP/routing/rules/neighbours, sockets, Binder/HAL services, списки tty/SPI/I2C/rpmsg/remoteproc, существующие отфильтрованные журналы каналов |

В QNX inventory552935 найдены текущий DIM_Slog.log и архивы DIM_Slog_Boot…zip
именно в `/userdata/DLT`, а не в отсутствовавшем Android `/mnt/userdata/DLT`.
Также перечислены `/persist/dim_config`, cartype.cfg, openwfd_core/screen,
uart-display-config/swdl_display и PSD resources. До этого сборщик имел только
их имена. Новая кнопка пытается получить содержимое выбранных деревьев.

Подтверждённый исторический обмен Android `.15:50335` ↔ AP/VP `.1:50500`
не следует смешивать с QNX `.2`. SOME/IP/IPCP, QNX SPI4 bridge и UART/display
маршруты — разные интерфейсы; наличие имени, сокета или файла не подтверждает
физическую команду и не даёт автоматически доступ к файловой системе peer.
Не производится port scan, поиск паролей, открытие новых сервисов или чтение
сырого CAN/SPI/UART. Эта версия сохраняет доступные свидетельства каналов,
а не объявляет все каналы обнаруженными/все прошивки скачанными.

## Целостность и границы

`qnx/tree` сохраняет структуру путей. Payload streaming: 2KiB qconn frames,
локальный spool одного файла и ZIP; размер дерева не удерживается в памяти.
Нет квоты числа/глубины/содержимого QNX файлов и общего таймера. Проверяются
два чтения, stat до/после; локальные SHA-256 и размеры каждого ZIP entry
записываются в дисковый manifest.tsv. Изменившиеся/частичные байты сохраняются
с отдельным статусом. `full-tree.tsv` содержит пропуски, aliases, отказы и EOF.
Протокольная потеря синхронизации прерывает текущий сбор, не вызывает blind retry.
Недостаток места, завершение процесса или аппаратный сбой могут помешать
финализации ZIP; это не атомарный снимок и не готовая копия для восстановления.

QNX open flags RDONLY|NONBLOCK|NOCTTY|NOFOLLOW|LARGEFILE=`0x9880` сверены с
[rust-lang/libc nto](https://github.com/rust-lang/libc/blob/main/src/unix/nto/mod.rs).
Семантика NOFOLLOW/nonblocking проверена по
[QNX7 open](https://qnx.com/developers/docs/7.0.0/com.qnx.doc.neutrino.lib_ref/topic/o/open.html).
Формат open/stat/read/directory соответствует
[nfogh/qconn fileservice, b8afec6](https://github.com/nfogh/qconn/blob/b8afec687b3f3354fab6228aa9f70aee63a9948f/src/fileservice.ts).
Это новые флаги относительно прежнего FIELD: аппаратная совместимость OPEN;
при отказе нет fallback, снимающего NOFOLLOW.

Для корней проверяются предки, для directory listing после metadata-open
переоткрывается directory handle с проверкой dev/inode. Symlinks не следуются;
валидированный readlink/lstat в используемом диалекте отсутствует, поэтому
цель отвергнутой ссылки не выдумывается. Дубликаты directory identity отмечаются.
Подмена родительского пути между запросами не исключена: snapshot live.
Special objects могут открываться только для stat, их содержимое не читается.
Не выбраны `/dev`, `/proc/PID`, VM Android/Linux disk images и firmware block devices.
Имена IPC в общем inventory — metadata, не запросы к device endpoints.

Android channel snapshots ограничены1MiB на команду, logcat последними6000строками
до фильтра, kernellog текущим кольцом; truncation/exit записываются. Отсутствие
записи не означает отсутствие канала. Снимки не включают Android disk image.

## Доступ и хранение

Одна операция через общий mutex дампов. Удалённый запуск требует сохранённого
сопряжения и прежнего разрешения remote_commands; отзыв доступа отменяет сбор.
Кнопка доступна в Диагностике и LAN web UI. Два последних Natro-vehicle ZIP
хранятся в cache/exports; пользовательская копия Download/Natro не удаляется.
Архив содержит закрытые файлы и может содержать персональные данные; хранить
лично, raw payloads не публиковать в GitHub.

Большие экспортные ZIP скачиваются без Blob в памяти Safari: POST выдаёт
короткую HttpOnly/SameSite cookie, привязанную к export+owner. URL содержит
только UUID, без постоянного pairing token или cookie secret. При GET проверяются
expiry и действующее сопряжение; во время передачи — отзыв сопряжения.
Поддержаны long byte ranges. HTTP watchdog ограничивает бездействие, а не
длительность передачи с прогрессом. Для старта после истечения10min нужно снова
«Проверить экспорт / скачать». Полевой Safari large-file gate остаётся OPEN.

## Проверки

Локальный replay реального production Java проходит под64MiB heap: файл20MiB
полностью дважды прочитан; ZIP размеры и SHA всех entry проверены. Проверены
пустой файл, изменённый stat, special/link exclusion, invalid names/path traversal,
alias identity, transport loss и сохранение4096байт частичного файла с failed EOF.
Cookie: непредсказуемость, expiry, другой export, дубли cookies; ranges>2GiB,
отказ malformed/unsatisfied ranges. Полный Android CI и APK фиксируются отдельно.

Крылья: renderer/hide остаётся OPEN. Белая полоса: отдельный отчёт552935 и
сравнение MConfig48; добавление файлов не объявляется исправлением этих симптомов.
