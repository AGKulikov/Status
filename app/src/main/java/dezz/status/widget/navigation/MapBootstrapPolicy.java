/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

/** Reject only a uniform opaque-white bootstrap; never require tiles or content complexity. */
public final class MapBootstrapPolicy {
    public static final long SETTLE_MS = 200, MAX_WAIT_MS = 800;
    private MapBootstrapPolicy() {}
    public static boolean opaqueWhite(int[] pixels) {
        if (pixels == null || pixels.length == 0) return false;
        int white = 0;
        for (int p : pixels)
            if ((p >>> 24) >= 250 && ((p >>> 16) & 255) >= 248
                    && ((p >>> 8) & 255) >= 248 && (p & 255) >= 248) white++;
        return white * 100L >= pixels.length * 99L;
    }
    public static boolean mayReveal(long elapsed, int differentFrames, boolean copied, boolean white) {
        return elapsed >= MAX_WAIT_MS || (elapsed >= SETTLE_MS && differentFrames >= 2 && copied && !white);
    }
}
