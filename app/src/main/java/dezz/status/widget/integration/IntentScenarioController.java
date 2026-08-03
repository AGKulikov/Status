/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dezz.status.widget.Preferences;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;

/**
 * Runtime for exact, one-shot Android Intent rules.
 *
 * <p>The external Intent selects only a rule which was already saved by the user. Connector,
 * resource, operation and payload always come from that immutable saved rule; incoming extras
 * can never turn this exported entry point into an arbitrary device-control endpoint.</p>
 */
public final class IntentScenarioController {
    private static final String TAG = "IntentScenario";
    static final long DEBOUNCE_MS = 750L;
    // A short boot/reconnect grace makes a steering press useful while the fresh snapshot is
    // arriving, without allowing a physical command to fire unexpectedly a minute later.
    static final long READY_TIMEOUT_MS = 15_000L;
    private static final long[] READY_RETRY_MS = {250L, 500L, 1_000L, 2_000L, 5_000L};

    private final Context context;
    private final IntentActionRuleStore store;
    private final ConnectorActionDispatcher dispatcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClaimTracker claims = new ClaimTracker(DEBOUNCE_MS);
    private final Map<String, Execution> executions = new LinkedHashMap<>();

    private Map<String, IntentActionRule> enabledByAction = Collections.emptyMap();
    private Map<String, IntentActionRule> enabledById = Collections.emptyMap();
    private Set<String> registeredActions = Collections.emptySet();
    private boolean receiverRegistered;
    private boolean destroyed;

