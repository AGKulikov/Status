/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

import dezz.status.widget.Preferences;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.sprut.SprutActionValue;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutPath;

/**
 * Latest-state-wins bridge from the selected iPhone's Bluetooth presence to one writable
 * Sprut.hub switch.
 *
 * <p>The saved path is never trusted on its own. Every write is resolved again against the
 * controller's current authoritative catalog and must still point to a writable boolean-like
 * characteristic. Delayed RPC completions are guarded by both a session epoch and a write token,
 * so an old "connected" completion cannot override a newer disconnect.</p>
 */
public final class PhoneSprutPresenceExporter {
    private static final String TAG = "PhoneSprutPresence";

    private final Preferences prefs;
    private final SprutHubController sprut;
    private final Handler main;

    @Nullable private SprutPath targetPath;
    @Nullable private Runnable scheduledRetry;
    private boolean configured;
    private boolean desiredConnected;
    private boolean hasPhoneState;
    private boolean inFlight;
    private boolean inFlightConnected;
    private boolean reconcileAfterInFlight;
    private boolean forceNextWrite;
    private boolean stopped;
    private int consecutiveFailures;
    private long sessionEpoch;
    private long writeSequence;
    private long inFlightEpoch;
    private long inFlightToken;
    @NonNull private String selectedPhoneAddress = "";
    @NonNull private String lastError = "";

    public PhoneSprutPresenceExporter(@NonNull Preferences prefs,
                                      @NonNull SprutHubController sprut,
                                      @NonNull Handler main) {
        this.prefs = Objects.requireNonNull(prefs, "prefs");
        this.sprut = Objects.requireNonNull(sprut, "sprut");
        this.main = Objects.requireNonNull(main, "main");
    }

    /** Reloads the selected switch without trusting a cached Sprut catalog. */
    public void reconfigure() {
        runOnMain(this::reconfigureOnMain);
    }

    /** Receives only the selected iPhone's effective connection state. */
    public void onPhoneConnectionChanged(boolean connected) {
        runOnMain(() -> {
            if (stopped) return;
            hasPhoneState = true;
            desiredConnected = connected;
            scheduleNow();
        });
    }

    public void onSprutConnectionChanged(@NonNull SprutHubController.State state) {
        Objects.requireNonNull(state, "state");
        runOnMain(() -> {
            if (stopped) return;
            if (state == SprutHubController.State.ONLINE) {
                scheduleNow();
            } else {
                invalidateSprutSession();
            }
        });
    }

    /** Called only after SprutHubController has installed a full authoritative snapshot. */
    public void onSprutCatalogChanged() {
        runOnMain(() -> {
            if (stopped) return;
            reconcileAuthoritativeState();
        });
    }

    /** Reconciles an external switch change or the EVENT_UPDATE acknowledging our write. */
    public void onSprutCharacteristicChanged(@NonNull SprutPath path) {
        Objects.requireNonNull(path, "path");
        runOnMain(() -> {
            if (stopped || targetPath == null || !targetPath.equals(path)
                    || !isSprutReady()) return;
            reconcileAuthoritativeState();
        });
    }

    /**
     * Best-effort cleanup. Normal ACL disconnect events are delivered before this method; this
     * final write covers an explicit service/connector shutdown while Sprut is still online.
     */
    public void stop() {
        runOnMain(() -> {
            if (stopped) return;
            desiredConnected = false;
            hasPhoneState = true;
            cancelRetry();
            invalidateInFlight();
            if (configured && targetPath != null && isSprutReady()) {
                writeBestEffortFalse(targetPath);
            }
            stopped = true;
            sessionEpoch++;
        });
    }

    private void reconfigureOnMain() {
        if (stopped) return;

        SprutPath oldTarget = targetPath;
        boolean oldConfigured = configured;
        SprutPath parsed = parsePath(prefs.phoneSprutPresencePath.get());
        String nextPhoneAddress = normalizeAddress(prefs.phoneDeviceAddress.get());
        boolean phoneChanged = !selectedPhoneAddress.equals(nextPhoneAddress);
        boolean phoneConnectorEnabled = prefs.phoneConnectorEnabled.get();
        boolean nextConfigured = phoneConnectorEnabled
                && prefs.phoneSprutPresenceEnabled.get() && parsed != null
                && !nextPhoneAddress.isEmpty();
        if (!phoneConnectorEnabled) {
            hasPhoneState = true;
            desiredConnected = false;
        }

        boolean changed = oldConfigured != nextConfigured
                || !Objects.equals(oldTarget, parsed) || phoneChanged;
        if (!changed) {
            scheduleNow();
            return;
        }

        // If the mapping was removed or moved while the phone was present, clear the former
        // accessory before forgetting its path. This write is validated against the live catalog.
        if (oldConfigured && oldTarget != null && isSprutReady()
                && (!nextConfigured || !Objects.equals(oldTarget, parsed) || phoneChanged)) {
            writeBestEffortFalse(oldTarget);
        }

        cancelRetry();
        invalidateInFlight();
        forceNextWrite = false;
        clearRetryState();
        lastError = "";
        sessionEpoch++;
        targetPath = parsed;
        configured = nextConfigured;
        selectedPhoneAddress = nextPhoneAddress;
        if (phoneChanged) {
            // Never carry a positive state across a device-identity boundary. The controller
            // must explicitly prove that the newly selected address is connected.
            hasPhoneState = true;
            desiredConnected = false;
        }
        if (!configured) return;
        scheduleNow();
    }

