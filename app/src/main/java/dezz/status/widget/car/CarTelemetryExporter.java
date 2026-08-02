/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutPath;

/**
 * Long-lived, latest-value-wins bridge from the vehicle SDK to writable Sprut.hub
 * characteristics.
 *
 * <p>The bridge deliberately owns no transport credentials and never trusts the cached Sprut
 * catalog for writes. Each send resolves the target against the controller's current authoritative
 * snapshot and runs the strict converter again. A new configuration or Sprut session invalidates
 * all cached vehicle samples: every selected metric must be sampled again before it may be sent.
 * Transient write failures retain only the newest sample and retry with bounded backoff.</p>
 */
public final class CarTelemetryExporter {
    private static final String TAG = "CarTelemetryExporter";

    private final CarIntegration car;
    private final SprutHubController sprut;
    private final Handler main;
    private final CarSprutBindingStore store;
    private final Map<String, CarTelemetryValue> latest = new LinkedHashMap<>();
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    @Nullable private CarIntegration.TelemetryListener activeTelemetryListener;
    private boolean stopped;
    private long sampleSequence;
    private long sessionEpoch;
    private long subscriptionEpoch;
    private long writeSequence;

    public CarTelemetryExporter(@NonNull Preferences prefs, @NonNull CarIntegration car,
                                @NonNull SprutHubController sprut, @NonNull Handler main) {
        this.car = Objects.requireNonNull(car, "car");
        this.sprut = Objects.requireNonNull(sprut, "sprut");
        this.main = Objects.requireNonNull(main, "main");
        store = new CarSprutBindingStore(Objects.requireNonNull(prefs, "prefs"));
    }

    /** Reloads mappings after the About screen or settings import changes preferences. */
    public void reconfigure() {
        if (stopped) return;

        // A configuration boundary also invalidates an outstanding RPC. Its future cannot be
        // cancelled reliably, but the epoch makes its eventual completion harmless.
        sessionEpoch++;
        stopTelemetrySubscription();
        for (Slot old : slots.values()) cancelScheduled(old);
        slots.clear();
        latest.clear();

        List<CarSprutBinding> bindings = store.load();
        Map<String, String> targetOwners = new LinkedHashMap<>();
        for (CarSprutBinding binding : bindings) {
            if (!binding.enabled) continue;
            String targetId = binding.targetPath.stableId();
            String existing = targetOwners.putIfAbsent(targetId, binding.metricId);
            if (existing != null) {
                Log.w(TAG, "Ignored duplicate Sprut target " + targetId + " for "
                        + binding.metricId + "; already used by " + existing);
                continue;
            }
            slots.put(binding.metricId, new Slot(binding));
        }

        // Do not collect a cache while Sprut is offline. The authoritative catalog callback will
        // start a new, narrowly-filtered vehicle snapshot after reconnect.
        if (isSprutReady()) startFreshTelemetrySubscription();
    }

    /** Must be called only after SprutHubController publishes a new authoritative snapshot. */
    public void onSprutCatalogChanged() {
        if (stopped) return;
        main.post(() -> {
            if (stopped) return;
            beginFreshSession(true);
        });
    }

    public void onSprutConnectionChanged(@NonNull SprutHubController.State state) {
        if (stopped) return;
        main.post(() -> {
            if (stopped) return;
            if (state == SprutHubController.State.ONLINE) {
                // Normally the preceding authoritative catalog callback already started the
                // subscription. Keep this fallback for controllers that publish ONLINE alone.
                if (activeTelemetryListener == null && isSprutReady()) {
                    beginFreshSession(true);
                } else {
                    flush();
                }
                return;
            }
            beginFreshSession(false);
        });
    }