    private final BroadcastReceiver dynamicReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (intent == null || !isExplicitlyTargetedAt(intent, context.getPackageName())) {
                Log.w(TAG, "Ignored Intent that was not targeted at this package");
                return;
            }
            triggerAction(intent.getAction());
        }
    };

    public IntentScenarioController(@NonNull Context context, @NonNull Preferences preferences,
                                    @NonNull ConnectorActionDispatcher dispatcher) {
        this.context = context.getApplicationContext();
        this.store = new IntentActionRuleStore(preferences);
        this.dispatcher = dispatcher;
    }

    /** Reloads the strict allowlist and registers only its exact enabled actions. */
    public void reconfigure() {
        if (destroyed) return;
        final List<IntentActionRule> rules;
        try {
            rules = store.loadStrict();
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Intent action configuration is invalid; all actions disabled", invalid);
            applyRules(Collections.emptyList());
            return;
        }
        applyRules(rules);
    }

    /** Called only with an internal service Intent after the exported receiver did strict lookup. */
    public boolean triggerRuleId(@Nullable String ruleId, @Nullable String triggerToken,
                                 @Nullable String ruleFingerprint, long deadlineElapsed) {
        String id = ruleId == null ? "" : ruleId.trim();
        IntentActionRule rule = enabledById.get(id);
        if (rule == null || !rule.matchesTriggerToken(triggerToken)
                || !rule.matchesExecutionFingerprint(ruleFingerprint)) {
            Log.w(TAG, "Ignored unknown, changed, or disabled Intent rule id");
            return false;
        }
        return submit(rule, deadlineElapsed);
    }

    /** Exact, case-sensitive dynamic-receiver lookup. */
    public boolean triggerAction(@Nullable String intentAction) {
        IntentActionRule rule = intentAction == null ? null : enabledByAction.get(intentAction);
        if (rule == null) {
            Log.w(TAG, "Ignored unknown or disabled Intent action");
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        return submit(rule, deadlineAfter(now));
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        unregisterDynamicReceiver();
        for (Execution execution : new ArrayList<>(executions.values())) {
            if (execution.retry != null) main.removeCallbacks(execution.retry);
            claims.release(execution.rule.id);
        }
        executions.clear();
        enabledByAction = Collections.emptyMap();
        enabledById = Collections.emptyMap();
    }

    /**
     * A package-scoped implicit Intent or an explicit component is accepted. Unscoped broadcasts
     * are deliberately ignored because any installed application could otherwise discover and
     * invoke a configured action name.
     */
    public static boolean isExplicitlyTargetedAt(@NonNull Intent intent,
                                                  @NonNull String packageName) {
        ComponentName component = intent.getComponent();
        if (component != null) return packageName.equals(component.getPackageName());
        return packageName.equals(intent.getPackage());
    }

    private void applyRules(List<IntentActionRule> rules) {
        Map<String, IntentActionRule> byAction = IntentActionRuleStore.enabledByAction(rules);
        LinkedHashMap<String, IntentActionRule> byId = new LinkedHashMap<>();
        for (IntentActionRule rule : rules) {
            if (rule.enabled) byId.put(rule.id, rule);
        }
        enabledByAction = byAction;
        enabledById = Collections.unmodifiableMap(byId);

        // A not-yet-dispatched command must never survive an edit which changes its target/value.
        for (Execution execution : new ArrayList<>(executions.values())) {
            IntentActionRule current = enabledById.get(execution.rule.id);
            if (!execution.dispatched && !execution.rule.equals(current)) finish(execution);
        }

        LinkedHashSet<String> nextActions = new LinkedHashSet<>(byAction.keySet());
        if (registeredActions.equals(nextActions)) return;
        unregisterDynamicReceiver();
        registeredActions = Collections.unmodifiableSet(nextActions);
        if (nextActions.isEmpty()) return;

        IntentFilter filter = new IntentFilter();
        for (String action : nextActions) filter.addAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(dynamicReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(dynamicReceiver, filter);
            }
            receiverRegistered = true;
        } catch (RuntimeException failure) {
            registeredActions = Collections.emptySet();
            Log.e(TAG, "Could not register Intent action receiver", failure);
        }
    }

    private boolean submit(IntentActionRule rule, long deadlineElapsed) {
        if (destroyed || enabledById.get(rule.id) != rule) return false;
        long now = SystemClock.elapsedRealtime();
        if (!isAcceptableDeadline(now, deadlineElapsed)) {
            Log.w(TAG, "Ignored expired or invalid Intent rule deadline");
            return false;
        }
        if (!claims.tryClaim(rule.id, now)) {
            Log.i(TAG, "Ignored debounced or in-flight Intent rule " + rule.id);
            return false;
        }
        Execution execution = new Execution(rule, deadlineElapsed);
        executions.put(rule.id, execution);
        attempt(execution);
        return true;
    }

    private void attempt(Execution execution) {
        if (destroyed || executions.get(execution.rule.id) != execution) return;
        IntentActionRule configured = enabledById.get(execution.rule.id);
        if (!execution.rule.equals(configured)) {
            finish(execution);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // The deadline is absolute. A stalled main looper must not turn a 15-second grace period
        // into a gate command that fires minutes later merely because the connector is ready by
        // the time this callback finally runs.
        if (isExpired(now, execution.deadlineElapsed)) {
            Log.w(TAG, "Intent rule expired before dispatch " + execution.rule.id);
            finish(execution);
            return;
        }
        if (!dispatcher.isReady(execution.rule.command)) {
            long delay = READY_RETRY_MS[Math.min(execution.retryIndex,
                    READY_RETRY_MS.length - 1)];
            execution.retryIndex++;
            execution.retry = () -> attempt(execution);
            main.postDelayed(execution.retry, Math.min(delay,
                    execution.deadlineElapsed - now));
            return;
        }

        // From this point retries are forbidden. An I/O failure after dispatch may be an
        // acknowledgement loss, and repeating SET/TOGGLE could be unsafe or reverse the result.
        execution.dispatched = true;
        final CompletableFuture<Void> future;
        try {
            future = dispatcher.dispatch(execution.rule.command, contextPayload(execution.rule));
        } catch (RuntimeException failure) {
            Log.w(TAG, "Intent rule dispatch failed before submission: "
                    + execution.rule.id, failure);
            finish(execution);
            return;
        }
        future.whenComplete((ignored, failure) -> main.post(() -> {
            if (failure == null) {
                Log.i(TAG, "Intent rule completed: " + execution.rule.id);
            } else {
                Log.w(TAG, "Intent rule failed without retry: " + execution.rule.id, failure);
            }
            finish(execution);
        }));
    }

    static boolean isExpired(long nowElapsed, long deadlineElapsed) {
        return nowElapsed >= deadlineElapsed;
    }

    /** Shared receiver-time deadline; saturated for completeness on synthetic/test clocks. */
    public static long deadlineAfter(long nowElapsed) {
        return nowElapsed > Long.MAX_VALUE - READY_TIMEOUT_MS
                ? Long.MAX_VALUE : nowElapsed + READY_TIMEOUT_MS;
    }

    static boolean isAcceptableDeadline(long nowElapsed, long deadlineElapsed) {
        return deadlineElapsed > nowElapsed
                && deadlineElapsed - nowElapsed <= READY_TIMEOUT_MS;
    }

    private void finish(Execution execution) {
        if (executions.get(execution.rule.id) != execution) return;
        if (execution.retry != null) main.removeCallbacks(execution.retry);
        executions.remove(execution.rule.id);
        claims.release(execution.rule.id);
    }

    private void unregisterDynamicReceiver() {
        if (!receiverRegistered) return;
        try {
            context.unregisterReceiver(dynamicReceiver);
        } catch (IllegalArgumentException ignored) {
            // The process/ROM may already have discarded a dynamic registration.
        }
        receiverRegistered = false;
    }

    private static JSONObject contextPayload(IntentActionRule rule) {
        try {
            return new JSONObject()
                    .put("schema", IntentActionRule.SCHEMA_VERSION)
                    .put("request_id", UUID.randomUUID().toString())
                    .put("trigger", "android_intent")
                    .put("rule_id", rule.id)
                    // Never leak the bearer suffix into connector payloads or diagnostic logs.
                    .put("intent_action_prefix",
                            IntentActionRule.intentActionPrefix(rule.intentAction))
                    .put("created_at", System.currentTimeMillis());
        } catch (JSONException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static final class Execution {
        final IntentActionRule rule;
        final long deadlineElapsed;
        int retryIndex;
        boolean dispatched;
        @Nullable Runnable retry;

        Execution(IntentActionRule rule, long deadlineElapsed) {
            this.rule = rule;
            this.deadlineElapsed = deadlineElapsed;
        }
    }

    /** Pure monotonic per-rule gate covered by local JVM tests. */
    static final class ClaimTracker {
        private final long debounceMs;
        private final Map<String, Long> lastAccepted = new LinkedHashMap<>();
        private final Set<String> active = new LinkedHashSet<>();

        ClaimTracker(long debounceMs) {
            if (debounceMs < 0L) throw new IllegalArgumentException("negative debounce");
            this.debounceMs = debounceMs;
        }

        boolean tryClaim(String id, long nowElapsed) {
            if (active.contains(id)) return false;
            Long previous = lastAccepted.get(id);
            if (previous != null && nowElapsed >= previous
                    && nowElapsed - previous < debounceMs) return false;
            lastAccepted.put(id, nowElapsed);
            active.add(id);
            return true;
        }

        void release(String id) { active.remove(id); }
    }
}
