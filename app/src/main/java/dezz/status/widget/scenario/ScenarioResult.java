/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable outcome of evaluating one local cross-connector scenario. */
public final class ScenarioResult {
    public final String scenarioId;
    /** False means the scenario is disabled/empty or an ordinary condition is stale/unavailable;
     * neither branch runs. */
    public final boolean determinate;
    public final boolean matched;
    /** Conditions that evaluated true before ordered evaluation stopped. */
    public final List<String> matchedConditionIds;
    /** First failing condition in ALL mode; null for success, disabled, empty, and ANY mode. */
    public final String failedConditionId;
    /** Selected local actions: true-branch actions when matched, false-branch actions when a
     * determinate condition does not match, or empty when the outcome is indeterminate. */
    public final List<LocalAction> actions;

    ScenarioResult(String scenarioId, boolean determinate, boolean matched,
                   List<String> matchedConditionIds,
                   String failedConditionId, List<LocalAction> actions) {
        this.scenarioId = scenarioId;
        this.determinate = determinate;
        this.matched = matched;
        this.matchedConditionIds = immutableCopy(matchedConditionIds);
        this.failedConditionId = failedConditionId;
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
    }

    private static List<String> immutableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
