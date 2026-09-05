/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/** Pure policy for keeping HOME content inside system and app-owned safe edges. */
public final class LauncherSafeAreaPolicy {
    private LauncherSafeAreaPolicy() {}

    /** Safe margins inside the outer HOME window. */
    public static final class Insets {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Insets(int left, int top, int right, int bottom) {
            this.left = Math.max(0, left);
            this.top = Math.max(0, top);
            this.right = Math.max(0, right);
            this.bottom = Math.max(0, bottom);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;
            if (!(other instanceof Insets)) return false;
            Insets value = (Insets) other;
            return left == value.left && top == value.top
                    && right == value.right && bottom == value.bottom;
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, top, right, bottom);
        }
    }

    /**
     * @param systemTopInset current top system-window inset, or zero in immersive mode
     * @param widgetEnabled saved master switch
     * @param statusBarMode true only for the full-width top-row widget mode
     * @param widgetRunning whether the overlay service currently owns a window
     * @param measuredWidgetHeight current measured overlay height in pixels
     */
    public static int topInset(int systemTopInset, boolean widgetEnabled,
                               boolean statusBarMode, boolean widgetRunning,
                               int measuredWidgetHeight) {
        int system = Math.max(0, systemTopInset);
        if (!widgetEnabled || !statusBarMode || !widgetRunning) return system;
        // Status Widget uses FLAG_LAYOUT_IN_SCREEN at absolute y=0. Its measured bottom and the
        // app content's system inset therefore describe overlapping top reservations, not two
        // stacked rows. Taking the larger boundary prevents both overlap and a duplicate blank
        // system-bar strip in non-immersive mode.
        return Math.max(system, Math.max(0, measuredWidgetHeight));
    }

    /**
     * Combines system bars, the app's top status row, and a HOME-only climate fallback.
     * Reservations on the same edge overlap physically, so the correct boundary is their maximum,
     * never their sum.
     */
    @NonNull
    public static Insets insets(int systemLeftInset, int systemTopInset,
                                int systemRightInset, int systemBottomInset,
                                boolean widgetEnabled, boolean statusBarMode,
                                boolean widgetRunning, int measuredWidgetHeight,
                                boolean localClimateReservation, int climateEdge,
                                int climateExtent) {
        int left = Math.max(0, systemLeftInset);
        int top = topInset(systemTopInset, widgetEnabled, statusBarMode,
                widgetRunning, measuredWidgetHeight);
        int right = Math.max(0, systemRightInset);
        int bottom = Math.max(0, systemBottomInset);
        if (!localClimateReservation) return new Insets(left, top, right, bottom);

        int extent = Math.max(0, climateExtent);
        switch (climateEdge) {
            case 1:
                top = Math.max(top, extent);
                break;
            case 2:
                left = Math.max(left, extent);
                break;
            case 3:
                right = Math.max(right, extent);
                break;
            case 0:
            default:
                bottom = Math.max(bottom, extent);
                break;
        }
        return new Insets(left, top, right, bottom);
    }

    /** Only this state is an app-local workaround, not a global WindowManager reservation. */
    public static boolean isLocalClimateReservation(@Nullable String runtimeStatus) {
        return "reserved_local".equals(runtimeStatus);
    }

    /** A local fallback affects only the HOME instance on the climate window's display. */
    public static boolean isLocalClimateReservation(@Nullable String runtimeStatus,
                                                    int climateDisplayId,
                                                    int launcherDisplayId) {
        return climateDisplayId == launcherDisplayId
                && isLocalClimateReservation(runtimeStatus);
    }
}
