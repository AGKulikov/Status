# Музыка: пересечение подготовки и PLAY

Источник: архив Natro-diagnostics-1100753096801331388.zip.
Music v24026523 (Natro 2), PID4902. Главный поток ждёт результат Media3
в onGetRoot; Media3Looper ждёт future в onPlay. Native session появляется
до завершения браузерной подготовки. Natro отправляет PLAY через эту сессию,
обходя ещё незавершённый bootstrap. Код подтверждает отсутствие барьера.

Исправлен порядок на стороне Natro: наличие session не разрешает cold PLAY
при незавершённой подготовке. Таймаут тоже не является разрешением; retries
остаются bounded, manual cancellation сохраняется. Это устраняет наблюдаемый
конкурентный путь, но не доказывает устранение всех native deadlock причин.

Полный APK/Android CI и FIELD пока OPEN. Нужен Natro3.4.5 с прежним Music2.


## 07.11.2026 — проверенный APK 3.4.5 и совместимость

Прежнее APK/CI OPEN выше — историческое состояние. CI42361490968 SUCCESS:
2283 Android /285 tool tests,0failure/error/skip. Подписанный APK содержит
`blocksColdPlay` и `paired_prepare_barrier`; stable signer и exactAPI28 PASS.
`music-mod` не отличается от b04f768, поэтому используется существующая Music
Natro2/code24026523. Новый companion APK не нужен. SOURCE/TEST/APK PASS,
FIELD остаётся OPEN: порядок запросов проверен кодом/replay, отсутствие native
deadlock на автомобиле не доказано. [Протокол выпуска](../releases/3.4.5/README_RU.md).
