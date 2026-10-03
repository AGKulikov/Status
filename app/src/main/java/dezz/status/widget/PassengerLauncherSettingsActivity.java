/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

/** Settings may be edited centrally; opening the canvas always targets the passenger screen. */
public final class PassengerLauncherSettingsActivity extends LauncherSettingsActivity {
    @Override public boolean isPassengerLauncherProfile() { return true; }
}
