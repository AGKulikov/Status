/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

/** Geometry observed in MonjaroPanel 3.2.1: passenger display, no driver rail inset. */
public final class PassengerPanelPlacement {
    public static final int DISPLAY_ID = 3;
    private PassengerPanelPlacement() {}
    public static int x(int screenWidth, int panelWidth, boolean right) {
        return right ? Math.max(0, screenWidth - panelWidth) : 0;
    }
}
