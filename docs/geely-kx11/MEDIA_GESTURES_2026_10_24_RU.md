# Медиакнопки: серии и удержание — 24.10.2026

База: AGKulikov/Status, release/natro-3.3.5,
06c53c2 (source APK ed846b2b7f6514a2b6a1a9392d5769eeb2efe686, code208021368).
Разработка: feature/media-gestures-20261024, кандидат3.3.6/code208021369.

## Источник и уровень доказательства

Приватный XSFInputService.apk, SHA256
`c4e380408d952298370ce8f9df177e902233c2a24825f15a02c1dbf371fccc14`,
проверен по DEX на этой дате. APK/DEX/декомпилят в Git не включены.
Метаданные выбранных методов и code SHA — в MEDIA_GESTURES_2026_10_24_SOURCE.json.
Это STATIC одной версии XSF, не доказательство физической доставки на всех прошивках.

- R play/pause имеет физический код200085, next200087, previous200088.
  Не путать обычный ECarXMediaPlayPauseAction85 с рулевым R вариантом.
- InputImpl.KeyEventListenerStub пишет onKeyPressed/onKeyReleased с cookie/keyCode
  до вызова callback. XSF cookie0; копия HMI cookie1 исключена для MEDIA.
- onShortClick создаёт готовые Android85/87/88 пары с новым uptime. Это уже
  распознанный клик, его DOWN/UP не измеряет физическую длительность.
- R play/pause.onLongPressTriggered также создаёт пару85. Next/previous во время
  удержания создают90/89 с repeat; эти события нельзя считать вторым кликом.
- XSF имеет отдельные call/DIM/gallery/CarPlay ветви. InputImpl raw code сам по
  себе не даёт права запускать media-назначение: нужно свежее NEDIA того же press.
- Native LCFG durationHoldShort загружается из API; fallback1500мс.
  Порог Natro1000мс не равен гарантированной задержке действия: до подтверждения
  native media-route выполнение ожидает, затем ограничено750мс. Другой LCFG
  или отсутствие log/route дают диагностический отказ, а не выдуманный long.

## Реализация

MEDIA использует общий ButtonGestureEngine и каталог ButtonAction. Три группы
mediaPrevious/mediaPlayPause/mediaNext имеют независимые1/2/3/long. Серия600мс
ожидается только при назначенном следующем количестве кликов, третье отпускание
немедленно. Второй DOWN отменяет таймер первого короткого до своего отпускания
или long. Повторный DOWN не перезапускает таймер. Long подавляет короткое даже
при пустом long-назначении, как у SRC/VA/★.

MediaGestureGate сопоставляет физические edges по исходному времени с реальной
NEDIA ветвью, включая обратный порядок доставки. Не требует новых XSF патчей:
прежний MEDIA→NEDIA патч включается прежней явной настройкой. Коды звонка и меню
не перехватываются. Без изменённых назначений MEDIA идёт по прежнему пути.
Для single default создан action109 «Обычное действие медиакнопки», выбранный
плеер по-прежнему определяет AudioManager/штатный SRC. Генерируемая для него пара
резервируется в прежнем identity cache и не распознаётся повторно.

При настроенном ключе его native DOWN/UP/repeat поглощаются одним владельцем;
подтверждённое назначение выполняется один раз. Просрочка750мс, потеря reader,
переполнение и смена настроек/маршрута сбрасывают ожидающие события. Синтетический
короткий без physical UP не подтверждает long. Ни shell, ни запуск приложения
не выполняются в таймерах input-потока. Это расширение ограничено мультимедиа.

## Хранение и совместимость

Существующий DE vehicle_buttons, binding.<group>.<gesture>.{action,app,command,
package,shortcut}; типы/Apply/Cancel/общий backup сохраняются. Single default109,
остальные0. Отсутствующее назначение не включает перехват, явный0 подавляет
single. Нет новых namespace/identity/persistent roots. Старые ключи сохраняются.
Defaults335…368→369 рассмотрены отдельно, будущее370 и334 отклоняются.

