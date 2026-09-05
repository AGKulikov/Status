# Natro 2.6.9: повторный аудит регрессий прежних падений

Дата исходного аудита: 02.09.2026; повторная проверка визуальных исправлений: 03.09.2026. Объект проверки — текущее дерево ветки
`feature/navigation-hud-v2` после доработок 2.6.9. Этот документ фиксирует статический результат;
он не заменяет длительную проверку на физическом ECARX KX11.

## Результат

В исполняемый код не вернулся ни один из подтверждённых аварийных путей Navigator. Дополнительно
устранены два защитных пробела: исключение из отложенного poll/inset callback оконного режима
теперь не выходит в главный looper Навигатора, а переполнение лимита слушателей коннекторов больше
не бросает `IllegalStateException` в процесс Natro.

Автоматическая проверка исходного commit
`078591241a580067291c0eff33594212a1ab1dc0` (дерево
`16c01838204a3371771186bf9b21b2b19d67c93f`) завершилась успешно в CI run 231: пройдены Android
unit-тесты, сборка Natro, компиляция изолированного Navigator `classes19.dex`, компиляция HUD Speed
bridge и baseline-verifier self-tests. Задание подписи не запускалось; новая финальная APK-пара не
выпускалась.

Исправления по аппаратным снимкам опубликованы исходным commit
`836da17ab359a8d2da3d1d646c84586b31ca541b` (дерево
`e64dfa5e9597dd0777d5b06ee6150deef06efba5`) и полностью прошли CI run 232: включая Android
unit-тесты, сборку Natro, изолированный Navigator `classes19.dex`, HUD Speed bridge,
baseline-verifier self-tests и упаковку неподписанной пары. Задание подписи было пропущено.

| Проверенный механизм | Состояние текущего дерева | Автоматический барьер |
|---|---|---|
| Automotive `NavigationLayer` на независимом `OffscreenMapWindow`, даже с выключенной камерой | Исполняемых ссылок нет; дорожные события использует только standalone `RoadEventsLayer` | Release-verifier отклоняет factory/settings/create и on-route API |
| Вторая `GuidanceCamera`, включая парковку в `FREE` | Исполняемых ссылок и второго владельца камеры нет | DEX-verifier отклоняет `GuidanceCamera` и `setUseLayerCamera` |
| Применение type 2038 до появления Activity token | Вход в оконный режим остаётся только в конце `onResumeFragments` | Source-contract запрещает `onActivityPreCreate` и проверяет точку hook |
| Повторный type-2038 `setAttributes` из обновления карты/слоёв | `sameWindowContract` отделяет оконные параметры от общего JSON; идентичный snapshot идемпотентен | Java source-contract проверяет обе отсечки |
| Пересоздание renderer/`OffscreenMapWindow` по таймеру или на обычном обновлении данных | Таймер пересоздания отсутствует; новая generation создаётся только для новой surface/геометрии | Контракты `NAV-012/013`, surface-generation и no-flicker tests |
| Освобождение Android surface до `MapWindow.removeSurface` | Сначала выполняется `removeSurface`, затем очищаются ссылки и release | Source-contract жизненного цикла renderer |
| Исключение из новых отложенных оконных poll/inset callbacks | Каждый новый callback имеет локальный `try/catch`; poller сохраняет контролируемое расписание через `finally`; отложенный первый layout фиксированной кнопки также изолирован | Два source-contract набора проверяют все восемь границ |
| `Too many connector value listeners` после повторного создания информационных плиток | Плитки по-прежнему делят один upstream-listener; лишний listener при полном лимите безопасно отклоняется и журналируется | Lifecycle/hub contracts плюс unit-тест заполненного лимита |

## Расширенный DEX-запрет

`tools/verify_kx11_navigation_pair.py` теперь отклоняет выпуск, если в добавленном Navigator DEX
обнаружен любой из маркеров:

- `NavigationLayerFactory`;
- `NavigationLayerSettings`;
- `createNavigationLayer`;
- `setUseLayerCamera`;
- `setUseLayerRoadEvents`;
- `setRoadEventVisibleOnRoute`;
- `GuidanceCamera`.

Такой запрет специально шире прежнего: он ловит не только уже наблюдавшуюся диагностическую строку,
но и попытку вернуть тот же механизм под другим вспомогательным методом.

## Повторная проверка изменений 03.09.2026

- Центральное резервирование камеры меняет только `IconStyle.anchor` и порядок уже существующего
  screen-space прохода. Оно не создаёт Automotive `NavigationLayer`, `GuidanceCamera`, новый
  `MapWindow`, renderer или источник позиции.
- Исправление верхнего отступа владеет только listener/layout состоянием позднего
  `PaddingtonView`; оконный type, flags и число транзакций `Window.setAttributes` не менялись.
- Фиксация кнопки использует существующий стабильный overlay Natro. Новый отложенный callback
  выполняется только после первого layout, обёрнут `try/catch` и не меняет оконную identity.
- Локально прошли 5 baseline-verifier тестов и 11 navigation-mod тестов (1 пропущен по внешней
  зависимости). Полная Java/DEX-компиляция подтверждена CI run 232; отсутствие асинхронного
  vendor-сбоя может подтвердить только длительный `GATE-007` на KX11.

## Что ещё нельзя считать подтверждённым

Статический аудит доказывает отсутствие известных Java/DEX-механизмов, но не доказывает отсутствие
асинхронного сбоя в vendor MapKit/SurfaceFlinger на Android 9. Перед следующим APK остаётся открытым
`GATE-007`: 30 минут без маршрута, 30 минут с маршрутом и три возврата из фона. Одновременно должен
пройти `GATE-021`, а в журнале не должно быть завершения bridge, исчезновения окна или возврата на
HOME.
