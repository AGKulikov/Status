# Отказ запуска обеих карт в Natro/Navigator 2.8.8

**Получен map-журнал 131720:** [проверка установленной пары и отложенный readback](MAP_CHECK_131720_RU.md). Прежняя ошибка listener больше не возникает после установки mapfix; запуск карт ещё не принят.

**Последующий аппаратный результат 13.09:** после установки mapfix обе карты по-прежнему не запускаются. [Продолжение диагностики](POST_MAPFIX_FAILURE_RU.md); GATE-082 не пройден.

Пользователь сообщил, что после 2.8.8 карта полностью отсутствует и на HUD, и на
приборке, затем подтвердил установку обоих APK из комплекта. В выданном Navigator
найден конкретный дефект: регистрация `MapLoadedListener` использует неверную
сигнатуру. Он обрывает `HudMapRenderer.startRenderer()` до подключения Surface.

## Доказательство по фактическому APK

Проверен `Navigator-30.3.0-Natro-2.8.8-signed.apk`, SHA-256
`d2620fc5dae6ef05a26aa079ea3d272e498b6a6e0b4d765add772fcf9730a70f`, 155149731 байт.
Это точный ранее выданный артефакт из [протокола 2.8.8](../releases/2.8.8/README_RU.md).
`classes12.dex` имеет SHA-256
`5f618db97dfa9d9e76ebdae849be9d3c251e79ec90bc853703cc7b4ed0620b23`.

| Декларация в APK | Сигнатура |
|---|---|
| `com.yandex.mapkit.map.Map.setMapLoadedListener` | `(java.lang.ref.WeakReference) → void`, public abstract |
| `com.yandex.mapkit.map.internal.MapBinding.setMapLoadedListener` | `(java.lang.ref.WeakReference) → void`, public native |
| `MapLoadedListener.onMapLoaded` | `(MapLoadStatistics) → void` |
| `MapLoadStatistics.getRenderObjectCount` | `() → int` |

Полный собственный результат анализа деклараций: [actual-api.json](actual-api.json).
Произвольный похожий APK не принимается: воспроизводящий скрипт сначала проверяет
точные SHA APK и DEX. Оригинальный APK, чужой bytecode и декомпиляты не публикуются.

Код 2.8.8 создавал правильный proxy `MapLoadedListener`, но затем искал метод
`setMapLoadedListener(MapLoadedListener)`. Такой перегрузки нет ни в Map, ни в
MapBinding. `ReflectMethods.publicMethod()` вызывает `Class.getMethod()` с точными
типами параметров; результат — `NoSuchMethodException`, а не автоматическая
упаковка listener в WeakReference.

Регистрация выполнялась после создания OffscreenMapWindow и получения Map,
**до `addSurface` и применения профиля**. Исключение попадало в catch startRenderer,
вызывало stopRenderer и сообщение о потере Surface. До уведомления готовности и
трёх содержательных кадров дело не доходило. Оба экрана используют один класс
HudMapRenderer, поэтому дефект действует на HUD и приборку.

В 2.8.8 неверная сигнатура использовалась также при снятии listener; исключение
там поглощалось. Проверки прежнего CI компилировали reflection-код без настоящего
MapKit API и тестировали политику первых кадров отдельно. Они не проверяли эту
сигнатуру и потому не обнаружили дефект.

## Исправление и его границы

`MapLoadedListenerBinding.set()` использует фактический параметр WeakReference.
HudMapRenderer вызывает его и при регистрации, и при очистке. Сам listener остаётся
сильным полем renderer до остановки; иначе native weak reference могла бы потерять
обработчик из-за GC. Очистка передаёт пустую WeakReference. Подтверждения session и
generation, проверка renderObjectCount, три содержательных кадра и pixel-защита
от белого/пустого буфера сохранены. Таймаут не открывает непроверенную картинку.

Исправление находится в моде Navigator; протокол сообщений 21/22 и capability
0x10000 остаются совместимыми с Natro 2.8.8. Код приложения Natro в этом изменении
не менялся. Уже выданный APK 2.8.8 содержит ошибку и не заменялся другим файлом.
После прямой команды пользователя «Собирай» 13.09 выпущен отдельный подписанный
[Navigator 2.8.8-mapfix](../releases/2.8.8-mapfix/README_RU.md) с прежним сертификатом.

## Проверки

Четыре новых теста выполняют производственный binding с фактической сигнатурой
SDK. Старый lookup воспроизводимо бросает NoSuchMethodException до подключения,
новый передаёт исходный listener через WeakReference и получает callback. Проверены
раздельные HUD/cluster, очистка и замена обработчика, распространение ошибки
native-регистрации и использование этого binding обоими концами lifecycle renderer.
Ещё семь прежних тестов map/trip прошли: в том числе белые/пустые кадры,
day/night/прозрачные дороги и последовательность ACK/трёх кадров. Всего локально
успешны 11 проверок. Это JVM-replay и статический анализ; native MapKit на KX11
в этом окружении не исполнялся.

Для повторной аппаратной приёмки GATE-082 сначала необходим запуск обеих карт
с исправленным модом. Затем проверить холодный запуск, resize, выключение/включение
карты, reconnect и прозрачные дороги с сохранением отсутствия белой вспышки.
Точные логи текущего запуска с магнитолы не предоставлены; установленный дефект
в выданном коде не исключает дополнительных ошибок после прохождения этого места.

```bash
python3 -m pip install androguard==4.1.4
python3 tools/audit_map_loaded_listener.py /path/to/Navigator-30.3.0-Natro-2.8.8-signed.apk --output /tmp/actual-api.json
python3 -m unittest tools.tests.test_map_loaded_listener_binding tools.tests.test_map_regressions -v
```

Исправление, тесты, аудит и уточнение требований опубликованы в commit
[`33851bcdff77814c976e55b609885385a0aeab25`](https://github.com/AGKulikov/Status/commit/33851bcdff77814c976e55b609885385a0aeab25),
tree `4152acc5f940c6edddb37766a5087d0f1f877d9c`. Дерево побайтно совпадает с локальным;
все 350 прежних ID требований сохранены. Релизный workflow для этого commit пропущен.

[CI 34747441467](https://github.com/AGKulikov/Status/actions/runs/34747441467) прошёл:
компиляция Natro и все unit-тесты приложения, компиляция всех исходников Navigator
bridge, все тесты инструментов и отдельная проверка отсутствия созданного APK.
Это завершило проверку исходников; обновлённый APK теперь выпущен, аппаратный
GATE-082 остаётся открытым.
