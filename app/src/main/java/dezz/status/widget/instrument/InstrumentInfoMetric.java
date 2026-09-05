/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Selectable rows for the modular auxiliary information block. */
public enum InstrumentInfoMetric {
    RANGE("Запас хода", "ISensor.range_total", "ISensor.range_fuel", "км", 0),
    FUEL("Топливо", "ISensor.fuel_level", "", "л", 1),
    BATTERY("Батарея", "ISensor.ev_battery_level", "", "%", 0),
    AMBIENT_TEMPERATURE("На улице", "ISensor.ambient_temp", "", "°C", 0),
    COOLANT_TEMPERATURE("Охлаждающая жидкость", "ISensor.coolant_temp", "", "°C", 0),
    INSTANT_CONSUMPTION("Мгновенный расход", "ISensor.instant_fuel_consumption", "",
            "л/100", 1),
    AVERAGE_CONSUMPTION("Средний расход", "ISensor.avg_fuel_consumption", "",
            "л/100", 1),
    TRIP_CONSUMPTION("Расход поездки", "ISensor.avg_fuel_consumption_ignition", "",
            "л/100", 1),
    ODOMETER("Пробег", "ISensor.odometer", "", "км", 0),
    RPM("Обороты", "ISensor.rpm", "", "об/мин", 0),
    SPEED("Скорость", "ISensor.speed", "", "км/ч", 0),
    NONE("Не показывать", "", "", "", 0);

    @NonNull public final String label;
    @NonNull public final String metricId;
    @NonNull public final String fallbackMetricId;
    @NonNull public final String unit;
    public final int decimals;

    InstrumentInfoMetric(@NonNull String label, @NonNull String metricId,
                         @NonNull String fallbackMetricId, @NonNull String unit,
                         int decimals) {
        this.label = label;
        this.metricId = metricId;
        this.fallbackMetricId = fallbackMetricId;
        this.unit = unit;
        this.decimals = decimals;
    }

    @NonNull
    public static InstrumentInfoMetric fromName(@Nullable String raw,
                                                @NonNull InstrumentInfoMetric fallback) {
        if (raw == null) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
