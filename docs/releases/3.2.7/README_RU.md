# Natro 3.2.7 / 208021360

[Stable/latest natro-v3.2.7](https://github.com/AGKulikov/Status/releases/tag/natro-v3.2.7)
опубликован17.10.2026 в18:18:44МСК, release415876139. Publisher
[39044443773](https://github.com/AGKulikov/Status/actions/runs/39044443773),
job117430265859 SUCCESS: exact signed APK восстановлен из CI-кандидата и публичной
подписи без private key, повторно проверен; все7assets draft скачаны обратно
и побайтно сравнены до stable. GitHub latest/tag и digests сверены после публикации.
Tag указывает на точный SOURCE ниже. [Протокол публикации](publication-verification.json).

Докачка QNX включает исключения 977 проверенных файлов из полного и частичного
дампов, native lstat/readlink для целей ссылок, исправление ложной дедупликации
каталогов и расширенные канальные снимки. Исправление updater для Android 9 из
3.2.6 сохранено. [Инструкция и изменения](release-notes.md).

## Доказательства выпуска

- SOURCE: `74058cc3c3ebe6c3ae7d1d6b0f52f7ff6388081e`; tree
  `7e1c95f4ef5a16bf2c7dc22bb534844619204e13`. Удалённое дерево совпало с
  локальным1e31abe; публикация явно подтверждена пользователем17.10 в18:06МСК.
- CI [39043771400](https://github.com/AGKulikov/Status/actions/runs/39043771400),
  job117428317776 SUCCESS: 2174 Android tests,265 tool tests,108 Java cases,
  0 failures/errors/skipped. Settings/89-owner backup inventory, JavaScript,
  Navigator compilation и проверка resource внутри APK PASS.
- APK: 29 760 096 bytes; SHA256
  `8b8828c85542f9a126b959e77a1146d520c8cc0a395fc7f9643edcacc63c9434`.
  Package ru.natro.statuswidget, label Natro, min/targetSDK28, не debug.
- Один прежний сертификат
  `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`,
  exact API28/v3, v2 и zipalign16KiB PASS.1517 unsigned payload entries
  побайтно сохранены; добавлены только записи подписи. VersionCode+1 против3.2.6,
  install-over metadata PASS. Временные извлечённые ключ/пароль удалены.
- Фактические977исключений в APK совпали с исходным ресурсом; DEX markers
  нового сборщика присутствуют.
- Navigator classes19.dex побайтно равен3.2.6:
  `710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96`.
  Navigator/Helper обновлять только ради этого выпуска не требуется.

[Машинный отчёт](release-report.json), [подпись](signature-verification.txt),
[точный манифест](natro-update.json), [исходный отчёт подписи](signing-report-original.json).
В исходном отчёте generic navigatorPairRequired=true; последующее побайтное
сравнение подтверждает повторное использование, это отражено в окончательном
release-report. [Локальные проверки](local-verification.json) и
[история блокировки](PREPUBLICATION_HISTORY_RU.md) сохранены, новый CI заменяет
прежние локальные SKIP для технических проверок.

## Границы проверки

FIELD докачки/native link dialect на KX11 и реальная установка поверх остаются
OPEN. LAN визуально на устройстве в этом выпуске не проверялась. Крылья, белая
полоса, burst OEM callbacks и причина закрытого listener не объявлены исправленными.

Сохраняйте оба старых архива: новый ZIP — дополнение. При совпавших пути/размере
содержимое удалённого файла заново не хешируется; для полного снимка есть
«Повторить всё». Свежие журналы и mappings обновляются.

[Технический контракт](../../geely-kx11/research-2026-10-17-white-bar/QCONN_LINKS_RU.md),
[исследование крыльев](../../geely-kx11/research-2026-10-17-white-bar/VEHICLE_578306_RU.md).
