# Natro3.1.8 / 208021351 — расширение QNX evidence

Пользователь одобрил сборку14.10.2026 в08:55 МСК. База — опубликованный
research commit c0b7622ca5831cc9aa26f4e59126c02fabb87754, поверх3.1.7.

## Состав

Прежние9 Android и6 QNX файлов сохранены. В начало QNX allowlist добавлены
variant.cfg, /bin/openwfd_server, /bin/openwfd_telltale, /bin/screen,
/lib/libopenwfd_qnx.so. Пути подтверждены старым Deep Evidence inventory,
но доступность и размеры текущих файлов требуют нового дампа. На файл отдельный
stream, open flags0, stat, два полных чтения и сравнение, close. 8MiB/file,
64MiB total. Неподходящий тип/размер отмечается без чтения и не обрывает остальные
фиксированные цели; transport/protocol failure прекращает QNX без retry.

После обычных файлов — отдельная60с попытка process metadata. Directory GET
только /proc, до256 страниц/128KiB и256 уникальных numeric PID. Exefile максимум
4096 байтов; только для openwfd_server/openwfd_telltale/screen/screen-pwrmgr/
cluster_controller читается mappings максимум64KiB, до8 процессов. ДоEOF,
включая проверку превышения на один байт; stat size псевдофайла не используется.
Нет cmdline/environment/as/ctl/devctl, shell launcher, arbitrary path grants,
экспорта памяти, перезапуска служб, firmware writes или автомобильных команд.
Другие обнаруженные executable/library пути НЕ загружаются автоматически.

Имена процессов и mappings сохраняются в qnx/metadata с отдельными статусами
и SHA256 в manifest. Это однократный неатомарный snapshot, не verified firmware
payload. Даже timeout/denial/malformed reply сохраняют накопленную метаинформацию
и ранее собранные файлы. Данные не публикуются и передаются только пользователем.
Если процессный этап не начался, manifest содержит not_collected.
Полный набор бинарников не скрывает отказ metadata: Report.complete и UI
отмечают частичный дамп при любом неполном процессном этапе, включая отказ
exefile/mappings. Отдельный replay покрывает все20 файлов плюс отказ metadata.

## Пределы и проверка

Общий budget dump600с вместо180, per-file60с и process-stage60с; обычная
консоль60с не изменена. Пользовательское подтверждение сообщает состав и10минут.
Закрытие экрана/отмена закрывает принадлежащий сессии socket. Нет reconnect/retry
после отказа. Размер и число файлов не означают аппаратного успеха.

Локальные Java replays: бинарные фрагменты, double-read mismatch, forbidden path,
noise/cancel, directory/EOF, PID dedup и отказ path injection, mappings selection,
denial, overflow длины, бесконечные directory pages, превышение exefile limit,
частичный ZIP и запрет traversal metadata name. Полный Android CI/sign/APK
фиксируются отдельно после фактического выполнения.

Defaults/ключи не менялись. Explicit migration335…350→351, same-schema351,
отказ334/352; сохранение явных значений проверяется отдельным Android test.
Четыре storage owner пересмотрены в FULL_BACKUP_CONTRACT_RU.md.
Navigator/helper не менялись. Исправления3.1.7 включены без изменения поведения.

## Приёмка на машине

Установить поверх3.1.7, открыть «Диагностика», снять системный дамп, не закрывать
экран до завершения; отправить ZIP лично. Проверить manifest и qnx-diagnostics,
CRC/SHA/размеры новых файлов, фактический variant и связь PID/exefile/mappings.
QNX7 procfs и directory dialect известны из документации/старого host reader,
но новый Java-путь не объявляется испытанным на KX11. Ошибка доступа — результат,
не повод менять firewall/права. Крылья пока не отключаются.
