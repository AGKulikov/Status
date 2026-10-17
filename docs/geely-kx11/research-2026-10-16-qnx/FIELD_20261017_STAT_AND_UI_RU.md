# 17.10.2026 — QNX stat, повтор белой полосы и предупреждение iPhone

## Первичные свидетельства

Пользователь собрал системный дамп без перезагрузки после успешного QCONN-контроля,
затем сообщил: «Исправляй. И посмотри логи, опять белая полоса появилась.
И не отображается уведомление о низком заряде айфона».

- `Natro-system-0f61705b-d159-4d86-8b9e-c23e6bf7e604.zip`, SHA256
  `bb7237d0faae647a9f61d99624662851fe8e054c4289d67b2c917e298a32d136`.
  25 entries, CRC PASS; все20 содержательных manifest SHA256/размеров совпадают.
- `Natro-diagnostics-1792232071000.zip`, SHA256
  `02c977eb7bf443ea4b78cd7abab3dd89ee3b8f61714ee46595cc59ca0b359561`.
  9 entries, CRC PASS; все8 manifest channel SHA256/размеров совпадают.
  Version3.2.3/code208021356, session=e12-3263; debug/read_logs/dump=true.
  Main/recorder flush complete, foreign-process live tail не запрошен.
  operations.log содержит3 malformed complete lines. Нет оснований объявлять логи полными.

Оригинальные архивы, приватные журналы и бинарные файлы прошивки в Git не публикуются.

## QNX: подтверждённая ошибка разбора, не TCP отказ

QCONN/service file и перечисление каталогов состоялись. Найдено812 путей,
проверено13; EOF получен для11 каталогов, включая `/`, `/.boot`, `/lib64`,
`/bin`, `/etc`, `/ib64`, `/scripts`, `/usr`, `/sbin`, `/vm`, `/tmp`.
Это частичный журнал пространства имён, а не812 скачанных файлов.
На `/proc`, stage=inventory_directory_stat, rx=14183, tx=783, elapsed=597мс:

```text
o:ffffffffffffffff:44ab5000:800:0:0:0:6ad37485:6ad37485:6ad37485:416d:2:0:0:0:0
error=qnx_bad_number
```

SOURCE3.2.3 использовал общий hex-parser с максимумом12 символов, в том числе
для inode. Первое поле содержит16. Ошибка прервала inventory-first pipeline:
7Android файлов собраны, QNX target files0, процессы/mappings/slog2 не запускались.
Прежние Connection refused остаются отдельными наблюдениями; их первопричина неизвестна.

SOURCE patch: отдельный stat parser допускает до16 hex-цифр. Inode/device
обрабатываются как uint64 bit patterns и выводятся без отрицательного переполнения.
All-ones inode/device консервативно не считается надёжной идентичностью:
каталоги с таким значением не сливаются в alias. Существующий механизм
identity_unavailable/possible_cycle_identity_unknown сохраняет пределы выводов.
Размеры остаются неотрицательными signed-long; переполнение отвергается.
Общий parser дескрипторов/count, ограничения пакетов, endpoint и handshake не изменены.

TEST: replay точного stat-ответа выше; два каталога с одинаковым all-ones inode
и разными детьми оба перечисляются; uint64 high-bit/maximum, signed maximum,
overflow/negative/prefix/empty/malformed inputs проверены. Невалидный размер
не превращается в успешный нулевой файл. FIELD/APK patch пока не проверены.

## Белая полоса: FIELD-002 / CLUSTER-028 / DIM-WHITE-317 OPEN

В свежем journal наблюдается global_mode=1, sdk_mode=1, selected_mode=1,
expected=allow, readback=allow, wrote=false; окно панели alive/attached/drawn,
display2,size1920x720. display_owner_probe own=false,failed=false.
Это совпадение разрешения с выбранной политикой, а не доказательство скрытых пикселей.
При mode1 политика сейчас не подавляет штатный оверлей; восстановление mode3
требует подтверждённых ownForeground и navigationIdle.

В системном before-windows снимке одновременно видимы Natro InstrumentPanelActivity
на display2 в[0,0..1920,720], `dimmenu` APPLICATION_OVERLAY с SYSTEM_ALERT_WINDOW
и его отдельное BASE_APPLICATION окно в[728,720..1568,1032]. Также есть другие
OEM/сторонние окна. Отсутствие эксклюзивного владельца может быть реальным,
а не ошибкой regex. Эти снимки не определяют цвет конкретного пикселя и не доказывают,
какое из окон создаёт наблюдаемую полосу. Точное событие перехода mode3→1
не восстановлено из сохранившегося свежего хвоста.

SOURCE: добавлена классификация результатов owner probe — target stack count,
число visible panel/other/unparsed и текущие OEM navigation evidence с возрастом.
Сырые task dumps и имена посторонних приложений не логируются.
TEST: own/other/unparsed/invalid classification и прежние fail-closed tests PASS.
Это диагностическая доработка, НЕ исправление физической полосы.
Запрет штатных окон в mode1 и ослабление приоритетных/owner gates не вводились.

## Аккумулятор: NATRO-008 / BATTERY-EXACT-317 OPEN

В свежем journal точные значения ble_bas45→44→43→42, exact=true.
Helper последний battery40, но он округлённый fallback и не основание для порога.
В сохранённом журнале нет подтверждения warning_presented; выбранные пользователем
пороги, enabled и latch/pending состояния на момент пропуска не записывались.
Значение42 само по себе не доказывает достижение пользовательского порога.
Пользователю задан вопрос о двух настроенных порогах; ответ ещё не получен.

SOURCE: change-deduplicated battery evidence теперь включает enabled, оба порога,
оба latch/pending, status/popup switches, lock gate и foreground blocker.
При достижении порога логируется принятие/отказ очереди отдельно от факта показа.
Новые настройки/хранилища/миграции не добавлены, latch не сбрасывается вслепую,
возврат к coarse Helper/ANCS предупреждениям не выполнен.
TEST: production-method replay проверяет evidence, exact/coarse, два порога,
coherent snapshot, pending, recovery hysteresis и retry после queue rejection.
Это устранение пробела диагностики, причина отсутствия видимого уведомления пока OPEN.

## Проверки и границы выпуска

```text
PYTHONPATH=tools/tests python -m unittest tools.tests.test_system_file_dump tools.tests.test_adb_service_replay tools.tests.test_phone_battery_source_runtime tools.tests.test_media_oem_controls -v
Ran 17 tests in 10.107s — OK
git diff --check — PASS
```

Это host/replay tests, не весь Android suite и не тест на автомобиле.
В тот же source change включена ранее подготовленная правка сохранения частичного
ADB stdout при timeout/cancel/disconnect; см. QNX_TERMINAL_20261017_RU.md.
Новый APK не выдан;3.2.3 уже установленная не содержит эти правки.
Существующий CI workflow всё ещё маркирует build3.2.3: его возможный artifact
не заменяет выпущенный3.2.3 и не должен выдаваться как новый релиз.
Для следующего APK нужны новая версия/code, стабильная подпись и release gates.
Повторять тот же сбор3.2.3 для воспроизведения stat-дефекта не требуется.
