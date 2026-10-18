# 18.10.2026: два wings ZIP, диалог переноса и сравнение mNavi/MConfig

## Итог и границы

В двух новых ZIP Natro3.3.0 найден конкретный дефект сборщика: полный штатный
`screencap -h` завершился с exit1 и был отброшен. Поэтому вместо адресных built-in
снимков получен только default-unmapped снимок центрального экрана. Это не
изображение приборки и не отрицательное доказательство наличия крыльев в Android.
Внесено узкое SOURCE-исправление с production replay. Нового APK и FIELD-проверки нет.

Пользователь уточнил: нужен механизм **пункта mNavi переноса активного приложения
с основного экрана на приборку и обратно без остановки приложения**, применённый
к переключателю Natro. Ветки перезапуска выбранного навигатора не являются
автоматически этим механизмом. Runtime переключателя в этой ревизии не изменён:
точное соответствие проверенного APK описанному пользователем сценарию пока OPEN.

## Источники

Все хеши SHA-256. Чужие APK/DEX, сырые журналы, фотографии и приватные координаты
в Git не добавляются. Публикуются наши выводы и идентификация источников.

| Источник | Размер | SHA-256 |
|---|---:|---|
| Natro-wings-65013fe7-fad9-41cf-9ea4-6c8936e02dac.zip, normal | 170804 | `13cec206ee88646d423e8511fcbcfc01b99a3822e6f8757f4b6210866bbb4e61` |
| Natro-wings-f60c4f07-fe4d-4d56-9374-8c519656a19b.zip, visible | 161558 | `1125b5c27229e153abfd34f8690fce639411db8d18d3e018bbfe926c3fed3b9c` |
| MConfig+_v48.apk | 1443980 | `f2dcf0388c58602278bf4f91836d0802dce892feed69ec96f5a51978f8dd7db7` |
| mNavi_2.0(2).apk | 2997398 | `410d3464af2a1b6ea374e9818e6752f75ff79b837fdb2846e4a7652919180be3` |
| mNavi_2.0(1).apk и mNavi_2.0.apk | каждый2997398 | тот же `410d3464af2a1b6ea374e9818e6752f75ff79b837fdb2846e4a7652919180be3` |
| YN_MonjaroMOD_v30_3_0_M+mHUD_v2_1+mNavi+minimod_v1_1_sign.apk | 155351844 | `753360b6d043a011d4c9767034a5edebc051e9239127dd83d0e1d5f482a7482a` |

Фото IMG_8810 (08:56) SHA `794f356bf0be4168f9d6292c3eb7144589977625c66a4da6158f0fcf66f4e9a7`;
IMG_8811 (08:58) SHA `27befbcef945957b4719e73681c1fd67cd92973ca5ff95d685541addce214d3a`.
Фото диалога IMG_6BFDEE6E-77C7-45A5-9F4A-BF7DFAF6A626.jpeg SHA
`946da8766a5472571e3915d2718eea32d6d51b6af2548f806763b732bf6c5530`.
Текст: «Открыть это приложение на текущем экране?». По словам пользователя,
появляется при управлении включением/выключением приборки в настройках Natro.
Одновременного window/activity dump именно этого диалога нет. Исторический
PSD MessageDialog из старого HUD Lab не доказывает владельца текущего окна.

## Что действительно содержат два новых ZIP

CRC ZIP и 34/34 manifest size/SHA проверены. Каждый manifest содержит17записей,
из них16 collected; help — command_failed. Это два самостоятельных сеанса:

| Состояние | Интервал МСК | NaviMode до/после | Снимок |
|---|---|---|---|
| normal | 08:55:44.961–08:55:50.239 | 1 / 1 | default-unmapped1920×720, диагностика на HU |
| visible | 08:58:42.365–08:58:47.304 | 3 / 3 | default-unmapped1920×720, диагностика на HU |

Boot ID различаются; uptime около183.66/92.97с. Не выводить из этой пары
непрерывный переход1→3 или причинность конкретной команды. Display inventory
содержит built-in0…3; physical2/logical2/layerStack2 имеют1920×1080.
Совпадение этих ID в данном устройстве не разрешает подставлять logical ID
в screencap без обнаружения built-in namespace.

Visible: Natro InstrumentPanelActivity занимает(0,0)–(1920,800), HUD —
(0,720)–(728,910), dimmenu —(728,720)–(1648,1080); также есть поверхности
plus.monjaro, включая маленькую и полноэкранную. Normal: Activity приборки нет,
активен полноэкранный Sys2038 dimmenu, Natro DIM menu имеет другую геометрию.
Само присутствие имени/окна/слоя не устанавливает производителя пикселей крыльев.
В before composition есть Client, в after — Device. Обобщение «HWC overlays
отсутствуют» неверно. Имена/токены между разными boots не являются общей идентичностью.

