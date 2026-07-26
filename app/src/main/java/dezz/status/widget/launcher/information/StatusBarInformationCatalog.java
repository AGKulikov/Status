/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.BrickType;

/** One canonical catalog shared by status-bar bricks and driver information tiles. */
public final class StatusBarInformationCatalog {
    private static final String SYSTEM_PREFIX = "system.status.";
    private static final String TYPE_HINT_PREFIX = "status_bar:";

    private StatusBarInformationCatalog() {
    }

    @NonNull
    public static List<InformationPanelConfig.Item> items() {
        List<InformationPanelConfig.Item> result = new ArrayList<>();
        result.add(system(BrickType.TIME, "Время", ""));
        result.add(system(BrickType.DATE, "Дата и день недели", ""));
        result.add(system(BrickType.MEDIA, "Воспроизведение", ""));
        result.add(system(BrickType.WIFI, "Wi‑Fi", ""));
        result.add(system(BrickType.GPS, "GPS", ""));
        result.add(system(BrickType.BLUETOOTH, "Bluetooth", ""));
        result.add(vehicle(BrickType.INDOOR_TEMP, "ISensor.indoor_temp",
                "Температура в салоне", "°C"));
        result.add(vehicle(BrickType.OUTDOOR_TEMP, "ISensor.ambient_temp",
                "Температура за бортом", "°C"));
        return result;
    }

    @NonNull
    private static InformationPanelConfig.Item system(@NonNull BrickType type,
                                                      @NonNull String label,
                                                      @NonNull String unit) {
        return InformationPanelConfig.Item.system(
                SYSTEM_PREFIX + type.name().toLowerCase(Locale.ROOT),
                label, unit, TYPE_HINT_PREFIX + type.name());
    }

    @NonNull
    private static InformationPanelConfig.Item vehicle(@NonNull BrickType type,
                                                       @NonNull String sourceId,
                                                       @NonNull String label,
                                                       @NonNull String unit) {
        return InformationPanelConfig.Item.vehicle(
                sourceId, label, unit, TYPE_HINT_PREFIX + type.name());
    }

    @Nullable
    public static BrickType type(@NonNull InformationPanelConfig.Item item) {
        if (item.sourceTypeHint.startsWith(TYPE_HINT_PREFIX)) {
            return parse(item.sourceTypeHint.substring(TYPE_HINT_PREFIX.length()));
        }
        if (item.sourceKind == InformationPanelConfig.SourceKind.SYSTEM
                && item.sourceId.startsWith(SYSTEM_PREFIX)) {
            return parse(item.sourceId.substring(SYSTEM_PREFIX.length()));
        }
        if ("ISensor.indoor_temp".equals(item.sourceId)) return BrickType.INDOOR_TEMP;
        if ("ISensor.ambient_temp".equals(item.sourceId)) return BrickType.OUTDOOR_TEMP;
        return null;
    }

    @Nullable
    public static BrickType typeForTarget(@Nullable String target) {
        if (target == null) return null;
        String systemTarget = "info:system:" + SYSTEM_PREFIX;
        if (target.startsWith(systemTarget)) {
            return parse(target.substring(systemTarget.length()));
        }
        if (target.equals("info:vehicle:ISensor.indoor_temp")) return BrickType.INDOOR_TEMP;
        if (target.equals("info:vehicle:ISensor.ambient_temp")) return BrickType.OUTDOOR_TEMP;
        return null;
    }

    @NonNull
    public static String fallbackIcon(@NonNull BrickType type) {
        switch (type) {
            case MEDIA:
                return "media";
            case WIFI:
                return "status_wifi";
            case GPS:
                return "status_gps";
            case BLUETOOTH:
                return "status_bluetooth";
            case INDOOR_TEMP:
            case OUTDOOR_TEMP:
                return "temperature";
            case DATE:
                return "calendar";
            case TIME:
            default:
                return "notification";
        }
    }

    @Nullable
    private static BrickType parse(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return BrickType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
