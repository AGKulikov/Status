# Настройка Home Assistant и MQTT для Status Widget HA

В приложении это два независимых коннектора. Прямой HA API удобен каталогом сущностей и не
требует брокера; MQTT полезен для собственных retained-топиков и интеграций, где состояние уже
публикуется в брокер. Их можно включать по отдельности или одновременно. Третий независимый
коннектор описан в [SPRUTHUB_SETUP_RU.md](SPRUTHUB_SETUP_RU.md).

## Прямое подключение Home Assistant API

1. В профиле Home Assistant создайте **Long-Lived Access Token**.
2. В Status Widget HA откройте **Автоматизация и Home Assistant → Home Assistant: подключение
   и выбор сущностей**.
3. Укажите базовый адрес, например `http://homeassistant.local:8123`, и новый token. Для
   внешней сети используйте HTTPS с доверенным сертификатом либо VPN.
4. Нажмите **Сохранить и подключиться**, дождитесь синхронизации сущностей и затем нажмите
   **Обновить сущности**.
5. Найдите устройство по `entity_id`, названию, состоянию или атрибуту. У нужного `state` либо
   конкретного атрибута нажмите **В основную строку** или **Во всплывающий**.

Прямой коннектор при каждом подключении сначала получает полный актуальный снимок, а затем
слушает `state_changed`. При обрыве связанные элементы переходят в `stale`; после reconnect
новый снимок снимает этот признак. Token хранится через Android Keystore и не экспортируется.
Подписи, цвета, иконки и видимость задаются локальными правилами элемента, поэтому передавать
готовый цвет из HA не требуется.

## 1. Брокер и отдельный пользователь

Для Home Assistant OS установите **Mosquitto broker**: **Настройки → Приложения →
Установить приложение → Mosquitto broker**. Создайте отдельного пользователя, например
`statuswidget`, в **Настройки → Люди → Пользователи**. Имена `homeassistant` и `addons`
зарезервированы. Официальная инструкция брокера:
https://github.com/home-assistant/addons/blob/master/mosquitto/DOCS.md

Убедитесь, что интеграция MQTT добавлена в **Настройки → Устройства и службы**. Официальная
документация: https://www.home-assistant.io/integrations/mqtt

Не открывайте обычный порт `1883` напрямую в интернет. Для доступа магнитолы извне домашней
сети безопаснее VPN до домашней сети. Альтернатива — MQTT TLS с нормальным сертификатом и
портом `8883`; приложение проверяет системную цепочку сертификата.

## 2. Настройки приложения

В Status Widget HA откройте **Автоматизация и Home Assistant** и заполните:

- MQTT включён;
- адрес — IP/DNS брокера, доступный магнитоле;
- порт `1883` для локальной/VPN-сети либо ваш TLS-порт;
- TLS — согласно брокеру;
- пользователь и пароль — отдельная учётная запись `statuswidget`;
- Device ID — например `geely`;
- Base topic — `statuswidget/v1`;
- QoS — `1`;
- Keepalive — `30` секунд;
- «Не давать MQTT-потоку засыпать» — включено для магнитолы.

Нажмите **Сохранить**. Вверху экрана должно появиться `Состояние: подключено`. Пароль хранится
через Android Keystore и не попадает в экспорт настроек или пресеты.

## 3. Первый статус `cover.vezd`

Создайте основной HA-кирпичик:

- ID: `vezd`;
- название: `Въезд`;
- до первого состояния: `…`, цвет `#80FFFFFF`;
- устаревший статус: `…`, цвет `#80FFFFFF`;
- таймаут: `0`, если состояние не должно устаревать само по себе;
- пустой статус: пустая строка, цвет `transparent`.

Добавьте в `automations.yaml` или вставьте эту автоматизацию через YAML-редактор. Замените
`input_boolean.show_gate_in_car` на свой helper/условие видимости либо уберите его и оставьте
`visible: true`.

```yaml
alias: Status Widget — въездные ворота
id: status_widget_gate_vezd
triggers:
  - trigger: homeassistant
    event: start
  - trigger: state
    entity_id:
      - cover.vezd
      - input_boolean.show_gate_in_car
actions:
  - action: mqtt.publish
    data:
      topic: statuswidget/v1/geely/state/main/vezd
      qos: 1
      retain: true
      payload: >-
        {% set states_map = {
          'open': ['Открыто', '#FF4CAF50'],
          'opening': ['Открывается', '#FFFF9800'],
          'closing': ['Закрывается', '#FFF44336'],
          'closed': ['Закрыто', '#FFFFFFFF']
        } %}
        {% set current = states('cover.vezd') %}
        {% set shown = states_map.get(current, ['', 'transparent']) %}
        {{ {
          'text': shown[0],
          'color': shown[1],
          'visible': is_state('input_boolean.show_gate_in_car', 'on'),
          'updated_at': (as_timestamp(now()) * 1000) | int
        } | tojson }}
mode: restart
```

