# Natro 3.1.7 — 14.10.2026

Подписан Natro3.1.7/code208021350, пакет ru.natro.statuswidget, прежний сертификат.
Проверены метаданные установки поверх3.1.6; физическая установка на KX11 OPEN.

## Что изменено

- Локальные предупреждения обоих порогов заряда требуют точного процента и
  совпадающего источника. Helper/coarse HFP не вызывают предупреждение и не
  сбрасывают уже показанный latch; отложенный показ перепроверяет текущие данные.
- Белая полоса: mode3 использует `ignore` вместо жёсткого `deny`; вне mode3 и
  при отключении возвращается `allow`. Защита штатного меню и gates восстановления
  навигации не ослаблены. Устранение физического повтора полосы пока не доказано.
- Для анализа повторов добавлены события phone-battery с источником/уровнем и
  реально выполненным local_exact показом, без текста ANCS и идентичности телефона.

Подробности и границы: [SOURCE_REVIEW_RU.md](SOURCE_REVIEW_RU.md).
Разбор крыльев: [QNX init/UART](../../geely-kx11/research-2026-10-14-dim/README_RU.md).
Способ отдельного отключения крыльев с сохранением карты не найден; патча нет.

## Проверки и происхождение

- Build commit: `097af8b880ac56a0c179e0e9af0a2270230a5cf5`.
- Build tree: `c9e43fa7b3a1f2c686a35f730f56cf838d3f676b`.
- [CI38514899594](https://github.com/AGKulikov/Status/actions/runs/38514899594): SUCCESS.
- 2140 Android-тестов,422 suites;246 tool/replay tests;108 Java policy/source
  cases. Failures/errors/skips=0. XML2140/422 пересчитаны независимо.
- Storage inventory85 owners, editor inventory48 sources/253 forms/3010 labels.
- Первый CI38513092817 остановился на двух пропущенных JUnit imports; исправлен
  тестовый исходник. Ни APK, ни результаты неуспешного прогона не использованы.
- Локальные replays до CI:241 tests,4 environment skips,0 failures; это не
  подмена полного CI246/0 skips.
- Загруженные artifacts совпали с GitHub digest: candidate ZIP
  `1a1f974d2e9e0c876826f014f27717a8807ae37e440c3bc5006b355b3dea602a`,
  tools ZIP `98939e7fe9e4e80d7df724ecdae7bf7226ca73eb8961f7e6a3fd4b2cd56fe139`.
- Дерево исходников на GitHub совпало с локальным, candidate.json содержит
  соответствующие commit/tree. Новые exactPhoneBatteryPercentage,
  whiteBarAppOp и local_exact diagnostic markers присутствуют в classes2.dex.
- Проверены v2/v3, один stable signer, zipalign16KiB, minSdk/targetSdk28,
  release non-debuggable и сохранение всех1516 payload entries после подписи.
  Все signing tools побайтно совпали с проверенным комплектом3.1.6.

APK SHA256:
`fc2db6c080b491a0e9132d3fc074a23d15d0f6e361f2ab910db88f3d243bc273`.

Сертификат SHA256:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.

Машинные результаты: [release-report.json](release-report.json),
[signature-verification.txt](signature-verification.txt). Ключи/пароли,
пользовательские сырые логи и proprietary firmware не публикуются.

## Установка и оставшиеся gates

Обновлять Natro поверх3.1.6 без удаления приложения. Navigator patch
`710af1b6fa476a5e78c3e19809a59a89de70a698135f4b296c45df98962b0f96`
побайтно совпал с3.1.6; установленный совместимый Navigator менять не нужно.
Helper не изменялся. Консервативный staging pairRequired=true не означает,
что в этом выпуске изменён Navigator.

На KX11 проверить readback ignore в mode3, штатное меню/volume и возврат allow,
переходы сна/питания, два порога по точному источнику и отсутствие повторов после
reconnect. Возможный отдельный ANCS-системный дубль требует наблюдения и не
блокируется по догадке. После проверки нужен обычный диагностический ZIP;
прошивку для проверки этих двух исправлений изменять не требуется.
