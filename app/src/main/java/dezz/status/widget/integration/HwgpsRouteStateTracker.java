/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Event-driven HWGPS Fix-widget state machine, kept free of Android APIs for regression tests.
 *
 * <p>HWGPS 4.5.27 keeps its overlay window attached and changes only the root view alpha. Its
 * exported window metadata consequently cannot reveal whether the widget is actually visible.
 * The exported fix-state broadcast is the only usable external evidence. This tracker mirrors
 * the evidenced fast loss/recovery hysteresis and requires a fresh state-request response before
 * accepting a plain {@code notFixed} transition.</p>
 */
final class HwgpsRouteStateTracker {
    /**
     * OEM {@code J2.k#b}: its fast-loss branch (unexported stability tier
     * {@code Y.f <= 1}) waits eight seconds.
     */
    static final long LOSS_CONFIRMATION_MS = 8_000L;
    /**
     * OEM has an unexported stability-tier {@code Y.f == 2} branch which waits twenty seconds.
     * This is only the final-probe expiry here; reaching it is never treated as visibility
     * evidence by itself. That tier is synthesized from HWGPS-private quality inputs and must not
     * be confused with the exported GNSS satellite count.
     */
    static final long OEM_SLOW_LOSS_CONFIRMATION_MS = 20_000L;
    /** OEM {@code J2.k#b}: a recovered route remains visible for ten seconds. */
    static final long RECOVERY_CONFIRMATION_MS = 10_000L;
    static final long NO_DEADLINE = -1L;

    static final class Update {
        @NonNull final HwgpsRouteStatePolicy.State state;
        final boolean requestSnapshot;
        final long deadlineAtMs;

        Update(@NonNull HwgpsRouteStatePolicy.State state,
               boolean requestSnapshot,
               long deadlineAtMs) {
            this.state = state;
            this.requestSnapshot = requestSnapshot;
            this.deadlineAtMs = deadlineAtMs;
        }
    }

    private enum LossCause { NONE, EXPLICIT_AUTO_SHOW, CONFIRMED_NO_FIX }

    @NonNull private HwgpsRouteStatePolicy.State state =
            HwgpsRouteStatePolicy.State.UNAVAILABLE;
    @NonNull private LossCause lossCause = LossCause.NONE;
    private boolean routeArmed;
    private long lossCandidateAtMs = NO_DEADLINE;
    private boolean lossCandidateCorroborated;
    private boolean lossFinalProbePending;
    private long recoveryCandidateAtMs = NO_DEADLINE;
    private boolean recoveryCandidateCorroborated;
    private boolean recoveryFinalProbePending;

    @NonNull
    Update accept(@Nullable String raw, long nowMs) {
        switch (HwgpsRouteStatePolicy.signal(raw)) {
            case AUTO_SHOW:
                clearPending();
                // J2.k#b returns from filtered/spoofing before updating its T0 hysteresis.
                // Preserve an already-confirmed plain-loss cause so the private ten-second
                // recovery dwell is not erased by an intervening explicit adverse state. The
                // explicit branch does not prove that a route existed, so it must not arm a
                // later plain notFixed sample by itself.
                if (lossCause != LossCause.CONFIRMED_NO_FIX) {
                    lossCause = LossCause.EXPLICIT_AUTO_SHOW;
                }
                state = HwgpsRouteStatePolicy.State.ROUTE_LOST;
                return update(false);

            case ROUTE_AVAILABLE:
                routeArmed = true;
                clearLossCandidate();
                if (state == HwgpsRouteStatePolicy.State.ROUTE_LOST
                        && lossCause == LossCause.CONFIRMED_NO_FIX) {
                    if (recoveryCandidateAtMs == NO_DEADLINE) {
                        recoveryCandidateAtMs = nowMs;
                        recoveryCandidateCorroborated = false;
                        recoveryFinalProbePending = false;
                        return update(true);
                    }
                    if (recoveryFinalProbePending) {
                        clearRecoveryCandidate();
                        lossCause = LossCause.NONE;
                        state = HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE;
                        return update(false);
                    }
                    recoveryCandidateCorroborated = true;
                    return evaluate(nowMs);
                }
                clearRecoveryCandidate();
                lossCause = LossCause.NONE;
                state = HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE;
                return update(false);

            case NO_FIX:
                clearRecoveryCandidate();
                if (state == HwgpsRouteStatePolicy.State.ROUTE_LOST
                        && lossCause == LossCause.CONFIRMED_NO_FIX) {
                    return update(false);
                }
                // filtered/spoofing stopped being externally true. Do not let their previous
                // immediate result turn a subsequent unconfirmed notFixed sample into a sticky
                // false positive.
                if (lossCause == LossCause.EXPLICIT_AUTO_SHOW) {
                    lossCause = LossCause.NONE;
                    state = HwgpsRouteStatePolicy.State.UNAVAILABLE;
                }
                if (!routeArmed) {
                    clearLossCandidate();
                    state = HwgpsRouteStatePolicy.State.UNAVAILABLE;
                    return update(false);
                }
                if (lossCandidateAtMs == NO_DEADLINE) {
                    lossCandidateAtMs = nowMs;
                    lossCandidateCorroborated = false;
                    lossFinalProbePending = false;
                    return update(true);
                }
                if (lossFinalProbePending) {
                    clearLossCandidate();
                    lossCause = LossCause.CONFIRMED_NO_FIX;
                    state = HwgpsRouteStatePolicy.State.ROUTE_LOST;
                    return update(false);
                }
                lossCandidateCorroborated = true;
                return evaluate(nowMs);

            case UNKNOWN:
            default:
                // Future/malformed broadcasts are not route evidence. Disarm as well as clearing
                // the result so a later isolated notFixed cannot reuse stale route availability.
                reset();
                return update(false);
        }
    }

