# Natro 3.3.8 — Алиса и восстановление автозапуска

[Изменения и установка пары APK](release-notes.md).
SOURCE `52069153bee7afd9447a88ef6a5ab803bf7fcfe8`, tree `e9693676836ae6e88dd62c4c5ef8ac460f62618e`.
[Полный CI40096694911](https://github.com/AGKulikov/Status/actions/runs/40096694911) SUCCESS:
2223 Android-теста (436 XML suites), 279 tool/replay tests и108 Java policy/source cases;
failures/errors/skipped0. Settings audit48/253/3017, backup inventory92 PASS.

Новый Navigator необходим для Алисы: старый APK3.1.4 не имел dd.call_alice receiver.
В парном APK изменён только собственный classes19.dex;17226 остальных payload
entries, manifest/resources/native libraries сохранены. Проверено совпадение
неизменяемого payload закреплённого baseline2.9.8 и последнего выданного3.1.4.
Navigator сохраняет нативные versionName30.3.0/versionCode739564630 по контракту
одного DEX; имя файла обозначает парную версию Natro. Helper прежний.

Оба APK: прежний единственный signer, v2/v3, точный API28 и zipalign16KiB PASS.
Natro3.3.8/code208021371: +1 против фактической подписанной3.3.7,1517 CI payload
entries неизменны. Подпись/manifest/provenance/полный XML recount проверены.

| APK | SHA256 | Размер |
|---|---|---:|
| Natro-3.3.8-signed.apk | `ee7294017a970ed9eb640a87fc1c46bd9a34a4376f5fa5954de4ec656f482d95` |29842016|
| Navigator-30.3.0-Natro-3.3.8-signed.apk | `47484bb4a1fff020d1426638ea8c65e19bb828b544c7f78910e3787521e5c5cd` |156587626|

[Отчёт Natro](release-report.json), [подпись](signature-verification.txt),
[отчёт Navigator](navigator-release-report.json), [точный API28](navigator-api28-verification.txt).
Приватные ключи после подписи удалены; proprietary APK не публикуется в GitHub.

| Требование | SOURCE / TEST / APK | FIELD |
|---|---|---|
| ALICE-338-R2: адресный приём, сохранение фона/окна, cold launch только при отсутствии | PASS: receiver/request/native-route code, protocol tests, paired DEX | OPEN: фактический голос и состояние окна на KX11 |
| MUSIC-ALICE-338: исходный PLAYING, finish+2000ms, отмена ручным transport | PASS: 1999/2000ms, no-start/no-finish/abort/manual cases, generation до async capture | OPEN: реальный плеер/длинный диалог/фон |
| BOOT-338: music discovery/bounded retries, HOME recovery | PASS: resolver/policy tests, ранее существующий portable navigation replay | OPEN: cold boot; причина native ANR не установлена |
| Backup/install-over | PASS:335…370→371, unknown/effective values; +1/signer/identity | OPEN: установка поверх и runtime restore |

PUBLISH OPEN до publisher readback всех7assets; последующий статус фиксируется ниже.
Источники и ограничения: [Алиса](../../geely-kx11/ALICE_2026_10_24_RU.md),
[журнал boot](../../geely-kx11/BOOT_2026_10_24_RU.md). Android ANR в журнале
подтверждён, его стек отсутствует: причина зависания не объявляется исправленной.