    private void scheduleNow() {
        cancelRetry();
        if (stopped || !configured || !hasPhoneState || targetPath == null || inFlight
                || !isSprutReady()) return;
        sendLatest();
    }

    private void sendLatest() {
        if (stopped || !configured || !hasPhoneState || targetPath == null || inFlight
                || !isSprutReady()) return;

        SprutCatalog.Characteristic target = sprut.catalog().find(targetPath);
        final Object encoded;
        try {
            encoded = resolveDesiredValue(target, desiredConnected);
        } catch (IllegalArgumentException invalid) {
            reportError(invalid.getMessage());
            return;
        }

        if (!forceNextWrite && logicalEquals(target.currentValue(), desiredConnected)) {
            clearRetryState();
            lastError = "";
            return;
        }

        forceNextWrite = false;
        inFlight = true;
        inFlightConnected = desiredConnected;
        inFlightEpoch = sessionEpoch;
        inFlightToken = ++writeSequence;
        reconcileAfterInFlight = false;
        final long expectedEpoch = inFlightEpoch;
        final long expectedToken = inFlightToken;
        final boolean sentConnected = desiredConnected;
        final SprutPath sentPath = targetPath;

        sprut.writeCharacteristic(sentPath, encoded).whenComplete((response, failure) ->
                main.post(() -> {
                    if (stopped || expectedEpoch != sessionEpoch
                            || expectedToken != inFlightToken || !inFlight
                            || targetPath == null || !targetPath.equals(sentPath)) return;

                    boolean authoritativeMismatch = reconcileAfterInFlight;
                    invalidateInFlight();
                    CompletionDisposition disposition = completionDisposition(
                            failure != null, authoritativeMismatch,
                            desiredConnected, sentConnected);
                    if (failure == null) {
                        clearRetryState();
                        lastError = "";
                    } else {
                        reportError(rootMessage(failure));
                        scheduleRetry(++consecutiveFailures);
                        return;
                    }
                    if (disposition == CompletionDisposition.SEND_LATEST) {
                        // The hub accepted an obsolete value. Its local catalog may still expose
                        // the newer desired value from an earlier snapshot, so equality cannot be
                        // used to suppress the compensating write.
                        forceNextWrite = true;
                    }
                    if (disposition != CompletionDisposition.ACCEPT) scheduleNow();
                }));
    }

    private void reconcileAuthoritativeState() {
        if (!isSprutReady() || targetPath == null || !configured || !hasPhoneState) return;
        SprutCatalog.Characteristic target = sprut.catalog().find(targetPath);
        if (target == null) {
            if (inFlight) reconcileAfterInFlight = true; else scheduleNow();
            return;
        }
        if (logicalEquals(target.currentValue(), desiredConnected)) {
            cancelRetry();
            clearRetryState();
            lastError = "";
            if (canSettleMatchingAuthoritative(
                    inFlight, inFlightConnected, desiredConnected)) {
                forceNextWrite = false;
                invalidateInFlight();
            } else {
                // A still-pending opposite write may overwrite this matching snapshot after it
                // returns. Keep tracking it and compensate unconditionally on success.
                reconcileAfterInFlight = true;
            }
        } else if (inFlight) {
            reconcileAfterInFlight = true;
        } else {
            scheduleNow();
        }
    }