    @NonNull
    Update evaluate(long nowMs) {
        if (lossCandidateAtMs != NO_DEADLINE) {
            long deadline = lossCandidateAtMs + (lossFinalProbePending
                    ? OEM_SLOW_LOSS_CONFIRMATION_MS : LOSS_CONFIRMATION_MS);
            if (nowMs < deadline) return new Update(state, false, deadline);
            if (!lossFinalProbePending && lossCandidateCorroborated) {
                // A second sample proves that HWGPS answered the initial request. At the OEM's
                // eight-second visibility boundary request once more; only that post-boundary
                // snapshot may turn the candidate into a real loss. The OEM's twenty-second
                // slow boundary only bounds the candidate if HWGPS never answers that probe.
                lossFinalProbePending = true;
                lossCandidateCorroborated = false;
                return update(true);
            }
            if (!lossFinalProbePending) {
                clearLossCandidate();
            }
            if (lossFinalProbePending && nowMs >= deadline) clearLossCandidate();
        }
        if (recoveryCandidateAtMs != NO_DEADLINE) {
            long deadline = recoveryCandidateAtMs + (recoveryFinalProbePending
                    ? OEM_SLOW_LOSS_CONFIRMATION_MS : RECOVERY_CONFIRMATION_MS);
            if (nowMs < deadline) return new Update(state, false, deadline);
            if (!recoveryFinalProbePending && recoveryCandidateCorroborated) {
                recoveryFinalProbePending = true;
                recoveryCandidateCorroborated = false;
                return update(true);
            }
            if (!recoveryFinalProbePending) {
                clearRecoveryCandidate();
            } else if (nowMs >= deadline) {
                // The route might have recovered, but without a final request response neither
                // boolean result is trustworthy. Drop to UNKNOWN instead of sticking/fabricating.
                clearRecoveryCandidate();
                lossCause = LossCause.NONE;
                state = HwgpsRouteStatePolicy.State.UNAVAILABLE;
            }
        }
        return update(false);
    }

    void reset() {
        routeArmed = false;
        clearPending();
        lossCause = LossCause.NONE;
        state = HwgpsRouteStatePolicy.State.UNAVAILABLE;
    }

    @NonNull
    HwgpsRouteStatePolicy.State state() {
        return state;
    }

    @NonNull
    private Update update(boolean requestSnapshot) {
        long deadline = NO_DEADLINE;
        if (lossCandidateAtMs != NO_DEADLINE) {
            deadline = lossCandidateAtMs + (lossFinalProbePending
                    ? OEM_SLOW_LOSS_CONFIRMATION_MS : LOSS_CONFIRMATION_MS);
        } else if (recoveryCandidateAtMs != NO_DEADLINE) {
            deadline = recoveryCandidateAtMs + (recoveryFinalProbePending
                    ? OEM_SLOW_LOSS_CONFIRMATION_MS : RECOVERY_CONFIRMATION_MS);
        }
        return new Update(state, requestSnapshot, deadline);
    }

    private void clearPending() {
        clearLossCandidate();
        clearRecoveryCandidate();
    }

    private void clearLossCandidate() {
        lossCandidateAtMs = NO_DEADLINE;
        lossCandidateCorroborated = false;
        lossFinalProbePending = false;
    }

    private void clearRecoveryCandidate() {
        recoveryCandidateAtMs = NO_DEADLINE;
        recoveryCandidateCorroborated = false;
        recoveryFinalProbePending = false;
    }
}
