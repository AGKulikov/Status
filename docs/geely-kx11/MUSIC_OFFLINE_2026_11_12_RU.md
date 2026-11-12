# 12.11.2026 — повторные PLAY после готовности native session

Проверена пара Natro3.4.7/MusicNatro3, Android9 KX11. Фото пользователя
подтверждает надпись «Моя волна играет в офлайн-режиме» на треке «Души».
Архив Natro-diagnostics-4354946611094975696.zip остаётся приватным.

Последний boot: 17:46:38 подготовка, 17:46:42.416 session PLAY (STATE_NONE),
17:46:44.326 receiver PLAY, 17:46:46.516 session PLAY (BUFFERING),
17:46:48.526 ALREADY_PLAYING. Часы журнала Europe/Moscow.
configuredDelayMs=3000: в этом boot настройка3s, несмотря на прежнее сообщение2s.
В17:50:03 начался последний track_revision12, duration197640ms. Название в
журнале отсутствует; отождествление с «Души» основано на сообщении пользователя.
Нативных offline transitions/HTTP ошибок в архиве нет, root cause offline OPEN.

Ошибка SOURCE: KEY_YANDEX_SESSION_PLAY_ATTEMPTED запрещал unready-session PLAY,
но после этого код проваливался к receiver; BUFFERING принимался за пригодную
сессию для повторного PLAY. Новая ветка контроллера после подтверждённого
session dispatch вызывает только observePlayback. NONE/BUFFERING/ошибка/
отсутствие сессии не отправляют команды. PLAYING завершает план. Прежний
ограниченный бюджет наблюдения и manual/boot/enable guards сохраняются.
Сбой после отправки не вызывает автоматического перезапуска: по исчерпании
наблюдения план завершается, ручное управление остаётся доступным.

Добавлены сетевые snapshots при acquire/prepare/before_play: connected Wi-Fi,
тип active network и INTERNET/VALIDATED. Это диагностические признаки Android,
не доказательство доступности Яндекса и не определение режима волны.
wave_mode=unknown явно сохраняется; догадки об online по PLAYING исключены.
Схема хранения и пользовательские настройки не менялись, vendor patch не менялся.

SOURCE: candidate. TEST: targeted Android CI pending. APK: не выпущен.
FIELD: OPEN, устранение офлайн-режима и native-init side effects НЕ доказано.
Следующий шаг: native wave state/transition instrumentation по pinned Music APK;
не сбрасывать библиотеку и не перезапускать пользовательский playlist вслепую.

## Проверка опубликованного исправления
SOURCE 6db5fb18a01cf7b58793a3bdc97972f8adbe8665, tree90497316c1febcc787c940c3929cc35b104636f2.
CI43243646046/job131270417809 SUCCESS: production compilation and targeted
MediaAutoResume*, MediaResumeObservationTest, YandexColdSessionPolicyTest.
Backup inventory98owners PASS after schema-neutral controller review.
Это не полный release CI; APK не собран; FIELD OPEN.

## Дополнительный статический разбор штатной Музыки
Источник MusicNatro3 SHA25661be448f22cf3852a4a0ea12452b0e07adec60337dde5b8c1d423d459f1bbe9e.
В kwg (OfflineWavePrefetchTrigger) решение SWITCHED/KEPT_ONLINE зависит от
результата предварительной загрузки: PREFETCH_FAILED/LOW_RATIO и порогов.
Это отдельный путь от простой проверки наличия Wi-Fi. flx пишет событие
OFFLINEWAVE_START с reason; gbc перечисляет Prolongation, SkipToOffline,
CreateQueueWithSeeds, CreateQueueWithPassiveSession. В журнале Natro этих
событий нет. Не доказано, какой путь сработал в FIELD; сетевой успех телефона
не эквивалентен успеху предзагрузки Музыки. Чужой исходник/DEX не публикуется.
