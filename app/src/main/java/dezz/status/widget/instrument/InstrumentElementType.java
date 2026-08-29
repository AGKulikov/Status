/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

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

    GEAR("Передача", "Основное", "ISensor.gear", 5, 5),
    ODOMETER("Одометр", "Основное", "ISensor.odometer", 8, 3),
    RANGE("Запас хода", "Основное", "ISensor.range_total", 8, 3),
    AMBIENT_TEMPERATURE("Температура снаружи", "Основное",
            "ISensor.ambient_temp", 7, 3),
    CLOCK("Часы", "Основное", "", 7, 3),
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
                return true;
            default:
                return false;
        }
    }

    public boolean usesClock() {
        return this == CLOCK;
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
