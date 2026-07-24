/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** Default presentation presets; callers remain free to override every returned field. */
public final class SprutValueMapper {
    public static final String SOURCE_SPRUTHUB = "SPRUTHUB";
    public static final String COLOR_GREEN = "#FF4CAF50";
    public static final String COLOR_WHITE = "#FFFFFFFF";
    public static final String COLOR_ORANGE = "#FFFF9800";
    public static final String COLOR_RED = "#FFF44336";
    public static final String COLOR_YELLOW = "#FFFFC107";
    public static final String COLOR_TRANSPARENT = "#00000000";

    private SprutValueMapper() {}

    public static DisplayValue toDisplayPayload(
            SprutCatalog.Characteristic characteristic, long updatedAt) {
        return toDisplayPayload(characteristic).withUpdatedAt(updatedAt);
    }

    /** Ready-to-merge state patch. Disconnect handling deliberately belongs to the controller. */
    public static JSONObject toJsonPatch(
            SprutCatalog.Characteristic characteristic, long updatedAt) {
        DisplayValue display = toDisplayPayload(characteristic, updatedAt);
        JSONObject patch = new JSONObject();
        try {
            patch.put("text", display.text());
            patch.put("color", display.color());
            patch.put("visible", display.visible());
            patch.put("action_enabled", display.actionEnabled());
            patch.put("source", display.source());
            patch.put("fresh", display.fresh());
            patch.put("updated_at", display.updatedAt());
        } catch (JSONException error) {
            throw new IllegalStateException("Cannot create Sprut.hub display patch", error);
        }
        return patch;
    }

    public static DisplayValue toDisplayPayload(SprutCatalog.Characteristic characteristic) {
        Objects.requireNonNull(characteristic, "characteristic");
        Object value = characteristic.currentValue();
        boolean actionEnabled = characteristic.writable();
        String characteristicType = normalized(characteristic.type());
        String serviceType = normalized(characteristic.serviceType());

        if (value == null) {
            if (isStatelessAction(characteristicType, serviceType) && actionEnabled) {
                return new DisplayValue("Выполнить", COLOR_WHITE, true, true);
            }
            return new DisplayValue("…", COLOR_TRANSPARENT, false, actionEnabled);
        }

        if (isCoverState(characteristicType, serviceType, value)) {
            return coverState(asInt(value), actionEnabled);
        }

        if (isLockState(characteristicType, serviceType, value)) {
            switch (asInt(value)) {
                case 0: return new DisplayValue("Открыто", COLOR_GREEN, true, actionEnabled);
                case 1: return new DisplayValue("Закрыто", COLOR_WHITE, true, actionEnabled);
                case 2: return new DisplayValue("Заклинило", COLOR_RED, true, actionEnabled);
                default: return new DisplayValue("Неизвестно", COLOR_ORANGE, true, actionEnabled);
            }
        }

        if (isTemperature(characteristic, characteristicType, serviceType)) {
            return new DisplayValue(formatNumber(value) + temperatureUnit(characteristic.unit()),
                    COLOR_WHITE, true, actionEnabled);
        }

        Boolean bool = booleanLike(value, characteristicType);
        if (bool != null) {
            return new DisplayValue(bool ? enabledText(characteristicType) : disabledText(characteristicType),
                    bool ? COLOR_GREEN : COLOR_WHITE, true, actionEnabled);
        }

        String validName = validValueName(characteristic, value);
        if (!validName.isEmpty()) {
            return new DisplayValue(validName, COLOR_WHITE, true, actionEnabled);
        }

        String text = value instanceof Number ? formatNumber(value) : String.valueOf(value);
        String unit = displayUnit(characteristic.unit());
        if (!unit.isEmpty() && !text.endsWith(unit)) text += " " + unit;
        return new DisplayValue(text, COLOR_WHITE, true, actionEnabled);
    }

