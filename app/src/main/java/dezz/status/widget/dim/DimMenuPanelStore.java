/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import androidx.annotation.NonNull;

import dezz.status.widget.Preferences;

/** Persistence facade shared by settings, boot recovery and the foreground owner. */
public final class DimMenuPanelStore {
    @NonNull private final Preferences preferences;

    public DimMenuPanelStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public boolean isEnabled() { return preferences.dimMenuPanelEnabled.get(); }
    public void setEnabled(boolean value) { preferences.dimMenuPanelEnabled.set(value); }
    public boolean isAutostart() { return preferences.dimMenuPanelAutostart.get(); }
    public void setAutostart(boolean value) { preferences.dimMenuPanelAutostart.set(value); }

    @NonNull public DimMenuPanelConfig load() {
        return DimMenuPanelConfig.fromJson(preferences.dimMenuPanelConfigJson.get());
    }

    public void save(@NonNull DimMenuPanelConfig value) {
        preferences.dimMenuPanelConfigJson.set(value.toJson());
    }
}
