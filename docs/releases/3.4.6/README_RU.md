# Natro3.4.6 + MusicNatro3 — кандидат

Подготовка Music через native Media3 binding без legacy onGetRoot, подтверждение
реальной session/looper перед адресным PLAY. Новая фильтрация отмены Navigator
recovery сохраняет touch/navigation и игнорирует media/volume/key-up.

SOURCE подготовлен локально. TEST LOCAL PASS: 10 сценариев Natro bootstrap,
12 сценариев native provider, политики восстановления Навигатора, проверки
версии и инвентаря резервного копирования (12 unittest methods всего).
Оба production Music класса скомпилированы с Java source/target 8; дополнительно
проверены 5 классов/11 public fields исходного Music APK и семантика manifest:
от MusicNatro2 отличаются только versionName/versionCode.

Полный Android CI, DEX и APK ещё не собраны; FIELD OPEN. Автоматическая проверка
отклонила push в AGKulikov/Status из-за недостаточного для этой операции
подтверждения публикации исходников/нового workflow в публичном репозитории.
Обход отказа не выполнялся. Следующий шаг после явного разрешения — опубликовать
подготовленную ветку, пройти CI, подписать и проверить обе APK прежним ключом,
затем опубликовать комплект и проверить холодный старт на KX11.

Старый Navigator3.4.2 и Helper не меняются. Native I/O задержка Навигатора не
объявляется исправленной; исправляется слишком широкая отмена recovery.
[Разбор3.4.5](../../geely-kx11/BOOT_345_FIELD_2026_11_07_RU.md).
