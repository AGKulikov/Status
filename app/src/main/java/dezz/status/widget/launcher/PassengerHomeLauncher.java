/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Toast;

import dezz.status.widget.PassengerLauncherActivity;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Requests the independent passenger overlay; external applications remain display-scoped. */
public final class PassengerHomeLauncher {
    public static final int DISPLAY_ID = 3;
    private PassengerHomeLauncher() {}

    public static boolean available(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(DISPLAY_ID);
        return display != null && display.isValid() && display.getState() != Display.STATE_OFF;
    }

    public static Bundle options(Context context, Bundle original) {
        if (!available(context)) throw new IllegalStateException("Passenger display unavailable");
        Bundle result = original == null ? new Bundle() : new Bundle(original);
        result.putAll(ActivityOptions.makeBasic().setLaunchDisplayId(DISPLAY_ID).toBundle());
        return result;
    }

    public static boolean isHomeIntent(Intent intent) {
        android.content.ComponentName component=intent.getComponent();
        return component!=null && (component.getClassName().equals(dezz.status.widget.LauncherActivity.class.getName())
                || component.getClassName().equals(PassengerLauncherActivity.class.getName()))
                || Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME);
    }
    public static boolean isOwnActivity(Context context,Intent intent) {
        android.content.ComponentName component=intent.getComponent();
        return component!=null && context.getPackageName().equals(component.getPackageName())
                || context.getPackageName().equals(intent.getPackage());
    }
    public static Intent profileIntent(Context context,Intent source) {
        Intent target=new Intent(source);
        if(isOwnActivity(context,target)) {
            if(target.getComponent()!=null && target.getComponent().getClassName()
                    .equals(dezz.status.widget.LauncherSettingsActivity.class.getName()))
                target.setClass(context,dezz.status.widget.PassengerLauncherSettingsActivity.class);
            target.putExtra(dezz.status.widget.LauncherProfileActivity.EXTRA_PASSENGER_PROFILE,true);
        }
        return target;
    }
    /** Editors belong on MAIN; the transparent Package Installer result owner stays on PASS. */
    public static int targetDisplay(Context context, Intent intent) {
        android.content.ComponentName component = intent.getComponent();
        boolean uninstallProxy = component != null
                && AppUninstallProxyActivity.class.getName().equals(component.getClassName());
        return isOwnActivity(context, intent) && !uninstallProxy ? 0 : DISPLAY_ID;
    }
    public static Bundle targetOptions(Context context,Bundle original,int displayId) {
        if(displayId==DISPLAY_ID)return options(context,original);
        if(displayId!=0)throw new IllegalArgumentException("Unexpected HOME display");
        Bundle result=original==null?new Bundle():new Bundle(original);
        result.putAll(ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        return result;
    }
    public static boolean open(Context context) {return open(context,new Intent(Intent.ACTION_MAIN));}
    public static boolean open(Context context,Intent request) {
        Context app=context.getApplicationContext();
        try {
            androidx.core.content.ContextCompat.startForegroundService(app,
                    new Intent(app,PassengerHomeService.class).putExtra(PassengerHomeService.EXTRA_REQUEST,new Intent(request)));
            DiagnosticJournal.infoAsync("passenger-home","overlay_requested display=3; attachment=pending");
            return true;
        } catch(RuntimeException error) {reportFailure(context,error);return false;}
    }

    public static void reportFailure(Context context, RuntimeException error) {
        DiagnosticJournal.warn("passenger-home", "launch_failed=" + error.getClass().getSimpleName());
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                "Не удалось открыть окно пассажира. Проверьте, включён ли экран.",
                Toast.LENGTH_LONG).show());
    }
}
