# Natro3.4.4 — восстановление холодного запуска

Исправлен повтор paired Music bootstrap после таймаута, запуск процесса отделён
от15s подготовки медиасессии в общем45s request deadline. До3запросов, без очереди
за зависшим IPC; manual/boot/token guards сохранены. Navigator recovery просыпается
при fresh window evidence; причины ожидания/отмены записываются в журнал.

SOURCE dffc9dea7e4458dfaae96bbde09d5722af033a71,
tree275fc1720efec82e17f5592cfd8ac87352690501.
CI42357581030/job128358946999 SUCCESS:2281Android/285tool,0failures/errors/skips;
8host Music сценариев проверяют production source. Inventory98/48editors/253forms/
3017labels PASS. Defaults335…376→377 иsame377, unknown/future rejection проверены.

APK3.4.4/code208021377,29973088bytes,
SHA2569dc7d907eb2a2a972de10f7ccb6fa08b2b059f2beafa3e7975f25f73c575577b.
Прежний signer6e9855…65c75, один сертификат,v2/v3/exactAPI28/zipalign16KiB,
+1 против реальной3.4.3 и1517неизменных payload entries PASS.
[Отчёт](release-report.json), [CI](ci-verification.json), [подпись](signature-verification.txt).

Navigator3.4.2 classes19/native tool hashes совпадают; новый Navigator не требуется.
Music/Helper не менялись. SOURCE/TEST/APK PASS в этом объёме, PUBLISH PASS: stable/latest release437459199, publisher42358341071 SUCCESS.
Все7assets независимо скачаны и сверены, APK побайтно совпадает.
[Подтверждение](publication-verification.json).
FIELD повторных cold boots/музыки/окна/сохранения данных OPEN. Устранение native
I/O задержек/Firebase ANR не заявляется: исправлены координация и восстановление.
[Источники, гипотезы, ограничения](../../geely-kx11/BOOT_343_FIELD_2026_11_07_RU.md).
