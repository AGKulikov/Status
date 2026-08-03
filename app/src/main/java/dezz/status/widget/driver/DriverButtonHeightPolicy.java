/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

/** Keeps explicit driver-button heights identical in the live rail and scaled preview. */
public final class DriverButtonHeightPolicy {
    private DriverButtonHeightPolicy() {
    }

    public static boolean isExplicit(int configuredHeightPx) {
        return configuredHeightPx > 0;
    }

    public static int resolvedHeight(int measuredHeightPx, int configuredHeightPx, float scale) {
        return isExplicit(configuredHeightPx)
                ? Math.max(1, Math.round(configuredHeightPx * Math.max(.01f, scale)))
                : Math.max(1, measuredHeightPx);
    }

    /** A fixed button must not absorb free rail height through an untouched auto side. */
    public static int spacingRequest(int configuredHeightPx, int requestedPaddingPx) {
        return isExplicit(configuredHeightPx) ? 0 : requestedPaddingPx;
    }

    /** Explicit top/bottom values stay inside a fixed-height button instead of enlarging it. */
    public static int internalPadding(int configuredHeightPx, int requestedPaddingPx,
                                      int distributedPaddingPx, float scale,
                                      int resolvedHeightPx) {
        if (!isExplicit(configuredHeightPx)) return Math.max(0, distributedPaddingPx);
        if (requestedPaddingPx < 0) return 0;
        return Math.min(Math.max(0, resolvedHeightPx / 2),
                Math.max(0, Math.round(requestedPaddingPx * Math.max(.01f, scale))));
    }
}
