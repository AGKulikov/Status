# Natro3.3.9 — межпроцессный callback Алисы

[Установка и изменения](release-notes.md).
SOURCE `7a10536ae572f425d82d29bca37d9e3478998ffa`, tree `4f9f053ca280c42492fab771c8708509a6b593b9`.
CI[40171265473](https://github.com/AGKulikov/Status/actions/runs/40171265473) SUCCESS:
2228 Android tests,279tool tests,108Java cases;0failures/errors/skips.
Settings48/253/3017, backup92 PASS. Старый callback воспроизводит
BadParcelableException/ClassNotFoundException с foreign loader; новый framework
proxy декодируется и возвращает события через настоящий Android ResultReceiver.
Default Robolectric send shadow отключён только в этой группе.

Natro3.3.9/code208021372,29842016bytes, SHA256 `dcddcaedec1b3e9bd9be8c2f0e8f0d3b4a528f9c84026d8f7b3f66a8a29ac1cd`.
Navigator30.3.0/code739564630, pair3.3.9,156587626bytes,
SHA256 `52736b41714bba696cd54442804816741e2834b57a0d880702560667106a89dc`. Только classes19.dex изменён против последнего3.3.8
и закреплённого baseline;17226 прочих payload entries сохранены.
CI patch SHA `1aa6f536cf9464a1b0d32da5abfc47d7a528e5b9d509417dad41acb619956490`.
Один прежний signer,v2/v3/exactAPI28/zipalign16KiB PASS; Natro+1 против3.3.8,
1517CI payload entries unchanged. Полный XML recount и manifest/provenance PASS.
Приватные временные ключи удалены; Navigator передаётся отдельно. Helper прежний.

| Требование | SOURCE/TEST/APK | FIELD |
|---|---|---|
| ALICE-339: безопасная межпроцессная команда | PASS: negative old/new round-trip + malformed decode | OPEN: голос на KX11 |
| ALICE-338-R2: фон/окно; cold launch только при отсутствии | прежняя логика сохранена, регрессии PASS | OPEN |
| MUSIC-ALICE-338: исходный PLAYING, FINISHED+2000ms, manual cancel | прежняя логика и тесты PASS | OPEN: реальный плеер/диалог |
| BOOT-338 | прежний recovery сохранён | OPEN: новые waits были до обновления3.3.8 |
| install-over/defaults | подпись/+1/335…371→372 PASS | OPEN: фактическая установка/restore |

PUBLISH OPEN до draft/readback7assets/stable/latest. Исторический FIELD3.3.8
FAILED; точный exception в исходном ZIP отсутствует. Не объявлять исправленным
любой native ANR только на основании теста IPC.
[Разбор и пределы](../../geely-kx11/ALICE_2026_10_25_RU.md).


## 25.10.2026 10:11 МСК — 3.3.9 PUBLISH PASS

Stable/latest natro-v3.3.9, release423206375. Publisher40171787487/
job121103962418 SUCCESS; все7assets скачаны и побайтно сверены до публикации.
Tag/source7a10536ae572f425d82d29bca37d9e3478998ffa; CI40171265473 PASS.
Оба подписанных APK сохранены для передачи, Navigator приватно; Helper прежний.
SOURCE/TEST/APK/PUBLISH PASS в указанном объёме. FIELD новой пары OPEN;
прежний отказ3.3.8 сохранён. Временные приватные копии ключей удалены.
