# 18.10.2026: отменяемое закрытие приборки и освобождение DIM

Продолжение [сравнения mNavi/MConfig и полевых ZIP](FIELD_0859_AND_SWITCHING_RU.md).
Изменение выполняет поручение10:00МСК «Жду, доделывай». Историческое состояние
«runtime не менялся» в предыдущем отчёте относится к commit f84140b.

## Что изменено в SOURCE

- `InstrumentPanelCloseCoordinator`: отдельная операция OFF с deadline10с.
  Новый ON и новый OFF отменяют предыдущую операцию. Поздние callbacks и queued
  workers не вправе отзывать новый token или менять DIM нового поколения.
- Удаление только собственной InstrumentPanelActivity/task выполняется в MAIN
  во время OFF, до нового ON. Отложенное перечисление задач убрано.
- Unversioned CLOSE больше не отправляется; compatibility receiver игнорирует
  старый CLOSE при enabled=true. Natro/HUD/ANCS/media process не останавливается.
- Результат worker-авторизации проверяет epoch при доставке на MAIN. OFF,
  QuickBoot и изменение дисплея инвалидируют старую авторизацию.
- Отложенный OEM mode3 repair перепроверяет enabled перед записью.
- После отзыва token прежний настроенный PrivilegedShell читает `am stack list`.
  Это только чтение: shell не запускает, не переносит и не завершает приложения.
  Parser учитывает display, видимость и границы; собственная панель и штатные
  com.ecarx.dimmenu/com.ecarx.hud не считаются чужим dashboard. Любая другая
  видимая Activity удерживает DIM, включая другой экран Natro.
- При подтверждённом свободном дисплее и snapshot не старше1.5с выполняется
  mode1→readback1→DIM(2,8,8,[0]) на той же последовательной worker-очереди, что ON.
  Значение[0] взято из разобранного mNavi2.0, SHA/offset приведены в предыдущем отчёте.
  Отсутствие транспорта, неоднозначность/неполнота данных, очередь или timeout
  сохраняют режим и сообщаются пользователю как неподтверждённый возврат.
- При timeout/неподтверждённом окне используется прежний ограниченный incident
  recorder (состояния/стэки потоков). Это не системный window dump и не доказательство
  владельца OEM-диалога.

## Проверки

Production replay исполняет тот же CloseCoordinator:10циклов ON/OFF, отмена до
token revoke, во время owner read, до DIM restore; старые deadlines, OTHER/UNKNOWN
owner, timeout каждого этапа и OFF→ON→OFF. Parser replay дополнен штатными
HUD/menu, com.auto_soft.monjaro_dashboard, plus.monjaro и неполной visibility.
Native ICU проверяет реальные production regex. Эти локальные проверки PASS.
Общий `tools.tests.test_system_file_dump` + `tools.tests.test_backup_inventory`:
16tests PASS,32.079с. Inventory90owners PASS без новых fingerprints.

Полная Android CI текущей runtime-ревизии ожидается. Для предшествующего collector
commit f84140b CI39105026531 завершилась SUCCESS; это не проверка нового lifecycle.
Новая подписанная версия не выпущена, stable3.3.0 сохраняется.

## Ограничения и следующий шаг

Это исправление конкретных отличий OFF и защита от запоздавших операций. Оно
не установило причину окна «Открыть это приложение на текущем экране?» и не
подменяет generic active-app transfer из описания пользователя. Запуск панели
по-прежнему direct ActivityOptions/display2; при выключении закрывается выделенный
task согласно CLUSTER-026. Нельзя объявлять проблему полностью устранённой.

GATE-059/066 требуют10циклов на KX11, сохранения PID/HUD/ANCS/media, отсутствия
OEM-окна и позднего открытия, наличия ровно одной новой панели после ON.
При повторении окна нужен одновременный window/activity dump и точная версия
mNavi, чтобы определить реальный перехватчик. Полный QNX архив повторно не нужен.
Collector fix позволяет отдельно получить адресные built-in PNG и продолжить
поиск производителя крыльев; renderer и независимое hide остаются OPEN.