`retain: true` принципиален: брокер хранит последнее значение и отдаёт его магнитоле сразу
после загрузки или возвращения сети. Home Assistant официально описывает `qos` и `retain` у
действия `mqtt.publish`: https://www.home-assistant.io/actions/mqtt.publish/

## 4. Видимость штатных кирпичиков

У штатных элементов стабильные ID. Например, скрытие даты:

```yaml
action: mqtt.publish
data:
  topic: statuswidget/v1/geely/state/builtin/builtin.date
  payload: '{"visible": false}'
  qos: 1
  retain: true
```

Доступные ID: `builtin.time`, `builtin.date`, `builtin.media`, `builtin.wifi`, `builtin.gps`,
`builtin.bluetooth`, `builtin.indoor_temp`, `builtin.outdoor_temp`,
`builtin.home_assistant`.

Для надёжной видимости используйте `input_boolean`, `binary_sensor`, состояние зоны или другую
сущность, а не только одноразовое событие автоматизации. Автоматизация должна публиковать
текущее значение helper с `retain: true`.

## 5. Плитка ворот во всплывающем оверлее

Создайте плитку с типом `HA_DEVICE`, `automationId: gate_tile`, локальной иконкой `gate` и
`actionId: toggle_gate`. Состояние и видимость плитки публикуются отдельно:

```yaml
action: mqtt.publish
data:
  topic: statuswidget/v1/geely/state/popup/gate_tile
  qos: 1
  retain: true
  payload: >-
    {{ {
      'text': states('cover.vezd'),
      'color': '#FF4CAF50' if is_state('cover.vezd', 'open') else '#FFFFFFFF',
      'visible': is_state('input_boolean.show_gate_in_car', 'on'),
      'icon': 'gate',
      'action_enabled': true,
      'updated_at': (as_timestamp(now()) * 1000) | int
    } | tojson }}
```

Показ всего второго оверлея:

```yaml
action: mqtt.publish
data:
  topic: statuswidget/v1/geely/state/popup/_overlay
  payload: '{"visible": true}'
  qos: 1
  retain: true
```

Даже при `visible: true` окно автоматически исчезает, если в нём нет ни одного видимого
элемента.

Команду кнопки принимает такая автоматизация:

```yaml
alias: Status Widget — команда ворот
triggers:
  - trigger: mqtt
    topic: statuswidget/v1/geely/command/toggle_gate
actions:
  - action: cover.toggle
    target:
      entity_id: cover.vezd
mode: single
```

Команды публикуются без retain, содержат `request_id`, `automation_id`, время и последнее
наблюдаемое состояние. При необходимости включите локальный диалог подтверждения в настройках
конкретной плитки.

## 6. Резервный Android Broadcast

Приём `ru.natro.statuswidget.HA_UPDATE` сохранён. Package форка — `ru.natro.statuswidget`.
Home Assistant Companion поддерживает `command_broadcast_intent`; официальная документация:
https://companion.home-assistant.io/docs/notifications/notification-commands/#broadcast-intent

```yaml
action: notify.mobile_app_имя_магнитолы
data:
  message: command_broadcast_intent
  data:
    intent_package_name: ru.natro.statuswidget
    intent_action: ru.natro.statuswidget.HA_UPDATE
    intent_extras: >-
      scope:main,brick_id:vezd,text:Открыто,color:#FF4CAF50,visible:true
```

Broadcast остаётся резервом: Android может задерживать Companion при сне. Постоянный MQTT-клиент
в foreground-службе и retained-состояния являются основным каналом.

## 7. Проверка вручную

В интеграции MQTT выберите **Настроить → Опубликовать пакет**:

- topic: `statuswidget/v1/geely/state/main/vezd`;
- payload: `{"text":"Открыто","color":"#FF4CAF50","visible":true}`;
- QoS `1`, retain включён.

Для очистки ошибочного retained-состояния опубликуйте в тот же topic пустой payload с retain.
