# Natro 3.1.6 — 14.10.2026

Подписан APK ru.natro.statuswidget, versionCode208021349. Обновление поверх
3.1.5/208021348 с прежним сертификатом; фактическая установка на KX11 ещё не проверена.

## Состав

- QNX: отдельный qconn file stream на каждый из6 разрешённых файлов,60с на файл,
 180с на весь системный dump. Обычная консоль сохраняет60с. После транспортной или
 протокольной ошибки повторов и альтернативных диалектов нет.
- В ZIP добавлен qnx-diagnostics.txt: этап, путь, offset, RX/TX, время, ограниченный
 служебный ответ, исключение и suppressed deadline. Manifest различает отказ
 текущего файла и оставшиеся непроверенные. Собранные Android/QNX payload сохраняются.
- Список приложений: отдельная форма без автоматических разделов/preview,
 подтверждение «В общий черновик» и объяснение последующего «Сохранить».
- Узкий редактор: разделы сверху, параметры занимают ширину формы,
 предпросмотр отдельной кнопкой; высота диалога оставляет место подтверждению.

Сверка с исходным пользовательским KX11-QNX-Deep-Evidence.zip от15.08 описана
в PROJECT_REQUIREMENTS_RU.md и GEELY_KX11_KNOWLEDGE_RU.md. Проверены142 SHA256
payload. Production parser локально воспроизвёл control headers попытки00450
с реальным qcdisplaycfg.xml; proprietary payload в Git не помещён. Это replay,
а не физический запуск нового сборщика. Причина отказа3.1.5 неизвестна.

## Происхождение и проверки

- Build commit:7c0fb5877c927024b324ff2373dd9ef67e762b11.
- Build tree:a7f7e291182228591523a90c052799967ed57e89.
- [Build CI38506959645](https://github.com/AGKulikov/Status/actions/runs/38506959645): success.
- [Source CI38506959513](https://github.com/AGKulikov/Status/actions/runs/38506959513): success.
- 2135 Android unit-тестов,422 suites:0 failures/errors/skipped;245 tool/replay tests:OK.
- Проверены storage inventory85 owners и editor inventory48 sources/253 forms/3010 labels.
- Загруженный CI ZIP совпал с artifact digest; все21 изменённые локальные файлы
 совпали с опубликованными Git blobs. Новые collector markers присутствуют в DEX.
- Подпись v2/v3, один стабильный сертификат, zipalign16KiB;1516 payload entries
 не изменены подписанием. minSdk/targetSdk28, release non-debuggable.

SHA256 APK:
`d3a1f6f6a31503980efbc1c5206d7d4d39baabaee6d32d08dbfee55bb8c69e27`

SHA256 сертификата:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`

Полные метаданные:release-report.json и signature-verification.txt. Использованы
ранее полученные официальные signing tools36.0.0; их хеши включены в report.
Ключ/пароль и закрытая прошивка не публикуются.

## Совместимость и полевая приёмка

Navigator classes19.dex SHA256710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96
побайтно совпадает с кандидатом3.1.5. Поэтому уже установленный совместимый
Navigator не требует обновления; helperChanged=false. Общий staging manifest
по-прежнему содержит консервативный pairRequired=true: это не доказательство
изменения Navigator в этом выпуске.

На KX11 остаются OPEN: установка, фактическое чтение QNX, отмена во время сетевого
ожидания, ширина редактора и сохранение списка после выхода/перезапуска.
Для проверки: установить поверх3.1.5, выбрать приложения → «В общий черновик» →
«Сохранить», повторно открыть список; в журнале снять новый системный dump и
передать ZIP с manifest.tsv/qnx-diagnostics.txt. Наличие старого QNX успеха
не позволяет заранее отметить новые аппаратные проверки выполненными.
