# Патч Яндекс Навигатора 30.3.0 для Natro

Этот каталог содержит только написанный с нуля код интеграции. Исходный/декомпилированный код и
сам APK Яндекс Навигатора в git не добавляются.

## Уже реализовано в исходниках патча

- один lifecycle/intent entry point с пятью точечными вызовами без нового компонента в
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
- HUD-режим «только дороги»: substrate без дорожных тегов скрывается, фон MapKit и принимающий
  Natro `TextureView` получают настоящий alpha;
- адаптер активной сессии 30.3.0 без обхода приватных полей: primary `MapWindow`, NaviKit
  `Guidance`, automotive `Guidance`, `RoutePosition` и `Windshield`;
- отдельный слой светофоров с координатой, сигналом, стрелкой и цифровым отсчётом для HUD и
  приборной карты; статичный сигнал без таймера в этот слой не попадает, у каждой карты свой
  переключатель, основной `MapWindow` не меняется;
- отдельная географически привязанная подсказка по полосам для HUD и приборной карты: позиция
  берётся из `UpcomingLaneSign.getPosition()`, а слой исчезает при неактивном маршруте или
  устаревшем образце;
- versioned snapshot с координатой, курсом, скоростью, точным остатком пути/времени, ETA,
  манёвром, улицей, лимитом, полосами и светофорами; скорость и лимит переводятся из
  MapKit SI (м/с) в км/ч;
- один фактический владелец камеры каждого внешнего `MapWindow`: примитивные Guidance-кадры
  применяют точные zoomDelta/наклон/focus; обязательная камера минимального automotive-слоя
  событий включена, но припаркована в `FREE` с выключенными auto zoom/rotation/mode switching,
  а его маршрут и курсор отключены;
- событийная фоновая аренда MapKit: штатный location `GuidanceService` сохраняет процесс/сессию,
  а после `MapActivity.onStop()` рендер продолжается только пока HUD или приборная Surface жива;
  постоянного polling нет, снятие обеих поверхностей освобождает MapKit;
- штатные значки дорожных событий через Yandex road-events style provider и отдельное направление
  активных камер из `Windshield.getActiveSpeedCameras()`/`getActiveDirections()`; для всех 22
  `EventTag` можно выбрать «скрыть», «всегда» или «только с маршрутом»; `ALWAYS` обслуживает
  standalone-слой, а `ROUTE_ONLY` — слой той же automotive Navigation-сессии через штатное
  `setRoadEventVisibleOnRoute`, поэтому соседняя улица не считается маршрутом; нативный слой
  создаётся только на время активного пользовательского маршрута;
- цветовые сегменты пробок из `DrivingRoute.getJamSegments()` в одной полилинии с отдельной
  настраиваемой палитрой и обновлением через `ConditionsListener`;
- программный custom cursor как один стабильный `PlacemarkMapObject` с `ImageProvider`; он
  обновляется из уже опубликованного Guidance-кадра, не запускает второй GPS-источник и не
  пропадает при внутренних переключениях arrow/pin в `UserLocationLayer`;
- самостоятельная HUD-карточка ближайшего манёвра вне карты: стрелка, расстояние именно до
  манёвра, номер дороги и направление; она имеет собственную геометрию и скрывается целиком без
  свежего активного маршрута;
- применение безопасной части `mainMap` к настоящему `MapWindow`: focus/scale/FPS, day/night,
  POI/модели/JSON-стили; штатные камера, маршрут и курсор не заменяются.

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

Последняя команда завершается успешно только если бинарный manifest и `classes4.dex` совпадают
с закреплёнными SHA-256, а `resources.arsc`, все `res/`, `classes14.dex`, `classes18.dex`, assets
и native libraries остались побайтно равны рабочему 30.3.0; добавляется только `classes19.dex`,
а итоговая подпись совпадает с сертификатом Natro.

Для парной подписи Natro и Навигатора одним стабильным сертификатом используется
`tools/sign_navigation_hud_v2_pair.sh`. Скрипт принимает только unsigned-кандидат из CI,
проверяет его `SHA256SUMS.txt`, не копирует baseline Навигатора в результат и требует сертификат
SHA-256 `6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`.
Финальная парная сборка дополнительно создаёт `KX11-COMPATIBILITY.txt`: там проверяются
Android 9/API 28, ABI `arm64-v8a`, точные хэши manifest/classes4 и неизменность ресурсов,
геометрия основного окна `1760×720` и HUD `728×190 @ (0,720)` на Display ID 2.
Порядок безопасной установки и аппаратный чек-лист находятся в
`docs/KX11_NAVIGATION_HUD_V2_INSTALL_RU.md`.
