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
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_CONDITIONS = 128;
    public static final int MAX_ACTIONS = 128;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public final String id;
    public final boolean enabled;
    public final ConditionMode mode;
    public final List<Condition> conditions;
    /** Actions for the true branch. */
    public final List<LocalAction> actions;
    /** Optional actions for the false branch. Empty preserves connector/default presentation. */
    public final List<LocalAction> elseActions;
    /** Runtime-only compatibility marker for v1's implicit fail-closed true gates. */
    public final boolean legacyFailClosed;

    public Scenario(String id, boolean enabled, ConditionMode mode,
                    List<Condition> conditions, List<LocalAction> actions) {
        this(id, enabled, mode, conditions, actions, Collections.emptyList());
    }

    public Scenario(String id, boolean enabled, ConditionMode mode,
                    List<Condition> conditions, List<LocalAction> actions,
                    List<LocalAction> elseActions) {
        this(id, enabled, mode, conditions, actions, elseActions, false);
    }

    private Scenario(String id, boolean enabled, ConditionMode mode,
                     List<Condition> conditions, List<LocalAction> actions,
                     List<LocalAction> elseActions, boolean legacyFailClosed) {
        this.id = safeId(id);
        this.enabled = enabled;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.conditions = boundedCopy(conditions, MAX_CONDITIONS, "conditions");
        this.actions = boundedCopy(actions, MAX_ACTIONS, "actions");
        this.elseActions = boundedCopy(elseActions, MAX_ACTIONS, "elseActions");
        if (this.actions.size() + this.elseActions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("Too many scenario actions");
        }
        this.legacyFailClosed = legacyFailClosed;
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
            return new ScenarioResult(id, false, false, matchedIds, null,
                    Collections.emptyList());
        }

        if (mode == ConditionMode.ALL) {
            boolean unknown = false;
            for (Condition condition : conditions) {
                Truth truth = evaluate(condition, resolver);
                if (truth == Truth.TRUE) {
                    matchedIds.add(condition.id);
                } else if (truth == Truth.FALSE) {
                    // FALSE is decisive for ALL even when an earlier condition was UNKNOWN.
                    // Stopping here also keeps the documented ordered/short-circuit semantics.
                    return new ScenarioResult(id, true, false, matchedIds, condition.id,
                            elseActions);
                } else if (truth == Truth.UNKNOWN) {
                    unknown = true;
                }
            }
            if (unknown) {
                return new ScenarioResult(id, false, false, matchedIds, null,
                        Collections.emptyList());
            }
            return new ScenarioResult(id, true, true, matchedIds, null, actions);
        }

        boolean unknown = false;
        for (Condition condition : conditions) {
            Truth truth = evaluate(condition, resolver);
            if (truth == Truth.TRUE) {
                matchedIds.add(condition.id);
                return new ScenarioResult(id, true, true, matchedIds, null, actions);
            }
            if (truth == Truth.UNKNOWN) unknown = true;
        }
        if (unknown) {
            return new ScenarioResult(id, false, false, matchedIds, null,
                    Collections.emptyList());
        }
        return new ScenarioResult(id, true, false, matchedIds, null, elseActions);
    }

    public static Scenario fromJson(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Scenario is missing");
        // A schema-less document predates explicit false branches and therefore has v1's
        // fail-closed semantics. Treating it as v2 would make an existing popup appear when its
        // source is false/offline after an app update.
        int schemaVersion = object.optInt("schemaVersion", 1);
        if (schemaVersion < 1 || schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported scenario schemaVersion: " + schemaVersion);
        }
        JSONArray conditionArray = object.optJSONArray("conditions");
        JSONArray actionArray = object.optJSONArray("actions");
        // v1 never had an explicit false branch. Ignore a stray forward field rather than mixing
        // explicit branch actions with the legacy implicit fail-closed baseline.
        JSONArray elseActionArray = schemaVersion >= 2
                ? object.optJSONArray("elseActions") : null;
        int conditionCount = conditionArray == null ? 0 : conditionArray.length();
        int actionCount = actionArray == null ? 0 : actionArray.length();
        int elseActionCount = elseActionArray == null ? 0 : elseActionArray.length();
        if (conditionCount > MAX_CONDITIONS || actionCount > MAX_ACTIONS
                || elseActionCount > MAX_ACTIONS
                || actionCount + elseActionCount > MAX_ACTIONS) {
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
        List<LocalAction> elseActions = new ArrayList<>(elseActionCount);
        for (int index = 0; index < elseActionCount; index++) {
            JSONObject action = elseActionArray.optJSONObject(index);
            if (action == null) throw new IllegalArgumentException("Invalid scenario else action");
            elseActions.add(LocalAction.fromJson(action));
        }
        return new Scenario(object.optString("id", "scenario"),
                object.optBoolean("enabled", true),
                ConditionMode.fromJsonName(object.optString("mode", ConditionMode.ALL.jsonName())),
                conditions, actions, elseActions, schemaVersion == 1);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        // Preserve untouched v1 semantics on a parse/serialize round trip. The visual editor
        // creates a new v2 Scenario when the user explicitly chooses both branches.
        object.put("schemaVersion", legacyFailClosed ? 1 : SCHEMA_VERSION);
        object.put("id", id);
        object.put("enabled", enabled);
        object.put("mode", mode.jsonName());
        JSONArray conditionArray = new JSONArray();
        for (Condition condition : conditions) conditionArray.put(condition.toJson());
        object.put("conditions", conditionArray);
        JSONArray actionArray = new JSONArray();
        for (LocalAction action : actions) actionArray.put(action.toJson());
        object.put("actions", actionArray);
        if (!legacyFailClosed) {
            JSONArray elseActionArray = new JSONArray();
            for (LocalAction action : elseActions) elseActionArray.put(action.toJson());
            object.put("elseActions", elseActionArray);
        }
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Scenario)) return false;
        Scenario scenario = (Scenario) other;
        return enabled == scenario.enabled && legacyFailClosed == scenario.legacyFailClosed
                && id.equals(scenario.id) && mode == scenario.mode
                && conditions.equals(scenario.conditions) && actions.equals(scenario.actions)
                && elseActions.equals(scenario.elseActions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, enabled, mode, conditions, actions, elseActions,
                legacyFailClosed);
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

    private static Truth evaluate(Condition condition, ValueResolver resolver) {
        Input input = condition.resolve(resolver);
        if (!condition.explicitlyHandlesAvailability() && (!input.available || !input.fresh)) {
            return Truth.UNKNOWN;
        }
        return condition.matches(input) ? Truth.TRUE : Truth.FALSE;
    }

    private enum Truth { TRUE, FALSE, UNKNOWN }
}
