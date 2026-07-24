/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;

/** Pure connector-neutral state projection for one interactive smart-home HOME tile. */
public final class SmartHomeShortcutStatePolicy {
    public static final class State {
        @NonNull public final String iconKey;
        @NonNull public final String valueLabel;
        public final boolean present;
        public final boolean fresh;
        public final boolean available;
        public final boolean activeKnown;
        public final boolean active;

        State(@NonNull String iconKey, @NonNull String valueLabel, boolean present,
              boolean fresh, boolean available, boolean activeKnown, boolean active) {
            this.iconKey = iconKey;
            this.valueLabel = valueLabel;
            this.present = present;
            this.fresh = fresh;
            this.available = available;
            this.activeKnown = activeKnown;
            this.active = active;
        }
    }

    private SmartHomeShortcutStatePolicy() {}

    /** New shortcuts use an explicit state binding; old RULE entries safely fall back to command. */
    @Nullable
    public static SourceBinding bindingFor(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                           @Nullable IntentActionRule rule) {
        if (shortcut.stateBinding != null && shortcut.stateBinding.isBound()) {
            return shortcut.stateBinding;
        }
        ActionBinding command = rule == null ? null : rule.command;
        if (command == null || !command.isBound()) return null;
        return new SourceBinding(command.connectorType, command.connectorId, command.resourceId,
                "", SourceBinding.PRESENTATION_AUTO, "");
    }

    @NonNull
    public static State resolve(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                @Nullable IntentActionRule rule,
                                @NonNull Collection<ConnectorValue> values) {
        SourceBinding binding = bindingFor(shortcut, rule);
        ConnectorValue value = find(binding, values);
        return resolveValue(shortcut, rule, binding, value);
    }

    /** O(1)-lookup counterpart used by LauncherActivity's indexed live registry snapshot. */
    @NonNull
    public static State resolveValue(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                     @Nullable IntentActionRule rule,
                                     @Nullable ConnectorValue value) {
        SourceBinding binding = bindingFor(shortcut, rule);
        if (!matches(binding, value)) value = null;
        return resolveValue(shortcut, rule, binding, value);
    }

    @NonNull
    private static State resolveValue(LauncherShortcutStore.Shortcut shortcut,
                                      @Nullable IntentActionRule rule,
                                      @Nullable SourceBinding binding,
                                      @Nullable ConnectorValue value) {
        String icon = semanticIcon(shortcut, rule, binding, value);
        if (value == null) {
            return new State(icon, "…", false, false, false, false, false);
        }
        Object raw = value.resolveValue(binding == null ? "" : binding.valuePath);
        boolean available = value.available && value.readable;
        CoverState cover = coverState(raw, binding, value);
        String label = available
                ? cover == null ? display(raw, value.unit) : cover.label
                : "Недоступно";
        if (available && !value.fresh) label = "⟳ " + label;
        Active active = cover == null
                ? active(raw, binding, value)
                : new Active(true, cover.active);
        return new State(icon, bounded(label), true, value.fresh, available,
                active.known, active.value);
    }

    private static boolean matches(@Nullable SourceBinding binding,
                                   @Nullable ConnectorValue value) {
        return binding != null && binding.isBound() && value != null
                && value.connectorType == binding.connectorType
                && value.connectorId.equals(binding.connectorId)
                && value.resourceId.equals(binding.resourceId);
    }

    @Nullable
    private static ConnectorValue find(@Nullable SourceBinding binding,
                                       Collection<ConnectorValue> values) {
        if (binding == null || !binding.isBound()) return null;
        for (ConnectorValue value : values) {
            if (value.connectorType == binding.connectorType
                    && value.connectorId.equals(binding.connectorId)
                    && value.resourceId.equals(binding.resourceId)) return value;
        }
        return null;
    }

