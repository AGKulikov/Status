/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.Collections;

/** Self hiding never disables the package, SettingsHubActivity, PIN receiver or recovery service. */
final class NatroSelfVisibility {
    static final String LABEL = "Natro — значок и HOME";
    private static final String[] ENTRY_POINTS = {
            "dezz.status.widget.servicemode.LauncherTrampolineActivity",
            "dezz.status.widget.LauncherActivity"
    };
    private final Context context;
    private final PackageManager packages;
    private final SharedPreferences baseline;
    private final AppsToHideStorage tracked;

    NatroSelfVisibility(Context context) {
        this.context = context.getApplicationContext();
        packages = context.getPackageManager();
        baseline = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("natro_service_mode_self", Context.MODE_PRIVATE);
        tracked = new AppsToHideStorage(context);
    }

    boolean hasBaseline() { return baseline.contains("recorded"); }
    boolean hasExplicitHistory() { return baseline.contains("explicit_history"); }

    boolean apply(boolean hide) throws IOException {
        synchronized (NatroSelfVisibility.class) { return applyLocked(hide); }
    }

    private boolean applyLocked(boolean hide) throws IOException {
        if (hide && !hasBaseline()) {
            SharedPreferences.Editor edit = baseline.edit();
            for (String name : ENTRY_POINTS) edit.putInt(name,
                    packages.getComponentEnabledSetting(new ComponentName(context, name)));
            if (!edit.putBoolean("recorded", true).putBoolean("explicit_history", true).commit())
                throw new IOException("Не сохранён план восстановления Natro");
        }
        if (hide && !tracked.save(Collections.singletonMap(context.getPackageName(), LABEL)))
            throw new IOException("Не сохранён список восстановления Natro");
        if (!hasBaseline()) return !hide;
        for (String name : ENTRY_POINTS) {
            ComponentName component = new ComponentName(context, name);
            int wanted = hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : baseline.getInt(name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            packages.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP);
            if (packages.getComponentEnabledSetting(component) != wanted)
                throw new IOException("Состояние входа Natro не подтверждено");
        }
        if (!hide) {
            if (!tracked.removeAll(Collections.singleton(context.getPackageName())))
                throw new IOException("Не сохранено восстановление Natro");
            SharedPreferences.Editor edit = baseline.edit().remove("recorded");
            for (String name : ENTRY_POINTS) edit.remove(name);
            // Legacy cleanup must not overwrite the exact restored component states.
            if (!edit.commit()) {
                tracked.save(Collections.singletonMap(context.getPackageName(), LABEL));
                throw new IOException("Не завершён журнал восстановления Natro");
            }
            if (!tracked.hasHiddenApps()) KeepAliveService.stop(context);
        }
        return true;
    }
}
