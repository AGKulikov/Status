/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import dezz.status.widget.sprut.SprutCatalog;

/** Strict, side-effect-free conversion from numeric vehicle data to a Sprut typed value. */
public final class SprutCharacteristicConverter {
    private SprutCharacteristicConverter() {}

    /**
     * Converts one sample according to its binding and the current authoritative characteristic
     * metadata. No unit conversion, truncation, or clamping is implicit. Boolean destinations use
     * the explicit numeric convention {@code 0 = false}, {@code non-zero = true}.
     */
    @NonNull
    public static Object convert(@NonNull CarTelemetryValue sample,
                                 @NonNull CarSprutBinding binding,
                                 @NonNull SprutCatalog.Characteristic target) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(target, "target");
        if (!binding.enabled) throw new IllegalArgumentException("Binding is disabled");
        if (!sample.metricId.equals(binding.metricId)) {
            throw new IllegalArgumentException("Telemetry metric does not match binding");
        }
        if (!binding.targetPath.equals(target.path())) {
            throw new IllegalArgumentException("Sprut characteristic path does not match binding");
        }
        if (!binding.targetSnapshotSignature.isEmpty()
                && !binding.targetSnapshotSignature.equals(snapshotSignature(target))) {
            throw new IllegalArgumentException("Sprut characteristic metadata changed");
        }

