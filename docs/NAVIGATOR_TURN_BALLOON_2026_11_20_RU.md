# Повторная ошибка таблички: 20.11.2026

Опорный артефакт: Navigator 3.5.3, SHA-256
`8091dfb6be987e9e931cc297cffdc7294bd4da91e6032bd47700f9a7cb9911de`.

Разбор исходников выявил три независимых дефекта 3.5.3: список Texture сравнивался
по identity и сразу обнулял proof при setDistance/createTexture; CombinedLaneArtwork
создавался только для combined; уже масштабированный штатный bitmap получал ещё
один IconStyle.scale. Проверка проекции с допуском 2px также отвергала обычное
движение кадра. Это вывод из кода, не восстановление неизвестного журнала автомобиля.

В classes8 подтверждены ManeuverBalloonViewImpl -> ContextBalloonView ->
DayNightBalloonView -> BalloonView и getView/getTexture/getScale/getLegPosition.
У combined getContainer возвращает JointBalloonContainer; у standalone контейнером
служит сам view. В standalone binding есть два DirectionSignView, оба находятся
через дерево View, без обращения к обфусцированным именам. В classes12 проверен
PlacemarkMapObject.setIcon(ImageProvider,IconStyle,Callback), callback.onTaskFinished().
Оригинальные classes8/classes12 и ресурсы в обновлении не меняются.

Решение и границы: [выпуск 3.5.4](releases/3.5.4/README_RU.md). Native source token
не хранит View/Context; старые пиксели не распространяются на новый объект. Snapshot
Picture содержит штатные команды рисования и не зависит от последующего layout.
Короткое удержание не обновляет timestamp; hard invalidations и неоднозначность
снимают знак сразу. Замер движения проверяет каждого конкурента, а не выбирает
ближайший поворот. Последовательные снимки и поздние callback покрыты replay.

Следующий этап: полный CI с API28 native graphics, подпись точного tested payload,
публичная проверка двух манифестов, затем FIELD по условиям выпуска.
