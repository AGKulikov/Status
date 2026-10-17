# MConfig+ 48 и 46.1: скрытие полосы и стартовый сброс NaviMode

17.10.2026 пользователь предоставил MConfig+_v48.apk и попросил проверить,
изменён ли алгоритм белой полосы. APK не запускались и не изменялись.
В Git сохраняются собственные выводы, имена методов, смещения и хеши;
чужие APK, DEX и полное дизассемблирование не публикуются.

## Источники и способ сравнения

| APK | Размер | SHA-256 |
|---|---:|---|
| MConfig+_v48.apk | 1443980 | f2dcf0388c58602278bf4f91836d0802dce892feed69ec96f5a51978f8dd7db7 |
| MConfig+_v46.1.apk | 1321320 | a1f2f1d486422afd65a47ba06201ed0a87a8dd4f7b9052db860765a5e3fb35e0 |

Manifest обоих: plus.monjaro, versionCode=1, minSdk28, targetSdk29;
versionName соответственно48 и46.1. CRC обоих ZIP проверен. classes.dex v48:
3e8ca7d2618ed01829943a17ec6466b7272773aec9281a48396784658414d704;
v46.1:68e1bfe50b7e783ed7f1ec69eca96d6130c50750d044b2f0d083bffd59f3fc61.
Использованы manifest/aapt и dexdump, разобраны реальные ветвления, аргументы
вызовов и вызывающие методы. Это статический анализ, не выполнение на KX11.

## Что сохранилось

1. NotificationListener регистрирует ContentObserver для Settings.Global.NaviMode
   и вызывает проверку при создании службы.
2. Скрытие допускается только при whiteBar=true (default false).
3. Global mode3 передаёт false в helper, выбирающий deny; иной режим — true/allow.
4. Coroutine ждёт1500мс и трижды выполняет appops set для SYSTEM_ALERT_WINDOW
   только com.ecarx.dimmenu; между проходами100мс.
5. В этой ветви нет нового ignore, контроля готовности панели или нового
   автоматического возвращения mode3. Mode считывается до отложенной coroutine.

| Роль | 46.1, code_item | 48, code_item | Инструкций dexdump |
|---|---|---|---:|
| Проверка режима | NotificationListener.y, 0x1131a8 | NotificationListener.e, 0x12aa6c | 18/18 |
| Проверка whiteBar и постановка | NotificationListener.z(Context,boolean), 0x112264 | NotificationListener.n(Context,boolean), 0x12a7d8 | 17/17 |
| Coroutine AppOp | nun.k, 0x0fcca0 | ikn.s, 0x0dfb64 | 452/452 |
| Начальная настройка DIM | MConfigInitService.z, 0x10e924 | MConfigInitService.n, 0x126950 | 207/207 |

Для этих четырёх тел инструкции совпали после последовательного переименования
обфусцированных классов/членов и удаления числовых DEX reference indices.
Сохранены opcodes, регистры, смещения ветвлений, literal values/strings и полные
имена Android/Java/ECARX API. Это дополнительная проверка ручного разбора;
она не означает тождество всех вызываемых методов или всего APK.
NotificationListener.onCreate изменён в других местах (160→142 code units),
поэтому утверждение «весь NotificationListener одинаков» неверно.

SHA-256 insns bytes четырёх основных методов v48 в порядке таблицы:
1f9641f91ff1b5c2c3d5962e9a7349e264bc801d2d2bc09024535876da265fe1;
f8d58d5c9aa629485549a77cbc3874953c1d376b9e42722bdc549345e548783d;
22a2856f65b2976428c8e563177828724ffb10899ffce0436dbebd1f18d63f57;
d06e5112d8f7430756cfb1d84d067f00e395f2636c2685389c59ca6e9df46118.
Хеши относятся к полным insns_size*2 байтам после16-байтового code_item header.

## Отдельная зацепка: сброс режима при инициализации

В обеих версиях путь MConfigInitService выполняет:
- подключение существующего ADB helper и задержку200мс;
- Settings.Global.putInt(NaviMode,1), без проверки whiteBar;
- ещё200мс и appops get com.ecarx.dimmenu SYSTEM_ALERT_WINDOW;
- если ответ не содержит allow: force-stop dimmenu, пауза150мс,
  AppOp allow, пауза150мс и явный broadcast его MediaButtonReceiver.

PnAccessibilityService.onServiceConnected запускает MConfigInitService в обеих
версиях. BootService также содержит запуск этой службы. Само существование
процесса MConfig ещё не доказывает выполнение данного пути при конкретном сбое.
Отключение whiteBar останавливает helper скрытия, но само по себе не отключает
описанную независимую стартовую запись NaviMode=1.

В новых дампах 14:48/15:00 процесс plus.monjaro сменился3515→11031, а PID4586
и token56d7a8b dimmenu сохранились. В14:58:03 Natro увидел Global/SDK1 вместо3.
Стартовый путь MConfig — обоснованный кандидат инициатора, но временная метка
его onServiceConnected/putInt не собрана. Не утверждать установленную в машине
версию48 только потому, что пользователь прислал этот APK. Не объявлять MConfig
доказанной причиной и не выключать его Accessibility/медиакнопки автоматически.

См. [сопоставление дампов и изменения Natro](WHITE_BAR_552935_RU.md).