        BigDecimal transformed = decimal(sample.value)
                .multiply(decimal(binding.scale))
                .add(decimal(binding.offset));
        SprutCatalog.ValueType type = validateNumericTarget(target);
        final Object result;
        final BigDecimal numericResult;
        switch (type) {
            case BOOLEAN: {
                boolean logical = transformed.compareTo(BigDecimal.ZERO) != 0;
                // Keep the Java result typed as Boolean so the protocol boundary emits
                // boolValue, not intValue. The parallel 0/1 representation is used only for
                // numeric metadata constraints and validValues validation.
                result = logical;
                numericResult = logical ? BigDecimal.ONE : BigDecimal.ZERO;
                break;
            }
            case INTEGER: {
                BigDecimal integer = integer(transformed, binding.integerPolicy);
                validateIntrinsicIntegerRange(integer, target.format());
                try {
                    result = integer.intValueExact();
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("Value is outside the integer range",
                            overflow);
                }
                numericResult = integer;
                break;
            }
            case LONG: {
                BigDecimal integer = integer(transformed, binding.integerPolicy);
                validateIntrinsicIntegerRange(integer, target.format());
                try {
                    result = integer.longValueExact();
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("Value is outside the long range", overflow);
                }
                numericResult = integer;
                break;
            }
            case FLOAT: {
                float value = transformed.floatValue();
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Value is outside the float range");
                }
                result = value;
                numericResult = transformed;
                break;
            }
            case DOUBLE: {
                double value = transformed.doubleValue();
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Value is outside the double range");
                }
                result = value;
                numericResult = transformed;
                break;
            }
            case STRING:
                result = plain(transformed);
                numericResult = null;
                break;
            case UNKNOWN:
            default:
                throw new IllegalArgumentException("Unknown Sprut characteristic value type");
        }

        if (numericResult != null) {
            validateNumericConstraints(numericResult, target);
        }
        validateValidValues(result, numericResult, target.validValues());
        return result;
    }

    /**
     * Validates that a characteristic is a safe destination for numeric car telemetry and
     * returns the value type which must be written. Numeric-to-string and numeric-to-boolean
     * conversion are explicit and supported; characteristics whose type cannot be resolved are
     * not. Boolean conversion uses {@code 0 = false}, {@code non-zero = true}.
     *
     * <p>The decision is independent from the presence of a current car sample, so callers can
     * validate and persist a binding before the vehicle has produced its first value.</p>
     */
    @NonNull
    public static SprutCatalog.ValueType validateNumericTarget(
            @NonNull SprutCatalog.Characteristic target) {
        Objects.requireNonNull(target, "target");
        if (!target.writable()) {
            throw new IllegalArgumentException("Sprut characteristic is not writable");
        }
        SprutCatalog.ValueType type = resolvedType(target);
        switch (type) {
            case BOOLEAN:
            case INTEGER:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case STRING:
                return type;
            case UNKNOWN:
            default:
                throw new IllegalArgumentException(
                        "Unknown or unsupported Sprut characteristic value type");
        }
    }

    /**
     * Fingerprints only addressing/type/constraint metadata. Device, service and characteristic
     * display names are stored separately and may be renamed without invalidating a binding.
     */
    @NonNull
    public static String snapshotSignature(@NonNull SprutCatalog.Characteristic target) {
        Objects.requireNonNull(target, "target");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, target.path().stableId());
            update(digest, target.serviceType());
            update(digest, target.type());
            update(digest, target.format());
            update(digest, target.unit());
            // valueType itself is mutable: a characteristic selected while its current value is
            // null may move from UNKNOWN to a concrete wrapper after the first EVENT_UPDATE.
            // Prefer the stable format-derived result so that normal state arrival does not
            // invalidate an otherwise unchanged target.
            update(digest, resolvedType(target).name());
            update(digest, target.writable() ? "write" : "read-only");
            update(digest, canonical(target.minValue()));
            update(digest, canonical(target.maxValue()));
            update(digest, canonical(target.minStep()));
            for (SprutCatalog.ValidValue valid : target.validValues()) {
                update(digest, canonical(valid.value()));
                update(digest, valid.key());
            }
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @NonNull
    private static SprutCatalog.ValueType resolvedType(SprutCatalog.Characteristic target) {
        String format = normalizedFormat(target.format());
        switch (format) {
            case "bool":
            case "boolean":
                return SprutCatalog.ValueType.BOOLEAN;
            case "float":
            case "float32":
                return SprutCatalog.ValueType.FLOAT;
            case "double":
            case "float64":
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
                SprutCatalog.ValueType runtimeType = target.valueType();
                return runtimeType == null
                        ? SprutCatalog.ValueType.UNKNOWN : runtimeType;
        }
    }

    @NonNull
    private static BigDecimal integer(BigDecimal value, CarSprutBinding.IntegerPolicy policy) {
        switch (policy) {
            case EXACT:
                try {
                    return value.setScale(0, RoundingMode.UNNECESSARY);
                } catch (ArithmeticException error) {
                    throw new IllegalArgumentException(
                            "Fractional value requires an explicit rounding policy", error);
                }
            case ROUND_HALF_UP:
                return value.setScale(0, RoundingMode.HALF_UP);
            default:
                throw new IllegalArgumentException("Unsupported integer policy: " + policy);
        }
    }

    private static void validateNumericConstraints(BigDecimal value,
                                                   SprutCatalog.Characteristic target) {
        BigDecimal minimum = nullableDecimal(target.minValue(), "minValue");
        BigDecimal maximum = nullableDecimal(target.maxValue(), "maxValue");
        if (minimum != null && value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("Value is below the Sprut minimum");
        }
        if (maximum != null && value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Value is above the Sprut maximum");
        }
        BigDecimal step = nullableDecimal(target.minStep(), "minStep");
        if (step != null) {
            step = step.abs();
            if (step.signum() == 0) {
                throw new IllegalArgumentException("Sprut minStep must be greater than zero");
            }
            BigDecimal origin = minimum == null ? BigDecimal.ZERO : minimum;
            if (value.subtract(origin).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("Value does not align to Sprut minStep");
            }
        }
    }

    private static void validateIntrinsicIntegerRange(BigDecimal value, String rawFormat) {
        String format = normalizedFormat(rawFormat);
        BigDecimal minimum = null;
        BigDecimal maximum = null;
        switch (format) {
            case "uint8":
                minimum = BigDecimal.ZERO; maximum = BigDecimal.valueOf(255L); break;
            case "uint16":
                minimum = BigDecimal.ZERO; maximum = BigDecimal.valueOf(65_535L); break;
            case "uint32":
                minimum = BigDecimal.ZERO; maximum = new BigDecimal("4294967295"); break;
            case "uint64":
                // Sprut's longValue wrapper and Java's result type are signed 64-bit.
                minimum = BigDecimal.ZERO; maximum = BigDecimal.valueOf(Long.MAX_VALUE); break;
            case "int8":
                minimum = BigDecimal.valueOf(Byte.MIN_VALUE);
                maximum = BigDecimal.valueOf(Byte.MAX_VALUE); break;
            case "int16":
                minimum = BigDecimal.valueOf(Short.MIN_VALUE);
                maximum = BigDecimal.valueOf(Short.MAX_VALUE); break;
            case "int32":
                minimum = BigDecimal.valueOf(Integer.MIN_VALUE);
                maximum = BigDecimal.valueOf(Integer.MAX_VALUE); break;
            case "int64":
            case "long":
                minimum = BigDecimal.valueOf(Long.MIN_VALUE);
                maximum = BigDecimal.valueOf(Long.MAX_VALUE); break;
            default:
                return;
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Value is outside the Sprut format range");
        }
    }

    private static void validateValidValues(Object result, BigDecimal numericResult,
                                            List<SprutCatalog.ValidValue> validValues) {
        if (validValues.isEmpty()) return;
        for (SprutCatalog.ValidValue candidate : validValues) {
            Object allowed = candidate.value();
            if (numericResult != null && allowed instanceof Number) {
                BigDecimal numericAllowed = nullableDecimal((Number) allowed, "validValue");
                if (numericAllowed != null && numericResult.compareTo(numericAllowed) == 0) return;
            } else if (Objects.equals(result, allowed)
                    || (result instanceof String && allowed != null
                    && result.equals(String.valueOf(allowed)))) {
                return;
            }
        }
        throw new IllegalArgumentException("Value is not in Sprut validValues");
    }

    @NonNull private static BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Value must be finite");
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal nullableDecimal(Number value, String field) {
        if (value == null) return null;
        double decimal = value.doubleValue();
        if (!Double.isFinite(decimal)) {
            throw new IllegalArgumentException("Sprut " + field + " must be finite");
        }
        try {
            // Number.toString() preserves integral Long metadata above double's 53-bit exact
            // range while still producing the shortest stable decimal for Float/Double.
            return new BigDecimal(value.toString());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid Sprut " + field, malformed);
        }
    }

    @NonNull private static String plain(BigDecimal value) {
        String result = value.stripTrailingZeros().toPlainString();
        return "-0".equals(result) ? "0" : result;
    }

    @NonNull private static String normalizedFormat(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    @NonNull private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) return "non-finite";
            try {
                return "number:" + plain(new BigDecimal(value.toString()));
            } catch (NumberFormatException malformed) {
                return "invalid-number:" + value;
            }
        }
        if (value instanceof Boolean) return "boolean:" + value;
        return "string:" + value;
    }
}
