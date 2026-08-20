/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.integration.ActionBinding;

/** Typed and metadata-aware conversion for interactive Sprut.hub commands. */
public final class SprutActionValue {
    private SprutActionValue() {}

    /** Decodes SET payload or derives TOGGLE, then validates it against the live characteristic. */
    @NonNull
    public static Object resolve(@NonNull ActionBinding binding,
                                 @NonNull SprutCatalog.Characteristic characteristic) {
        Object raw;
        if (ActionBinding.OPERATION_TOGGLE.equals(binding.operation)) {
            raw = toggledValue(characteristic);
        } else if (ActionBinding.OPERATION_SET.equals(binding.operation)) {
            raw = decodePrimitive(binding.payload);
        } else {
            throw new IllegalArgumentException("Sprut.hub supports only SET and TOGGLE actions");
        }
        SprutCatalog.ValueType type = resolvedType(characteristic);
        validateIntrinsicIntegerRange(raw, characteristic.format(), type);
        Object converted = coerce(raw, type);
        validateMetadata(converted, characteristic);
        return converted;
    }

    /** Encodes one exact JSON primitive for {@link ActionBinding#payload}. */
    @NonNull
    public static String encodePrimitive(Object value) {
        if (value == null || value == JSONObject.NULL) {
            throw new IllegalArgumentException("Action value must not be null");
        }
        if (!(value instanceof Boolean) && !(value instanceof Number)
                && !(value instanceof CharSequence) && !(value instanceof Character)) {
            throw new IllegalArgumentException("Action value must be a JSON primitive");
        }
        JSONArray wrapper = new JSONArray();
        wrapper.put(value instanceof Character ? String.valueOf(value) : value);
        String json = wrapper.toString();
        return json.substring(1, json.length() - 1);
    }

    /** Human-readable value used by the editor without changing the typed command payload. */
    @NonNull
    public static String displayValue(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? "1 (включено)" : "0 (выключено)";
        return String.valueOf(value);
    }

