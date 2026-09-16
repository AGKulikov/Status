/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.Collections;

/** Durable write-ahead record. Hiding cannot begin until recovery information is committed. */
final class ServiceModeJournal {
    private final Context context;
    private final PackageManager packages;
    private final SharedPreferences original;
    private final AppsToHideStorage tracked;

    ServiceModeJournal(Context context) {
        this.context = context.getApplicationContext(); packages = context.getPackageManager();
        original = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("natro_service_mode_original", Context.MODE_PRIVATE);
        tracked = new AppsToHideStorage(context);
    }

    void validateTarget(String pkg, boolean disable) throws Exception {
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IOException("Недопустимое имя пакета");
        ApplicationInfo app = packages.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
        if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(app, context.getPackageName()))
            throw new IOException("Системное приложение или Natro исключено из сервисного режима");
        android.telecom.TelecomManager telecom = context.getSystemService(android.telecom.TelecomManager.class);
        if (disable && telecom != null && pkg.equals(telecom.getDefaultDialerPackage()))
            throw new IOException("Нельзя отключить звонилку для восстановления по PIN");
        if (disable && !app.enabled) throw new IOException("Приложение уже отключено; исходное состояние сохранено");
    }

    void beforeDisable(String pkg) throws Exception {
        int state = packages.getApplicationEnabledSetting(pkg);
        if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            throw new IOException("Приложение уже отключено");
        if (!original.edit().putInt(pkg, state).commit()) throw new IOException("Не сохранено исходное состояние");
        String label = packages.getApplicationInfo(pkg, 0).loadLabel(packages).toString();
        if (!tracked.save(Collections.singletonMap(pkg, label))) throw new IOException("Не сохранён список восстановления");
    }

    String command(String pkg, boolean disable) {
        if (disable) return "pm disable-user --user 0 " + pkg;
        int previous = original.getInt(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        return (previous == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? "pm default-state" : "pm enable")
                + " --user 0 " + pkg;
    }

    boolean hasBaseline(String pkg) { return original.contains(pkg); }

    boolean confirm(String pkg, boolean disable) {
        int actual = packages.getApplicationEnabledSetting(pkg);
        if (disable) return actual == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        int expected = original.getInt(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        if (actual != expected) return false;
        // This runs on the batch worker even when the settings Activity was destroyed.
        if (!tracked.removeAll(Collections.singleton(pkg))) return false;
        if (!original.edit().remove(pkg).commit()) {
            // Restore was real, but retain a discoverable recovery record on disk failure.
            tracked.save(Collections.singletonMap(pkg, pkg));
            return false;
        }
        if (!tracked.hasHiddenApps()) {
            packages.setComponentEnabledSetting(new ComponentName(context, LauncherTrampolineActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            KeepAliveService.stop(context);
        }
        return true;
    }
}
