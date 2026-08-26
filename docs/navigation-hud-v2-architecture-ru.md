# Навигация и HUD v2

## Зафиксированное решение

За основу модификации берётся именно переданный `YN_30.3.0_T2_`. В этой сборке уже правильно
решена совместимость переподписанного Навигатора с загрузкой карт и с оригинальными приложениями
Яндекса. Мы не воспроизводим и не «улучшаем» этот механизм: сохраняем его байты и добавляем новые
возможности в изолированном DEX с одним минимальным hook в `MapActivity`.

Навигатор остаётся единственным владельцем активной навигационной сессии и MapKit. Natro хранит
пользовательские настройки, компонует HUD и предоставляет поверхность вывода. Второй маршрут в
Natro не рассчитывается: основной экран и HUD показывают одну сессию, но двумя независимыми
рендерами.

## Неприкосновенный baseline 30.3.0

Проверен точный APK:

- package: `ru.yandex.yandexnavi`;
- versionCode: `739564630`;
- SHA-256 APK: `663018fb66074e001eed7caba8e33bee1bcf78f6798bc84949d253dcb348f27f`;
- SHA-256 сертификата рабочего мода: `a4590f0a8ad6c6a30b87900289a918be0fd6b036f82d2ec4551f3caf2d910892`;
- все 22 arm64-библиотеки, включая `libmaps-mobile.so`, совпадают с официальной сборкой 30.3.0;
- рабочая логика совместимости учётной записи находится в `classes14.dex`, а добавки текущего
  мода — в `classes18.dex`.

Инварианты новой сборки:

1. `AndroidManifest.xml`, `resources.arsc`, `classes14.dex`, `classes18.dex`, все `assets/**` и
   `lib/**` остаются побайтно равны рабочему baseline.
2. Разрешено изменить только `classes4.dex`, где находится `MapActivity`, и добавить новый
   `classes19.dex` с нашим кодом.
3. Нативный MapKit, загрузчик офлайн-карт, Passport-контур и существующие добавки 30.3.0 не
   патчатся повторно.
4. Полная пересборка APK через apktool запрещена для release-пути: она незаметно перезаписывает
   manifest/resources. Используется точечная замена DEX, затем zipalign и подпись.

Эти правила проверяет `tools/verify_navigation_mod_baseline.py`. Проверка сравнивает распакованные
байты каждого ZIP-entry и отдельно проверяет сертификат и схему подписи. Поэтому смена compression
level/zipalign допустима, а изменение любого защищённого файла — нет.

Финальный Навигатор подписывается release-сертификатом Natro
`6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75`. Оригинальные Яндекс
Музыка и другие приложения остаются со своими подписями: мы полагаемся на уже работающую
изоляцию из baseline 30.3.0 и не добавляем общих `signature`-permissions.

Это меняет сертификат самого Навигатора относительно переданного мода (`a459…` → `6e98…`), поэтому
обычное обновление поверх установленного APK Android отклонит. Для первой установки новой ветки
нужен отдельный проверенный сценарий сохранения данных/офлайн-карт и переустановки; последующие
версии с сертификатом Natro уже смогут обновляться поверх друг друга.

## Два независимых отображения одной навигации

- Навигатор публикует `NavigationSnapshotV2`: положение, курс, манёвр, расстояния, ETA, полосы,
  камеры/события и ограничение скорости.
- Тяжёлая геометрия маршрута идёт отдельно в `NavigationRouteGeometryV2` только при смене
  `routeEpoch`, а не в каждом кадре.
- Основная карта использует собственный `MapProfile`: камера, zoom, наклон, focus point, слои,
  цвета, размеры маршрута/курсора, day/night, стиль и FPS.
- HUD-карта получает другой `MapProfile` и отдельный `OffscreenMapWindow`. Android `Surface`,
  выданный Natro, преобразуется штатным `SurfaceFactory.from(...)` и добавляется в его
  `MapWindow.addSurface(...)`. Это не копия кадра основной карты, поэтому масштаб, камера, стиль и
  набор слоёв не связаны.
