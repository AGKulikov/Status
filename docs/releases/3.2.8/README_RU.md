# Natro3.2.8 /208021361 — сборщик QNX

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
Полный Android CI, APK подпись/provenance/install-over и FIELD пока OPEN.
Новые настройки и протокол Navigator/Helper не вводятся. Backup335…360→361
review; inventory обновлён только после review пяти прежних owners.

После установки: выполнить «Доскачать QNX и каналы связи» и передать ZIP.
QNX failure и неполный набор каналов различаются в manifest. Крылья/белая полоса
не объявлены исправленными; этот выпуск исправляет сбор evidence.
