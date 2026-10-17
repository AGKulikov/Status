# Natro3.2.8 /208021361 — сборщик QNX

## Дополнение FIELD17.10 19:15МСК

Vehicle372877 с version3.2.8 подтвердил в этом сеансе: QNX первым,966exclusions,
26resolved links, завершённый выбранный обход1080paths,8stable+2changed payloads,
13slog2/10mappings и сохранённый ZIP при последующем15s logcat timeout.50SHA/size PASS.
Это закрывает наблюдение о потере всего QNX из-за раннего Android snapshot;
3недоступных пути/live changes/optional logs остаются partial. Установка с сохранением
всех настроек и абсолютная полнота QNX отдельно не проверены. Прежние FIELD OPEN
ниже отражают этап выдачи; новый [разбор](../../geely-kx11/research-2026-10-17-white-bar/FIELD_1911_1915_RU.md)
уточняет только перечисленные gate. Полоса/крылья остаются OPEN.

[Stable/latest3.2.8](https://github.com/AGKulikov/Status/releases/tag/natro-v3.2.8)
опубликован17.10.2026 в19:03:53МСК,release415910302.
Publisher39047732914/job117439745681 SUCCESS: exact signed APK повторно
проверен без private key,7assets скачаны и побайтно сравнены до публикации.
Latest/tag/source/SHA/size прочитаны обратно. [Протокол](publication-verification.json).
Источник и все собственные изменения опубликованы, FIELD остаётся OPEN.


## Проверенный APK

SOURCE5e4f40cee990cda4f5832f7734e5e14d2140ea57,
tree82d94501627f5e54169eac183d0e0801dcfcc3ab.
[CI39046959187](https://github.com/AGKulikov/Status/actions/runs/39046959187),
job117437547451 SUCCESS:2175Android tests/265tool tests/108Java cases,
0failures/errors/skips. XML/CI artifact CRC,SHA и source/tree проверены.

APK29764192bytes,SHA2567eb5b575c9f7e3ee3967b2eba9e5bb9f285b7183c4758b3724af60ce3075976c.
Прежний единственный сертификат6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75,
v2/v3/exactAPI28/zipalign16KiB и +1 install-over metadata поверх3.2.7 PASS.
1517payload entries CI побайтно сохранены; DEX markers/977exclusions проверены.
Временные извлечённые signing key/password удалены; приватный резерв сохранён.
Navigator classes19 равен3.2.7 SHA710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96;
Navigator/Helper не требуют обновления. Generic pairRequired исходного signing
report уточнён фактическим сравнением, оригинальный отчёт сохранён отдельно.

[Отчёт](release-report.json),[подпись](signature-verification.txt),
[манифест](natro-update.json),[локальные проверки](local-verification.json).
SOURCE/TEST/APK приняты. FIELD установки/докачки/native link/exclusions OPEN.
Stable-публикация и readback завершены; протокол выше.


Дефект3.2.7 подтверждён архивом663180: channel-log терял ADB раньше QNX.
Теперь основной QNX собирается первым, до su probe и всех Android snapshots.
Снимки каналов идут один раз после QNX, каждый с15s transport deadline и
до1MiB stdout. При потере связи/missing exit остальные отмечены not_attempted;
нет повторов команды/reconnect/рестартов служб. Полученные файлы сохраняются
в partial ZIP. Stage/elapsed/timeout и suppressed exception объясняют отказ,
частичный вывод не теряется. Источник первичного полевого disconnect неизвестен.

Прежние977exclusions полного+частичного архивов, native lstat/readlink,
канонические roots/NOFOLLOW, отсутствие общей QNX квоты и «Повторить всё» сохранены.
Полный индекс/mappings и live журналы продолжают обновляться. Старые архивы
нужны для объединённой копии; same-size remote changes требуют полного повтора.
Исправлен устаревший текст System README про dev/inode dedup.

SOURCE готов;12 focused tests PASS: реальные collector/parser/ZIP с моделью
разрыва на root/log/missing exit, QNX failure, исключения, full20MiB streaming,
частичный stdout; ADB console deadline wiring/suppressed reason/no retries.
Историческое состояние перед CI: Android CI/APK были OPEN; текущий итог выше. FIELD остаётся OPEN.
Новые настройки и протокол Navigator/Helper не вводятся. Backup335…360→361
review; inventory обновлён только после review пяти прежних owners.

После установки: выполнить «Доскачать QNX и каналы связи» и передать ZIP.
QNX failure и неполный набор каналов различаются в manifest. Крылья/белая полоса
не объявлены исправленными; этот выпуск исправляет сбор evidence.
