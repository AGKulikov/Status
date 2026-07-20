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

/** Ordered, bounded cross-connector conditions producing local-only UI actions. */
public final class Scenario {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_CONDITIONS = 128;
    public static final int MAX_ACTIONS = 128;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public final String id;
    public final boolean enabled;
    public final ConditionMode mode;
    public final List<Condition> conditions;
    public final List<LocalAction> actions;

    public Scenario(String id, boolean enabled, ConditionMode mode,
                    List<Condition> conditions, List<LocalAction> actions) {
        this.id = safeId(id);
        this.enabled = enabled;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.conditions = boundedCopy(conditions, MAX_CONDITIONS, "conditions");
        this.actions = boundedCopy(actions, MAX_ACTIONS, "actions");
    }

    public Scenario(String id, List<Condition> conditions, List<LocalAction> actions) {
        this(id, true, ConditionMode.ALL, conditions, actions);
    }

    /** Evaluates in declaration order and short-circuits according to {@link #mode}. */
    public ScenarioResult evaluate(ValueResolverRegistry registry) {
        return evaluate((ValueResolver) Objects.requireNonNull(registry, "registry"));
    }

    /** Evaluates using a composite resolver or a {@link ValueResolverRegistry}. */
    public ScenarioResult evaluate(ValueResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        List<String> matchedIds = new ArrayList<>();
        if (!enabled || conditions.isEmpty()) {
            return new ScenarioResult(id, false, matchedIds, null, Collections.emptyList());
        }

        if (mode == ConditionMode.ALL) {
            for (Condition condition : conditions) {
                if (!condition.matches(resolver)) {
                    return new ScenarioResult(id, false, matchedIds, condition.id,
                            Collections.emptyList());
                }
                matchedIds.add(condition.id);
            }
            return new ScenarioResult(id, true, matchedIds, null, actions);
        }

        for (Condition condition : conditions) {
            if (condition.matches(resolver)) {
                matchedIds.add(condition.id);
                return new ScenarioResult(id, true, matchedIds, null, actions);
            }
        }
        return new ScenarioResult(id, false, matchedIds, null, Collections.emptyList());
    }

    public static Scenario fromJson(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Scenario is missing");
        int schemaVersion = object.optInt("schemaVersion", SCHEMA_VERSION);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported scenario schemaVersion: " + schemaVersion);
        }
        JSONArray conditionArray = object.optJSONArray("conditions");
        JSONArray actionArray = object.optJSONArray("actions");
        int conditionCount = conditionArray == null ? 0 : conditionArray.length();
        int actionCount = actionArray == null ? 0 : actionArray.length();
        if (conditionCount > MAX_CONDITIONS || actionCount > MAX_ACTIONS) {
            throw new IllegalArgumentException("Scenario exceeds safe list limits");
        }

        List<Condition> conditions = new ArrayList<>(conditionCount);
        for (int index = 0; index < conditionCount; index++) {
            JSONObject condition = conditionArray.optJSONObject(index);
            if (condition == null) throw new IllegalArgumentException("Invalid scenario condition");
            conditions.add(Condition.fromJson(condition, index));
        }
        List<LocalAction> actions = new ArrayList<>(actionCount);
        for (int index = 0; index < actionCount; index++) {
            JSONObject action = actionArray.optJSONObject(index);
            if (action == null) throw new IllegalArgumentException("Invalid scenario local action");
            actions.add(LocalAction.fromJson(action));
        }
        return new Scenario(object.optString("id", "scenario"),
                object.optBoolean("enabled", true),
                ConditionMode.fromJsonName(object.optString("mode", ConditionMode.ALL.jsonName())),
                conditions, actions);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schemaVersion", SCHEMA_VERSION);
        object.put("id", id);
        object.put("enabled", enabled);
        object.put("mode", mode.jsonName());
        JSONArray conditionArray = new JSONArray();
        for (Condition condition : conditions) conditionArray.put(condition.toJson());
        object.put("conditions", conditionArray);
        JSONArray actionArray = new JSONArray();
        for (LocalAction action : actions) actionArray.put(action.toJson());
        object.put("actions", actionArray);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Scenario)) return false;
        Scenario scenario = (Scenario) other;
        return enabled == scenario.enabled && id.equals(scenario.id) && mode == scenario.mode
                && conditions.equals(scenario.conditions) && actions.equals(scenario.actions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, enabled, mode, conditions, actions);
    }

    private static String safeId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid scenario id: " + raw);
        }
        return value;
    }

    private static <T> List<T> boundedCopy(List<T> source, int maxSize, String field) {
        List<T> values = source == null ? Collections.emptyList() : source;
        if (values.size() > maxSize) throw new IllegalArgumentException("Too many scenario " + field);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, field + " item"));
        return Collections.unmodifiableList(copy);
    }
}