- Навигатор рисует HUD-карту прямо в выданный Natro `Surface`. `Bitmap`, `ImageReader`,
  MediaProjection и покадровая передача RGBA не используются.
- Natro размещает карту как один из элементов HUD, а манёвр, дистанцию, полосы, скорость,
  светофоры, ETA и любые будущие элементы — независимо, любого размера и в любом месте HUD.

## IPC и граница доверия

Направление соединения выбрано как в устойчивой функциональной схеме YanaviHUD, но реализуется с
нуля: патч Навигатора сам делает explicit bind к
`ru.natro.statuswidget/dezz.status.widget.navigation.NavigationHudEndpointService`. Поэтому в
manifest Навигатора не добавляется экспортируемый service/provider/receiver.

Natro проверяет UID, точный package `ru.yandex.yandexnavi` и совпадение его подписи с установленным
Natro для каждого входящего Binder/Messenger-сообщения. Одних extras или action для доверия недостаточно. При
подключении стороны обмениваются версией протокола и capabilities; разрыв Binder, смерть процесса
или смена `surfaceGeneration` освобождают старый consumer-handle `Surface` и запускают ограниченное
переподключение. Сам producer остаётся во владении `TextureView` Natro до явного revoke.

Редактор Natro передаёт новый профиль в процесс `:hud` через отдельный
`NavigationConfigurationRelayService` с `exported=false`. Экспортируемый endpoint принимает только
Binder-соединение Навигатора и принципиально не читает конфигурацию из `startService` extras.

## Оконный режим основного Навигатора

В 29.4.2 оконный режим был не Android freeform и не окном Natro. Мод менял тип и параметры окна
собственной `MapActivity`, добавлял drag/resize handles, сохранял `x/y/width/height` и рисовал
скругление/рамку/тень; на Android 8+ использовался тот же тип окна 2038, который применяет новый
контроллер. В 30.3.0 этого кода и `TransparentSplashActivity` уже нет.

Новая реализация сохраняет полезную модель, но не переносит старый smali:

- отдельный `FloatingWindowController` в `classes19.dex`;
- единственный lifecycle/intent hook в `MapActivity` (`classes4.dex`);
- перемещение и изменение размера с раздельной блокировкой;
- сохранение геометрии, опциональная фиксация пропорций;
- скругление, фон, прозрачность, рамка, цвет/радиус тени;
- независимо скрываемые drag/resize handles и кнопка закрытия;
- обязательная кнопка «окно / полный экран» внутри Навигатора, с настройкой позиции, размера и
  прозрачности.

Все входы сходятся в один контроллер и дают одинаковый результат:

1. кнопка внутри нового Навигатора;
2. IPC-команда Natro `MSG_SET_MAIN_WINDOW_MODE`;
3. уже используемые Natro action `navi_win/ru.yandex.yandexnavi` и extra `ddnavwin=true`;
4. `ddnavforcewinfull=true` для возврата в полный экран;
5. оконный запуск deep link/сохранённого маршрута из Natro.

Manifest baseline не меняется. Для package нового Навигатора Natro сначала пробует экспортируемую
30.3.0 `MapActivity` как прямую оконную цель, чтобы промежуточный splash не потерял extras; старые
`TransparentSplashActivity` остаются fallback для других сборок. Общий контроллер читает
существующие extras в `onPostCreate` и `onNewIntent`. Поэтому старые ярлыки, сохранённые маршруты и
кнопки Natro работают и для старых модов, и для нового 30.3.0.

## Что берём из YanaviHUD — только как функциональную спецификацию

Разбор `YanaviHUD_v2.0.1` показал две правильные идеи:

- отдельная MapKit-поверхность со своей камерой/стилем вместо зеркала основной карты;
- второй overlay-surface для independently composed HUD-элементов и единый поток navigation
  state для обоих представлений.

Его исходный код, native gate, runtime hooks, лицензирование, сетевые подмены и привязка к
`com.sanchezmobiled.hudx` не копируются. Новый bridge, lifecycle, renderer и UI пишутся с нуля на
контрактах Natro.