    /**
     * Reconciles a live Sprut event for one of the selected targets with the latest car sample.
     * A matching value is an acknowledgement even if it arrived before the RPC future. An
     * external change away from the desired value clears de-duplication and schedules latest.
     */
    public void onSprutCharacteristicChanged(@NonNull SprutPath path) {
        if (stopped) return;
        main.post(() -> {
            if (stopped || !isSprutReady()) return;
            for (Slot slot : slots.values()) {
                if (!slot.binding.targetPath.equals(path) || slot.awaitingFreshSample) continue;
                CarTelemetryValue sample = latest.get(slot.binding.metricId);
                SprutCatalog.Characteristic target = sprut.catalog().find(path);
                if (sample == null || target == null) continue;

                final Object converted;
                try {
                    converted = SprutCharacteristicConverter.convert(
                            sample, slot.binding, target);
                } catch (IllegalArgumentException invalid) {
                    reportError(slot, invalid.getMessage());
                    continue;
                }

                if (valuesEqual(converted, target.currentValue())) {
                    boolean confirmsOwnWrite = slot.inFlight
                            && valuesEqual(converted, slot.inFlightValue);
                    cancelScheduled(slot);
                    clearRetry(slot);
                    slot.lastSent = converted;
                    slot.lastError = "";
                    if (confirmsOwnWrite) {
                        slot.lastSuccessfulWriteElapsed = SystemClock.elapsedRealtime();
                    }
                    // The RPC may still complete later. Clear its token so that completion cannot
                    // affect a newer write started after this authoritative event.
                    clearInFlight(slot);
                } else if (slot.inFlight) {
                    // Do not lose an authoritative divergence that races the RPC completion.
                    // A successful RPC only confirms acceptance, not the resulting Sprut value.
                    // Its completion must therefore re-check the current catalog and, when it is
                    // still different, issue the latest vehicle value again.
                    slot.reconcileAfterInFlight = true;
                } else {
                    slot.lastSent = null;
                    schedule(slot);
                }
            }
        });
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        sessionEpoch++;
        stopTelemetrySubscription();
        for (Slot slot : slots.values()) cancelScheduled(slot);
        slots.clear();
        latest.clear();
    }

    /** Package-visible for deterministic unit tests. Failure numbers start at one. */
    static long retryDelayMillis(int failureNumber) {
        if (failureNumber <= 1) return 1_000L;
        if (failureNumber == 2) return 2_000L;
        if (failureNumber == 3) return 5_000L;
        if (failureNumber == 4) return 10_000L;
        return 30_000L;
    }

    /** Package-visible pure completion policy for deterministic race-condition tests. */
    static CompletionDisposition completionDisposition(boolean failed,
                                                        boolean authoritativeMismatch,
                                                        long latestVersion,
                                                        long sentVersion) {
        if (failed) return CompletionDisposition.RETRY_LATEST;
        if (authoritativeMismatch) return CompletionDisposition.RECONCILE_AUTHORITATIVE;
        if (latestVersion > sentVersion) return CompletionDisposition.SEND_LATEST;
        return CompletionDisposition.ACCEPT;
    }

    private void beginFreshSession(boolean subscribeWhenReady) {
        sessionEpoch++;
        stopTelemetrySubscription();
        latest.clear();
        for (Slot slot : slots.values()) resetForFreshSample(slot);
        if (subscribeWhenReady && isSprutReady()) startFreshTelemetrySubscription();
    }

    private void resetForFreshSample(@NonNull Slot slot) {
        cancelScheduled(slot);
        slot.awaitingFreshSample = true;
        slot.latestVersion = 0L;
        slot.lastSent = null;
        slot.lastSuccessfulWriteElapsed = 0L;
        clearRetry(slot);
        clearInFlight(slot);
        slot.lastError = "";
    }

    private void startFreshTelemetrySubscription() {
        if (stopped || slots.isEmpty()) return;
        stopTelemetrySubscription();
        final long expectedSubscriptionEpoch = ++subscriptionEpoch;
        Set<String> metricIds = new LinkedHashSet<>(slots.keySet());
        CarIntegration.TelemetryListener listener = value ->
                handleTelemetry(expectedSubscriptionEpoch, value);
        activeTelemetryListener = listener;
        car.subscribeTelemetry(metricIds, listener);
    }