    @NonNull
    private static String semanticIcon(LauncherShortcutStore.Shortcut shortcut,
                                       @Nullable IntentActionRule rule,
                                       @Nullable SourceBinding binding,
                                       @Nullable ConnectorValue value) {
        if (shortcut.iconCustomized) return shortcut.icon;
        String domain = "";
        if (binding != null && binding.connectorType == ConnectorType.HOME_ASSISTANT) {
            int dot = binding.resourceId.indexOf('.');
            if (dot > 0) domain = binding.resourceId.substring(0, dot);
        }
        Map<String, Object> attributes = value == null
                ? java.util.Collections.emptyMap() : value.attributes;
        String deviceClass = first(attributes.get("device_class"),
                attributes.get("class"), attributes.get("type"));
        String type = (value == null ? "" : value.valueType + " " + value.unit) + " "
                + (binding == null ? "" : binding.resourceId);
        String name = rule == null ? shortcut.title
                : rule.accessoryLabel + " " + rule.serviceLabel + " "
                + rule.characteristicLabel;
        String suggested = SmartHomeIconResolver.suggest(domain, deviceClass, type, name);
        if ("devices".equals(suggested) && !"devices".equals(shortcut.icon)
                && !"apps".equals(shortcut.icon)) return shortcut.icon;
        return suggested;
    }

    /**
     * Home Assistant publishes textual cover states, while Sprut/HomeKit exposes
     * {@code CurrentDoorState} as an enum where {@code 0=open} and {@code 1=closed}.
     * Treating that enum as a generic boolean reverses the gate indicator, so cover semantics
     * must be resolved before the generic numeric active-state rule.
     */
    @Nullable
    private static CoverState coverState(@Nullable Object raw,
                                         @Nullable SourceBinding binding,
                                         ConnectorValue value) {
        String domain = "";
        if (binding != null && binding.connectorType == ConnectorType.HOME_ASSISTANT) {
            int dot = binding.resourceId.indexOf('.');
            if (dot > 0) domain = binding.resourceId.substring(0, dot);
        }
        String characteristicType = first(
                value.attributes.get("characteristic_type"),
                value.attributes.get("characteristicType")).toLowerCase(Locale.ROOT);
        String serviceType = first(
                value.attributes.get("service_type"),
                value.attributes.get("serviceType")).toLowerCase(Locale.ROOT);
        boolean cover = "cover".equals(domain)
                || (binding != null
                    && SourceBinding.PRESENTATION_COVER.equals(binding.presentation))
                || characteristicType.contains("currentdoorstate")
                || characteristicType.contains("doorstate")
                || serviceType.contains("garagedoor");
        if (!cover) return null;

        if (raw instanceof Number) {
            // Position and obstruction characteristics use different numeric domains.
            // CurrentDoorState uses 0..4 and TargetDoorState uses its compatible 0..1 subset.
            boolean stateCharacteristic =
                    !contains(characteristicType, "position", "obstruction");
            boolean doorState = stateCharacteristic && ("cover".equals(domain)
                    || characteristicType.contains("currentdoorstate")
                    || characteristicType.contains("doorstate")
                    || serviceType.contains("garagedoor"));
            if (!doorState) return null;
            double numeric = ((Number) raw).doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) return null;
            switch ((int) numeric) {
                case 0: return new CoverState("Открыто", true);
                case 1: return new CoverState("Закрыто", false);
                case 2: return new CoverState("Открывается", true);
                case 3: return new CoverState("Закрывается", true);
                case 4: return new CoverState("Остановлено", true);
                default: return null;
            }
        }

