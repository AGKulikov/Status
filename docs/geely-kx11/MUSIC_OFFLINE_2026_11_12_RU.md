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

## Native diagnostics implementation candidate
USER requests actual reason/time logging, not only network snapshots. Added
NativeWaveDiagnostics and exact-schema DEX entry hooks in Lnz0;.a(String,Map)
and Lzzh;.a(int,String,String,Throwable), both classes.dex in pinned MusicNatro3.
Hook tool verified on actual pinned classes.dex and synthetic high-register,
missing-schema and double-patch rejection fixtures. Native bodies retained.
Telemetry allowlist: OFFLINEWAVE_START(reason), PREFETCH_TRIGGER(decision,reason,
ratio/threshold/wait), ERROR (unknown native error, raw message excluded).
Native log allowlist: online-result-selected, keep-offline-after-online-failure,
online-migration-started. Migration attempt is not labelled completed playback.
PID, native elapsedRealtime and sequence correlate with Natro bootstrap.

Natro observer starts from existing diagnostics worker with existing READ_LOGS;
continues after boot autoplay completes. Sanitized events enter journal.log and
observations.txt; manifest includes observer state/losses/limits. No READ_LOGS or
debug off means capture unavailable; no event is never interpreted as online.
Transport is passive logcat (not authenticated); events never trigger actions.
Bounded 200-line tail/256-event dedupe/20 per second; ring rotation can lose events.
Native logging may be absent if no hook APK installed. No native user data in repo.

Required pair: new Natro with observer AND MusicNatro4 hooks+classes7. Existing
Natro3.4.7/Music3 cannot provide this channel. prepare_music_wave.py produces
unsigned Natro4 payload only (24026525); signature/install-over/release gates
remain mandatory and open. No new APK issued yet. Android/export tests pending CI.

## 13.11.2026 — closeout review in progress
The earlier two-hook candidate missed grouped analytics: actual kwg.c0 calls nz0.b(lc0,String), not nz0.a(String,Map). A third passive hook captures this path via the pinned public lc0 fields. Added native wall/elapsed clocks, thresholdType (ratio vs duration), and allowlisted exception categories without raw error text. Observer ingestion is exercised through the real captureLine path into the diagnostic ZIP, including replay deduplication and cancelled-generation rejection. The host coordinator fixture now supplies the read-only network snapshot dependency introduced on12November; the omitted fixture was a compile error, not a device failure. SOURCE candidate; full Android/DEX/APK and FIELD still pending.

## 13.11.2026 — release review
Targeted Android CI43365616986 for fbc144d6 completed successfully. Source review found an observer tail-replay defect: an arrival-time20/s limiter discarded later native events replayed together after boot. Rate windows now follow native PID/elapsed seconds; generation invalidation is serialized with ingestion. A40-second buffered-history regression verifies the final online result remains in the ZIP. Capture retains2000tail lines and2048dedupe entries (the earlier200/256 description is superseded). Buffer rotation and source bursts over20events/second remain explicit loss limits.

The pinned native fox.a branch logs ONLINE_RESULT_SELECTED only after a completed online prolongation result; fox.b logs ONLINE_MIGRATION_STARTED before awaiting migration. The latter is never reported as successful playback. Three entry hooks are reversible: removing them must restore the complete canonical native DEX, including method bodies/metadata. This is static APK verification, not execution on ECARX. Release pair: Natro3.4.8 and MusicNatro4.
