/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Complete HUD element catalog.
 *
 * <p>The catalog includes counterparts for mHUD 6.1 data and additional connector-neutral
 * and vehicle values. Composite layouts and behavior differ; see the dated mHUD widget audit.
 * Elements are data-driven, so adding one
 * to a panel never changes the external-display lifecycle or the editor grid.</p>
 */
public enum HudElementType {
    BACKDROP("Подложка", "Оформление", "", 16, 8),
    HORIZONTAL_GROUP("Горизонтальный ряд", "Компоновка", "", 18, 5),

    CLOCK("Часы", "Основное", "", 7, 3),

    MEDIA_ARTWORK("Обложка", "Медиа", "", 6, 6),
    MEDIA_COMBINED("Название и исполнитель", "Медиа", "", 12, 4),
    MEDIA_TITLE("Название трека", "Медиа", "", 10, 3),
    MEDIA_ARTIST("Исполнитель", "Медиа", "", 10, 3),
    MEDIA_ALBUM("Альбом", "Медиа", "", 10, 3),
    MEDIA_APPLICATION("Приложение-плеер", "Медиа", "", 9, 2),
    MEDIA_TIMER("Таймер трека", "Медиа", "", 8, 2),
    MEDIA_VOLUME("Громкость", "Медиа", "", 6, 2),

    NAV_MAP("Независимая карта", "Навигация", "", 22, 18),
    NAV_MANEUVER_ARROW("Стрелка манёвра", "Навигация", "", 9, 10),
    NAV_MANEUVER_TITLE("Описание манёвра", "Навигация", "", 14, 3),
    NAV_MANEUVER_SUBTEXT("Подсказка манёвра", "Навигация", "", 14, 3),
    NAV_STREET("Улица", "Навигация", "", 16, 3),
    NAV_DESTINATION("Пункт назначения", "Навигация", "", 16, 3),
    NAV_TURN_DISTANCE("Расстояние до поворота", "Навигация", "", 9, 3),
    NAV_DISTANCE_LEFT("Осталось расстояния", "Навигация", "", 10, 3),
    NAV_TIME_LEFT("Осталось времени", "Навигация", "", 9, 3),
    NAV_ARRIVAL_TIME("Время прибытия", "Навигация", "", 9, 3),
    NAV_SPEED("Скорость навигации", "Навигация", "ISensor.speed", 7, 4),
    NAV_LANES("Полосы движения", "Навигация", "", 14, 5),
    NAV_LANE_DISTANCE("Расстояние до полос", "Навигация", "", 8, 2),
    NAV_COMBINED("Карточка ближайшего манёвра", "Навигация", "", 18, 10),
    NAV_ROUTE_SUMMARY("Сводка маршрута · как в Навигаторе", "Навигация", "", 22, 5),
    NAV_TRIP_PROGRESS("Прогресс поездки", "Навигация", "", 16, 2),
    NAV_SPEED_LIMIT("Ограничение скорости", "Навигация", "ISensor.speed", 6, 6),
    NAV_TRAFFIC_LIGHTS("Светофоры", "Навигация", "", 9, 10),
    NAV_TRAFFIC_JAM("Пробка впереди", "Навигация", "", 18, 3),
    NAV_JAM_PROGRESS("Пробки / прогресс", "Навигация", "", 16, 2),
    NAV_ROUTE_GRAPHIC("Графика маршрута", "Навигация", "", 16, 4),

