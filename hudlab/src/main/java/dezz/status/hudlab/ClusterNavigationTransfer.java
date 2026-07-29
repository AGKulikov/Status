package dezz.status.hudlab;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reproduces the instrument-cluster transfer used by mNavi 2.0.
 *
 * <p>This is deliberately a one-shot diagnostic. It has no service, receiver, timer loop or
 * boot-time behavior. Every transfer is initiated by a visible button in HUD Lab.</p>
 */
final class ClusterNavigationTransfer {
    static final Target YANDEX_MAPS = new Target(
            "Яндекс Карты",
            "ru.yandex.yandexmaps",
            "ru.yandex.yandexmaps.SplashScreen");
    static final Target YANDEX_NAVI = new Target(
            "Яндекс Навигатор",
            "ru.yandex.yandexnavi",
            "ru.yandex.yandexnavi.core.NavigatorActivity");
    static final Target GOOGLE_MAPS = new Target(
            "Google Maps",
            "com.google.android.apps.maps",
            "com.google.android.apps.maps.MapsActivity");

    private static final int CENTER_DISPLAY_ID = 0;
    private static final int CLUSTER_DISPLAY_ID = 2;
    private static final int NAVI_MODE_OFF = 1;
    private static final int NAVI_MODE_FULL = 3;
    private static final long CLUSTER_LAUNCH_DELAY_MS = 600L;
    private static final long CENTER_LAUNCH_DELAY_MS = 200L;

    interface Listener {
        void onTraceChanged(String trace);
    }

    static final class Target {
        final String label;
        final String packageName;
        final String activityName;

        Target(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
        }
    }

    private final Activity activity;
    private final HudPrivilegedCommandRunner commands;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final StringBuilder trace = new StringBuilder();
    private int generation;
    private boolean closed;

    ClusterNavigationTransfer(
            Activity activity,
            HudPrivilegedCommandRunner commands,
            Listener listener) {
        this.activity = activity;
        this.commands = commands;
        this.listener = listener;
    }

