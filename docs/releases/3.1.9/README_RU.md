# Natro 3.1.9 — расширенный сбор графики

SOURCE/CI завершены; FIELD на KX11 остаётся OPEN.

- Source commit: dab6d6c4f969b60f6ec09593db7beda038e4942e
- Source tree: d52305366af6d41a320224a30f1e578fc4c89ba9
- CI: https://github.com/AGKulikov/Status/actions/runs/38565073099 — success.
- Android: 2142 tests, 0 failures/errors/skips; XML пересчитаны независимо.
- APK: Natro-3.1.9-signed.apk; code208021352.
- SHA256: 0832e3bc6c0bade9bc569dc09e0cb0cfad1d9113036120f148a1799d995a7353
- Прежний сертификат, v2/v3, zipalign16KiB; 1516 payload entries не изменены подписанием.
- Метаданные обновления поверх3.1.8 проверены. Физическая установка не проверена.
- classes19.dex побайтно совпадает с3.1.8: новая установка Navigator/helper не требуется.
  Поле pairRequired в исходном candidate — общее требование staging, не результат сравнения.

## Что изменено

Исправлен обрыв списка процессов на mappings65537байт: сначала inventory всех
доступных PID, затем mappings до256KiB с сохранением truncated/partial prefix.
Добавлены фиксированные графические библиотеки/конфиги (22 QNX файла),
Android graphics context и ограниченные диапазоны существующих DLT логов.
Подробные границы: SOURCE_REVIEW_RU.md. Никаких driver lock/logger start/записей в автомобиль.

## Следующий сбор

На стоящей машине установить APK поверх3.1.8. В диагностике запустить сбор
системных файлов, отметить текущий режим и оставить экран открытым.
Передать один полученный ZIP приватно, даже если статус partial.
Сначала проверить inventory/mappings/DLT и ошибки доступа; лишь затем решать,
нужен ли второй режим. Новый сбор не доказывает устранение крыльев или белой полосы.
