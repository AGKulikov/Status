/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Strict preference-backed JSON codec for one-shot Intent-to-command rules. */
public final class IntentActionRuleStore {
    public static final String PREFERENCE_KEY = "intentActionRulesJson";
    public static final int MAX_RULES = 128;
    public static final int MAX_JSON_CHARS = 1_048_576;

    private final Preferences.Str storage;

    /** Uses the registered preference so rules automatically join settings export/import. */
    public IntentActionRuleStore(@NonNull Preferences preferences) {
        this(Objects.requireNonNull(preferences, "preferences").intentActionRulesJson);
    }

    /**
     * Accepts the registered preference field without coupling this isolated model change to a
     * particular {@link Preferences} revision. Runtime integration should pass
     * {@code preferences.intentActionRulesJson}.
     */
    public IntentActionRuleStore(@NonNull Preferences.Str storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Strict load: malformed or ambiguous command configuration is never partially executed. */
    @NonNull
    public List<IntentActionRule> loadStrict() {
        return decode(storage.get());
    }

    public void save(@NonNull List<IntentActionRule> rules) {
        storage.set(encode(rules));
    }

    /** Decodes, validates bounds, and rejects duplicate ids or enabled actions. */
    @NonNull
    public static List<IntentActionRule> decode(String raw) {
        String json = raw == null ? "[]" : raw.trim();
        if (json.isEmpty()) json = "[]";
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Intent action configuration is too large");
        }
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() > MAX_RULES) {
                throw new IllegalArgumentException("Too many intent action rules");
            }
            ArrayList<IntentActionRule> result = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    throw new IllegalArgumentException("Intent action rule " + (index + 1)
                            + " must be an object");
                }
                result.add(IntentActionRule.fromJson(object));
            }
            validateUnique(result);
            return Collections.unmodifiableList(result);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid intent action configuration", error);
        }
    }

    /** Canonical deterministic JSON used by settings export/import. */
    @NonNull
    public static String encode(@NonNull List<IntentActionRule> rules) {
        Objects.requireNonNull(rules, "rules");
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("Too many intent action rules");
        }
        validateUnique(rules);
        JSONArray array = new JSONArray();
        try {
            for (IntentActionRule rule : rules) {
                array.put(Objects.requireNonNull(rule, "rule").toJson());
            }
        } catch (JSONException impossible) {
            throw new IllegalArgumentException("Could not encode intent action rules", impossible);
        }
        return array.toString();
    }

    /** Exact, case-sensitive lookup used by a dynamically registered BroadcastReceiver. */
    @NonNull
    public static Map<String, IntentActionRule> enabledByAction(
            @NonNull Collection<IntentActionRule> rules) {
        Objects.requireNonNull(rules, "rules");
        validateUnique(rules);
        LinkedHashMap<String, IntentActionRule> result = new LinkedHashMap<>();
        for (IntentActionRule rule : rules) {
            if (rule.enabled) result.put(rule.intentAction, rule);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateUnique(Collection<IntentActionRule> rules) {
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("Too many intent action rules");
        }
        Set<String> ids = new HashSet<>();
        Set<String> enabledActions = new HashSet<>();
        for (IntentActionRule rule : rules) {
            IntentActionRule checked = Objects.requireNonNull(rule, "rule");
            if (!ids.add(checked.id)) {
                throw new IllegalArgumentException("Duplicate intent action rule id: "
                        + checked.id);
            }
            if (checked.enabled && !enabledActions.add(checked.intentAction)) {
                // The random suffix is a bearer secret and must not enter service logs through
                // a configuration exception.
                throw new IllegalArgumentException("Duplicate enabled Intent action");
            }
        }
    }
}
