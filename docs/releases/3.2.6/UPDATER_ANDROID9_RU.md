# Ошибка проверки подписи в updater Android 9

FIELD17.10.2026 16:15МСК: IMG_8799.png, установленная Natro3.2.4,
собственная проверка остановила загрузку с сообщением о несовпадении подписи.
Пользователь сообщил об успешной ручной установке скачанного APK. Версия
после установки и сохранность всех настроек отдельно на скриншоте не видны.

## Причина в исходниках

NatroUpdater передавал getPackageArchiveInfo только GET_SIGNING_CERTIFICATES.
В AOSP Android9 вызов PackageParser.collectCertificates находится под проверкой
GET_SIGNATURES; без этого getPackageArchiveInfo возвращает пакет без SigningInfo.
Прежний stableSigner превращал null в false и показывал ошибку чужого ключа.
Это детерминированный дефект API28-пути; фактический объект PackageInfo с
магнитолы не получен, поэтому точное внутреннее состояние её OEM framework
не объявляется снятым. Firmware/framework APK не модифицируются.

Первичный источник, проверенный через GitHub API17.10.2026:
[AOSP android-9.0.0_r1 PackageManager.getPackageArchiveInfo](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/content/pm/PackageManager.java).
Robolectric4.17 ShadowPackageManager пропускает незарегистрированный архив
в реальный framework parser. Его default ShadowSigningInfo заменяет данные
конструктора; регрессия использует call-through shadow без implementations.

## Исправление

Оба флага запрашиваются при чтении APK и установленного пакета. Проверяется
единственный getApkContentsSigners current certificate, SHA256:
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
Legacy signatures и signing history не используются для допуска. Неизвестная
подпись остаётся отказом. Ошибки «не получен», «несколько», «чужой» различены,
подписаны источником «Скачанный APK»/«Установленная Natro», попадают в прежний
журнал. Размер, полный SHA, пакет, minSDK, versionCode/name, HTTPS allowlist,
повторная проверка перед installIntent и системное согласие сохранены.

## Проверки и их границы

Fixture — настоящий публичный Natro3.2.5, SHA256
`d3fed86ca73c76d1ff10eb3a89a22a731abc50df33ab1782e0986aa164a2daf6`.
CI скачивает только этот release asset и проверяет SHA до tests. В Git хранится
лишь публичный X.509 сертификат, извлечённый apksigner --print-certs-pem.
API28 regression: прежние flags/SigningInfo null, оба flags/current stable,
полная verify, hash refusal, equal-version refusal, повторная проверка
изменённых bytes при installIntent. База установленных пакетов моделируется.
Отдельная fault matrix: missing/null/empty/multiple/wrong current, installed
и archive labels, запрет fallback к legacy/history. Это не FIELD installer.

На момент SOURCE-записи CI/APK/FIELD3.2.6 OPEN. Итоговый протокол выпуска
дополнит результат. Для первого перехода со старой версии потребуется ручной
install-over3.2.6: исправленная проверка начинает работать после её установки.
Полная приёмка канала: следующая более новая stable версия скачивается через
HU/LAN, проходит проверку и обновляет Natro с сохранением настроек на KX11.

## Отдельный QNX эпизод

В16:22:38 на IMG_8800.jpeg прямой nc получил TCP RST/ACK от .2:8000.
Это отдельный FIELD отказ до QCONN greeting, не следствие updater и не
доказательство конкретного QNX процесса/фильтра. Записано в технической базе.