    AMBIENT_TEMPERATURE("Температура снаружи", "Автомобиль",
            "ISensor.ambient_temp", 7, 3),
    CABIN_TEMPERATURE("Температура салона", "Автомобиль",
            "ISensor.indoor_temp", 7, 3),
    CAR_SPEED("Скорость", "Автомобиль", "ISensor.speed", 7, 4),
    RPM("Обороты двигателя", "Автомобиль", "ISensor.rpm", 8, 3),
    FUEL_LEVEL("Остаток топлива", "Автомобиль", "ISensor.fuel_level", 8, 3),
    FUEL_REFILL("Сколько заправить", "Автомобиль", "Derived.refill_fuel", 8, 3),
    FUEL_RANGE("Запас хода", "Автомобиль", "ISensor.range_fuel", 8, 3),
    FUEL_RANGE_TOTAL("Общий запас хода", "Автомобиль", "ISensor.range_total", 8, 3),
    FUEL_CAPACITY("Объём топливного бака", "Автомобиль",
            "ICarInfo.fuel_capacity", 8, 3),
    FUEL_CONSUMPTION_INSTANT("Мгновенный расход", "Автомобиль",
            "ISensor.instant_fuel_consumption", 9, 3),
    FUEL_CONSUMPTION_AVERAGE("Средний расход", "Автомобиль",
            "ISensor.avg_fuel_consumption", 9, 3),
    FUEL_CONSUMPTION_TRIP("Расход за поездку", "Автомобиль",
            "ISensor.avg_fuel_consumption_ignition", 9, 3),
    GEAR("Передача", "Автомобиль", "ISensor.gear", 5, 4),
    ODOMETER("Одометр", "Автомобиль", "ISensor.odometer", 8, 3),
    COOLANT_TEMPERATURE("Температура ОЖ", "Автомобиль",
            "ISensor.coolant_temp", 8, 3),
    ENGINE_OIL_LEVEL("Уровень масла", "Автомобиль",
            "ISensor.engine_oil_level", 8, 3),
    EV_BATTERY("Тяговая батарея", "Автомобиль",
            "ISensor.ev_battery_level", 8, 3),
    HIGH_BEAM("Дальний свет", "Автомобиль", "IBcm.high_beam", 5, 4),
    AUTO_HOLD("Auto Hold", "Автомобиль", "External.auto_hold", 6, 3),
    TURN_SIGNALS("Поворотники", "Автомобиль", "", 12, 4),
    TURN_SIGNAL_LEFT("Левый поворотник", "Автомобиль",
            "IBcm.turn_signal_left", 5, 4),
    TURN_SIGNAL_RIGHT("Правый поворотник", "Автомобиль",
            "IBcm.turn_signal_right", 5, 4),

    TIRE_PRESSURE_FRONT_LEFT("Давление: переднее левое", "Шины",
            "TPMS.pressure.front_left", 7, 3),
    TIRE_PRESSURE_FRONT_RIGHT("Давление: переднее правое", "Шины",
            "TPMS.pressure.front_right", 7, 3),
    TIRE_PRESSURE_REAR_LEFT("Давление: заднее левое", "Шины",
            "TPMS.pressure.rear_left", 7, 3),
    TIRE_PRESSURE_REAR_RIGHT("Давление: заднее правое", "Шины",
            "TPMS.pressure.rear_right", 7, 3),
    TIRE_TEMPERATURE_FRONT_LEFT("Температура: переднее левое", "Шины",
            "TPMS.temperature.front_left", 7, 3),
    TIRE_TEMPERATURE_FRONT_RIGHT("Температура: переднее правое", "Шины",
            "TPMS.temperature.front_right", 7, 3),
    TIRE_TEMPERATURE_REAR_LEFT("Температура: заднее левое", "Шины",
            "TPMS.temperature.rear_left", 7, 3),
    TIRE_TEMPERATURE_REAR_RIGHT("Температура: заднее правое", "Шины",
            "TPMS.temperature.rear_right", 7, 3),

    SMART_HOME_STATUS("Статус умного устройства", "Умный дом", "", 10, 3),
    VEHICLE_TELEMETRY("Любой параметр автомобиля", "Данные", "", 9, 3),
    CONNECTOR_VALUE("Любое значение HA / MQTT / Sprut", "Данные", "", 10, 3),
    CUSTOM_TEXT("Произвольный текст", "Данные", "", 10, 3),
    UPDATE_STATUS("Статус приложения", "Данные", "", 8, 2);

    @NonNull public final String label;
    @NonNull public final String category;
    @NonNull public final String defaultMetricId;
    public final int defaultWidth;
    public final int defaultHeight;

    HudElementType(@NonNull String label, @NonNull String category,
                   @NonNull String defaultMetricId, int defaultWidth, int defaultHeight) {
        this.label = label;
        this.category = category;
        this.defaultMetricId = defaultMetricId;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
    }

    @Nullable
    public static HudElementType fromName(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
