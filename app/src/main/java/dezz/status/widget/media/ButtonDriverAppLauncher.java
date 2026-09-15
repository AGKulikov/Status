/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.app.ActivityOptions;
import android.content.*;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import dezz.status.widget.instrument.InstrumentDisplayLauncher;
import dezz.status.widget.instrument.InstrumentPanelActivity;
import dezz.status.widget.shell.PrivilegedShell;

/** DIM 3 -> 100 ms -> wake (2,8,8,{1}) -> 200 ms -> reset task -> display 2. */
final class ButtonDriverAppLauncher {
    private final Context context;
    private final Handler worker;
    private final Consumer<String> error;
    private final LinkedHashSet<String> tracked = new LinkedHashSet<>();
    private ContentObserver observer;
    private String active;
    private long generation;
    private boolean transition;
    ButtonDriverAppLauncher(Context context, Handler worker, Consumer<String> error) {
        this.context = context; this.worker = worker; this.error = error;
    }
    void toggle(ComponentName component) { worker.post(() -> {
        // Natro's own instrument activity has durable launch authorization; never force-stop its host.
        if (context.getPackageName().equals(component.getPackageName())
                && InstrumentPanelActivity.class.getName().equals(component.getClassName())) {
            InstrumentDisplayLauncher.launch(context); return;
        }
        long owner = ++generation;
        String pkg = component.getPackageName();
        boolean dedicated = component.getClassName().contains(".Driver");
        if (!dedicated && tracked.remove(pkg)) {
            boolean wasActive = pkg.equals(active);
            if (wasActive) active = null;
            transition = true;
            Runnable close = () -> stop(pkg, owner, 0, () -> { transition = false; removeObserverIfEmpty(); });
            if (wasActive) mode(1, owner, close, close); else close.run();
            return;
        }
        tracked.add(pkg);
        try { observe(); } catch (RuntimeException unavailable) { fail(pkg); return; }
        transition = true;
        Runnable launch = () -> stop(pkg, owner, 0, () -> worker.postDelayed(() -> {
            if (owner != generation) return;
            try {
                Intent intent = new Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (dedicated) context.startActivity(intent);
                else {
                    intent.setAction(Intent.ACTION_MAIN);
                    Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(2).toBundle();
                    options.putInt("android.activity.windowingMode", 5);
                    options.putInt("android.activity.SplitScreenShownPosition", 0);
                    context.startActivity(intent, options);
                }
                active = pkg; transition = false;
                if ("com.google.android.apps.maps".equals(pkg)) {
                    worker.postDelayed(() -> googleMaps(owner), 4000);
                    worker.postDelayed(() -> googleMaps(owner), 5000);
                }
            } catch (RuntimeException failed) { fail(pkg); }
        }, 50));
        if (dedicated || readMode() == 3) launch.run(); else mode(3, owner, launch, () -> forget(pkg));
    }); }
    private void mode(int mode, long owner, Runnable next, Runnable failedTransition) {
        try {
            Object menu = menu();
            if (menu == null) throw new IllegalStateException("DIM unavailable");
            Object accepted = menu.getClass().getMethod("switchNaviMode", int.class).invoke(menu, mode);
            if (Boolean.FALSE.equals(accepted)) throw new IllegalStateException("DIM rejected");
        } catch (Exception failed) { failedTransition.run(); transition = false; error.accept("Не удалось переключить дисплей водителя"); return; }
        worker.postDelayed(() -> {
            if (owner != generation) return;
            try {
                Class<?> type = Class.forName("ecarx.dimprotocol.DIMProtocolManager");
                Object manager = type.getMethod("getInstance", Context.class).invoke(null, context);
                type.getMethod("sendMessageToDIM", byte.class, byte.class, byte.class, byte[].class)
                        .invoke(manager, (byte) 2, (byte) 8, (byte) 8, new byte[]{1});
            } catch (Exception failed) { failedTransition.run(); transition = false; error.accept("Пробуждение дисплея водителя недоступно"); return; }
            worker.postDelayed(() -> { if (owner == generation) next.run(); }, 200);
        }, 100);
    }
    private void stop(String pkg, long owner, int attempt, Runnable next) {
        if (owner != generation) return;
        if (pkg.equals(context.getPackageName())) { next.run(); return; }
        if (attempt == 3) { next.run(); return; }
        PrivilegedShell.get(context).runCommand("am force-stop --user 0 " + MediaButtonController.quote(pkg),
                (output, failure) -> worker.post(() -> {
                    if (owner != generation) return;
                    if (failure != null) { fail(pkg); return; }
                    worker.postDelayed(() -> stop(pkg, owner, attempt + 1, next), 50);
                }));
    }
    private Object menu() throws Exception {
        Class<?> type = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
        Object dim = type.getMethod("create", Context.class).invoke(null, context);
        return dim == null ? null : type.getMethod("getDimMenuInteraction").invoke(dim);
    }
    private int readMode() {
        try { Object menu = menu(); return ((Number) menu.getClass().getMethod("getNaviMode").invoke(menu)).intValue(); }
        catch (Exception ignored) { return -1; }
    }
    private void observe() {
        if (observer != null) return;
        observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) {
                if (transition || Settings.Global.getInt(context.getContentResolver(), "NaviMode", 3) == 3) return;
                ++generation;
                for (String pkg : tracked) if (!pkg.equals(context.getPackageName()))
                    PrivilegedShell.get(context).runCommand("am force-stop --user 0 "
                            + MediaButtonController.quote(pkg), (output, failure) -> {});
                tracked.clear(); active = null; removeObserverIfEmpty();
            }
        };
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor("NaviMode"), false, observer);
    }
    private void removeObserverIfEmpty() {
        if (tracked.isEmpty() && observer != null) {
            context.getContentResolver().unregisterContentObserver(observer); observer = null;
        }
    }
    private void fail(String pkg) {
        forget(pkg);
        error.accept("Не удалось открыть приложение на дисплее водителя");
    }
    private void forget(String pkg) {
        transition = false; tracked.remove(pkg);
        if (pkg.equals(active)) active = null;
        removeObserverIfEmpty();
    }
    private void googleMaps(long owner) {
        if (owner != generation || !"com.google.android.apps.maps".equals(active)) return;
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:/?free=1&mode=d&entry=fnls"))
                .setClassName("com.google.android.apps.maps", "com.google.android.maps.MapsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException failed) { error.accept("Режим карты Google недоступен"); }
    }
}
