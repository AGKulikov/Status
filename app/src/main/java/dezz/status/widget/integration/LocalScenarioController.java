/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.scenario.LocalAction;
import dezz.status.widget.scenario.LocalField;
import dezz.status.widget.scenario.Scenario;
import dezz.status.widget.scenario.ScenarioResult;
import dezz.status.widget.scenario.TargetScope;
import dezz.status.widget.scenario.ValueResolver;

/**
 * Evaluates cross-connector conditions and applies only local UI overrides.
 *
 * <p>Example: a Home Assistant entity may be the condition source while the target is a popup
 * tile whose displayed value/action belongs to Sprut.hub. No connector command is executed by
 * this controller.</p>
 */
public final class LocalScenarioController implements ConnectorValueRegistry.Listener {
    private static final String TAG = "LocalScenario";
    private static final int MAX_SCENARIOS = 128;
    private static final int MAX_JSON_CHARS = 1_048_576;

    public interface Listener {
        void onScenarioTargetsChanged(@NonNull Set<String> canonicalTargets);
    }

    private final Preferences prefs;
    private final AutomationStateStore stateStore;
    private final ConnectorValueRegistry valueRegistry;
    private final Listener listener;
    private List<Scenario> scenarios = Collections.emptyList();
    private Map<String, JSONObject> previousOverrides = Collections.emptyMap();
    private String loadedJson = "";

    public LocalScenarioController(@NonNull Preferences prefs,
                                   @NonNull AutomationStateStore stateStore,
                                   @NonNull ConnectorValueRegistry valueRegistry,
                                   @NonNull Listener listener) {
        this.prefs = prefs;
        this.stateStore = stateStore;
        this.valueRegistry = valueRegistry;
        this.listener = listener;
        valueRegistry.addListener(this);
    }

    /** Reloads edited JSON when necessary and always re-evaluates against current snapshots. */
    public synchronized void reconfigure() {
        String json = prefs.localScenariosJson.get();
        if (!json.equals(loadedJson)) {
            scenarios = parse(json);
            loadedJson = json;
        }
        evaluateLocked();
    }

    @Override
    public void onValuesChanged(@NonNull Collection<ConnectorValue> changedValues) {
        synchronized (this) {
            evaluateLocked();
        }
    }

    public synchronized List<Scenario> scenarios() {
        return Collections.unmodifiableList(new ArrayList<>(scenarios));
    }

    public synchronized void destroy() {
        valueRegistry.removeListener(this);
        stateStore.clearScenarioOverrides();
        Set<String> changed = Collections.unmodifiableSet(
                new LinkedHashSet<>(previousOverrides.keySet()));
        previousOverrides = Collections.emptyMap();
        if (!changed.isEmpty()) listener.onScenarioTargetsChanged(changed);
    }

    private void evaluateLocked() {
        LinkedHashMap<String, JSONObject> overrides = buildOverrides(scenarios, valueRegistry);

        LinkedHashSet<String> changedTargets = changedTargets(previousOverrides, overrides);
        if (changedTargets.isEmpty()) return;

        stateStore.replaceScenarioOverrides(overrides);
        previousOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        listener.onScenarioTargetsChanged(Collections.unmodifiableSet(changedTargets));
    }

    /** Pure projection used by the controller and local JVM regression tests. */
    static LinkedHashMap<String, JSONObject> buildOverrides(List<Scenario> source,
                                                             ValueResolver resolver) {
        LinkedHashMap<String, JSONObject> overrides = new LinkedHashMap<>();

        // Positive boolean actions are fail-closed gates: visibility/interaction stays false
        // until a fresh condition explicitly enables it. A false action is instead a conditional
        // blocker and must not become an unconditional false override when its condition misses.
        // We never synthesize true, so a disconnected source cannot re-enable an action.
        for (Scenario scenario : source) {
            if (!scenario.enabled || scenario.conditions.isEmpty()) continue;
            for (LocalAction action : scenario.actions) {
                if (action.field != LocalField.VISIBLE
                        && action.field != LocalField.ACTION_ENABLED) continue;
                if (Boolean.TRUE.equals(action.booleanValue())) put(overrides, action, false);
            }
        }

        // Declaration order is deterministic. If several matching scenarios write the same
        // field, the later scenario wins, which also makes priority editable by reordering.
        for (Scenario scenario : source) {
            // Resolve directly against the neutral registry. The condition connector/profile is
            // independent from the local target, e.g. HA/default -> popup/sprut_gate.
            ScenarioResult result = scenario.evaluate(resolver);
            if (!result.matched) continue;
            for (LocalAction action : result.actions) put(overrides, action, action.value);
        }
        return overrides;
    }

    static LinkedHashSet<String> changedTargets(Map<String, JSONObject> previous,
                                                Map<String, JSONObject> current) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>(previous.keySet());
        candidates.addAll(current.keySet());
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        for (String target : candidates) {
            if (!samePatch(previous.get(target), current.get(target))) changed.add(target);
        }
        return changed;
    }

    private static boolean samePatch(JSONObject left, JSONObject right) {
        if (left == right) return true;
        if (left == null || right == null || left.length() != right.length()) return false;
        Iterator<String> keys = left.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!right.has(key) || !Objects.equals(left.opt(key), right.opt(key))) return false;
        }
        return true;
    }

    private static void put(Map<String, JSONObject> target, LocalAction action, Object value) {
        String key = targetKey(action.targetScope, action.targetId);
        JSONObject patch = target.computeIfAbsent(key, ignored -> new JSONObject());
        try {
            patch.put(fieldName(action.field), value);
        } catch (JSONException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static String targetKey(TargetScope scope, String id) {
        String stateScope;
        switch (scope) {
            case MAIN: stateScope = AutomationContract.SCOPE_MAIN; break;
            case POPUP: stateScope = AutomationContract.SCOPE_POPUP; break;
            case BUILTIN: stateScope = AutomationContract.SCOPE_BUILTIN; break;
            case OVERLAY: stateScope = AutomationContract.SCOPE_OVERLAY; break;
            default: throw new IllegalArgumentException("Unsupported target scope");
        }
        return stateScope + "|" + AutomationContract.requireSafeId(id);
    }

    private static String fieldName(LocalField field) {
        switch (field) {
            case VISIBLE: return "visible";
            case TEXT_COLOR: return "color";
            case ICON: return "icon";
            case BACKGROUND_COLOR: return "background_color";
            case ACTION_ENABLED: return "action_enabled";
            default: throw new IllegalArgumentException("Unsupported local field");
        }
    }

    @NonNull
    static List<Scenario> parse(String raw) {
        String json = raw == null ? "[]" : raw.trim();
        if (json.isEmpty()) json = "[]";
        if (json.length() > MAX_JSON_CHARS) {
            Log.w(TAG, "Ignored oversized local scenario configuration");
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() > MAX_SCENARIOS) {
                throw new IllegalArgumentException("Too many local scenarios");
            }
            ArrayList<Scenario> result = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                try {
                    result.add(Scenario.fromJson(item));
                } catch (IllegalArgumentException ignored) {
                    // Config imports are intentionally tolerant per entry. One forward-version or
                    // malformed scenario must not disable unrelated, otherwise-valid automations.
                }
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException | IllegalArgumentException error) {
            Log.w(TAG, "Ignored invalid local scenario configuration", error);
            return Collections.emptyList();
        }
    }
}