    public static String iconFor(SprutCatalog.Service service) {
        Objects.requireNonNull(service, "service");
        String type = normalized(service.type());
        if (containsAny(type, "garagedoor")) return "garage";
        if (containsAny(type, "windowcovering", "blind", "shutter", "gate")) return "gate";
        if (containsAny(type, "door", "contact", "motion", "occupancy")) return "door";
        if (containsAny(type, "light", "bulb")) return "light";
        if (containsAny(type, "lock")) return "lock";
        if (containsAny(type, "temperature", "thermostat", "heatercooler")) {
            return "temperature";
        }
        if (containsAny(type, "water", "leak", "faucet", "irrigation", "valve")) {
            return "water";
        }
        if (containsAny(type, "wifi")) return "wifi";
        if (containsAny(type, "location", "gps")) return "gps";
        if (containsAny(type, "bluetooth")) return "bluetooth";
        if (containsAny(type, "switch", "outlet", "fan", "pump", "button")) return "power";
        return "";
    }

    public static String iconFor(SprutCatalog.Characteristic characteristic) {
        Objects.requireNonNull(characteristic, "characteristic");
        String serviceType = normalized(characteristic.serviceType());
        String characteristicType = normalized(characteristic.type());
        String combined = serviceType + characteristicType;
        if (containsAny(combined, "garagedoor")) return "garage";
        if (containsAny(combined, "windowcovering", "blind", "shutter", "gate")) return "gate";
        if (containsAny(combined, "door", "contact", "motion", "occupancy")) return "door";
        if (containsAny(combined, "light", "bulb", "brightness")) return "light";
        if (containsAny(combined, "lock")) return "lock";
        if (containsAny(combined, "temperature", "thermostat", "heatercooler")) {
            return "temperature";
        }
        if (containsAny(combined, "water", "leak", "faucet", "irrigation", "valve")) {
            return "water";
        }
        if (containsAny(combined, "wifi")) return "wifi";
        if (containsAny(combined, "location", "gps")) return "gps";
        if (containsAny(combined, "bluetooth")) return "bluetooth";
        return characteristic.writable() ? "power" : "";
    }

    private static DisplayValue coverState(int state, boolean actionEnabled) {
        switch (state) {
            case 0: return new DisplayValue("Открыто", COLOR_GREEN, true, actionEnabled);
            case 1: return new DisplayValue("Закрыто", COLOR_WHITE, true, actionEnabled);
            case 2: return new DisplayValue("Открывается", COLOR_ORANGE, true, actionEnabled);
            case 3: return new DisplayValue("Закрывается", COLOR_RED, true, actionEnabled);
            case 4: return new DisplayValue("Остановлено", COLOR_YELLOW, true, actionEnabled);
            default:
                return new DisplayValue(String.valueOf(state), COLOR_WHITE, true, actionEnabled);
        }
    }

    private static boolean isCoverState(String characteristicType, String serviceType, Object value) {
        if (!(value instanceof Number)) return false;
        if (containsAny(characteristicType, "currentdoorstate", "doorstate")) return true;
        return containsAny(serviceType, "garagedoor")
                && !containsAny(characteristicType, "position", "obstruction", "target");
    }

    private static boolean isLockState(String characteristicType, String serviceType, Object value) {
        return value instanceof Number && containsAny(serviceType, "lock")
                && containsAny(characteristicType, "currentlockstate", "lockstate");
    }

    private static boolean isTemperature(SprutCatalog.Characteristic characteristic,
                                         String characteristicType, String serviceType) {
        String unit = normalized(characteristic.unit());
        return containsAny(characteristicType, "temperature")
                || containsAny(serviceType, "temperaturesensor", "thermostat")
                || containsAny(unit, "celsius", "fahrenheit");
    }

    private static boolean isStatelessAction(String characteristicType, String serviceType) {
        return containsAny(characteristicType, "programmableswitchevent", "execute", "trigger")
                || containsAny(serviceType, "button", "scenario", "statelessprogrammableswitch");
    }

    private static Boolean booleanLike(Object value, String characteristicType) {
        if (value instanceof Boolean) return (Boolean) value;
        if (!(value instanceof Number)) return null;
        if (!containsAny(characteristicType, "on", "active", "inuse", "motiondetected",
                "occupancydetected", "contactsensorstate", "leakdetected", "smokedetected")) {
            return null;
        }
        return ((Number) value).intValue() != 0;
    }

