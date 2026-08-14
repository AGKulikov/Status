/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import androidx.annotation.NonNull;

/** Pure arbitration between the renewable ECARX decision and lifecycle/a11y fallback. */
final class NavigatorWindowSourcePolicy {
    enum VendorDecision { NONE, WINDOWED, NOT_WINDOWED }

    enum ObservationAction { PUBLISH, RETRY }

    enum OptimisticAction { IDLE, START_OR_RESTART, KEEP, CANCEL }

    /**
     * Two-sample absence gate for ECARX inventory races.
     *
     * <p>The first inventory can land between TransparentSplashActivity disappearing and the
     * real Navigator window being attached.  Requiring confirmation before <em>any</em> ABSENT
     * publication protects both the initial hand-off and a later splash-to-content replacement.
     * A confirmed absence is nevertheless authoritative, even when no positive vendor frame was
     * observed first.</p>
     */
    static final class AbsenceGate {
        private int consecutiveAbsent;

        @NonNull ObservationAction observe(@NonNull NavigatorWindowFramePolicy.State state) {
            switch (state) {
                case WINDOWED:
                case FULLSCREEN:
                    consecutiveAbsent = 0;
                    return ObservationAction.PUBLISH;
                case ABSENT:
                    consecutiveAbsent++;
                    if (consecutiveAbsent < 2) return ObservationAction.RETRY;
                    consecutiveAbsent = 0;
                    return ObservationAction.PUBLISH;
                case UNKNOWN:
                default:
                    consecutiveAbsent = 0;
                    return ObservationAction.PUBLISH;
            }
        }
    }

    private NavigatorWindowSourcePolicy() {}

    /**
     * Keeps the vendor confirmation grace independent from the mutable lifecycle/a11y token.
     *
     * <p>An exact TransparentSplashActivity accessibility event upgrades the surface fallback
     * from launch-owned optimistic to lifecycle-confirmed.  It must not end the already-running
     * ECARX geometry grace.  A real surface close ({@code foreground=false}) still cancels it,
     * while every fresh launch marker restarts the bounded timer.</p>
     */
    @NonNull
    static OptimisticAction optimisticActionAfterSurfaceChange(
            boolean confirmationPending, boolean navigatorForeground,
            boolean launchOptimistic) {
        if (launchOptimistic) return OptimisticAction.START_OR_RESTART;
        if (!confirmationPending) return OptimisticAction.IDLE;
        return navigatorForeground ? OptimisticAction.KEEP : OptimisticAction.CANCEL;
    }

    @NonNull
    static VendorDecision decisionFor(@NonNull NavigatorWindowFramePolicy.Result result) {
        return decisionFor(result, false);
    }

    @NonNull
    static VendorDecision decisionFor(@NonNull NavigatorWindowFramePolicy.Result result,
                                      boolean optimisticConfirmationPending) {
        return decisionFor(result, optimisticConfirmationPending, VendorDecision.NONE);
    }

    @NonNull
    static VendorDecision decisionFor(@NonNull NavigatorWindowFramePolicy.Result result,
                                      boolean optimisticConfirmationPending,
                                      @NonNull VendorDecision currentDecision) {
        switch (result.state) {
            case WINDOWED:
                return VendorDecision.WINDOWED;
            case FULLSCREEN:
                return VendorDecision.NOT_WINDOWED;
            case ABSENT:
                // AbsenceGate already required two strong inventory samples. During an explicit
                // startActivity grace it must not cancel the bounded optimistic hand-off, but in
                // every other state it clears stale lifecycle/a11y fallback even when this is the
                // first vendor decision of the process.
                return optimisticConfirmationPending
                        ? VendorDecision.NONE : VendorDecision.NOT_WINDOWED;
            case UNKNOWN:
            default:
                if (optimisticConfirmationPending) return VendorDecision.NONE;
                // A visible yet ambiguous Yandex frame is explicitly not enough to claim the
                // synthetic window surface. API failure has no candidate and yields fallback.
                return result.visibleCandidateCount > 0
                        ? VendorDecision.NOT_WINDOWED : VendorDecision.NONE;
        }
    }

    static boolean isLive(@NonNull VendorDecision decision,
                          long confirmedAtElapsed, long nowElapsed, long leaseMs) {
        if (decision == VendorDecision.NONE || confirmedAtElapsed < 0L || leaseMs < 0L) {
            return false;
        }
        long age = nowElapsed - confirmedAtElapsed;
        return age >= 0L && age <= leaseMs;
    }

    static boolean effectiveWindow(boolean fallbackWindow,
                                   @NonNull VendorDecision decision,
                                   long confirmedAtElapsed, long nowElapsed, long leaseMs) {
        if (!isLive(decision, confirmedAtElapsed, nowElapsed, leaseMs)) {
            return fallbackWindow;
        }
        return decision == VendorDecision.WINDOWED;
    }

    /** Once vendor geometry takes ownership, its predecessor optimistic assertion is consumed. */
    static boolean fallbackAfterVendorTakeover(boolean fallbackWindow,
                                               @NonNull VendorDecision decision) {
        return decision == VendorDecision.NONE && fallbackWindow;
    }

    static boolean fallbackAfterOptimisticExpiry(boolean fallbackWindow,
                                                 boolean optimisticPending) {
        return !optimisticPending && fallbackWindow;
    }
}
