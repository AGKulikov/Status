/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import dezz.status.widget.car.CurrentTripMetrics;

/** Elements available to the 1920x720 instrument-panel editor. */
public enum InstrumentElementType {
    ANALOG_SPEEDOMETER("Спидометр · стрелка", "Аналоговые", "ISensor.speed", 12, 12),
    ANALOG_TACHOMETER("Тахометр · стрелка", "Аналоговые", "ISensor.rpm", 12, 12),
    ANALOG_FUEL_GAUGE("Топливо · стрелка", "Аналоговые", "ISensor.fuel_level", 9, 9),
    ANALOG_BATTERY_GAUGE("Батарея · стрелка", "Аналоговые",
            "ISensor.ev_battery_level", 9, 9),
    ANALOG_COOLANT_TEMPERATURE("Температура ОЖ · стрелка", "Аналоговые",
            "ISensor.coolant_temp", 9, 9),
    ANALOG_INSTANT_CONSUMPTION("Расход · стрелка", "Аналоговые",
            "ISensor.instant_fuel_consumption", 9, 9),

    DIGITAL_SPEEDOMETER("Спидометр · цифры", "Цифровые", "ISensor.speed", 8, 6),
    DIGITAL_TACHOMETER("Тахометр · цифры", "Цифровые", "ISensor.rpm", 8, 5),
    FUEL_GAUGE("Топливо · цифры", "Цифровые", "ISensor.fuel_level", 9, 3),
    BATTERY_GAUGE("Батарея · цифры", "Цифровые", "ISensor.ev_battery_level", 9, 3),
    COOLANT_TEMPERATURE("Температура ОЖ · цифры", "Цифровые",
            "ISensor.coolant_temp", 8, 3),
    INSTANT_CONSUMPTION("Мгновенный расход · цифры", "Цифровые",
            "ISensor.instant_fuel_consumption", 9, 3),
    AVERAGE_CONSUMPTION("Средний расход · цифры", "Цифровые",
            "ISensor.avg_fuel_consumption", 9, 3),
    TRIP_CONSUMPTION("Расход поездки · цифры", "Цифровые",
            "ISensor.avg_fuel_consumption_ignition", 9, 3),
    CURRENT_TRIP_DISTANCE("Текущая поездка · пробег", "Цифровые",
            CurrentTripMetrics.DISTANCE_ID, 9, 3),
    CURRENT_TRIP_DURATION("Текущая поездка · время", "Цифровые",
            CurrentTripMetrics.DURATION_ID, 9, 3),
    CURRENT_TRIP_AVERAGE_SPEED("Текущая поездка · средняя скорость", "Цифровые",
            CurrentTripMetrics.AVERAGE_SPEED_ID, 9, 3),

