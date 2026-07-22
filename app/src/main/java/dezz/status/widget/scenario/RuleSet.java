/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Ordered first-match display rules with an optional cross-connector value source. */
public final class RuleSet {
    public static final int SCHEMA_VERSION = 1;
    /** Human-facing editors do not impose a small rule limit. This high safety ceiling only
     * protects imports from accidentally trying to allocate an unbounded JSON collection. */
    public static final int MAX_RULES = 4_096;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public final String id;
    /** Null means the owning widget/brick input supplied to {@link #evaluate(Input)}. */
    public final ValueReference sourceReference;
    public final List<Rule> rules;

    public RuleSet(String id, List<Rule> rules) {
        this(id, null, rules);
    }

    public RuleSet(String id, ValueReference sourceReference, List<Rule> rules) {
        this.id = safeId(id);
        this.sourceReference = sourceReference;
        List<Rule> source = rules == null ? Collections.emptyList() : rules;
        if (source.size() > MAX_RULES) throw new IllegalArgumentException("Too many display rules");
        ArrayList<Rule> copy = new ArrayList<>(source.size());
        for (Rule rule : source) copy.add(Objects.requireNonNull(rule, "rule"));
        this.rules = Collections.unmodifiableList(copy);
    }

    public boolean usesOwnSource() {
        return sourceReference == null;
    }

    /** Evaluates an own-source set. A referenced set needs the resolver overload. */
    public Result evaluate(Input ownInput) {
        return evaluate(ownInput, null);
    }

    /** Resolves an explicit source through any connector resolver; own-source sets use
     * {@code ownInput}. */
    public Result evaluate(Input ownInput, ValueResolver resolver) {
        Input input;
        if (sourceReference == null) {
            input = ownInput == null ? Input.unavailable() : ownInput;
        } else {
            input = resolver == null ? Input.unavailable() : resolver.resolve(sourceReference);
        }
        for (int index = 0; index < rules.size(); index++) {
            Rule rule = rules.get(index);
            if (rule.matches(input)) return Result.matched(index, rule, input);
        }
        return Result.noMatch(input);
    }

    public static RuleSet fromJson(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Display rule set is missing");
        int schemaVersion = object.optInt("schemaVersion", SCHEMA_VERSION);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported display rule schemaVersion: "
                    + schemaVersion);
        }
        JSONObject sourceObject = object.optJSONObject("source");
        if (sourceObject == null) sourceObject = object.optJSONObject("sourceReference");
        ValueReference source = sourceObject == null ? null : ValueReference.fromJson(sourceObject);
        JSONArray array = object.optJSONArray("rules");
        int count = array == null ? 0 : array.length();
        if (count > MAX_RULES) throw new IllegalArgumentException("Too many display rules");
        List<Rule> rules = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            JSONObject rule = array.optJSONObject(index);
            if (rule == null) throw new IllegalArgumentException("Invalid display rule");
            rules.add(Rule.fromJson(rule, index));
        }
        return new RuleSet(object.optString("id", "display"), source, rules);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schemaVersion", SCHEMA_VERSION);
        object.put("id", id);
        if (sourceReference != null) object.put("source", sourceReference.toJson());
        JSONArray array = new JSONArray();
        for (Rule rule : rules) array.put(rule.toJson());
        object.put("rules", array);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuleSet)) return false;
        RuleSet ruleSet = (RuleSet) other;
        return id.equals(ruleSet.id) && Objects.equals(sourceReference, ruleSet.sourceReference)
                && rules.equals(ruleSet.rules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sourceReference, rules);
    }

    private static String safeId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid display rule set id: " + raw);
        }
        return value;
    }
}
