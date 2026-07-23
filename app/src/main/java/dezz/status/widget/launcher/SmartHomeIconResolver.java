/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Connector-neutral icon suggestion for smart-home devices.
 *
 * <p>Connectors use different type vocabularies (HA domains/device classes, Sprut service
 * types and arbitrary MQTT ids).  Keeping the heuristic here makes the initial icon useful
 * without coupling the launcher to a transport.  It is only a suggestion: the shortcut store
 * records whether the user changed the icon and a later rebind must then preserve that choice.</p>
 */
public final class SmartHomeIconResolver {
    private SmartHomeIconResolver() {}

    /** Returns a key from {@link LauncherIconResolver#presets()}. */
    @NonNull
    public static String suggest(@Nullable String domain, @Nullable String deviceClass,
                                 @Nullable String type, @Nullable String name) {
        String value = words(domain, deviceClass, type, name);

        if (has(value, "garage", "garage_door", "гараж")) return "garage";
        if (has(value, "gate", "ворот", "калит")) return "gate";
        if (has(value, "blind", "shade", "shutter", "curtain", "жалюз", "штор", "роллет")) {
            return "blinds";
        }
        if (has(value, "cover")) return "door";
        if (has(value, "door", "window", "opening", "двер", "окн")) return "door";
        if (has(value, "lock", "замок")) return "lock";
        if (has(value, "light", "lamp", "bulb", "свет", "ламп")) return "light";
        if (has(value, "temperature", "thermostat", "термостат", "температур")) {
            return has(value, "climate", "thermostat", "термостат")
                    ? "thermostat" : "temperature";
        }
        if (has(value, "humidity", "влажност")) return "humidity";
        if (has(value, "leak", "moisture", "water", "flood", "протеч", "вода")) {
            return "water";
        }
        if (has(value, "motion", "occupancy", "presence", "движен", "присутств")) {
            return "motion";
        }
        if (has(value, "smoke", "carbon_monoxide", "gas", "дым", "газ")) return "smoke";
        if (has(value, "camera", "doorbell", "камер", "звонок")) return "camera";
        if (has(value, "alarm", "security", "сигнализац", "охран")) return "alarm";
        if (has(value, "vacuum", "пылесос")) return "vacuum";
        if (has(value, "weather", "precipitation", "rain", "погод", "дожд")) return "weather";
        if (has(value, "battery", "аккумулятор", "батар")) return "battery";
        if (has(value, "energy", "electric", "power_meter", "энерги", "электр")) {
            return "energy";
        }
        if (has(value, "media_player", "speaker", "music", "audio", "музык", "колон")) {
            return "music";
        }
        if (has(value, "fan", "вентилятор")) return "fan";
        if (has(value, "climate", "heater", "heat", "кондиционер", "климат", "нагрев")) {
            return "climate";
        }
        if (has(value, "person", "device_tracker", "location", "геолокац", "местополож")) {
            return "location";
        }
        if (has(value, "car", "vehicle", "автомоб")) return "car";
        if (has(value, "phone", "телефон")) return "phone";
        if (has(value, "plug", "outlet", "socket", "розет")) return "plug";
        if (has(value, "switch", "input_boolean", "relay", "active", "реле",
                "выключатель")) return "power";
        if (has(value, "button", "input_button", "scene", "script", "automation",
                "сценар", "кнопк")) return "scenario";
        return "devices";
    }

    private static String words(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(value.trim().toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static boolean has(String haystack, String... needles) {
        for (String needle : needles) if (haystack.contains(needle)) return true;
        return false;
    }
}
