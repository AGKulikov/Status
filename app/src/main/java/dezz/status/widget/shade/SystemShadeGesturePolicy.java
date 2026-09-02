/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

/** Pure gesture decisions kept separate from WindowManager and covered by local tests. */
public final class SystemShadeGesturePolicy {
    private SystemShadeGesturePolicy() {}

    public static float revealForDrag(boolean initiallyOpen, float deltaY, int panelHeight) {
        float height = Math.max(1, panelHeight);
        float start = initiallyOpen ? height : 0f;
        return clamp(start + deltaY, 0f, height);
    }

    public static boolean settleOpen(boolean initiallyOpen, float deltaY,
                                     float velocityY, int openThreshold,
                                     int closeThreshold) {
        if (velocityY >= 900f) return true;
        if (velocityY <= -900f) return false;
        if (initiallyOpen) return deltaY > -Math.max(1, closeThreshold);
        return deltaY >= Math.max(1, openThreshold);
    }

    public static boolean canOpen(boolean enabled, boolean screenInteractive,
                                  boolean vehicleOverlayActive) {
        return enabled && screenInteractive && !vehicleOverlayActive;
    }

    /**
     * The closed trigger window stays thin while a gesture is in flight. It becomes full-screen
     * only once the gesture has settled to open; an unaccepted or short cancelled gesture never
     * expands it.
     */
    public static boolean expandWindowBeforeSettle(boolean currentlyOpen, boolean targetOpen) {
        return !currentlyOpen && targetOpen;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
