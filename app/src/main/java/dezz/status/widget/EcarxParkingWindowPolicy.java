/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import androidx.annotation.Nullable;

import java.util.List;

/** Parking UI visibility, independent of whether the vehicle's distance sensors are enabled. */
final class EcarxParkingWindowPolicy {
    static final String PARKING_PACKAGE = "com.ecarx.parking";
    static final long ABSENCE_CONFIRMATION_MS = 180L;

    enum State { UNKNOWN, HIDDEN, VISIBLE }

    static final class WindowSample {
        final String packageName;
        final int displayId;
        final int type;
        final int visibility;
        @Nullable final NavigatorWindowFramePolicy.Frame frame;

        WindowSample(String packageName, int displayId, int type, int visibility,
                     @Nullable NavigatorWindowFramePolicy.Frame frame) {
            this.packageName = packageName;
            this.displayId = displayId;
            this.type = type;
            this.visibility = visibility;
            this.frame = frame;
        }
    }

    private EcarxParkingWindowPolicy() {}

    static State classify(@Nullable NavigatorWindowFramePolicy.Frame displayBounds,
                          int targetDisplayId, @Nullable List<WindowSample> windows) {
        if (displayBounds == null || !displayBounds.isValid() || windows == null) {
            return State.UNKNOWN;
        }
        boolean incomplete = false;
        for (WindowSample sample : windows) {
            if (sample == null || sample.packageName == null || sample.packageName.isEmpty()) {
                incomplete = true;
                continue;
            }
            // A shared system UID, identity substring or an unrelated overlay is not evidence.
            if (!PARKING_PACKAGE.equals(sample.packageName)) continue;
            if (sample.displayId < 0) {
                incomplete = true;
                continue;
            }
            if (sample.displayId != targetDisplayId) continue;
            if (sample.visibility == 4 || sample.visibility == 8) continue;
            if (sample.visibility != 0 || sample.type <= 0) {
                incomplete = true;
                continue;
            }
            // Do not hold the queue for a toast, keyboard or wallpaper from the same process.
            if (sample.type == 2005 || sample.type == 2011
                    || sample.type == 2012 || sample.type == 2013) continue;
            NavigatorWindowFramePolicy.Frame frame = sample.frame;
            if (frame == null) {
                incomplete = true;
                continue;
            }
            if (frame.isValid() && frame.left < displayBounds.right
                    && frame.right > displayBounds.left && frame.top < displayBounds.bottom
                    && frame.bottom > displayBounds.top) return State.VISIBLE;
        }
        return incomplete ? State.UNKNOWN : State.HIDDEN;
    }

    /** The KX11 adapter returns its previous array when its Binder call fails. */
    static final class FreshSnapshots {
        @Nullable private Object[] previous;

        boolean accept(@Nullable Object raw) {
            if (!(raw instanceof Object[]) || raw == previous) return false;
            previous = (Object[]) raw;
            return true;
        }
    }

    /** Main-thread state. Only confirmed visibility/closure changes the hold; no expiry fallback. */
    static final class VisibilityState {
        private boolean active;
        private long firstAbsenceAt = -1L;

        void observe(State state, long now) {
            switch (state) {
                case VISIBLE:
                    active = true;
                    firstAbsenceAt = -1L;
                    break;
                case HIDDEN:
                    if (!active) {
                        reset();
                    } else if (firstAbsenceAt < 0L) {
                        firstAbsenceAt = now;
                    } else if (now - firstAbsenceAt >= ABSENCE_CONFIRMATION_MS) {
                        reset();
                    }
                    break;
                case UNKNOWN:
                    firstAbsenceAt = -1L;
                    break;
            }
        }

        boolean isActive() { return active; }
        boolean needsAbsenceConfirmation() { return firstAbsenceAt >= 0L; }

        void reset() {
            active = false;
            firstAbsenceAt = -1L;
        }
    }
}