    GEAR("Передача", "Основное", "ISensor.gear", 5, 5),
    ODOMETER("Одометр", "Основное", "ISensor.odometer", 8, 3),
    RANGE("Запас хода", "Основное", "ISensor.range_total", 8, 3),
    AMBIENT_TEMPERATURE("Температура снаружи", "Основное",
            "ISensor.ambient_temp", 7, 3),
    CLOCK("Часы", "Основное", "", 7, 3),
    INFO_BLOCK("Информационный блок", "Основное", "", 10, 8),
    NAVIGATION_INFO("Маршрутный блок", "Навигация", "", 12, 7),
    NAV_MANEUVER_ARROW("Стрелка манёвра", "Навигация", "", 9, 10),
    NAV_MANEUVER_TITLE("Описание манёвра", "Навигация", "", 14, 3),
    NAV_MANEUVER_SUBTEXT("Подсказка манёвра", "Навигация", "", 14, 3),
    NAV_STREET("Улица", "Навигация", "", 16, 3),
    NAV_DESTINATION("Пункт назначения", "Навигация", "", 16, 3),
    NAV_TURN_DISTANCE("Расстояние до поворота", "Навигация", "", 9, 3),
    NAV_DISTANCE_LEFT("Осталось расстояния", "Навигация", "", 10, 3),
    NAV_TIME_LEFT("Осталось времени", "Навигация", "", 9, 3),
    NAV_ARRIVAL_TIME("Время прибытия", "Навигация", "", 9, 3),
    NAV_SPEED("Скорость навигации", "Навигация", "", 7, 4),
    NAV_LANES("Полосы движения", "Навигация", "", 14, 5),
    NAV_LANE_DISTANCE("Расстояние до полос", "Навигация", "", 8, 2),
    NAV_MANEUVER_CARD("Карточка ближайшего манёвра", "Навигация", "", 18, 10),
    NAVIGATION_ROUTE_SUMMARY("Сводка маршрута · как в Навигаторе", "Навигация", "", 22, 5),
    NAV_TRIP_PROGRESS("Прогресс поездки", "Навигация", "", 16, 2),
    NAV_SPEED_LIMIT("Ограничение скорости", "Навигация", "", 6, 6),
    NAV_TRAFFIC_LIGHTS("Светофоры", "Навигация", "", 9, 10),
    TRAFFIC_JAM("Пробка впереди", "Навигация", "", 14, 3),
    NAV_JAM_PROGRESS("Пробки / прогресс", "Навигация", "", 16, 2),
    NAV_ROUTE_GRAPHIC("Графика маршрута", "Навигация", "", 16, 4),
    NAV_MAP("Независимая карта", "Навигация", "", 22, 14);

    @NonNull public final String label;
    @NonNull public final String category;
    @NonNull public final String metricId;
    public final int defaultWidth;
    public final int defaultHeight;

    InstrumentElementType(@NonNull String label, @NonNull String category,
                          @NonNull String metricId, int defaultWidth, int defaultHeight) {
        this.label = label;
        this.category = category;
        this.metricId = metricId;
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
    }

    public boolean isAnalogGauge() {
        switch (this) {
            case ANALOG_SPEEDOMETER:
            case ANALOG_TACHOMETER:
            case ANALOG_FUEL_GAUGE:
            case ANALOG_BATTERY_GAUGE:
            case ANALOG_COOLANT_TEMPERATURE:
            case ANALOG_INSTANT_CONSUMPTION:
                return true;
            default:
                return false;
        }
    }

    /** Numeric alternatives which can be mixed independently with every analog gauge. */
    public boolean isDigitalGauge() {
        switch (this) {
            case DIGITAL_SPEEDOMETER:
            case DIGITAL_TACHOMETER:
            case FUEL_GAUGE:
            case BATTERY_GAUGE:
            case COOLANT_TEMPERATURE:
            case INSTANT_CONSUMPTION:
            case AVERAGE_CONSUMPTION:
            case TRIP_CONSUMPTION:
            case CURRENT_TRIP_DISTANCE:
            case CURRENT_TRIP_DURATION:
            case CURRENT_TRIP_AVERAGE_SPEED:
                return true;
            default:
                return false;
        }
    }

    public boolean usesClock() {
        return this == CLOCK;
    }

    public boolean usesNavigationState() {
        switch (this) {
            case NAVIGATION_INFO:
            case NAV_MANEUVER_ARROW:
            case NAV_MANEUVER_TITLE:
            case NAV_MANEUVER_SUBTEXT:
            case NAV_STREET:
            case NAV_DESTINATION:
            case NAV_TURN_DISTANCE:
            case NAV_DISTANCE_LEFT:
            case NAV_TIME_LEFT:
            case NAV_ARRIVAL_TIME:
            case NAV_SPEED:
            case NAV_LANES:
            case NAV_LANE_DISTANCE:
            case NAV_MANEUVER_CARD:
            case NAVIGATION_ROUTE_SUMMARY:
            case NAV_TRIP_PROGRESS:
            case NAV_SPEED_LIMIT:
            case NAV_TRAFFIC_LIGHTS:
            case TRAFFIC_JAM:
            case NAV_JAM_PROGRESS:
            case NAV_ROUTE_GRAPHIC:
                return true;
            default:
                return false;
        }
    }

    @Nullable
    public static InstrumentElementType fromName(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
