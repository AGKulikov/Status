/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONObject;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** Deterministic coercion shared by all rules. No locale, script, or regex evaluation. */
final class RuleValues {
    private RuleValues() {}

    static boolean isNull(Object value) {
        return value == null || value == JSONObject.NULL;
    }

    static boolean isEmpty(Object value) {
        if (isNull(value)) return true;
        if (value instanceof CharSequence) return value.toString().trim().isEmpty();
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        return value.getClass().isArray() && Array.getLength(value) == 0;
    }

    static String display(Object value) {
        return isNull(value) ? "" : String.valueOf(value);
    }

    static BigDecimal number(Object value) {
        if (isNull(value) || value instanceof Boolean) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof BigInteger) return new BigDecimal((BigInteger) value);
        if (value instanceof Number || value instanceof CharSequence) {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return null;
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Boolean bool(Object value) {
        if (isNull(value)) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            BigDecimal number = number(value);
            return number == null ? null : number.compareTo(BigDecimal.ZERO) != 0;
        }
        if (!(value instanceof CharSequence)) return null;
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        switch (text) {
            case "true":
            case "on":
            case "yes":
            case "1":
            case "open":
            case "opened":
            case "available":
                return true;
            case "false":
            case "off":
            case "no":
            case "0":
            case "closed":
            case "unavailable":
                return false;
            default:
                BigDecimal numeric = number(text);
                return numeric == null ? null : numeric.compareTo(BigDecimal.ZERO) != 0;
        }
    }

    static boolean equal(Object actual, Object expected) {
        if (isNull(actual) || isNull(expected)) {
            return isNull(actual) && isNull(expected);
        }
        if (actual instanceof Boolean || expected instanceof Boolean) {
            Boolean left = bool(actual);
            Boolean right = bool(expected);
            if (left != null && right != null) return left.equals(right);
        }
        BigDecimal leftNumber = number(actual);
        BigDecimal rightNumber = number(expected);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber) == 0;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }
}