## Проверка и следующий шаг

10 production replay tests PASS: три кнопки/1–3 клика/удержание/повторы,
короткое→удержание, обратная доставка, missing UP, чужой native key и отсутствие
media-route, stale/reset, раздельные кнопки, превышение600мс, явныйNONE и лимиты.
7 прежних media/physical replay PASS. Добавлены Android Apply/Cancel и migration
regression; полный CI/APK ещё OPEN. Аппаратные проверки не выполнялись.

FIELD gate MEDIA-GESTURES-336, GATE-079/095: каждая кнопка/жест, отсутствие
двойного действия/залипания/позднего воспроизведения, родные кнопки при отсутствии
назначений/OFF, Android/штатный SRC, звонок/DIM/gallery, cold/update/Apply/Cancel,
backup и3×30мин. Отдельно измерить source edge→native route→gesture→player;
подтверждение отправки не считать слышимым звуком. Если native позже1750мс,
по журналу уточнить конкретный LCFG, не расширять stale TTL автоматически.


## 24.10.2026 — 3.3.6: SOURCE/TEST/APK проверены, FIELD OPEN

SOURCE `dd1a2b78fd86e6dadfffdfc1db2343ba42a987cf`, tree `22a7d0e908fc84af69698926066c15d7c65db623` опубликованы в
feature/media-gestures-20261024; удалённое дерево совпадает с локальным.
CI [40076243146](https://github.com/AGKulikov/Status/actions/runs/40076243146)
SUCCESS:2194 Android-теста /429 XML suites и279 tool/replay tests,
failures/errors/skipped0. Settings audit48/253/3017 иbackup inventory92 PASS.

APK3.3.6/code208021369,29821536bytes, SHA256 `8fb5419e039ba90b0377344e7c684d0178cec9db3ad4ff7352c45fffe0dd8372`.
Один прежний signer, v2/v3/точныйAPI28, package/label/non-debug/zipalign16KiB,
versionCode+1 против фактической3.3.5 и1517 неизменных CI payload entries PASS.
Navigator classes19.dex имеет прежний SHA710af1b6…; Helper не менялся.
Дополнительная установка Navigator/Helper для этого обновления не требуется.
Публичный signature-only handoff не содержит ключа/пароля. Публикация
stable/latest выполняется через draft/readback всех7assets; до её результата
PUBLISH OPEN. Физическая установка/сохранение данных иMEDIA-GESTURES-336,
GATE-079/095 остаются FIELD OPEN: ни повторяемость на руле, ни фактический
отклик под нагрузкой без аппаратной трассы не объявлены подтверждёнными.

## 24.10.2026 11:11 МСК — 3.3.6 опубликована

Stable/latest: release422602052, natro-v3.3.6, draft=false/prerelease=false.
Publisher40077041237/job120822527435 SUCCESS; tag указывает на source
`dd1a2b78fd86e6dadfffdfc1db2343ba42a987cf`. Все7assets скачаны и побайтно
сверены publisher; затем независимо скачаны по публичным ссылкам и проверены
по SHA256/размеру/SHA256SUMS. Манифест содержит3.3.6/code208021369 и SHA
`8fb5419e039ba90b0377344e7c684d0178cec9db3ad4ff7352c45fffe0dd8372`.
SOURCE/TEST/APK/PUBLISH PASS в указанном объёме. FIELD MEDIA-GESTURES-336,
GATE-079/095 и фактический install-over с сохранением настроек остаются OPEN.
Временные приватные копии комплекта подписи удалены; постоянный резерв сохранён.

## Дополнительный вопрос: лайк

В3.3.6 LauncherMediaController.like() обслуживает экранное сердце, с
переключением текущего USER_RATING/notification-state и резервом через
предоставленное плеером notification action. Отдельного ButtonAction или
LauncherShortcutStore.Builtin для лайка нет. Перенос в каталог физических
назначений ещё не выполнен; ответ пользователя о требуемом добавлении
не подменяется заявлением о готовой функции. Это SOURCE, FIELD не выполнялся.