## Риск поверхности HUD на KX11

Обычный `TextureView`/`Presentation` даёт прямой MapKit Surface и удобную компоновку, но штатный
слой HUD на этой ГУ может оказаться выше окна приложения. Текущий shell `SurfaceControl` Natro
точно умеет быть верхним слоем, однако пока принимает PNG через локальный socket и не передаёт
настоящий `Surface` в Навигатор.

Поэтому release gate состоит из двух вариантов:

1. сначала проверить `TextureView` (он допускает alpha/clipping и перекрытие другими HUD-элементами)
   при корректно отключённом штатном HUD;
2. если OEM-слой просвечивает, расширить существующий `app_process` helper до Binder surface
   broker: нижний Surface для MapKit, верхний прозрачный Surface для элементов Natro.

До физического теста на KX11 нельзя считать порядок SurfaceFlinger решённым только по эмулятору.

## Этапы реализации и приёмка

1. Baseline verifier и versioned contracts — выполнено.
2. Минимальный patch pipeline: `classes4.dex` hook + новый `classes19.dex`, без apktool rebuild —
   выполнено.
3. `FloatingWindowController`, внутренняя кнопка и полная совместимость оконных запусков Natro —
   выполнено.
4. Безопасный Natro endpoint, snapshot/route callbacks и восстановление соединения — выполнено;
   публикация данных из внутренних объектов 30.3.0 продолжается.
5. Отдельный `OffscreenMapWindow`, настоящий Surface-элемент HUD и независимые
   редакторы обоих `MapProfile` — базовый lifecycle и хранение настроек выполнены; применение
   внутренних слоёв основной карты и синхронизация камеры/маршрута продолжаются.
6. Редактор HUD, где карта и каждый навигационный элемент свободно перемещаются/масштабируются.
7. Стендовые тесты, затем KX11: загрузка офлайн-карт, совместное наличие Яндекс Музыки, QuickBoot,
   холодный старт, оконный deep link, сворачивание, перестроение маршрута, day/night, 30 минут
   движения и серия перезагрузок.

## Публичные основания решения

- Yandex MapKit External Surfaces предупреждает, что дополнительный Surface того же MapWindow
  дублирует его камеру и стили: https://yandex.com/maps-api/docs/mapkit/android/static/tutorials/map_surface.html
- `OffscreenMapWindow` и External Surfaces — штатный путь отдельного рендера без `MapView`:
  https://yandex.com/maps-api/docs/mapkit/com/yandex/mapkit/map/OffscreenMapWindow.html и
  https://yandex.com/maps-api/docs/mapkit/android/static/tutorials/map_surface.html
- Android `TextureView` поддерживает поток из другого процесса, alpha и clipping; его
  `SurfaceTexture` штатно превращается в `Surface`:
  https://developer.android.com/reference/android/view/TextureView
- Android `Surface` реализует `Parcelable`, поэтому его producer handle передаётся Binder-ом без
  передачи кадров в Bundle: https://developer.android.com/reference/android/view/Surface
- Yandex MapKit demo — публичная опора для обычного lifecycle MapKit:
  https://github.com/yandex/mapkit-android-demo
- `hudnav` показывает полезную границу «одно navigation state — отдельный HUD display», но строит
  собственный маршрут и не переиспользует сессию Яндекс Навигатора:
  https://github.com/ingebyd/hudnav
- BYDMate/DisplayMirror используют virtual display или зеркалирование. Это возможный fallback, но
  не независимая HUD-карта: https://github.com/AndyShaman/BYDMate и
  https://github.com/Baghdady92/DisplayMirror
- Shizuku показывает устойчивый общий принцип `app_process` + Binder для привилегированного
  помощника, но его код в Natro не встраивается: https://github.com/RikkaApps/Shizuku
- ynavi-zee показывает воспроизводимый подход к точечным изменениям автомобильного Яндекс
  Навигатора; наш release gate значительно строже и запрещает drift защищённых entries:
  https://github.com/maxim-saplin/ynavi-zee
