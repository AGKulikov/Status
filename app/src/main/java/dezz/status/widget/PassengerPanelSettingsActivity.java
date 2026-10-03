/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

/** Uses the same editor with a separate persistent panel profile. */
public final class PassengerPanelSettingsActivity extends DriverPanelSettingsActivity {
    @Override protected boolean passengerPanel() { return true; }
}
