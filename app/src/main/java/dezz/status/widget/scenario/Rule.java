/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One bounded, declarative condition and its optional presentation overrides. */
public final class Rule {
    private static final int MAX_FIELD_CHARS = 256;
    private static final int MAX_OPERAND_CHARS = 1_024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public final String id;
    public final String field;
    public final Operator operator;
    public final String operand;
    public final String secondOperand;
    public final Output output;

    public Rule(String id, String field, Operator operator, String operand,
                String secondOperand, Output output) {
        this.id = safeId(id);
        this.field = safeField(field);
        this.operator = Objects.requireNonNull(operator, "operator");
        this.operand = bounded(operand, "operand", MAX_OPERAND_CHARS, false);
        this.secondOperand = bounded(secondOperand, "secondOperand", MAX_OPERAND_CHARS, false);
        this.output = output == null ? Output.none() : output;
    }

    public boolean matches(Input input) {
        return matchesCondition(input, field, operator, operand, secondOperand);
    }

    /** Shared matcher used by display rules and cross-connector scenario conditions. */
    static boolean matchesCondition(Input input, String field, Operator operator, String operand,
                                    String secondOperand) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(operator, "operator");
        switch (operator) {
            case ALWAYS: return true;
            case FRESH: return input.fresh;
            case STALE: return !input.fresh;
            case AVAILABLE: return input.available;
            case UNAVAILABLE: return !input.available;
            default: break;
        }

        // A disconnected or expired source must not accidentally satisfy a normal comparison
        // (especially NOT_EQUALS/EMPTY). State operators above are the explicit escape hatch.
        if (!input.available || !input.fresh) return false;

        Object actual = input.resolve(field);
        switch (operator) {
            case EQUALS:
                return RuleValues.equal(actual, operand);
            case NOT_EQUALS:
                return !RuleValues.equal(actual, operand);
            case EQUALS_IGNORE_CASE:
                return !RuleValues.isNull(actual) && text(actual).equalsIgnoreCase(operand);
            case NOT_EQUALS_IGNORE_CASE:
                return RuleValues.isNull(actual) || !text(actual).equalsIgnoreCase(operand);
            case GREATER:
                return compare(actual, operand, comparison -> comparison > 0);
            case GREATER_OR_EQUAL:
                return compare(actual, operand, comparison -> comparison >= 0);
            case LESS:
                return compare(actual, operand, comparison -> comparison < 0);
            case LESS_OR_EQUAL:
                return compare(actual, operand, comparison -> comparison <= 0);
            case BETWEEN:
                BigDecimal value = RuleValues.number(actual);
                BigDecimal lower = RuleValues.number(operand);
                BigDecimal upper = RuleValues.number(secondOperand);
                return value != null && lower != null && upper != null
                        && value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
            case BETWEEN_EXCLUSIVE:
                BigDecimal exclusiveValue = RuleValues.number(actual);
                BigDecimal exclusiveLower = RuleValues.number(operand);
                BigDecimal exclusiveUpper = RuleValues.number(secondOperand);
                return exclusiveValue != null && exclusiveLower != null && exclusiveUpper != null
                        && exclusiveValue.compareTo(exclusiveLower) > 0
                        && exclusiveValue.compareTo(exclusiveUpper) < 0;
            case CONTAINS:
                return !RuleValues.isNull(actual)
                        && String.valueOf(actual).contains(operand);
            case CONTAINS_IGNORE_CASE:
                return !RuleValues.isNull(actual) && text(actual).toLowerCase(Locale.ROOT)
                        .contains(operand.toLowerCase(Locale.ROOT));
            case STARTS_WITH:
                return !RuleValues.isNull(actual) && text(actual).startsWith(operand);
            case ENDS_WITH:
                return !RuleValues.isNull(actual) && text(actual).endsWith(operand);
            case EMPTY:
                return RuleValues.isEmpty(actual);
            case NOT_EMPTY:
                return !RuleValues.isEmpty(actual);
            case TRUE:
                return Boolean.TRUE.equals(RuleValues.bool(actual));
            case FALSE:
                return Boolean.FALSE.equals(RuleValues.bool(actual));
            default:
                // Special operators returned above; keeping the default closed prevents a new
                // enum value from becoming an accidental match.
                return false;
        }
    }

    public static Rule fromJson(JSONObject object, int fallbackIndex) {
        if (object == null) throw new IllegalArgumentException("Scenario rule is missing");
        return new Rule(object.optString("id", "rule_" + Math.max(0, fallbackIndex)),
                object.optString("field", Input.FIELD_VALUE),
                Operator.fromJsonName(object.optString("operator", Operator.ALWAYS.jsonName())),
                object.optString("operand", ""), object.optString("secondOperand", ""),
                Output.fromJson(object.optJSONObject("output")));
    }

    public static Rule fromJson(JSONObject object) {
        return fromJson(object, 0);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("field", field);
        object.put("operator", operator.jsonName());
        object.put("operand", operand);
        object.put("secondOperand", secondOperand);
        object.put("output", output.toJson());
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Rule)) return false;
        Rule rule = (Rule) other;
        return id.equals(rule.id) && field.equals(rule.field) && operator == rule.operator
                && operand.equals(rule.operand) && secondOperand.equals(rule.secondOperand)
                && output.equals(rule.output);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, field, operator, operand, secondOperand, output);
    }

    private interface ComparisonPredicate { boolean test(int comparison); }

    private static boolean compare(Object actual, String expected,
                                   ComparisonPredicate predicate) {
        BigDecimal left = RuleValues.number(actual);
        BigDecimal right = RuleValues.number(expected);
        return left != null && right != null && predicate.test(left.compareTo(right));
    }

    private static String text(Object actual) {
        return RuleValues.isNull(actual) ? "" : String.valueOf(actual);
    }

    private static String safeId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid scenario rule id: " + raw);
        }
        return value;
    }

    private static String safeField(String raw) {
        String value = bounded(raw, "field", MAX_FIELD_CHARS, true);
        if (value.isEmpty()) return Input.FIELD_VALUE;
        if (Input.FIELD_VALUE.equals(value) || Input.FIELD_FRESH.equals(value)
                || Input.FIELD_AVAILABLE.equals(value) || Input.FIELD_READABLE.equals(value)
                || Input.FIELD_WRITABLE.equals(value) || Input.FIELD_TYPE.equals(value)
                || Input.FIELD_UNIT.equals(value) || Input.FIELD_ATTRIBUTES.equals(value)) {
            return value;
        }
        if (value.startsWith(Input.ATTRIBUTE_PREFIX)
                && value.length() > Input.ATTRIBUTE_PREFIX.length()) return value;
        throw new IllegalArgumentException("Unknown scenario field: " + raw);
    }

    private static String bounded(String raw, String name, int maxLength, boolean trim) {
        String value = raw == null ? "" : (trim ? raw.trim() : raw);
        if (value.length() > maxLength || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid scenario " + name);
        }
        return value;
    }
}
