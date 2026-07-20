/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** One provider-neutral condition reading a value through a connector resolver. */
public final class Condition {
    public final String id;
    public final ValueReference reference;
    public final String field;
    public final Operator operator;
    public final String operand;
    public final String secondOperand;

    public Condition(String id, ValueReference reference, String field, Operator operator,
                     String operand, String secondOperand) {
        // Reuse display-rule validation so both engine surfaces have identical limits/coercion.
        Rule validated = new Rule(id, field, operator, operand, secondOperand, Output.none());
        this.id = validated.id;
        this.reference = Objects.requireNonNull(reference, "reference");
        this.field = validated.field;
        this.operator = validated.operator;
        this.operand = validated.operand;
        this.secondOperand = validated.secondOperand;
    }

    public boolean matches(ValueResolverRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return matches(registry.resolve(reference));
    }

    public boolean matches(ValueResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        Input input;
        try {
            input = resolver.resolve(reference);
        } catch (RuntimeException ignored) {
            input = Input.unavailable();
        }
        return matches(input == null ? Input.unavailable() : input);
    }

    public boolean matches(Input input) {
        return Rule.matchesCondition(Objects.requireNonNull(input, "input"), field, operator,
                operand, secondOperand);
    }

    public static Condition fromJson(JSONObject object, int fallbackIndex) {
        if (object == null) throw new IllegalArgumentException("Scenario condition is missing");
        JSONObject reference = object.optJSONObject("reference");
        return new Condition(object.optString("id", "condition_" + Math.max(0, fallbackIndex)),
                ValueReference.fromJson(reference),
                object.optString("field", Input.FIELD_VALUE),
                Operator.fromJsonName(object.optString("operator", Operator.ALWAYS.jsonName())),
                object.optString("operand", ""), object.optString("secondOperand", ""));
    }

    public static Condition fromJson(JSONObject object) {
        return fromJson(object, 0);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("reference", reference.toJson());
        object.put("field", field);
        object.put("operator", operator.jsonName());
        object.put("operand", operand);
        object.put("secondOperand", secondOperand);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Condition)) return false;
        Condition condition = (Condition) other;
        return id.equals(condition.id) && reference.equals(condition.reference)
                && field.equals(condition.field) && operator == condition.operator
                && operand.equals(condition.operand)
                && secondOperand.equals(condition.secondOperand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, reference, field, operator, operand, secondOperand);
    }
}