    /** Whether TOGGLE has exactly two known states and is therefore deterministic. */
    public static boolean supportsToggle(@NonNull SprutCatalog.Characteristic characteristic) {
        if (isBooleanLike(characteristic)) return true;
        List<SprutCatalog.ValidValue> valid = characteristic.validValues();
        if (valid.size() == 2) return true;
        if (!(characteristic.currentValue() instanceof Number)) return false;
        Number min = characteristic.minValue();
        Number max = characteristic.maxValue();
        Number step = characteristic.minStep();
        if (min == null || max == null) return false;
        try {
            BigDecimal span = decimal(max).subtract(decimal(min)).abs();
            if (span.compareTo(BigDecimal.ONE) == 0 && looksBooleanSemantic(characteristic)) {
                return true;
            }
            return step != null && span.compareTo(decimal(step).abs()) == 0;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** True for native booleans and legacy integer 0/1 characteristics such as On/Active. */
    public static boolean isBooleanLike(@NonNull SprutCatalog.Characteristic characteristic) {
        SprutCatalog.ValueType type = resolvedType(characteristic);
        if (type == SprutCatalog.ValueType.BOOLEAN
                || characteristic.currentValue() instanceof Boolean) return true;
        if (!looksBooleanSemantic(characteristic)) return false;
        Number min = characteristic.minValue();
        Number max = characteristic.maxValue();
        if (min == null || max == null) return false;
        try {
            return decimal(min).compareTo(BigDecimal.ZERO) == 0
                    && decimal(max).compareTo(BigDecimal.ONE) == 0;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @NonNull
    private static Object toggledValue(SprutCatalog.Characteristic characteristic) {
        Object current = characteristic.currentValue();
        if (current instanceof Boolean) return !((Boolean) current);
        if (resolvedType(characteristic) == SprutCatalog.ValueType.BOOLEAN) {
            return !asBoolean(current);
        }
        List<SprutCatalog.ValidValue> valid = characteristic.validValues();
        if (valid.size() == 2) {
            Object first = valid.get(0).value();
            Object second = valid.get(1).value();
            if (equivalent(current, first)) return second;
            if (equivalent(current, second)) return first;
            throw new IllegalArgumentException("Current value is outside the two-state list");
        }
        if (supportsToggle(characteristic)) {
            Number min = characteristic.minValue();
            Number max = characteristic.maxValue();
            if (equivalent(current, min)) return max;
            if (equivalent(current, max)) return min;
            throw new IllegalArgumentException("Current value is not a toggle endpoint");
        }
        throw new IllegalArgumentException("TOGGLE is unsafe for this characteristic");
    }

    @NonNull
    private static Object decodePrimitive(String payload) {
        String raw = payload == null ? "" : payload.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Action value is empty");
        try {
            Object decoded = new JSONArray("[" + raw + "]").get(0);
            if (decoded instanceof JSONObject) {
                JSONObject object = (JSONObject) decoded;
                if (!object.has("value")) {
                    throw new IllegalArgumentException("Action object must contain value");
                }
                decoded = object.get("value");
            }
            if (decoded == JSONObject.NULL || decoded instanceof JSONArray
                    || decoded instanceof JSONObject) {
                throw new IllegalArgumentException("Action value must be a JSON primitive");
            }
            return decoded;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Action value is not valid JSON", error);
        }
    }

    @NonNull
    private static Object coerce(Object raw, SprutCatalog.ValueType type) {
        try {
            switch (type) {
                case BOOLEAN:
                    return asBoolean(raw);
                case INTEGER: {
                    BigDecimal value = decimal(raw);
                    return value.intValueExact();
                }
                case LONG: {
                    BigDecimal value = decimal(raw);
                    return value.longValueExact();
                }
                case FLOAT: {
                    float value = Float.parseFloat(String.valueOf(raw));
                    if (!Float.isFinite(value)) throw new NumberFormatException("not finite");
                    return value;
                }
                case DOUBLE: {
                    double value = Double.parseDouble(String.valueOf(raw));
                    if (!Double.isFinite(value)) throw new NumberFormatException("not finite");
                    return value;
                }
                case STRING:
                    return String.valueOf(raw);
                case UNKNOWN:
                default:
                    if (raw instanceof Boolean || raw instanceof String) return raw;
                    if (raw instanceof Integer || raw instanceof Long || raw instanceof Float
                            || raw instanceof Double) return raw;
                    if (raw instanceof Number) return ((Number) raw).doubleValue();
                    return String.valueOf(raw);
            }
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Value does not match " + type + ": " + raw,
                    error);
        }
    }

    private static boolean asBoolean(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Boolean is not finite");
            return value != 0d;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        switch (text) {
            case "1":
            case "true":
            case "on":
            case "yes":
            case "вкл":
                return true;
            case "0":
            case "false":
            case "off":
            case "no":
            case "выкл":
                return false;
            default:
                throw new IllegalArgumentException("Value is not boolean: " + raw);
        }
    }

    private static void validateMetadata(Object value,
                                         SprutCatalog.Characteristic characteristic) {
        List<SprutCatalog.ValidValue> valid = characteristic.validValues();
        if (!valid.isEmpty()) {
            boolean accepted = false;
            for (SprutCatalog.ValidValue candidate : valid) {
                if (equivalent(value, candidate.value())) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) throw new IllegalArgumentException("Value is outside validValues");
        }
        if (!(value instanceof Number)) return;
        BigDecimal numeric = decimal(value);
        if (characteristic.minValue() != null
                && numeric.compareTo(decimal(characteristic.minValue())) < 0) {
            throw new IllegalArgumentException("Value is below minValue");
        }
        if (characteristic.maxValue() != null
                && numeric.compareTo(decimal(characteristic.maxValue())) > 0) {
            throw new IllegalArgumentException("Value is above maxValue");
        }
        if (characteristic.minStep() != null) {
            BigDecimal step = decimal(characteristic.minStep()).abs();
            if (step.signum() == 0) throw new IllegalArgumentException("minStep must be positive");
            BigDecimal base = characteristic.minValue() == null
                    ? BigDecimal.ZERO : decimal(characteristic.minValue());
            if (numeric.subtract(base).remainder(step).stripTrailingZeros().signum() != 0) {
                throw new IllegalArgumentException("Value does not match minStep");
            }
        }
    }

    /**
     * Enforces the range declared by an integer format even when Sprut.hub omits minValue and
     * maxValue. uint64 is intentionally capped at Long.MAX_VALUE: both the catalog model and the
     * Sprut protocol adapter use the signed 64-bit longValue wire wrapper.
     */
    private static void validateIntrinsicIntegerRange(Object raw, String rawFormat,
                                                      SprutCatalog.ValueType type) {
        if (type != SprutCatalog.ValueType.INTEGER && type != SprutCatalog.ValueType.LONG) return;
        BigDecimal minimum;
        BigDecimal maximum;
        switch (normalizedFormat(rawFormat)) {
            case "int8":
                minimum = BigDecimal.valueOf(Byte.MIN_VALUE);
                maximum = BigDecimal.valueOf(Byte.MAX_VALUE);
                break;
            case "int16":
                minimum = BigDecimal.valueOf(Short.MIN_VALUE);
                maximum = BigDecimal.valueOf(Short.MAX_VALUE);
                break;
            case "int32":
                minimum = BigDecimal.valueOf(Integer.MIN_VALUE);
                maximum = BigDecimal.valueOf(Integer.MAX_VALUE);
                break;
            case "int64":
                minimum = BigDecimal.valueOf(Long.MIN_VALUE);
                maximum = BigDecimal.valueOf(Long.MAX_VALUE);
                break;
            case "uint8":
                minimum = BigDecimal.ZERO;
                maximum = BigDecimal.valueOf(255L);
                break;
            case "uint16":
                minimum = BigDecimal.ZERO;
                maximum = BigDecimal.valueOf(65_535L);
                break;
            case "uint32":
                minimum = BigDecimal.ZERO;
                maximum = new BigDecimal("4294967295");
                break;
            case "uint64":
                minimum = BigDecimal.ZERO;
                maximum = BigDecimal.valueOf(Long.MAX_VALUE);
                break;
            default:
                return;
        }
        BigDecimal numeric = decimal(raw);
        if (numeric.compareTo(minimum) < 0 || numeric.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Value is outside the Sprut format range");
        }
    }

    private static boolean equivalent(Object left, Object right) {
        if (left == null || right == null || left == JSONObject.NULL || right == JSONObject.NULL) {
            return left == right;
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            try { return asBoolean(left) == asBoolean(right); }
            catch (IllegalArgumentException ignored) { return false; }
        }
        if (left instanceof Number && right instanceof Number) {
            try { return decimal(left).compareTo(decimal(right)) == 0; }
            catch (IllegalArgumentException ignored) { return false; }
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    @NonNull
    private static BigDecimal decimal(Object value) {
        if (value instanceof Float && !Float.isFinite((Float) value)
                || value instanceof Double && !Double.isFinite((Double) value)) {
            throw new IllegalArgumentException("Number is not finite");
        }
        try {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Value is not numeric: " + value, error);
        }
    }

    @NonNull
    private static SprutCatalog.ValueType resolvedType(
            SprutCatalog.Characteristic characteristic) {
        // The declared format describes what the characteristic accepts. Prefer it over the
        // wrapper of the last live value: some Sprut.hub firmwares expose a boolean format while
        // reporting the current value through a legacy intValue wrapper.
        String format = normalizedFormat(characteristic.format());
        switch (format) {
            case "bool":
            case "boolean":
                return SprutCatalog.ValueType.BOOLEAN;
            case "float":
            case "float32":
                return SprutCatalog.ValueType.FLOAT;
            case "double":
            case "float64":
            case "number":
                return SprutCatalog.ValueType.DOUBLE;
            case "int":
            case "integer":
            case "int8":
            case "int16":
            case "int32":
            case "uint8":
            case "uint16":
                return SprutCatalog.ValueType.INTEGER;
            case "long":
            case "int64":
            case "uint32":
            case "uint64":
                return SprutCatalog.ValueType.LONG;
            case "string":
            case "utf8":
                return SprutCatalog.ValueType.STRING;
            default:
                break;
        }
        if (characteristic.valueType() != SprutCatalog.ValueType.UNKNOWN) {
            return characteristic.valueType();
        }
        Object current = characteristic.currentValue();
        if (current instanceof Boolean) return SprutCatalog.ValueType.BOOLEAN;
        if (current instanceof Integer || current instanceof Short || current instanceof Byte) {
            return SprutCatalog.ValueType.INTEGER;
        }
        if (current instanceof Long) return SprutCatalog.ValueType.LONG;
        if (current instanceof Float) return SprutCatalog.ValueType.FLOAT;
        if (current instanceof Number) return SprutCatalog.ValueType.DOUBLE;
        if (current instanceof String) return SprutCatalog.ValueType.STRING;
        return SprutCatalog.ValueType.UNKNOWN;
    }

    private static boolean looksBooleanSemantic(SprutCatalog.Characteristic characteristic) {
        String key = (characteristic.type() + " " + characteristic.name())
                .trim().toLowerCase(Locale.ROOT).replaceAll("[^a-zа-я0-9]+", " ");
        return key.equals("on") || key.startsWith("on ")
                || key.equals("active") || key.startsWith("active ")
                || key.equals("enabled") || key.startsWith("enabled ")
                || key.contains(" detected") || key.startsWith("detected ")
                || key.endsWith(" detected");
    }

    private static String normalizedFormat(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
    }
}
