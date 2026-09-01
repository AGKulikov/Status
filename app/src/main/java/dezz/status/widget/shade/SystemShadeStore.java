/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import androidx.annotation.NonNull;

import dezz.status.widget.Preferences;

/** Small persistence boundary shared by runtime and the live editor. */
public final class SystemShadeStore {
    @NonNull private final Preferences preferences;

    public SystemShadeStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public boolean isEnabled() { return preferences.systemShadeEnabled.get(); }
    public void setEnabled(boolean enabled) { preferences.systemShadeEnabled.set(enabled); }
    public boolean isAutostart() { return preferences.systemShadeAutostart.get(); }
    public void setAutostart(boolean enabled) { preferences.systemShadeAutostart.set(enabled); }

    @NonNull public SystemShadeConfig load() {
        return SystemShadeConfig.fromJson(preferences.systemShadeConfigJson.get());
    }

    public void save(@NonNull SystemShadeConfig config) {
        preferences.systemShadeConfigJson.set(config.toJson());
    }
}