    private static String enabledText(String characteristicType) {
        if (containsAny(characteristicType, "motiondetected")) return "Движение";
        if (containsAny(characteristicType, "occupancydetected")) return "Присутствие";
        if (containsAny(characteristicType, "leakdetected")) return "Протечка";
        if (containsAny(characteristicType, "smokedetected")) return "Дым";
        if (containsAny(characteristicType, "contactsensorstate")) return "Открыто";
        return "Включено";
    }

    private static String disabledText(String characteristicType) {
        if (containsAny(characteristicType, "motiondetected")) return "Нет движения";
        if (containsAny(characteristicType, "occupancydetected")) return "Нет присутствия";
        if (containsAny(characteristicType, "leakdetected")) return "Сухо";
        if (containsAny(characteristicType, "smokedetected")) return "Нет дыма";
        if (containsAny(characteristicType, "contactsensorstate")) return "Закрыто";
        return "Выключено";
    }

    private static String validValueName(SprutCatalog.Characteristic characteristic, Object value) {
        for (SprutCatalog.ValidValue valid : characteristic.validValues()) {
            if (valuesEqual(valid.value(), value)) {
                if (!valid.name().isEmpty()) return valid.name();
                return valid.key();
            }
        }
        return "";
    }

    private static boolean valuesEqual(Object first, Object second) {
        if (first instanceof Number && second instanceof Number) {
            return Double.compare(((Number) first).doubleValue(),
                    ((Number) second).doubleValue()) == 0;
        }
        return Objects.equals(first, second);
    }

    private static int asInt(Object value) { return ((Number) value).intValue(); }

    private static String formatNumber(Object value) {
        if (!(value instanceof Number)) return String.valueOf(value);
        try {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return String.format(Locale.ROOT, "%s", value);
        }
    }

    private static String temperatureUnit(String raw) {
        String unit = normalized(raw);
        if (containsAny(unit, "fahrenheit")) return " °F";
        return " °C";
    }

    private static String displayUnit(String raw) {
        String unit = normalized(raw);
        if (unit.isEmpty() || "none".equals(unit)) return "";
        if (containsAny(unit, "percentage", "percent")) return "%";
        if (containsAny(unit, "celsius")) return "°C";
        if (containsAny(unit, "fahrenheit")) return "°F";
        if (containsAny(unit, "lux")) return "lx";
        return raw == null ? "" : raw.trim();
    }

    private static String normalized(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    public static final class DisplayValue {
        private final String text;
        private final String color;
        private final boolean visible;
        private final boolean actionEnabled;
        private final String source;
        private final boolean fresh;
        private final long updatedAt;

        public DisplayValue(String text, String color, boolean visible, boolean actionEnabled) {
            this(text, color, visible, actionEnabled, SOURCE_SPRUTHUB, true,
                    System.currentTimeMillis());
        }

        public DisplayValue(String text, String color, boolean visible, boolean actionEnabled,
                            String source, boolean fresh, long updatedAt) {
            this.text = Objects.requireNonNull(text, "text");
            this.color = Objects.requireNonNull(color, "color");
            this.visible = visible;
            this.actionEnabled = actionEnabled;
            this.source = Objects.requireNonNull(source, "source");
            this.fresh = fresh;
            this.updatedAt = updatedAt;
        }

        public String text() { return text; }

        public String color() { return color; }

        public boolean visible() { return visible; }

        public boolean actionEnabled() { return actionEnabled; }

        public String source() { return source; }

        public boolean fresh() { return fresh; }

        public long updatedAt() { return updatedAt; }

        private DisplayValue withUpdatedAt(long value) {
            return new DisplayValue(text, color, visible, actionEnabled, source, fresh, value);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DisplayValue)) return false;
            DisplayValue value = (DisplayValue) other;
            return visible == value.visible && actionEnabled == value.actionEnabled
                    && fresh == value.fresh && updatedAt == value.updatedAt
                    && text.equals(value.text) && color.equals(value.color)
                    && source.equals(value.source);
        }

        @Override public int hashCode() {
            return Objects.hash(text, color, visible, actionEnabled, source, fresh, updatedAt);
        }

        @Override public String toString() {
            return "DisplayValue{" + text + ", " + color + ", visible=" + visible
                    + ", actionEnabled=" + actionEnabled + ", source=" + source
                    + ", fresh=" + fresh + ", updatedAt=" + updatedAt + '}';
        }
    }
}
