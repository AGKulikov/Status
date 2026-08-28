# Патч Яндекс Навигатора 30.3.0 для Natro

Этот каталог содержит только написанный с нуля код интеграции. Исходный/декомпилированный код и
сам APK Яндекс Навигатора в git не добавляются.

## Уже реализовано в исходниках патча

- один lifecycle/intent entry point с четырьмя точечными вызовами без нового компонента в
  manifest; окно включается в конце `onResumeFragments`, как в рабочем 29.4.2,
  после создания оконного токена Activity и MapKit;
- оконный контроллер собственной `MapActivity`;
- внутренняя кнопка «окно / полный экран»;
- перемещение, resize, независимые locks, сохранение геометрии, aspect lock;
- скругление, фон, opacity, border, shadow и настройка кнопки;
- совместимость с существующими командами Natro: `navi_win/...`, `ddnavwin`,
  `ddnavforcewinfull` и оконные deep links;
- explicit bind к Natro с проверкой UID, package и совпадения сертификатов в обе стороны;
- получение независимой конфигурации карт/окна;
- lifecycle настоящего HUD Surface и отдельный MapKit `OffscreenMapWindow` через штатные
  `SurfaceFactory.from`/`MapWindow.addSurface`, без screenshot/ImageReader/покадровой передачи
  bitmap;
- адаптер активной сессии 30.3.0 без обхода приватных полей: primary `MapWindow`, NaviKit
  `Guidance`, automotive `Guidance`, `RoutePosition` и `Windshield`;
- versioned snapshot с координатой, курсом, скоростью, точным остатком пути/времени, ETA,
  манёвром, улицей, лимитом, полосами и светофорами; скорость и лимит переводятся из
  MapKit SI (м/с) в км/ч;
- единственный владелец камеры HUD (поток Guidance), плавное перемещение без конкурирующего
  anchor у `UserLocationLayer`, независимые zoomDelta/tilt/focus и прямой рендер активного
  `DrivingRoute.getGeometry()` со своими цветом/обводкой/толщиной маршрута;
- цветовые сегменты пробок из `DrivingRoute.getJamSegments()` в одной полилинии с отдельной
  настраиваемой палитрой и обновлением через `ConditionsListener`;
- программный custom cursor через штатные `UserLocationObjectListener`/`ViewProvider`, без новых
  drawable/resource entries в APK;
- применение независимого `mainMap` к настоящему `MapWindow`: camera/focus/scale/FPS, day/night,
  POI/модели/JSON-стили, собственный маршрут и курсор с восстановлением исходных подслоёв при
  отключении профиля.

Цвета пробок непосредственно на активном маршруте независимы для `mainMap` и `hudMap`. Общий
фоновый слой пробок основной карты пока остаётся штатным слоем Навигатора: публичный
`LayerIds.getJamsLayerId()` в этой версии помечен устаревшим, потому что отдельного jams-layer
больше нет. Код намеренно не ищет его через приватные поля 30.3.0.

Старое покадровое копирование не используется.

## Сборка из точного baseline

1. Собрать изолированный `classes19.dex`:

   ```bash
   ANDROID_SDK_ROOT=/path/to/android-sdk \
     tools/build_navigation_patch_dex.sh build/navigation-mod
   ```

2. Подготовить выровненный, но ещё не подписанный APK. Нужен apktool 3.0.3 с SHA-256
   `dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423`:

   ```bash
   APKTOOL_JAR=/protected/tools/apktool_3.0.3.jar \
   ZIPALIGN=/path/to/android-sdk/build-tools/36.0.0/zipalign \
     tools/build_navigation_mod_30_3.sh \
       /protected/YN_30.3.0_T2_.apk \
       build/navigation-mod/classes19.dex \
       build/navigation-mod/YN_30.3.0_Natro-unsigned.apk
   ```

3. Подписать тем же защищённым release-ключом, которым подписан Natro. Пароль передаётся только
   через environment:

   ```bash
   KEYSTORE_FILE=/protected/natro-release.jks \
   KEY_ALIAS=status-widget-ha \
   KEY_PASSWORD='...' \
   APKSIGNER=/path/to/android-sdk/build-tools/36.0.0/apksigner \
     tools/sign_navigation_mod_30_3.sh \
       /protected/YN_30.3.0_T2_.apk \
       build/navigation-mod/YN_30.3.0_Natro-unsigned.apk \
       build/navigation-mod/YN_30.3.0_Natro-signed.apk
   ```

Последняя команда завершается успешно только если manifest/resources, Passport DEX, существующий
DEX мода, assets и native libraries остались побайтно равны рабочему 30.3.0, изменён ровно
`classes4.dex`, добавлен ровно `classes19.dex`, а итоговая подпись совпала с сертификатом Natro.

Для парной подписи Natro и Навигатора одним стабильным сертификатом используется
`tools/sign_navigation_hud_v2_pair.sh`. Скрипт принимает только unsigned-кандидат из CI,
проверяет его `SHA256SUMS.txt`, не копирует baseline Навигатора в результат и требует сертификат
SHA-256 `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
Финальная парная сборка дополнительно создаёт `KX11-COMPATIBILITY.txt`: там проверяются
Android 9/API 28, ABI `arm64-v8a`, исходная идентичность manifest/resources Навигатора,
геометрия основного окна `1760×720` и HUD `728×190 @ (0,720)` на Display ID 2.
Порядок безопасной установки и аппаратный чек-лист находятся в
`docs/KX11_NAVIGATION_HUD_V2_INSTALL_RU.md`.
