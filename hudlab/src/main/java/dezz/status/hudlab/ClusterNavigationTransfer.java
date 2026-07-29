package dezz.status.hudlab;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;

import com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Launches HUD Lab's own marker Activity on the instrument-cluster display.
 *
 * <p>The transition deliberately mirrors the relevant mNavi 2.0 path. No navigation
 * application is started or stopped. The four force-stops in mNavi only clear the selected
 * third-party navigation task; HUD Lab instead closes its own previous marker Activity.</p>
 */
final class ClusterNavigationTransfer {
    private static final int CLUSTER_DISPLAY_ID = 2;
    private static final int NAVI_MODE_OFF = 1;
    private static final int NAVI_MODE_FULL = 3;
    private static final int MAX_RESET_POLLS = 4;
    private static final long RESET_POLL_MS = 400L;
    private static final long ARM_DELAY_MS = 500L;
    private static final long LAUNCH_DELAY_MS = 600L;
    private static final long PROBE_DURATION_MS = 30_000L;

    interface Listener {
        void onTraceChanged(String trace);
    }

    private final Activity activity;
    private final HudPrivilegedCommandRunner commands;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final StringBuilder trace = new StringBuilder();
    private int generation;
    private boolean closed;
    private boolean startEventReceived;
    private File journalFile;
    private String journalError;

    ClusterNavigationTransfer(
            Activity activity,
            HudPrivilegedCommandRunner commands,
            Listener listener) {
        this.activity = activity;
        this.commands = commands;
        this.listener = listener;
    }

    void showIdleStatus() {
        if (trace.length() != 0) {
            append("Служба запуска: " + accessibilityState());
            return;
        }
        append("ТЕСТ СОБСТВЕННОГО ЭКРАНА · ещё не запускался");
        append("Цель: Android displayId=2");
        append("Служба запуска: " + accessibilityState());
    }

