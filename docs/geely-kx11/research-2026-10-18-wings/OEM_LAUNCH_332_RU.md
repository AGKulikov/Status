# 18.10.2026 — системный диалог переноса и исправления переключателя 3.3.2

Уровни: STATIC по предоставленной прошивке, SOURCE и локальный production replay.
Android CI/APK заполняются в [выпуске 3.3.2](../../releases/3.3.2/README_RU.md).
FIELD GATE-059/066 и независимое скрытие крыльев остаются OPEN.

## Источник и граница вывода

Повторно получен пользовательский `KX11-Cruise-Trace-20260809-001402.zip`:
324549107байт, SHA-256 `bbb4b7bb8c2f04864ef41777c6679e2881d2db3084563c9d10518a20cb61bc05`. Точные SHA исследованных payload ниже.
`getprop.txt`: `qti/kx11_high/kx11_high:9/20.24.10.23024.41151/539:userdebug/test-keys`.

| Payload | SHA-256 |
|---|---|
| BOOTCLASSPATH_framework.jar | `f97f15765c2ff9ac6d4ffd9c30f42d7fb277bf5ccab1bac59fee1bc72ada99b6` |
| SYSTEMSERVERCLASSPATH_services.jar | `4dabfeb1740bb12544d86c668c3f32c204057133eef111f46114f5f20670b441` |

Метод: чтение DEX androguard4.1.4, сопоставление ветвлений/вызовов и их байтовых
offset внутри методов. Чужие JAR/DEX, декомпилированный код и сырые журналы в Git
не добавлены. Совпадение этих JAR с текущими файлами работающей машины не проверено;
статический маршрут объясняет наблюдаемый симптом, но не доказывает исполненную
ветвь конкретного отказа 16:34:33.

## Что делает одиночный startActivity

`ContextImpl.startActivity(Intent, Bundle)` вызывает Activity-overload
`Instrumentation.execStartActivity`. В нём:

1. `0x42…0x5e`: выбирается `android.activity.launchDisplayId`, при отсутствии —
   дисплей контекста. Для Natro в options явно указан2.
2. `0x60`: `isTopOtherThanCurrentDisplay(intent, targetDisplay)`.
3. AMS `getOtherThanCurrentDisplay`: для0 возвращает3, для **любого другого** —0.
   Таким образом, водительский2 здесь ошибочно попадает в общую CSD/PSD-проверку.
4. AMS `isTopOtherThanCurrentDisplay` сравнивает **пакет**, а не компонент/affinity.
   На центральном экране учитываются верхние пакеты split или fullscreen.
   Настройки Natro на0 подходят под ту же проверку, что приборка Natro на2.
5. При true и `mShowUserDialog=false` (`0x14c…0x180`) вызывается
   `startActivityOnCurrentDisplayChecked`; метод возвращает null (`0x1a8`),
   не выполняя обычный `IActivityManager.startActivity` (`0x2fc`). Поэтому
   отсутствие исключения из `Context.startActivity` **не означает созданное окно**.
6. Обработчик OK вызывает `finishTopActivityOnOtherThanCurrentDisplay`; пакетная
   CSD/PSD-логика может затронуть центральную задачу. Это не нужный контракт приборки.

Это конкретнее прежней гипотезы «same-process против external caller»:
проверка ориентирована на целевой пакет/экран, а не только на процесс вызывающего.
Прежнее сравнение mNavi не доказывало причину. Точный generic active-app handler
mNavi по-прежнему не установлен; nav-specific force-stop не перенесён в Natro.

## Выбранный публичный путь

`Context.startActivities(new Intent[]{instrumentIntent}, options)` запускает
**одну** собственную Activity с существующей отдельной affinity/singleTask.
В данной прошивке `ContextImpl.startActivities` →
`Instrumentation.execStartActivities` → `execStartActivitiesAsUser` →
`IActivityManager.startActivities` (`0x116`) → AMS/ActivityStartController →
обычный ActivityStarter. Options передаются последнему (здесь единственному)
intent. У этого пути нет пакетного OEM-преддиалога.

Проверки Android caller/user/доступа к display и SafeActivityOptions сохраняются.
Не используются hidden API, изменение глобального `mShowUserDialog`, подмена
OEM-action, автонажатие OK, `am force-stop`, shell start или перенос задачи настроек.
Ошибки платформы возвращаются координатору без запуска на другом дисплее.
Существующие token/epoch/enabled checks, 100/200ms DIM preparation,
проверка STARTED/attached/visible/first draw и bounded retry сохраняются.
Общий15s timeout готовности не сокращён: устранён конкретный маршрут, который
мог возвращаться до реального запуска.

## Закрытие: подтверждённое освобождение и переходные состояния

3.3.1 завершал close на первом UNKNOWN; сырой ответ отказа727998 не сохранён.
Нельзя объявлять установленной причиной ни пустой stack, ни echo, ни формат строки.
Новая реализация покрывает эти реальные классы отказа отдельно:

- `InstrumentOwnerQuery` обрамляет `am stack list` уникальными BEGIN/EXIT,
  требует оба маркера, exit0, отсутствие truncation/transport error и возраст≤1500ms
  от фактического начала исполнения. Очередь shell не обновляет возраст снимка.
- Успешный полный список с пустой/удалённой задачей display2 разрешает FREE.
  Видимая собственная панель остаётся UNKNOWN до завершения WindowManager.
  Чужая панель сохраняет DIM; известные штатные HUD/dimmenu отделены от dashboard.
- UNKNOWN перечитывается максимум5раз через150/300/600/1200ms, в общем10s deadline.
  Новый ON отменяет старое чтение, таймер и DIM callback. Неизвестное состояние
  никогда не превращается в FREE по таймауту.
- В журнале — exit/begin/freshness и причина parser с числами stacks/tasks,
  без raw dump, имён чужих приложений и launch token. Toast/incident только после
  окончательной неудачи, а не на каждой промежуточной проверке.

Локальный production replay:10циклов; задержанные revoke/read/DIM/deadline;
UNKNOWN→FREE/OTHER; исчерпание/отмена retry; empty complete vs unconfirmed;
visible own, malformed/truncated input; trim/echo/CRLF, lost BEGIN/EXIT,
nonzero status, transport error, stale/backward clock и oversize.
Android API28 тест дополнительно проверяет одну точную Activity/options/token
и отсутствие неадресного fallback при SecurityException. Это не эмуляция OEM UI.

## Крылья и оставшаяся проверка

Оба3.3.1 wings ZIP уже имеют адресные PNG: builtin2=1920×1080,
до/после6Client layers. На PNG крыльев нет; у пары разные boot IDs.
Это не устанавливает конечный QDI1 output и не доказывает внешний DIM renderer.
Не добавлен фиктивный переключатель скрытия и не меняется alpha всего Android layer.
[FIELD источники](FIELD_331_WINGS_RU.md), [конкретная граница следующего исследования](NEXT_STEPS_RU.md).

GATE-059/066: на KX11 нужны10 ON/OFF, первый кадр без OEM-преддиалога, stock DIM
послеOFF, отсутствие позднего открытия, сохранение PID/HUD/ANCS/media. Для крыльев
нужна корреляция физического кадра с проверенной границей композиции или прошивка
установленного внешнего DIM renderer. Повторный полный дамп уже полученного QNX
этот пробел сам по себе не закрывает.
