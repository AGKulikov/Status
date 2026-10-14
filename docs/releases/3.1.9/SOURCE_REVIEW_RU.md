# Natro3.1.9 /208021352 — расширенный graphics collector

Запрос14.10.2026 15:21 МСК: расширить read-only сбор и исправить metadata stage,
который в3.1.8 остановился после65537байт mappings screen. Не патч крыльев.

## Изменения

QNX сначала перечисляет exefile всех PID в прежних пределах256PID/256pages/
128KiB directory. Затем отдельно читает mappings максимум8 graphics processes:
screen, screen-pwrmgr, openwfd_server, openwfd_telltale, wfd_be,
cluster_controller, sc_qnx_vip_proxy. Максимум256KiB/mapping с limit+1 probe.
При усечении дескриптор закрывается, префикс сохраняется, обход продолжается.
При повреждении stream обход останавливается, валидные предыдущие chunks
сохраняются; оставшиеся PID помечаются not_attempted_after_failure. Успешный
inventory не становится ошибочным только из-за последующего mappings failure.

22 фиксированных QNX targets: прежние11 плюс wfd_be, libWFD.so.1, libplanedef.so,
libdalconfig.so.1, libhalmdputil_s8155.so, dlt_logadapter.conf,
dlt_usbdatalogger.conf, /lib/graphics.conf, /etc/dlt.conf,
/var/log/display_recovery_info.txt и /var/log/la_gvm.txt. Фиксированные файлы
читаются дважды со stat;8MiB/file. Старый лог не объявляется текущим событием.
NEEDED не доказывает runtime path; отсутствие/отказ отражаются в manifest.
Автоматических загрузок неизвестных путей из mappings нет.

AndroidGraphics: dumpsys display, SurfaceFlinger --list, clock/uptime перед
и после DLT stage (не синхронный снимок QNX/приборки),64KiB/context. Ручная
метка normal/navigation/unspecified записывается в README, никаких mode setters.

DLT: только существующий /mnt/userdata/DLT; canonical path и regular-file
проверки перед чтением, ссылки/алиасы отвергаются без альтернативного пути.
Не скрытые имена [A-Za-z0-9][A-Za-z0-9._-]{0,127}, глубина2, максимум8 подкаталогов,
64 entries/directory и128 entries суммарно. Неизвестные расширения только inventory.
По mtime выбираются4 последних dlt/log/txt; сохраняется до2MiB block-aligned tail.
Offsets, inode/size/mtime до/после, локальный SHA и partial/change статусы сохранены.
stat не делает mutable log атомарным; range может начинаться посреди DLT record.
До120с между командами graphics stage, плюс deadline уже начатой ADB-команды;
общая сессия600с. После transport failure никаких повторов/переподключений.

Общий payload budget64MiB, включая metadata/DLT. Metadata cap3MiB для суммарных
QNX mappings; process stage60с, fixed QNX60с/file. При ограничении не писать
успех: подробности manifest/qnx-diagnostics/graphics/dlt/inventory.tsv.
2 последних ZIP в cache; явный Download/Natro/export как раньше.

## Безопасность и границы

Нет driver lock, OpenWFD composition query, новых daemon/root/remount/firewall
операций, logger start/log mask, записей UART/CAN/IPCP, process memory/as/ctl,
cmdline/environment, чтения частных данных приложений. Используется только уже
принятый ADB/su0 context без попытки повысить его после отказа. Файлы и журналы
закрытые и могут содержать личные данные: передавать приватно, не в Git.
Проверки файлов на изменяющейся системе не обеспечивают атомарность или защиту
от всех TOCTOU-подмен. Разбор buffer descriptors/unknown source вне этого scope.

## Проверки

Локальный production replay: размер65537, oversized/exact cap, inventory-first,
продолжение после truncation/denial, сохранение prefix при потере транспорта;
DLT binary bytes/offset, rotation, caps, path rejection, denied root, partial
transfer, реальное выполнение generated shell только на временных fixtures.
Android unit suite/CI, APK/signature и FIELD фиксируются отдельно после выполнения.
Ревизия backup: прежние roots/defaults, явный upgrade335…351→352.

## FIELD после выпуска

На стоящей машине снять один расширенный дамп, отметить текущий режим, экран
оставить открытым. Даже partial ZIP отправить: failed/denied/truncated — данные,
не повод повторять без анализа. После проверки покрытия будет понятно, нужен
ли второй дамп в другом режиме. Автоматически режим не менять и блокировки
графики не использовать. Цель — найти renderer; независимый hide крыльев OPEN.
