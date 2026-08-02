/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut.preset;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import dezz.status.widget.popup.PopupIconCatalog;
import dezz.status.widget.sprut.SprutPath;

/**
 * Connector-neutral recommendation for turning one Sprut.hub service into a popup tile.
 *
 * <p>The recommendation deliberately contains no UI configuration and no connector instance id.
 * A caller can map the characteristic paths to its own source/action binding model.</p>
 */
public final class SprutPopupPreset {
    public enum Presentation {
        AUTO,
        COVER,
        BOOLEAN,
        TEMPERATURE,
        RAW
    }

    public enum ActionOperation {
        SET,
        TOGGLE
    }

    private final String iconId;
    private final String title;
    private final Presentation presentation;
    private final SprutPath primaryCharacteristicPath;
    private final SprutPath actionCharacteristicPath;
    private final ActionOperation actionOperation;
    private final Object defaultActionPayload;
    private final List<StatusRule> statusRules;
    private final int columnSpan;
    private final int rowSpan;

    SprutPopupPreset(String iconId, String title, Presentation presentation,
                     SprutPath primaryCharacteristicPath, SprutPath actionCharacteristicPath,
                     ActionOperation actionOperation, Object defaultActionPayload,
                     List<StatusRule> statusRules, int columnSpan, int rowSpan) {
        String normalizedIcon = normalize(iconId);
        if (!PopupIconCatalog.IDS.contains(normalizedIcon)) {
            throw new IllegalArgumentException("Unknown popup icon id: " + iconId);
        }
        if (columnSpan < 1 || rowSpan < 1) {
            throw new IllegalArgumentException("Popup spans must be positive");
        }
        if (actionCharacteristicPath == null && actionOperation != null) {
            throw new IllegalArgumentException("An action operation requires a characteristic path");
        }
        this.iconId = normalizedIcon;
        this.title = title == null ? "" : title.trim();
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        this.primaryCharacteristicPath = primaryCharacteristicPath;
        this.actionCharacteristicPath = actionCharacteristicPath;
        this.actionOperation = actionOperation;
        this.defaultActionPayload = defaultActionPayload;
        this.statusRules = statusRules == null || statusRules.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(statusRules));
        this.columnSpan = columnSpan;
        this.rowSpan = rowSpan;
    }

    public String iconId() { return iconId; }

    public String title() { return title; }

    public Presentation presentation() { return presentation; }

    public Optional<SprutPath> primaryCharacteristicPath() {
        return Optional.ofNullable(primaryCharacteristicPath);
    }

    public Optional<SprutPath> actionCharacteristicPath() {
        return Optional.ofNullable(actionCharacteristicPath);
    }

    public Optional<ActionOperation> actionOperation() {
        return Optional.ofNullable(actionOperation);
    }

    /** Empty for toggle actions and SET actions that require the user to select a target value. */
    public Optional<Object> defaultActionPayload() {
        return Optional.ofNullable(defaultActionPayload);
    }

    public List<StatusRule> statusRules() { return statusRules; }

    public int columnSpan() { return columnSpan; }

    public int rowSpan() { return rowSpan; }

    /** Returns the first matching status style, including a possible fallback rule. */
    public Optional<StatusStyle> statusFor(Object value) {
        StatusStyle fallback = null;
        for (StatusRule rule : statusRules) {
            if (rule.kind() == StatusRule.Kind.FALLBACK) {
                if (fallback == null) fallback = rule.style();
            } else if (rule.matches(value)) {
                return Optional.of(rule.style());
            }
        }
        return Optional.ofNullable(fallback);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class StatusStyle {
        private final String label;
        private final String argbColor;

        public StatusStyle(String label, String argbColor) {
            this.label = label == null ? "" : label.trim();
            this.argbColor = requireArgb(argbColor);
        }

        public String label() { return label; }

        /** Android-style {@code #AARRGGBB}. */
        public String argbColor() { return argbColor; }

        private static String requireArgb(String raw) {
            String color = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (!color.matches("#[0-9A-F]{8}")) {
                throw new IllegalArgumentException("Expected #AARRGGBB color, got: " + raw);
            }
            return color;
        }
    }

    public static final class StatusRule {
        public enum Kind {
            EXACT,
            RANGE,
            FALLBACK
        }

        private final Kind kind;
        private final Object expectedValue;
        private final Double minimumInclusive;
        private final Double maximumInclusive;
        private final StatusStyle style;

        private StatusRule(Kind kind, Object expectedValue, Double minimumInclusive,
                           Double maximumInclusive, StatusStyle style) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.expectedValue = expectedValue;
            this.minimumInclusive = minimumInclusive;
            this.maximumInclusive = maximumInclusive;
            this.style = Objects.requireNonNull(style, "style");
        }

        public static StatusRule exact(Object expectedValue, String label, String argbColor) {
            return new StatusRule(Kind.EXACT, expectedValue, null, null,
                    new StatusStyle(label, argbColor));
        }

        public static StatusRule range(Double minimumInclusive, Double maximumInclusive,
                                       String label, String argbColor) {
            if (minimumInclusive == null && maximumInclusive == null) {
                throw new IllegalArgumentException("A range needs at least one boundary");
            }
            if (minimumInclusive != null && maximumInclusive != null
                    && minimumInclusive > maximumInclusive) {
                throw new IllegalArgumentException("Minimum must not exceed maximum");
            }
            return new StatusRule(Kind.RANGE, null, minimumInclusive, maximumInclusive,
                    new StatusStyle(label, argbColor));
        }

        public static StatusRule fallback(String label, String argbColor) {
            return new StatusRule(Kind.FALLBACK, null, null, null,
                    new StatusStyle(label, argbColor));
        }

        public Kind kind() { return kind; }

        public Optional<Object> expectedValue() { return Optional.ofNullable(expectedValue); }

        public Optional<Double> minimumInclusive() {
            return Optional.ofNullable(minimumInclusive);
        }

        public Optional<Double> maximumInclusive() {
            return Optional.ofNullable(maximumInclusive);
        }

        public StatusStyle style() { return style; }

        public boolean matches(Object actual) {
            switch (kind) {
                case FALLBACK:
                    return true;
                case RANGE:
                    if (!(actual instanceof Number)) return false;
                    double number = ((Number) actual).doubleValue();
                    return !Double.isNaN(number)
                            && (minimumInclusive == null || number >= minimumInclusive)
                            && (maximumInclusive == null || number <= maximumInclusive);
                case EXACT:
                default:
                    return equivalent(expectedValue, actual);
            }
        }

        private static boolean equivalent(Object expected, Object actual) {
            if (expected == actual) return true;
            if (expected == null || actual == null) return false;
            if (expected instanceof Number && actual instanceof Number) {
                try {
                    return new BigDecimal(expected.toString())
                            .compareTo(new BigDecimal(actual.toString())) == 0;
                } catch (NumberFormatException ignored) {
                    return Double.compare(((Number) expected).doubleValue(),
                            ((Number) actual).doubleValue()) == 0;
                }
            }
            if (expected instanceof Boolean) {
                Boolean decoded = booleanValue(actual);
                return decoded != null && expected.equals(decoded);
            }
            if (actual instanceof Boolean) {
                Boolean decoded = booleanValue(expected);
                return decoded != null && actual.equals(decoded);
            }
            return String.valueOf(expected).trim().equalsIgnoreCase(String.valueOf(actual).trim());
        }

        private static Boolean booleanValue(Object value) {
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (number == 0d) return false;
                if (number == 1d) return true;
                return null;
            }
            String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(text) || "on".equals(text) || "yes".equals(text)
                    || "1".equals(text)) return true;
            if ("false".equals(text) || "off".equals(text) || "no".equals(text)
                    || "0".equals(text)) return false;
            return null;
        }
    }
}
