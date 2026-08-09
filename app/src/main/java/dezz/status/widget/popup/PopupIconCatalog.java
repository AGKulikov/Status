/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.launcher.LauncherIconResolver;

/** Offline-only icon allow-list. MQTT never supplies arbitrary paths, files or URLs. */
public final class PopupIconCatalog {
    private static final List<String> PERSISTED_ONLY_IDS = Collections.unmodifiableList(
            Arrays.asList("hood_open", "car_window", "sunroof", "mirror_fold",
                    "parking_sensor", "child_lock"));

    private PopupIconCatalog() {}

    public static final List<String> IDS = buildIds();

    /** Human labels in exactly the same order as {@link #IDS}. */
    public static final List<String> LABELS = buildLabels();

    @DrawableRes
    public static int resolve(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        // The allow-list is still compiled into the APK: this never resolves a file, URI, URL,
        // resource name or server-provided path. Unknown persisted values remain rejected.
        return IDS.contains(id) || PERSISTED_ONLY_IDS.contains(id)
                ? LauncherIconResolver.resource(id) : 0;
    }

    public static boolean isAllowed(@NonNull String id) { return resolve(id) != 0; }

    @NonNull private static List<String> buildIds() {
        List<String> values = new ArrayList<>();
        for (LauncherIconResolver.Preset preset : LauncherIconResolver.presets()) {
            values.add(preset.key);
        }
        return Collections.unmodifiableList(values);
    }

    @NonNull private static List<String> buildLabels() {
        List<String> values = new ArrayList<>();
        for (LauncherIconResolver.Preset preset : LauncherIconResolver.presets()) {
            values.add(preset.label);
        }
        return Collections.unmodifiableList(values);
    }
}
