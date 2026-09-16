/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.drivemode;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import dezz.status.widget.drivemode.car.DriveModeRepository;
import dezz.status.widget.drivemode.service.DriveModeOverlayService;
import dezz.status.widget.drivemode.settings.DriveModeSettings;

/** Natro-owned facade: does not replace the application's existing lifecycle. */
public final class MonjaroSelectorApp {
    private static MonjaroSelectorApp instance;
    private final Context context;
    private final DriveModeSettings settings;
    private MonjaroSelectorApp(Context context) {
        this.context = context.getApplicationContext(); settings = new DriveModeSettings(this.context);
    }
    public static synchronized MonjaroSelectorApp get(Context context) {
        if (instance == null) instance = new MonjaroSelectorApp(context);
        DriveModeRepository.get().init(context);
        return instance;
    }
    public DriveModeSettings getSettings() { return settings; }
    public void startServiceIfPermitted() {
        if (settings.isEnabled() && Settings.canDrawOverlays(context))
            context.startForegroundService(new Intent(context, DriveModeOverlayService.class));
    }
}
