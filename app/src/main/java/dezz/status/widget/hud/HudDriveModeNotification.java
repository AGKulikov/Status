/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import dezz.status.widget.car.CarControlState;
import dezz.status.widget.drivemode.car.DriveModeCatalog;

/** Confirmed states only. Boot/reconnect seeds silently; repeated samples do not extend a toast. */
final class HudDriveModeNotification {
    static final String CONTROL_ID = "vehicle.drive_mode";
    static final long DISPLAY_MS = 3_000L;
    private int previous = -1;
    private int displayed = -1;
    private long visibleUntil;
    private long observedAt;

    boolean accept(CarControlState state, long now) {
        if (!CONTROL_ID.equals(state.controlId)) return false;
        if (state.observedAtMillis > 0 && state.observedAtMillis < observedAt) return false;
        observedAt = Math.max(observedAt, state.observedAtMillis);
        boolean valid = state.available && state.known && Double.isFinite(state.value)
                && state.value == (int) state.value && state.value != 255
                && DriveModeCatalog.byCode((int) state.value) != null;
        if (!valid) {
            boolean wasVisible = visibleCode(now) >= 0;
            previous = displayed = -1;
            visibleUntil = 0;
            return wasVisible;
        }
        int code = (int) state.value;
        boolean changed = previous >= 0 && previous != code;
        previous = code;
        if (changed) {
            displayed = code;
            visibleUntil = now + DISPLAY_MS;
        }
        return changed;
    }

    int visibleCode(long now) { return now < visibleUntil ? displayed : -1; }
    long remainingMillis(long now) { return Math.max(0L, visibleUntil - now); }
    void reset() { previous = displayed = -1; visibleUntil = observedAt = 0L; }
}