    void moveToCluster(final Target target) {
        final int operation = begin(
                target.label + " → displayId=2",
                "1/5 force-stop выбранного приложения через локальный ADB");
        forceStop(target, operation, new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(operation)) {
                    return;
                }
                append("2/5 проверка текущего NaviMode и точный reset 1/[0], если он не равен 3");
                String reset = prepareDimForCluster();
                append(reset);
                append("3/5 switchNaviMode(3) + DIM (2,8,8,[1])");
                append(setDimState(NAVI_MODE_FULL, true));
                append("4/5 пауза 600 мс перед междисплейным запуском");
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isCurrent(operation)) {
                            return;
                        }
                        launch(target, CLUSTER_DISPLAY_ID);
                        append("5/5 ActivityOptions.setLaunchDisplayId(2) отправлен");
                        captureWindowState(target, operation, "ПОСЛЕ ЗАПУСКА");
                    }
                }, CLUSTER_LAUNCH_DELAY_MS);
            }
        });
    }

    void restoreToCenter(final Target target) {
        final int operation = begin(
                target.label + " → displayId=0",
                "1/4 force-stop выбранного приложения через локальный ADB");
        forceStop(target, operation, new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(operation)) {
                    return;
                }
                append("2/4 switchNaviMode(1) + DIM (2,8,8,[0])");
                append(setDimState(NAVI_MODE_OFF, false));
                append("3/4 пауза 200 мс перед возвратом на центральный экран");
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isCurrent(operation)) {
                            return;
                        }
                        launch(target, CENTER_DISPLAY_ID);
                        append("4/4 displayId=0 + windowingMode=5 отправлен");
                        captureWindowState(target, operation, "ПОСЛЕ ВОЗВРАТА");
                    }
                }, CENTER_LAUNCH_DELAY_MS);
            }
        });
    }

    void restoreDimOnly() {
        int operation = begin(
                "Восстановление навигационного слоя приборки",
                "switchNaviMode(1) + DIM (2,8,8,[0])");
        append(setDimState(NAVI_MODE_OFF, false));
        append("Готово. Приложения не запускались и не останавливались.");
        captureWindowState(null, operation, "DIM RESTORE");
    }

    void close() {
        closed = true;
        generation++;
        main.removeCallbacksAndMessages(null);
    }

    private int begin(String title, String firstStep) {
        generation++;
        trace.setLength(0);
        append("mNavi exact test · " + title);
        append(firstStep);
        return generation;
    }

    private void forceStop(
            final Target target,
            final int operation,
            final Runnable continuation) {
        String packageName = target.packageName;
        String command = "for N in 1 2 3 4; do "
                + "su 0 am force-stop --user 0 " + packageName
                + " >/dev/null 2>&1 || am force-stop --user 0 " + packageName
                + " >/dev/null 2>&1; sleep 0.1; done; "
                + "echo FORCE_STOP_OK:" + packageName;
        commands.runTrusted(command, new HudPrivilegedCommandRunner.Callback() {
            @Override
            public void onFinished(String output, String error) {
                if (!isCurrent(operation)) {
                    return;
                }
                if (error != null) {
                    append("FORCE-STOP ERROR · " + error);
                    append("Цепочка остановлена: без локального ADB это уже не точный путь mNavi.");
                    return;
                }
                append(output == null || output.trim().isEmpty()
                        ? "FORCE-STOP OK"
                        : output.trim());
                continuation.run();
            }
        });
    }

    private String prepareDimForCluster() {
        try {
            DimMenuInteraction menu = new DimMenuInteraction(activity);
            int current = menu.getNaviMode();
            if (current == NAVI_MODE_FULL) {
                return "NaviMode уже 3: предварительный reset не требуется";
            }
            return "NaviMode=" + current + " · " + setDimState(NAVI_MODE_OFF, false);
        } catch (Throwable failure) {
            return "NaviMode read ERROR · " + describe(failure)
                    + "\nПробую продолжить без условного reset.";
        }
    }

    private String setDimState(int naviMode, boolean clusterActive) {
        StringBuilder result = new StringBuilder();
        try {
            // mNavi explicitly initializes this class before constructing DimMenuInteraction.
            Class.forName("com.ecarx.xui.adaptapi.car.Car");
        } catch (Throwable failure) {
            result.append("Car init WARN · ").append(describe(failure)).append(" · ");
        }
        try {
            DimMenuInteraction menu = new DimMenuInteraction(activity);
            boolean accepted = menu.switchNaviMode(naviMode);
            result.append("switchNaviMode(")
                    .append(naviMode)
                    .append(")=")
                    .append(accepted);
        } catch (Throwable failure) {
            result.append("switchNaviMode ERROR · ").append(describe(failure));
        }
        result.append(" · ");
        try {
            Class<?> managerClass = Class.forName("ecarx.dimprotocol.DIMProtocolManager");
            Method getInstance = managerClass.getMethod("getInstance", Context.class);
            Object manager = getInstance.invoke(null, activity);
            Method send = managerClass.getMethod(
                    "sendMessageToDIM",
                    byte.class,
                    byte.class,
                    byte.class,
                    byte[].class);
            byte[] payload = new byte[]{(byte) (clusterActive ? 1 : 0)};
            send.invoke(manager, (byte) 2, (byte) 8, (byte) 8, payload);
            result.append("DIM(2,8,8,[")
                    .append(clusterActive ? 1 : 0)
                    .append("])=OK");
        } catch (Throwable failure) {
            result.append("raw DIM ERROR · ").append(describe(failure));
        }
        return result.toString();
    }

    private void launch(Target target, int displayId) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN, Uri.parse(""));
            intent.setClassName(target.packageName, target.activityName);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            Bundle bundle = options.toBundle();
            if (displayId != CLUSTER_DISPLAY_ID && bundle != null) {
                bundle.putInt("android.activity.SplitScreenShownPosition", 0);
                bundle.putInt("android.activity.windowingMode", 5);
            }
            activity.startActivity(intent, bundle);
        } catch (Throwable failure) {
            append("LAUNCH ERROR · " + describe(failure));
        }
    }

    private void captureWindowState(
            final Target target,
            final int operation,
            final String phase) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(operation)) {
                    return;
                }
                String packagePattern = target == null
                        ? "com.auto_soft.monjaro_dashboard"
                        : target.packageName + "|com.auto_soft.monjaro_dashboard";
                String command = "echo '--- WINDOW ---'; "
                        + "dumpsys window windows | grep -E 'mDisplayId|isVisible|"
                        + packagePattern
                        + "' | tail -n 120; "
                        + "echo '--- ACTIVITY ---'; "
                        + "dumpsys activity activities | grep -E 'mResumedActivity|displayId=|"
                        + packagePattern
                        + "' | tail -n 120";
                commands.runTrusted(command, new HudPrivilegedCommandRunner.Callback() {
                    @Override
                    public void onFinished(String output, String error) {
                        if (!isCurrent(operation)) {
                            return;
                        }
                        if (error != null) {
                            append(phase + " TRACE ERROR · " + error);
                            return;
                        }
                        String value = output == null ? "" : output.trim();
                        append(phase + ":\n" + (value.isEmpty() ? "совпадений нет" : value));
                    }
                });
            }
        }, 900L);
    }

    private boolean isCurrent(int operation) {
        return !closed && operation == generation;
    }

    private void append(String line) {
        if (trace.length() > 0) {
            trace.append('\n');
        }
        trace.append(line);
        listener.onTraceChanged(trace.toString());
    }

    private static String describe(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty()
                ? ""
                : " · " + message.trim());
    }
}