    private void writeBestEffortFalse(@NonNull SprutPath path) {
        SprutCatalog.Characteristic target = sprut.catalog().find(path);
        if (target == null) return;
        try {
            // Never trust a matching snapshot here: a previously submitted "on" may still be
            // accepted after that snapshot. Cleanup is rare, and one redundant false prevents a
            // stopped connector or a formerly selected iPhone from leaving presence enabled.
            Object value = resolveDesiredValue(target, false);
            sprut.writeCharacteristic(path, value).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    Log.w(TAG, "Could not clear old presence target " + path + ": "
                            + rootMessage(failure));
                }
            });
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Could not clear invalid presence target " + path + ": "
                    + invalid.getMessage());
        }
    }

    @NonNull
    private static Object resolveDesiredValue(@Nullable SprutCatalog.Characteristic target,
                                              boolean desired) {
        if (target == null) {
            throw new IllegalArgumentException("Выбранный переключатель больше не существует");
        }
        if (!target.writable()) {
            throw new IllegalArgumentException("Выбранная характеристика недоступна для записи");
        }
        if (!SprutActionValue.isBooleanLike(target)) {
            throw new IllegalArgumentException(
                    "Выбранная характеристика больше не является переключателем");
        }
        ActionBinding binding = new ActionBinding(ConnectorType.SPRUTHUB,
                SourceBinding.DEFAULT_CONNECTOR_ID, target.path().stableId(),
                ActionBinding.OPERATION_SET, SprutActionValue.encodePrimitive(desired));
        return SprutActionValue.resolve(binding, target);
    }

    private void scheduleRetry(int failureNumber) {
        cancelRetry();
        if (stopped || !configured || targetPath == null) return;
        final Runnable[] owner = new Runnable[1];
        owner[0] = () -> {
            if (scheduledRetry != owner[0]) return;
            scheduledRetry = null;
            scheduleNow();
        };
        scheduledRetry = owner[0];
        main.postDelayed(owner[0], retryDelayMillis(failureNumber));
    }

    private void invalidateSprutSession() {
        sessionEpoch++;
        cancelRetry();
        invalidateInFlight();
        forceNextWrite = false;
        clearRetryState();
    }

    private void invalidateInFlight() {
        inFlight = false;
        inFlightEpoch = 0L;
        inFlightToken = 0L;
        inFlightConnected = false;
        reconcileAfterInFlight = false;
    }

    private void cancelRetry() {
        Runnable pending = scheduledRetry;
        scheduledRetry = null;
        if (pending != null) main.removeCallbacks(pending);
    }

    private void clearRetryState() {
        consecutiveFailures = 0;
    }

    private boolean isSprutReady() {
        return SprutHubController.isSynced() && SprutHubController.active() == sprut;
    }

    private void reportError(@Nullable String raw) {
        String message = raw == null || raw.trim().isEmpty() ? "unknown error" : raw.trim();
        if (!message.equals(lastError)) {
            Log.w(TAG, (targetPath == null ? "unbound" : targetPath) + ": " + message);
            lastError = message;
        }
    }

    private void runOnMain(@NonNull Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run(); else main.post(action);
    }

    @Nullable
    private static SprutPath parsePath(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return SprutPath.parse(raw);
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Ignored invalid Sprut presence path", invalid);
            return null;
        }
    }

    @NonNull
    static String normalizeAddress(@Nullable String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    static long retryDelayMillis(int failureNumber) {
        if (failureNumber <= 1) return 1_000L;
        if (failureNumber == 2) return 2_000L;
        if (failureNumber == 3) return 5_000L;
        if (failureNumber == 4) return 10_000L;
        return 30_000L;
    }

    @NonNull
    static CompletionDisposition completionDisposition(boolean failed,
                                                        boolean authoritativeMismatch,
                                                        boolean latest,
                                                        boolean sent) {
        if (failed) return CompletionDisposition.RETRY_LATEST;
        if (latest != sent) return CompletionDisposition.SEND_LATEST;
        if (authoritativeMismatch) return CompletionDisposition.RECONCILE_AUTHORITATIVE;
        return CompletionDisposition.ACCEPT;
    }

    static boolean canSettleMatchingAuthoritative(boolean inFlight,
                                                  boolean sent,
                                                  boolean latest) {
        return !inFlight || sent == latest;
    }

    static boolean logicalEquals(@Nullable Object actual, boolean expected) {
        Boolean logical = logicalValue(actual);
        return logical != null && logical == expected;
    }

    @Nullable
    private static Boolean logicalValue(@Nullable Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString()).compareTo(BigDecimal.ZERO) != 0;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        if (value == null) return null;
        switch (String.valueOf(value).trim().toLowerCase(Locale.ROOT)) {
            case "1":
            case "true":
            case "on":
            case "yes":
            case "вкл":
                return true;
            case "0":
            case "false":
            case "off":
            case "no":
            case "выкл":
                return false;
            default:
                return null;
        }
    }

    @NonNull
    private static String rootMessage(@NonNull Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) result = result.getCause();
        String message = result.getMessage();
        return message == null || message.trim().isEmpty()
                ? result.getClass().getSimpleName() : message.trim();
    }

    enum CompletionDisposition {
        ACCEPT,
        SEND_LATEST,
        RECONCILE_AUTHORITATIVE,
        RETRY_LATEST
    }
}
