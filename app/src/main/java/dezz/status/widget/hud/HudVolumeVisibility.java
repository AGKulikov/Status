/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** Initial sampling is silent; only actual volume-step changes open the transient HUD widget. */
final class HudVolumeVisibility {
    static final long DISPLAY_MS = 2_000L;
    private int lastSteps = -1;
    private long visibleUntil;

    boolean sample(int steps, long now) {
        if (steps < 0) { reset(); return false; }
        boolean changed = lastSteps >= 0 && lastSteps != steps;
        lastSteps = steps;
        if (changed) visibleUntil = now + DISPLAY_MS;
        return changed;
    }

    boolean visible(long now) { return visibleUntil > 0L && now < visibleUntil; }
    void reset() { lastSteps = -1; visibleUntil = 0L; }
}
