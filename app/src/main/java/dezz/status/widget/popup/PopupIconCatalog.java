/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.R;

/** Offline-only icon allow-list. MQTT never supplies arbitrary paths, files or URLs. */
public final class PopupIconCatalog {
    private PopupIconCatalog() {}

    public static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            "gate", "garage", "light", "lock", "power", "temperature", "water", "door",
            "wifi", "gps", "bluetooth"));

    @DrawableRes
    public static int resolve(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (id) {
            case "gate": return R.drawable.ic_popup_gate;
            case "garage": return R.drawable.ic_popup_garage;
            case "light": return R.drawable.ic_popup_light;
            case "lock": return R.drawable.ic_popup_lock;
            case "power": return R.drawable.ic_popup_power;
            case "temperature": return R.drawable.ic_popup_temperature;
            case "water": return R.drawable.ic_popup_water;
            case "door": return R.drawable.ic_popup_door;
            case "wifi": return R.drawable.ic_status_filled_wifi_internet;
            case "gps": return R.drawable.ic_status_filled_gps_good;
            case "bluetooth": return R.drawable.ic_status_filled_bt_connected;
            default: return 0;
        }
    }

    public static boolean isAllowed(@NonNull String id) { return resolve(id) != 0; }
}
