/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Data-only presentation suggestions. Styling and colors deliberately stay in the widget layer. */
public final class HaEntityMapper {
    private HaEntityMapper() {}

    /**
     * Reads {@code state}, {@code entity_id}, {@code last_updated}, or an attribute dot path.
     * Attribute paths may be written as either {@code attributes.battery.level} or
     * {@code battery.level}; numeric segments index arrays.
     */
    public static Object rawValue(HaEntity entity, String dotPath) {
        Objects.requireNonNull(entity, "entity");
        String path = dotPath == null ? "" : dotPath.trim();
        if (path.isEmpty() || "state".equals(path)) return entity.state();
        if ("entity_id".equals(path)) return entity.entityId();
        if ("last_updated".equals(path)) return entity.lastUpdated();
        if ("attributes".equals(path)) return entity.attributes();
        if (path.startsWith("attributes.")) path = path.substring("attributes.".length());

        Object current = entity.attributes();
        String[] segments = path.split("\\.");
        for (int index = 0; index < segments.length; index++) {
            if (current instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) current;
                String remaining = join(segments, index);
                if (map.containsKey(remaining)) return map.get(remaining);
                if (!map.containsKey(segments[index])) return null;
                current = map.get(segments[index]);
            } else if (current instanceof List<?>) {
                int item;
                try {
                    item = Integer.parseInt(segments[index]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
                List<?> list = (List<?>) current;
                if (item < 0 || item >= list.size()) return null;
                current = list.get(item);
            } else {
                return null;
            }
        }
        return current;
    }

    public static Presentation suggestedPresentation(HaEntity entity) {
        return suggestedPresentation(entity, "state");
    }

    public static Presentation suggestedPresentation(HaEntity entity, String dotPath) {
        Objects.requireNonNull(entity, "entity");
        Object raw = rawValue(entity, dotPath);
        boolean statePath = dotPath == null || dotPath.trim().isEmpty()
                || "state".equals(dotPath.trim());
        String state = raw == null ? "" : String.valueOf(raw).trim();
        boolean available = raw != null && !"unknown".equalsIgnoreCase(state)
                && !"unavailable".equalsIgnoreCase(state);
        String icon = suggestedIcon(entity);

        if (statePath && "cover".equals(entity.domain())) {
            return new Presentation(coverText(state), PresentationKind.COVER, icon, available, raw);
        }

        if (isTemperature(entity, dotPath)) {
            String unit = stringAttribute(entity, "unit_of_measurement");
            String text = formatRaw(raw);
            if (!unit.isEmpty() && !text.endsWith(unit)) text += " " + unit;
            return new Presentation(text, PresentationKind.TEMPERATURE, icon, available, raw);
        }

        Boolean booleanValue = booleanValue(entity, raw, statePath);
        if (booleanValue != null) {
            boolean contact = isContactClass(stringAttribute(entity, "device_class"));
            String text = contact
                    ? (booleanValue ? "Открыто" : "Закрыто")
                    : (booleanValue ? "Включено" : "Выключено");
            return new Presentation(text, PresentationKind.BOOLEAN, icon, available, raw);
        }

        String text;
        if ("unavailable".equalsIgnoreCase(state)) text = "Недоступно";
        else if ("unknown".equalsIgnoreCase(state)) text = "Неизвестно";
        else text = formatRaw(raw);
        return new Presentation(text, PresentationKind.RAW, icon, available, raw);
    }

    private static String coverText(String state) {
        switch (state.toLowerCase(Locale.ROOT)) {
            case "open": return "Открыто";
            case "opening": return "Открывается";
            case "closing": return "Закрывается";
            case "closed": return "Закрыто";
            case "stopped": return "Остановлено";
            case "unavailable": return "Недоступно";
            case "unknown": return "Неизвестно";
            default: return state;
        }
    }

    private static Boolean booleanValue(HaEntity entity, Object raw, boolean statePath) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (!statePath || raw == null) return null;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("on".equals(value) || "true".equals(value)) return true;
        if ("off".equals(value) || "false".equals(value)) return false;
        String domain = entity.domain();
        if (("binary_sensor".equals(domain) || "input_boolean".equals(domain))
                && ("1".equals(value) || "0".equals(value))) return "1".equals(value);
        return null;
    }

    private static boolean isTemperature(HaEntity entity, String dotPath) {
        String deviceClass = stringAttribute(entity, "device_class").toLowerCase(Locale.ROOT);
        String unit = stringAttribute(entity, "unit_of_measurement").toLowerCase(Locale.ROOT);
        String path = dotPath == null ? "" : dotPath.toLowerCase(Locale.ROOT);
        return "temperature".equals(deviceClass) || path.contains("temperature")
                || unit.contains("°c") || unit.contains("°f")
                || unit.contains("celsius") || unit.contains("fahrenheit");
    }

    private static boolean isContactClass(String deviceClass) {
        String value = deviceClass.toLowerCase(Locale.ROOT);
        return "door".equals(value) || "garage_door".equals(value) || "window".equals(value)
                || "opening".equals(value) || "lock".equals(value);
    }

    private static String suggestedIcon(HaEntity entity) {
        String configured = stringAttribute(entity, "icon");
        if (!configured.isEmpty()) return configured;
        String deviceClass = stringAttribute(entity, "device_class").toLowerCase(Locale.ROOT);
        if ("cover".equals(entity.domain())) return "mdi:garage";
        if ("temperature".equals(deviceClass)) return "mdi:thermometer";
        if (isContactClass(deviceClass)) return "mdi:door";
        switch (entity.domain()) {
            case "light": return "mdi:lightbulb";
            case "lock": return "mdi:lock";
            case "switch":
            case "input_boolean": return "mdi:toggle-switch";
            default: return "";
        }
    }

    private static String stringAttribute(HaEntity entity, String name) {
        Object value = entity.attribute(name);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String formatRaw(Object value) {
        if (value == null) return "";
        if (value instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                // Fall through to the lossless string representation.
            }
        }
        return String.valueOf(value);
    }

    private static String join(String[] segments, int start) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < segments.length; index++) {
            if (result.length() > 0) result.append('.');
            result.append(segments[index]);
        }
        return result.toString();
    }

    public enum PresentationKind { COVER, BOOLEAN, TEMPERATURE, RAW }

    public static final class Presentation {
        private final String text;
        private final PresentationKind kind;
        private final String suggestedIcon;
        private final boolean available;
        private final Object rawValue;

        Presentation(String text, PresentationKind kind, String suggestedIcon,
                     boolean available, Object rawValue) {
            this.text = text;
            this.kind = kind;
            this.suggestedIcon = suggestedIcon;
            this.available = available;
            this.rawValue = rawValue;
        }

        public String text() { return text; }

        public PresentationKind kind() { return kind; }

        public String suggestedIcon() { return suggestedIcon; }

        public boolean available() { return available; }

        public Object rawValue() { return rawValue; }
    }
}
