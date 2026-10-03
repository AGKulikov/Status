/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** Future HOME keys are isolated by default; device-wide player/radio policy stays shared. */
public final class PassengerLauncherProfile {
    public static final String PREFIX = "passengerLauncher.";
    private PassengerLauncherProfile() {}

    public static String storageKey(String key) {
        if (!key.startsWith("launcher")) return key;
        switch (key) {
            case "launcherMediaAutoResumeEnabled":
            case "launcherMediaAutoResumeDelaySeconds":
            case "launcherMediaFixedPlayerEnabled":
            case "launcherMediaFixedPlayerPackage":
            case "launcherHideSystemStatusBar":
            case "launcherSystemStatusBarOriginalPolicy":
                return key;
            default:
                return PREFIX + key;
        }
    }
}