        String state = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        switch (state) {
            case "open":
            case "opened":
            case "открыто":
                return new CoverState("Открыто", true);
            case "opening":
            case "открывается":
                return new CoverState("Открывается", true);
            case "closed":
            case "закрыто":
                return new CoverState("Закрыто", false);
            case "closing":
            case "закрывается":
                return new CoverState("Закрывается", true);
            case "stopped":
            case "остановлено":
                return new CoverState("Остановлено", true);
            default:
                return null;
        }
    }

    @NonNull
    private static Active active(@Nullable Object raw, @Nullable SourceBinding binding,
                                 ConnectorValue value) {
        if (raw instanceof Boolean) return new Active(true, (Boolean) raw);
        String domain = "";
        if (binding != null && binding.connectorType == ConnectorType.HOME_ASSISTANT) {
            int dot = binding.resourceId.indexOf('.');
            if (dot > 0) domain = binding.resourceId.substring(0, dot);
        }
        String characteristicType = first(
                value.attributes.get("characteristic_type"),
                value.attributes.get("characteristicType"));
        String type = (domain + " " + value.valueType + " "
                + first(value.attributes.get("device_class")) + " "
                + (binding == null ? "" : binding.resourceId) + " "
                + first(value.attributes.get("service_type"),
                value.attributes.get("serviceType")) + " "
                + characteristicType + " "
                + (binding == null ? "" : binding.presentation))
                .toLowerCase(Locale.ROOT);
        boolean binary = contains(type, "bool", "binary_sensor", "switch", "light", "lock",
                "lightbulb", "relay", "cover", "fan", "vacuum", "siren", "alarm",
                "motion", "occupancy", "leak", "smoke", "contactsensor", "garagedoor",
                "currentdoorstate", "currentlockstate", "contactsensorstate",
                "motiondetected", "occupancydetected", "leakdetected", "smokedetected",
                "inuse", "presentation_boolean")
                || containsExact(characteristicType.trim().toLowerCase(Locale.ROOT),
                "on", "active");
        if (raw instanceof Number) {
            return binary ? new Active(true, Math.abs(((Number) raw).doubleValue()) > 1e-9d)
                    : new Active(false, false);
        }
        String text = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (containsExact(text, "on", "true", "open", "opening", "unlocked", "active",
                "detected", "home", "playing", "heat", "cool", "auto", "cleaning")) {
            return new Active(true, true);
        }
        if (containsExact(text, "off", "false", "closed", "closing", "locked", "inactive",
                "clear", "not_home", "idle", "paused", "standby", "unavailable", "unknown")) {
            return new Active(true, false);
        }
        return new Active(false, false);
    }

    @NonNull
    private static String display(@Nullable Object raw, String unit) {
        final String value;
        if (raw == null) value = "—";
        else if (raw instanceof Boolean) value = (Boolean) raw ? "Вкл" : "Выкл";
        else if (raw instanceof Number) {
            try {
                value = new BigDecimal(String.valueOf(raw)).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return bounded(String.valueOf(raw));
            }
        } else {
            String text = String.valueOf(raw).trim();
            switch (text.toLowerCase(Locale.ROOT)) {
                case "on": value = "Вкл"; break;
                case "off": value = "Выкл"; break;
                case "open": value = "Открыто"; break;
                case "opening": value = "Открывается"; break;
                case "closed": value = "Закрыто"; break;
                case "closing": value = "Закрывается"; break;
                case "locked": value = "Закрыт"; break;
                case "unlocked": value = "Открыт"; break;
                case "playing": value = "Играет"; break;
                case "paused": value = "Пауза"; break;
                case "unavailable": value = "Недоступно"; break;
                case "unknown": value = "—"; break;
                default: value = text.isEmpty() ? "—" : text;
            }
        }
        String suffix = unit == null ? "" : unit.trim();
        return bounded(suffix.isEmpty() || "—".equals(value) ? value : value + " " + suffix);
    }

    private static String bounded(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() <= 48 ? value : value.substring(0, 47) + "…";
    }

    private static String first(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static boolean contains(String haystack, String... needles) {
        for (String needle : needles) if (haystack.contains(needle)) return true;
        return false;
    }

    private static boolean containsExact(String value, String... expected) {
        for (String item : expected) if (item.equals(value)) return true;
        return false;
    }

    private static final class CoverState {
        @NonNull final String label;
        final boolean active;

        CoverState(@NonNull String label, boolean active) {
            this.label = label;
            this.active = active;
        }
    }

    private static final class Active {
        final boolean known;
        final boolean value;
        Active(boolean known, boolean value) {
            this.known = known;
            this.value = value;
        }
    }
}
