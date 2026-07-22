/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Preference-backed codec for one optional Sprut.hub target per stable vehicle metric. */
public final class CarSprutBindingStore {
    public static final String PREFERENCE_KEY = "carSprutBindingsJson";

    private final Preferences.Str storage;

    /** Uses the registered string preference so mappings participate in settings export/import. */
    public CarSprutBindingStore(@NonNull Preferences preferences) {
        storage = Objects.requireNonNull(preferences, "preferences").carSprutBindingsJson;
    }

    @NonNull public List<CarSprutBinding> load() {
        return decode(storage.get());
    }

    public void save(@NonNull List<CarSprutBinding> bindings) throws JSONException {
        storage.set(encode(bindings));
    }

    /**
     * Lenient read: malformed entries are isolated. The first binding for either a metric or a
     * target wins, so an imported/hand-edited JSON file cannot present two active writers for one
     * Sprut characteristic.
     */
    @NonNull
    public static List<CarSprutBinding> decode(String json) {
        List<CarSprutBinding> result = new ArrayList<>();
        Set<String> metricIds = new HashSet<>();
        Set<String> targetIds = new HashSet<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) continue;
                try {
                    CarSprutBinding binding = CarSprutBinding.fromJson(object);
                    String targetId = binding.targetPath.stableId();
                    if (metricIds.contains(binding.metricId)
                            || (binding.enabled && targetIds.contains(targetId))) {
                        continue;
                    }
                    metricIds.add(binding.metricId);
                    if (binding.enabled) targetIds.add(targetId);
                    result.add(binding);
                } catch (IllegalArgumentException ignored) {
                    // A damaged imported mapping must not disable unrelated vehicle metrics.
                }
            }
        } catch (JSONException ignored) {
            // Invalid root is equivalent to no bindings; the next save repairs the preference.
        }
        return result;
    }

    /** Strict write: metric ids and targets of enabled writers may not be duplicated. */
    @NonNull
    public static String encode(@NonNull List<CarSprutBinding> bindings) throws JSONException {
        Objects.requireNonNull(bindings, "bindings");
        JSONArray array = new JSONArray();
        Set<String> metricIds = new HashSet<>();
        Set<String> targetIds = new HashSet<>();
        for (CarSprutBinding binding : bindings) {
            if (binding == null) continue;
            if (!metricIds.add(binding.metricId)) {
                throw new IllegalArgumentException("Duplicate vehicle metric: " + binding.metricId);
            }
            if (binding.enabled) {
                String targetId = binding.targetPath.stableId();
                if (!targetIds.add(targetId)) {
                    throw new IllegalArgumentException("Duplicate Sprut target: " + targetId);
                }
            }
            array.put(binding.toJson());
        }
        return array.toString();
    }
}