Help обоих сеансов одинаковый, SHA
`f96337598a376c6872ed869cf9f1a56b0e3b6d4c0478d41f07a902fca8a83877`.
Оригинальные PNG SHA: normal
`000c2273acf9dd4aefe7a032d9746100a905c5571b04be46a0093552d17eb629`, visible
`7aa668a4e3a090757a97f3150e7c433b02393c27f7ead0cc58d5edc53af633fd`.

## Узкое исправление сборщика

`WingsEvidenceArchive.recognizedHelp` принимает только целиком совпавший известный
шестистрочный API28 help с exit0/1, без truncation; допускает CRLF и внешние
пробелы/переводы строк. Оригинальные bytes/output и exit1 сохраняются в manifest.
Иные команды по-прежнему требуют exit0. Permission denied, неизвестный exit,
exit2, усечённый вывод, error trailer и неизвестный help даже с exit0 не дают
разрешения на адресные снимки. Сломанный транспорт останавливает дальнейший сбор.

Сохраняются прежние пределы:170с на сбор,15с/1MiB на команду, до4 обнаруженных
built-in дисплеев,8MiB на PNG, проверка PNG framing/CRC/SHA, уникальный временный
путь и явный cleanup status. Режим DIM/процессы/разрешения не меняются.
Storage review: прежний cache/exports, retention2, Download-export и backup policy;
новых prefs/keys/defaults/roots или схемы миграции нет.

TEST: `python -m unittest discover -s tools/tests -p test_system_file_dump.py -q`:
10tests PASS,41.186с. Production replay включает положительный exit1 и шесть
отрицательных случаев выше, сохранение настоящего exit/help и запрет подмены
namespace. Это JVM-исполнение collector, не проверка screenshot на KX11.
Дополнительный совместный прогон `tools.tests.test_system_file_dump` и
`tools.tests.test_backup_inventory`:16tests PASS,34.033с; reviewed storage
inventory90owners PASS. Полная Android CI для этой ревизии не выполнялась.

## mNavi: точная привязка к меню, а не только поиск force-stop

APK CRC проверен, classes.dex SHA
`60ae8ffe89ca4033b7f529faffff15348fb75af34692efad7c5d3a4fc322ba3e`.
Статически разобраны DEX instructions/ветвления и resources.arsc. APK не запускался.

| Элемент в проверенной2.0 | Привязка | Прослеженная ветвь |
|---|---|---|
| «На экран водителя», resource0x7f0e0070 | a0/w0.x→OverlayService.a0; w→CentralAccessibilityService.G | OverlayService.k→c1/d0 variant1; CentralAccessibilityService.l→variant0 |
| «На центральный экран», resource0x7f0e006f | a0/w0.x→OverlayService.b0; w→CentralAccessibilityService.H | OverlayService.k→c1/a2 variant1; CentralAccessibilityService.l→c1/y variant1 |
| Наблюдение состояния | OverlayService.e | Чтение видимости выбранного nav package/display; DRIVER_SCREEN_STATE broadcast |
| Освобождение режима при отсутствии nav/dashboard | OverlayService.F→c1/a2 variant5 | mode1 и DIM(2,8,8,[0]), без вызова helper остановки |

В **первых двух конкретных цепочках этой2.0** вызывается b(service, continuation).
Он выбирает только Yandex Maps / Navigator / Google Maps по настройке nav app
и до3раз отправляет `su 0 am force-stop --user 0 <nav-package>` с100мс между
первыми попытками. Затем c(service,display) делает явный MAIN/NEW_TASK запуск
из процесса mNavi: display2 или0; только для2 добавляет windowingMode5 и
SplitScreenShownPosition0. Вход на2 использует mode3/DIM[1] и отложенный запуск;
возврат учитывает видимость отдельного dashboard owner перед возвратом mode1.

Это доказательство **наличия вызова в конкретном UI call chain**, не успешного
force-stop на автомобиле: shell result/фактическая смена PID данного нажатия
не получены. Сам c(...) пакеты не останавливает. Утверждение «mNavi всегда
останавливает приложение при любом переносе» отозвано. Наблюдение пользователя
без остановки не опровергнуто. Равным образом этот nav-specific путь нельзя
выдать за найденный перенос произвольного активного приложения.

Ключевые anchors (code item offset; SHA instruction bytes):

- OverlayService.k:0x7a260; `08c9961fb29d4cabfd0ebd32630a6e83283de37f00b7e9e1c0d2c2ab92ae3cce`.
- c1/d0.k:0x67554; `03db16b2f1b12336264c934b043eb5444d6897f022e610b9a90fe24500b4363f`.
- c1/a2.k:0x6cce4; `0b73985d87d1d548e9b84e068878b2c636df621e4201e81be6f7509670188dca`.
- OverlayService.b:0x79720; `7b1c81f47b1a030948c4b0f60f8facb88436a8d80f7abeb3be7f762e2be699b4`.
- OverlayService.c:0x79f54; `ce855f3f1a18660c9de4681385664ef5e688f28318c7ab029ffff53b61128134`.

