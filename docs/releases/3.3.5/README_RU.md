# Natro 3.3.5 — медиакнопки и уведомления светофоров

Версия **3.3.5 / 208021368**, пакет `ru.natro.statuswidget`. Подготовлен подписанный
APK для обновления поверх 3.3.4 / 208021367 без удаления приложения.
Публикация stable/latest ещё выполняется; готовность канала подтверждается отдельным readback.

## Что включено

- Активный приём `NEDIA` на отдельном потоке; общий контроль повторов с manifest receiver.
- Диагностика action/thread/event/received для обоих входов медиакнопок.
- Повторные уведомления светофоров, меняющие только время обновления, ограничены до 1 Гц.
  Новые показания, цвет, countdown, состав, положение и очистка передаются сразу.
- Порог свежести медиакоманд 750 мс сохранён: просроченные команды не исполняются пачкой.

Это изменения SOURCE/TEST/APK, **не доказанное устранение задержки на KX11**.
Причинная связь нагрузки светофоров и отказа кнопок остаётся гипотезой.

## Происхождение и проверки

- Ветка разработки и выпуска: `release/natro-3.3.5`.
- APK source: `ed846b2b7f6514a2b6a1a9392d5769eeb2efe686`.
- Source tree: `0b11b8520a668679005e951b71e86185493b2710`.
- [Полный CI 39904116513](https://github.com/AGKulikov/Status/actions/runs/39904116513): SUCCESS.
  2192 Android-теста в 429 XML-наборах; 269 tool/replay tests; ошибок и пропусков нет.
- Проверки настроек: 48 редакторов, 253 метода, 3012 labels; inventory 92 owners.
  Статический inventory не доказывает полноту runtime backup/restore.
- Повторно локально пройдены 8 handoff/update-channel/media-policy tests.
- Проверены архивные SHA-256, APK ZIP, один прежний подписант, v2/v3, точный API 28,
  package/label, minSdk/targetSdk 28, non-debug и zipalign 16 KiB.
- Все 1517 payload entries совпадают с точным неподписанным CI-кандидатом.
- Подпись и версия сравнены с реальным опубликованным APK 3.3.4.
- SHA-256 APK: `cbd4bf5fa474bbf0c13d5c3d12ea56d9030d777ab3d5dd3a4bc8b0f716510768`.
- Сертификат: `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

Defaults migration 335…367 → 368 покрыта `PreferencesDefaultsTest`: прежние
effective/explicit/unknown значения сохраняются. Физическая установка с сохранением
настроек на магнитоле пока не подтверждена.

`classes19.dex` побайтно совпал с кандидатом 3.3.4; SHA-256
`710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96`.
Существующий совместимый Navigator по-прежнему нужен, но переустанавливать его для
этого обновления не требуется. Helper не менялся. Исторический `pairRequired=true`
в candidate описывает необходимость совместимой пары, а не нового Navigator APK.

## Матрица приёмки

| Требование | SOURCE | TEST / APK | FIELD |
|---|---|---|---|
| BASE-MEDIAKEY / MEDIA_LATENCY | `MediaButtonController.java`, общий `MediaKeyPolicy` | Полный Android CI; duplicate/freshness replay; точный подписанный payload | OPEN: холодный/тёплый старт, обе очередности duplicate delivery, OFF/defaultSource |
| MEDIA_LATENCY, нагрузка светофоров | `NavigationDataRepository.java`, `TrafficNotificationPolicy.java` | 400 одинаковых пакетов / 20 с → 20 heartbeat, изменения/clear/reset немедленно | OPEN: причинность очереди и влияние на реальную задержку |
| GATE-079 | Указанные входы и диагностика | SOURCE/TEST/APK выполнены в указанном объёме | OPEN: 3 × 30 минут, отметки 10/20 минут, реальный плеер |
| REL-001…006, REL-010, REL-015 | Точный release source и прежний ключ | Identity, версия +1, defaults, сертификат, v2/v3/API 28, alignment, payload PASS | Физический install-over и сохранение настроек не проверены |
| REL-007…009, REL-013, GATE-004 | Публичные исходники и отчёты, прежний Navigator/Helper | Signature-only handoff; draft → readback 7 assets → stable/latest | Публикация выполняется; аппаратные gates не закрывает |

## Публикация

Publisher использует только публичную подпись и заголовки ZIP из `signing-handoff.json`,
не ключ или пароль. Он повторно проверяет точный CI, подпись, payload, XML и manifest;
создаёт draft, скачивает и сравнивает все 7 файлов, затем публикует stable/latest.
Тег должен указывать именно на APK source, а не последующий documentation commit.
Временные копии ключа и пароля удалены; исходный приватный резерв сохранён.

[Машинный отчёт](release-report.json), [подпись](signature-verification.txt),
[update manifest](natro-update.json), [описание](release-notes.md).
Скрытие крыльев, устранение задержки и прочие аппаратные критерии не объявляются выполненными.