    void openAccessibilitySettings() {
        append("Открываю «Специальные возможности». Включите «HUD Lab · запуск на приборке», затем вернитесь.");
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            activity.startActivity(intent);
        } catch (Throwable failure) {
            append("ACCESSIBILITY SETTINGS ERROR · " + describe(failure));
        }
    }

    void moveOwnScreenToCluster() {
        final int operation = begin("СОБСТВЕННЫЙ ЭКРАН → DISPLAY 2");
        startEventReceived = false;
        ClusterProbeActivity.finishActive();
        append(display2Summary());
        append("Контекст запуска: " + accessibilityState());
        append("До запуска закрывается только прежняя тестовая Activity HUD Lab; навигаторы не затрагиваются.");
        captureWindowState("ДО", operation, new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(operation)) {
                    return;
                }
                pollResetIfNeeded(operation, 1);
            }
        });
    }

    void restore() {
        generation++;
        final int operation = generation;
        append("--- ЗАКРЫТИЕ ТЕСТА И ВОССТАНОВЛЕНИЕ DIM ---");
        ClusterProbeActivity.finishActive();
        append(setDimState(NAVI_MODE_OFF, false));
        append("Тестовая Activity закрыта; сторонние приложения не запускались и не останавливались.");
        captureWindowState("ПОСЛЕ ЗАКРЫТИЯ", operation, null);
    }

    void onProbeEvent(String event, int displayId, String state) {
        if (closed) {
            return;
        }
        if (ClusterProbeActivity.EVENT_STARTED.equals(event)
                || ClusterProbeActivity.EVENT_RESUMED.equals(event)
                || ClusterProbeActivity.EVENT_UPDATED.equals(event)) {
            startEventReceived = true;
        }
        append("ACTIVITY " + event + " · фактический displayId=" + displayId
                + (state == null || state.trim().isEmpty() ? "" : "\n" + state.trim()));
        if (ClusterProbeActivity.EVENT_STARTED.equals(event)) {
            append(displayId == CLUSTER_DISPLAY_ID
                    ? "РЕЗУЛЬТАТ API: УСПЕХ — Activity действительно создана на displayId=2."
                    : "РЕЗУЛЬТАТ API: ОШИБКА МАРШРУТИЗАЦИИ — система создала Activity на displayId="
                    + displayId + " вместо 2.");
        }
    }

    void appendTelemetry(String value) {
        append("КРЫЛЬЯ · " + value);
    }

    void captureManualWindowState() {
        int operation = generation;
        append("★★ РУЧНАЯ МЕТКА: пользователь подтвердил появление нижних крыльев.");
        captureWindowState("РУЧНАЯ МЕТКА КРЫЛЬЕВ", operation, null);
    }

    void close() {
        closed = true;
        generation++;
        main.removeCallbacksAndMessages(null);
    }

    private int begin(String title) {
        generation++;
        trace.setLength(0);
        startJournal();
        append("HUD Lab 0.30 · " + title);
        append(journalFile == null
                ? "ЖУРНАЛ ФАЙЛА: ERROR · " + journalError
                : "ЖУРНАЛ ФАЙЛА: " + journalFile.getAbsolutePath());
        append("Разобранная последовательность mNavi 2.0 без запуска навигатора.");
        return generation;
    }

    /**
     * mNavi resets 3 -> 1/[0] only when getNaviMode() is already 3. Its original loop
     * checks the readback up to four times at 400 ms intervals, then waits 500 ms before
     * arming mode 3 again.
     */
    private void pollResetIfNeeded(final int operation, final int attempt) {
        if (!isCurrent(operation)) {
            return;
        }
        Integer current = readNaviMode();
        append("NaviMode poll " + attempt + "/" + MAX_RESET_POLLS + " = "
                + (current == null ? "ERROR" : current));
        if (current != null && current == NAVI_MODE_FULL) {
            append("Режим уже 3: отправляю импульсный reset 1 + DIM [0], как mNavi.");
            append(setDimState(NAVI_MODE_OFF, false));
            if (attempt < MAX_RESET_POLLS) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pollResetIfNeeded(operation, attempt + 1);
                    }
                }, RESET_POLL_MS);
                return;
            }
        } else {
            append("Предварительный reset не нужен: mNavi сразу переходит к включению режима 3.");
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                armAndLaunch(operation);
            }
        }, ARM_DELAY_MS);
    }

    private void armAndLaunch(final int operation) {
        if (!isCurrent(operation)) {
            return;
        }
        append("ARM: switchNaviMode(3) + DIM (2,8,8,[1])");
        append(setDimState(NAVI_MODE_FULL, true));
        append("Пауза 600 мс перед запуском — точное значение из mNavi.");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchProbe(operation);
            }
        }, LAUNCH_DELAY_MS);
    }

    private void launchProbe(final int operation) {
        if (!isCurrent(operation)) {
            return;
        }
        String token = Long.toHexString(System.nanoTime());
        try {
            String route;
            if (ClusterLaunchAccessibilityService.isConnected()) {
                ClusterLaunchAccessibilityService.launchProbe(PROBE_DURATION_MS, token);
                route = "AccessibilityService (тот же тип Context, что у mNavi)";
            } else {
                ClusterLaunchProtocol.start(activity, PROBE_DURATION_MS, token);
                route = "foreground Activity fallback; служба HUD Lab не включена";
            }
            append("LAUNCH API: OK · " + route);
            append(ClusterLaunchProtocol.describe());
        } catch (Throwable failure) {
            append("LAUNCH API: ERROR · " + describe(failure));
            launchViaAdbFallback(operation, token);
            return;
        }
        scheduleVerification(operation);
    }

    private void launchViaAdbFallback(final int operation, String token) {
        String component = activity.getPackageName() + "/" + ClusterProbeActivity.class.getName();
        String command = "am start --user 0 --display 2 --windowingMode 5"
                + " -n " + component
                + " --el duration_ms " + PROBE_DURATION_MS
                + " --es launch_token " + token;
        append("CONTROL ADB: " + command);
        commands.runTrusted(command, new HudPrivilegedCommandRunner.Callback() {
            @Override
            public void onFinished(String output, String error) {
                if (!isCurrent(operation)) {
                    return;
                }
                append(error == null
                        ? "CONTROL ADB RESULT:\n" + trim(output)
                        : "CONTROL ADB ERROR · " + error);
                scheduleVerification(operation);
            }
        });
    }

    private void scheduleVerification(final int operation) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isCurrent(operation)) {
                    captureWindowState("ПОСЛЕ +1.2с", operation, null);
                }
            }
        }, 1200L);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(operation)) {
                    return;
                }
                if (!startEventReceived) {
                    append("РЕЗУЛЬТАТ API: onCreate/onResume тестовой Activity не получен за 3 секунды.");
                }
                captureWindowState("ПОСЛЕ +3.0с", operation, null);
            }
        }, 3000L);
    }

    private Integer readNaviMode() {
        try {
            Class.forName("com.ecarx.xui.adaptapi.car.Car");
            Context context = ClusterLaunchAccessibilityService.activeContext(activity);
            return new DimMenuInteraction(context).getNaviMode();
        } catch (Throwable failure) {
            append("getNaviMode ERROR · " + describe(failure));
            return null;
        }
    }

    private String setDimState(int naviMode, boolean clusterActive) {
        StringBuilder result = new StringBuilder();
        Context context = ClusterLaunchAccessibilityService.activeContext(activity);
        try {
            Class.forName("com.ecarx.xui.adaptapi.car.Car");
        } catch (Throwable failure) {
            result.append("Car init WARN · ").append(describe(failure)).append(" · ");
        }
        try {
            DimMenuInteraction menu = new DimMenuInteraction(context);
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
            Method getInstance = managerClass.getMethod("getInstance", android.content.Context.class);
            Object manager = getInstance.invoke(null, context);
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

    private void captureWindowState(
            final String phase,
            final int operation,
            final Runnable continuation) {
        String packageName = activity.getPackageName();
        String command = "echo '--- DISPLAY 2 ---'; "
                + "dumpsys display | grep -E -A3 -B2 'DisplayDeviceInfo|displayId 2|mDisplayId=2|local:2' "
                + "| tail -n 120; "
                + "echo '--- WINDOW DISPLAY CONTENT 2 ---'; "
                + "dumpsys window displays | grep -Ei -A80 -B5 "
                + "'mDisplayId=2|displayId=2|DisplayContent[^0-9]*2' | head -n 600; "
                + "echo '--- WINDOW LAYERS / FRAMES ---'; "
                + "dumpsys window windows | grep -Ei -A18 -B6 '"
                + packageName
                + "|ClusterProbeActivity|mDisplayId=2|displayId=2|cluster|dashboard' "
                + "| head -n 700; "
                + "echo '--- ACTIVITY TASKS ---'; "
                + "dumpsys activity activities | grep -Ei -A18 -B6 '"
                + packageName
                + "|ClusterProbeActivity|displayId=2|mDisplayId=2|cluster|dashboard' "
                + "| head -n 700; "
                + "echo '--- SURFACEFLINGER RELATED STATE ---'; "
                + "dumpsys SurfaceFlinger | grep -Ei -A10 -B6 '"
                + packageName
                + "|ClusterProbeActivity|cluster|dashboard|instrument|speed|rpm|range|fuel|navi|dim' "
                + "| head -n 900";
        commands.runTrusted(command, new HudPrivilegedCommandRunner.Callback() {
            @Override
            public void onFinished(String output, String error) {
                if (!isCurrent(operation)) {
                    return;
                }
                append(error == null
                        ? phase + " DUMPSYS:\n" + trim(output)
                        : phase + " DUMPSYS ERROR · " + error);
                if (continuation != null) {
                    continuation.run();
                }
            }
        });
    }

    private String display2Summary() {
        try {
            DisplayManager manager = (DisplayManager) activity.getSystemService("display");
            Display display = manager == null ? null : manager.getDisplay(CLUSTER_DISPLAY_ID);
            if (display == null) {
                return "DISPLAY 2: НЕ НАЙДЕН в Android DisplayManager.";
            }
            return "DISPLAY 2: " + display.getName()
                    + " · valid=" + display.isValid()
                    + " · state=" + display.getState()
                    + " · flags=0x" + Integer.toHexString(display.getFlags());
        } catch (Throwable failure) {
            return "DISPLAY 2 CHECK ERROR · " + describe(failure);
        }
    }

    private String accessibilityState() {
        boolean connected = ClusterLaunchAccessibilityService.isConnected();
        boolean enabled = false;
        try {
            String setting = Settings.Secure.getString(
                    activity.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String expected = new ComponentName(
                    activity,
                    ClusterLaunchAccessibilityService.class).flattenToString();
            if (setting != null) {
                for (String item : setting.split(":")) {
                    if (expected.equalsIgnoreCase(item)) {
                        enabled = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (connected) {
            return "ПОДКЛЮЧЕНА";
        }
        return enabled
                ? "включена в настройках, но Context ещё не подключён"
                : "НЕ ВКЛЮЧЕНА — будет использован foreground fallback";
    }

    private boolean isCurrent(int operation) {
        return !closed && operation == generation;
    }

    private void append(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        if (trace.length() > 0) {
            trace.append('\n');
        }
        trace.append(line);
        appendJournal(line);
        listener.onTraceChanged(trace.toString());
    }

    private void startJournal() {
        journalFile = null;
        journalError = null;
        try {
            File root = activity.getExternalFilesDir("cluster-traces");
            if (root == null) {
                root = new File(activity.getFilesDir(), "cluster-traces");
            }
            if (!root.exists() && !root.mkdirs()) {
                throw new IllegalStateException("не удалось создать " + root);
            }
            String stamp = new SimpleDateFormat(
                    "yyyyMMdd-HHmmss",
                    Locale.ROOT).format(new Date());
            File target = new File(root, "hudlab-cluster-" + stamp + ".txt");
            if (!target.createNewFile()) {
                target = new File(
                        root,
                        "hudlab-cluster-" + stamp + "-" + generation + ".txt");
                if (!target.createNewFile()) {
                    throw new IllegalStateException("имя журнала уже занято");
                }
            }
            journalFile = target;
        } catch (Throwable failure) {
            journalError = describe(failure);
        }
    }

    private void appendJournal(String value) {
        File target = journalFile;
        if (target == null) {
            return;
        }
        String timestamp = new SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.ROOT).format(new Date());
        String record = "[" + timestamp + "] " + value + "\n";
        try (FileOutputStream output = new FileOutputStream(target, true)) {
            output.write(record.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Throwable failure) {
            journalError = describe(failure);
            journalFile = null;
        }
    }

    private static String trim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "нет совпадений";
        }
        String trimmed = value.trim();
        int limit = 36_000;
        return trimmed.length() <= limit
                ? trimmed
                : trimmed.substring(0, limit / 2)
                + "\n…середина дампа обрезана; сохранены начало и конец…\n"
                + trimmed.substring(trimmed.length() - (limit / 2));
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
