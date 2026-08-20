/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.Locale;

import dezz.status.widget.launcher.LauncherIconResolver;

/** Offline-only icon allow-list. MQTT never supplies arbitrary paths, files or URLs. */
public final class PopupIconCatalog {
    private PopupIconCatalog() {}

    @DrawableRes
    public static int resolve(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        // The allow-list is still compiled into the APK: this never resolves a file, URI, URL,
        // resource name or server-provided path. Unknown persisted values remain rejected.
        return isAllowedId(id)
                ? LauncherIconResolver.resource(id) : 0;
    }

    public static boolean isAllowed(@NonNull String id) { return resolve(id) != 0; }

    /** Allocation-free allow check on the popup hot path. */
    public static boolean isAllowedId(@NonNull String id) {
        if (LauncherIconResolver.isKnownKey(id)) return true;
        switch (id) {
            case "hood_open":
            case "car_window":
            case "sunroof":
            case "mirror_fold":
            case "parking_sensor":
            case "child_lock":
                return true;
            default:
                return false;
        }
    }
}