В трёх доступных standalone APK байты одинаковы, поэтому это не три независимые
версии. Ограниченный просмотр DEX string tables и ресурсов YN mod30.3.0 не
выявил описанный generic menu handler; отсутствие искомых строк не объявляется
полным анализом всех его DEX/native путей. Для точного эталона нужен APK реально
используемой версии/уточнение самого меню, а не очередной полный QNX архив.

## MConfig48 и отличие текущего Natro

MConfig classes.dex SHA
`3e8ca7d2618ed01829943a17ec6466b7272773aec9281a48396784658414d704`.
vdr.s0x165e7c (instruction SHA
`ae36058811ab84b3cbe8cfa7d52f716dbfa8dacdf044300425d512d6bd393ca5`)
содержит mode3→100мс→DIM[1]→200мс→3 force-stop выбранной цели с50мс→bxa.s.
bxa.s0xafe28 (SHA
`d0370da861eb519bb189c714da68fbfe4a216dd126c67162ff4815d136243960`)
создаёт MAIN/NEW_TASK, display2/windowing5/split0 либо default-screen ветвь.
Есть возврат через cgz и наблюдатель GlobalNaviMode htu.onChange: при уходе
из3 очищает tracked set и запускает cleanup. Не все UI callers MConfig разрешены,
поэтому не заявляется, что каждая его команда переноса обязательно перезапускает цель.
White-bar AppOps — отдельная ветвь, её не следует смешивать с переносом Activity.

Natro baseline3609679: собственная InstrumentPanelActivity, singleTask с отдельной
affinity; same-process applicationContext.startActivity(display2/windowing5/split0).
Выключение отменяет coordinator, снимает cluster projection, завершает только свою
Activity/task и запрашивает mode1. Self-force-stop отсутствует. Внешний Navigator
handoff сохранён в compatibility API, но обычным launcher не используется.
Разница внешнего caller mNavi и same-process caller Natro установлена в SOURCE;
её причинная связь с текущим OEM-диалогом **пока гипотеза**, не доказанный фикс.

Уточнение пользователя09:35МСК переносит внимание на симметрию DIM, а не только
caller. В Natro off нет запуска панели на display0, команды DIM(2,8,8,[0])
и проверки другого dashboard перед возвратом mode1. Это установленные отличия
от разобранной ветви mNavi. Закрытие собственной выделенной страницы вместо
переноса навигатора на HU имеет иной смысл; перенос основной задачи настроек
Natro не является допустимым исправлением. Не выдавать отсутствие команды[0]
за доказанную причину диалога без проверки исполнения и реального окна.

Close worker и ACTION_CLOSE broadcast не имеют явного общего generation ID.
Это кандидат для отдельного concurrency replay; DIM lane уже последовательная,
поэтому одной строки без guard недостаточно для заявления о воспроизведённой
гонке или причине пользовательского диалога. Эти механизмы пока не менялись.

## Продолжение и приёмка

1. Установить точный APK/пункт меню из пользовательского сценария; проследить
   UI→выбор активной task→операцию переноса→обратную операцию. Отдельно проверить
   результат stop-команд, если они существуют; не выводить успех из наличия вызова.
2. Реализовать тот же подтверждённый способ адресации дисплея для своей панели,
   сохранив Natro/HUD/ANCS и настройки на HU. Не переносить произвольное активное
   приложение пользователя вместо панели, не добавлять force-stop/reboot.
3. Синтетически проверить on→off→on, поздний DIM/token/broadcast callback,
   отсутствие дисплея и неподтверждённый launch; затем полную Android CI.
4. GATE-059/066: не менее10циклов на стоящей машине; без OEM-диалога, без потери
   HUD/ANCS/медиа, без reboot, без переноса настроек на приборку. Сопоставить
   before/after task/display, PID и фактическое изображение. API accepted не равно FIELD.
5. После отдельной доставки collector fix получить адресные PNG вместе с фото
   приборки, затем продолжить план NEXT_STEPS_RU.md. Владелец крыльев и независимое
   скрытие остаются OPEN; новая команда всего NaviMode не считается их скрытием.

SOURCE collector и исследование готовы к публикации. TEST локальный указан выше;
полная Android CI этой ревизии не выполнена. APK нового нет, update channel не менялся.
FIELD переключения и renderer/hide OPEN. Полный семантический разбор всех архивов
или всех вариантов mNavi/MConfig не заявляется.