    private void stopTelemetrySubscription() {
        // Increment before unregistering so an already-queued callback from the old vendor
        // listener is rejected even when unregister itself is asynchronous.
        subscriptionEpoch++;
        CarIntegration.TelemetryListener old = activeTelemetryListener;
        activeTelemetryListener = null;
        if (old != null) car.unsubscribeTelemetry(old);
    }

    /** CarIntegration guarantees this callback is already on the main thread. */
    private void handleTelemetry(long expectedSubscriptionEpoch,
                                 @NonNull CarIntegration.TelemetryValue value) {
        if (stopped || expectedSubscriptionEpoch != subscriptionEpoch) return;
        Slot slot = slots.get(value.id);
        if (slot == null) return;

        final CarTelemetryValue sample;
        try {
            sample = new CarTelemetryValue(value.id, value.value, value.observedAtMillis,
                    value.unit);
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Ignored invalid vehicle sample " + value.id + ": "
                    + invalid.getMessage());
            return;
        }
        long version = ++sampleSequence;
        latest.put(sample.metricId, sample);
        slot.latestVersion = version;
        slot.awaitingFreshSample = false;
        schedule(slot);
    }

    private void flush() {
        if (stopped || !isSprutReady()) return;
        for (Slot slot : slots.values()) schedule(slot);
    }

    private void schedule(@NonNull Slot slot) {
        if (stopped || !isActive(slot) || slot.awaitingFreshSample
                || slot.inFlight || slot.scheduled != null) return;
        if (latest.get(slot.binding.metricId) == null || !isSprutReady()) return;

        long now = SystemClock.elapsedRealtime();
        long throttleDue = slot.lastSuccessfulWriteElapsed == 0L ? 0L
                : saturatedAdd(slot.lastSuccessfulWriteElapsed, slot.binding.minIntervalMs);
        long dueAt = Math.max(throttleDue, slot.retryNotBeforeElapsed);
        long delay = Math.max(0L, dueAt - now);
        Runnable task = () -> {
            slot.scheduled = null;
            sendLatest(slot);
        };
        slot.scheduled = task;
        if (delay == 0L) main.post(task); else main.postDelayed(task, delay);
    }

    private void sendLatest(@NonNull Slot slot) {
        if (stopped || !isActive(slot) || slot.awaitingFreshSample || slot.inFlight
                || !isSprutReady()) return;
        CarTelemetryValue sample = latest.get(slot.binding.metricId);
        if (sample == null) return;
        SprutCatalog.Characteristic target = sprut.catalog().find(slot.binding.targetPath);
        if (target == null) {
            reportError(slot, "Целевая характеристика больше не существует");
            return;
        }

        final Object converted;
        try {
            converted = SprutCharacteristicConverter.convert(sample, slot.binding, target);
        } catch (IllegalArgumentException invalid) {
            reportError(slot, invalid.getMessage());
            return;
        }
        // The authoritative snapshot/event is the best possible acknowledgement. If Sprut already
        // contains the desired value there is no reason to issue an idempotent write.
        if (valuesEqual(converted, target.currentValue())) {
            slot.lastSent = converted;
            clearRetry(slot);
            slot.lastError = "";
            return;
        }
        if (valuesEqual(converted, slot.lastSent)) return;

        slot.inFlight = true;
        slot.inFlightValue = converted;
        slot.inFlightEpoch = sessionEpoch;
        slot.inFlightToken = ++writeSequence;
        slot.reconcileAfterInFlight = false;
        final long writeEpoch = sessionEpoch;
        final long writeToken = slot.inFlightToken;
        final long sentVersion = slot.latestVersion;
        sprut.writeCharacteristic(slot.binding.targetPath, converted)
                .whenComplete((response, failure) -> main.post(() -> {
                    if (stopped || !isActive(slot)) return;
                    if (writeEpoch != sessionEpoch || slot.inFlightEpoch != writeEpoch
                            || slot.inFlightToken != writeToken) return;
                    Object sent = slot.inFlightValue;
                    boolean authoritativeMismatch = slot.reconcileAfterInFlight;
                    clearInFlight(slot);
                    CompletionDisposition disposition = completionDisposition(failure != null,
                            authoritativeMismatch, slot.latestVersion, sentVersion);
                    if (failure == null) {
                        // When an EVENT_UPDATE diverged during this RPC, retaining sent as the
                        // de-duplication value would suppress the required corrective write.
                        slot.lastSent = disposition == CompletionDisposition.RECONCILE_AUTHORITATIVE
                                ? null : sent;
                        slot.lastSuccessfulWriteElapsed = SystemClock.elapsedRealtime();
                        slot.lastError = "";
                        clearRetry(slot);
                    } else {
                        reportError(slot, rootMessage(failure));
                        int failureNumber = ++slot.consecutiveFailures;
                        slot.retryNotBeforeElapsed = saturatedAdd(SystemClock.elapsedRealtime(),
                                retryDelayMillis(failureNumber));
                    }
                    // Success coalesces a newer value received in flight; failure retries the
                    // latest value even when no new car event arrives. An authoritative mismatch
                    // observed during the RPC always forces a fresh catalog comparison.
                    if (disposition != CompletionDisposition.ACCEPT) schedule(slot);
                }));
    }

    private boolean isSprutReady() {
        return SprutHubController.isSynced() && SprutHubController.active() == sprut;
    }

    private boolean isActive(@NonNull Slot slot) {
        return slots.get(slot.binding.metricId) == slot;
    }

    private void reportError(Slot slot, @Nullable String raw) {
        String message = raw == null || raw.trim().isEmpty() ? "unknown error" : raw.trim();
        if (!message.equals(slot.lastError)) {
            Log.w(TAG, slot.binding.metricId + " → " + slot.binding.targetPath + ": " + message);
            slot.lastError = message;
        }
    }

    private void cancelScheduled(Slot slot) {
        if (slot.scheduled != null) main.removeCallbacks(slot.scheduled);
        slot.scheduled = null;
    }

    private static void clearRetry(Slot slot) {
        slot.consecutiveFailures = 0;
        slot.retryNotBeforeElapsed = 0L;
    }

    private static void clearInFlight(Slot slot) {
        slot.inFlight = false;
        slot.inFlightValue = null;
        slot.inFlightEpoch = 0L;
        slot.inFlightToken = 0L;
        slot.reconcileAfterInFlight = false;
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    static boolean valuesEqual(@Nullable Object first, @Nullable Object second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (first instanceof Boolean || second instanceof Boolean) {
            Boolean a = logicalValue(first);
            Boolean b = logicalValue(second);
            return a != null && b != null && a.equals(b);
        }
        if (first instanceof Number && second instanceof Number) {
            try {
                BigDecimal a = new BigDecimal(first.toString()).stripTrailingZeros();
                BigDecimal b = new BigDecimal(second.toString()).stripTrailingZeros();
                return a.compareTo(b) == 0;
            } catch (NumberFormatException ignored) {
                return Double.compare(((Number) first).doubleValue(),
                        ((Number) second).doubleValue()) == 0;
            }
        }
        return first.equals(second);
    }

    @Nullable
    private static Boolean logicalValue(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? number != 0d : null;
        }
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        switch (text) {
            case "1":
            case "true":
            case "on":
                return true;
            case "0":
            case "false":
            case "off":
                return false;
            default:
                return null;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) result = result.getCause();
        String message = result.getMessage();
        return message == null || message.trim().isEmpty()
                ? result.getClass().getSimpleName() : message;
    }

    enum CompletionDisposition {
        ACCEPT,
        SEND_LATEST,
        RECONCILE_AUTHORITATIVE,
        RETRY_LATEST
    }

    private static final class Slot {
        final CarSprutBinding binding;
        @Nullable Object lastSent;
        @Nullable Object inFlightValue;
        @Nullable Runnable scheduled;
        boolean awaitingFreshSample = true;
        boolean inFlight;
        boolean reconcileAfterInFlight;
        int consecutiveFailures;
        long latestVersion;
        long inFlightEpoch;
        long inFlightToken;
        long lastSuccessfulWriteElapsed;
        long retryNotBeforeElapsed;
        String lastError = "";

        Slot(CarSprutBinding binding) { this.binding = binding; }
    }
}
