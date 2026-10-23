# Медиакнопки: вход XSF и перегрузка broadcast

База SOURCE: 3490952ebfa93d3251dd545a8782d96f9bc01250, Natro3.3.4.
FIELD: Natro-diagnostics-8999223560370910451.zip, 21.10.2026,
session d6d-2d4a. journal SHA256 ceb63e3d564d0822c1f7e093cb4ea447203d8d7b18bbef3fbb11fb71c4dcfad2.
XSFInputService.apk SHA256 c4e380408d952298370ce8f9df177e902233c2a24825f15a02c1dbf371fccc14.
Проприетарный APK и сырые пользовательские логи в Git не добавлены.

## Доказательства и ограничения

8 событий переданы; 26 (13 пар) отброшены как discarded_source_stale.
Первая неудачная DOWN: code87, event362757, received389649, age26892ms;
последняя DOWN: code88, event371971, received402122, age30151ms.
Успешные пары ранее имели age36–297ms. Это задержка от создания KeyEvent,
а не измерение физического нажатия. queue_ms=0 в stale-ветви задаётся кодом
до постановки в очередь: это не независимый замер состояния command executor.

STATIC XSF: ECarXMediaPlayPauseAction.onShortClick(0x3a9130),
ECarXRMediaNextAction.onShortClick(0x3aa24c), Previous(0x3aadf4) создают
KeyEvent из uptimeMillis после проверок выбранного режима. Через base.injectInputEvent
и InputManagerHelper.sendBroadcastMedia(0x3af0d4) передаётся исходный KeyEvent,
sendBroadcast, flags0x01000020. Это не ordered-вызов. Между созданием и Binder
есть логирование: статический код не исключает runtime-блокировку этих вызовов.
Оригинальный APK имеет MEDIA; фактический NEDIA-маршрут меняется в VDEX.

За20s uptime110000–130000 и190000–210000 получено по1 TRAFFIC_LIGHT_UPDATE.
За330000–350000:333,350000–370000:315,370000–390000:351.
Рост начался в интервале220000–240000 (143), затем240000–260000 (323).
Нагрузка предшествует отказу; причинность общей системной очереди не доказана.
Непрерывной блокировки main30s в данном интервале не наблюдалось.
Архив не содержит актуальных XSF send-логов или dumpsys activity broadcasts;
actions-tail относится к15.09 и не заменяет отсутствующие данные21.10.

## Изменение SOURCE

- Активный HandlerThread подписан также на NEDIA. Manifest receiver сохранён для
  холодного процесса. Оба входа используют один executor и identity cache.
- Ingress пишет action/thread/event/received, чтобы сравнить оба пути доставки.
- Traffic comparison исключает updatedAt и порядок LinkedHashMap. Прежняя
  JSON-схема хранения не меняется. Все принятые пакеты обновляют timestamps.
  Одинаковые показания уведомляют не чаще1Hz; изменения countdown/color/source,
  позиции/набора и clear проходят сразу. Heartbeat сохраняет обновление liveness.
- Порог750ms сохранён: поздние команды не исполняются накопленной пачкой.

TEST:6 portable tests PASS — реальные production policy classes,400 повторов/20s,
heartbeat20уведомлений, смены/clear/reset, duplicate DOWN/UP, новые нажатия,
граница750ms и реальное stale-событие; прежние routing/patch tests сохранены.
Это не Android broadcast integration/load test. Полная Android-сборка и APK OPEN.
FIELD OPEN: холодный/тёплый старт, обе очередности duplicate delivery,
OFF/defaultSource,3×30min по GATE-079 с отметками10/20min и реальным плеером.
Нужно проверить source_age в обоих путях; оставшаяся задержка требует снимка
системной broadcast-очереди и сопоставления XSF send-time с ingress.
