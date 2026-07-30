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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    static final int TEST_NAVI_MODE_3_ONLY = 1;
    static final int TEST_OPCODE_8_ONLY = 2;
    static final int TEST_LAUNCH_WITHOUT_ARM = 3;
    static final int TEST_FULL_MNAVI_SEQUENCE = 4;

    private static final int CLUSTER_DISPLAY_ID = 2;
    private static final int NAVI_MODE_OFF = 1;
    private static final int NAVI_MODE_FULL = 3;
    private static final long CLEAN_BASELINE_DELAY_MS = 900L;
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
    private String expectedLaunchToken;
    private int preparedOperation = -1;
    private int preparedTestKind;
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

    void prepareTest(final int testKind, final Runnable ready) {
        if (!isSupportedTest(testKind)) {
            throw new IllegalArgumentException("неизвестный тест приборки: " + testKind);
        }
        final int operation = begin(testTitle(testKind));
        preparedOperation = operation;
        preparedTestKind = testKind;
        startEventReceived = false;
        expectedLaunchToken = null;
        ClusterProbeActivity.finishActive();
        append(display2Summary());
        append("Контекст запуска: " + accessibilityState());
        append("Навигаторы не запускаются и не останавливаются.");
        append("ПОДГОТОВКА ЧИСТОГО ЭТАЛОНА: NaviMode 1 + opcode 8=[0].");
        append(setDimState(NAVI_MODE_OFF, false));
        append("Команды выше относятся только к подготовке эталона. "
                + "После паузы будет отправлено ровно одно исследуемое воздействие.");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(operation)) {
                    return;
                }
                captureWindowState("ЧИСТЫЙ ЭТАЛОН ДО ТЕСТА", operation, ready);
            }
        }, CLEAN_BASELINE_DELAY_MS);
    }

    void executePreparedTest(int testKind) {
        final int operation = preparedOperation;
        if (!isCurrent(operation) || preparedTestKind != testKind) {
            append("ТЕСТ НЕ ЗАПУЩЕН: подготовленный эталон устарел.");
            return;
        }
        append("--- ИССЛЕДУЕМОЕ ВОЗДЕЙСТВИЕ ---");
        if (testKind == TEST_NAVI_MODE_3_ONLY) {
            append("A: отправляю ТОЛЬКО switchNaviMode(3). Opcode 8 не отправляется.");
            append(switchNaviModeOnly(NAVI_MODE_FULL));
            scheduleVerification(operation, false);
            return;
        }
        if (testKind == TEST_OPCODE_8_ONLY) {
            append("B: отправляю ТОЛЬКО DIM (2,8,8,[1]). NaviMode не меняется.");
            append(sendRawDimOnly(true));
            scheduleVerification(operation, false);
            return;
        }
        if (testKind == TEST_LAUNCH_WITHOUT_ARM) {
            append("C: запускаю Activity без switchNaviMode и без opcode 8.");
            launchProbe(operation);
            return;
        }
        append("D: полная последовательность mNavi: switchNaviMode(3) "
                + "+ DIM (2,8,8,[1]), затем запуск через 600 мс.");
        append(setDimState(NAVI_MODE_FULL, true));
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchProbe(operation);
            }
        }, LAUNCH_DELAY_MS);
    }

    void restore() {
        generation++;
        final int operation = generation;
        preparedOperation = -1;
        preparedTestKind = 0;
        expectedLaunchToken = null;
        main.removeCallbacksAndMessages(null);
        append("--- ЗАКРЫТИЕ ТЕСТА И ВОССТАНОВЛЕНИЕ DIM ---");
        ClusterProbeActivity.finishActive();
        append(setDimState(NAVI_MODE_OFF, false));
        append("Тестовая Activity закрыта; сторонние приложения не запускались и не останавливались.");
        captureWindowState("ПОСЛЕ ЗАКРЫТИЯ", operation, null);
    }

    boolean onProbeEvent(String event, int displayId, String state, String launchToken) {
        if (closed) {
            return false;
        }
        if (expectedLaunchToken == null || !expectedLaunchToken.equals(launchToken)) {
            append("ACTIVITY EVENT IGNORED · event=" + event
                    + " · token=" + (launchToken == null ? "нет" : launchToken)
                    + " · текущий тест не ожидал этот запуск.");
            return false;
        }
        if (ClusterProbeActivity.EVENT_STARTED.equals(event)
                || ClusterProbeActivity.EVENT_RESUMED.equals(event)
                || ClusterProbeActivity.EVENT_UPDATED.equals(event)) {
            startEventReceived |= displayId == CLUSTER_DISPLAY_ID;
        }
        append("ACTIVITY " + event + " · фактический displayId=" + displayId
                + (state == null || state.trim().isEmpty() ? "" : "\n" + state.trim()));
        if (ClusterProbeActivity.EVENT_STARTED.equals(event)) {
            append(displayId == CLUSTER_DISPLAY_ID
                    ? "РЕЗУЛЬТАТ API: УСПЕХ — Activity действительно создана на displayId=2."
                    : "РЕЗУЛЬТАТ API: ОШИБКА МАРШРУТИЗАЦИИ — система создала Activity на displayId="
                    + displayId + " вместо 2.");
        }
        return true;
    }

    void appendTelemetry(String value) {
        append("КРЫЛЬЯ · " + value);
    }

    void captureManualWindowState(boolean wingsVisible) {
        int operation = generation;
        append(wingsVisible
                ? "★★ РУЧНОЙ РЕЗУЛЬТАТ: НИЖНИЕ КРЫЛЬЯ ВИДНЫ."
                : "☆☆ РУЧНОЙ РЕЗУЛЬТАТ: НИЖНИХ КРЫЛЬЕВ НЕТ.");
        captureWindowState(
                wingsVisible
                        ? "РУЧНАЯ МЕТКА · КРЫЛЬЯ ЕСТЬ"
                        : "РУЧНАЯ МЕТКА · КРЫЛЬЕВ НЕТ",
                operation,
                null);
    }

    synchronized boolean hasExportableJournal() {
        return resolveJournalFile() != null;
    }

    synchronized String suggestedJournalFileName() {
        File source = resolveJournalFile();
        if (source != null) {
            return source.getName();
        }
        String stamp = new SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.ROOT).format(new Date());
        return "hudlab-cluster-" + stamp + ".txt";
    }

    synchronized void writeJournalTo(OutputStream output) throws IOException {
        if (output == null) {
            throw new IOException("системное хранилище не открыло выходной файл");
        }
        File source = resolveJournalFile();
        if (source == null) {
            throw new IOException("журнал приборки ещё не создан");
        }
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream input = new FileInputStream(source)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        output.flush();
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
        append("HUD Lab 0.32 · " + title);
        append(journalFile == null
                ? "ЖУРНАЛ ФАЙЛА: ERROR · " + journalError
                : "ЖУРНАЛ ФАЙЛА: " + journalFile.getAbsolutePath());
        append("Изолированная матрица DIM/NaviMode по разобранной последовательности mNavi 2.0.");
        return generation;
    }

    private void launchProbe(final int operation) {
        if (!isCurrent(operation)) {
            return;
        }
        if (!ClusterLaunchAccessibilityService.isConnected()) {
            append("LAUNCH НЕ ОТПРАВЛЕН: служба «HUD Lab · запуск на приборке» "
                    + "не подключена. Foreground fallback отключён, потому что в 0.31 "
                    + "его перехватывал PSD MessageDialog и создавал ложный результат.");
            scheduleVerification(operation, true);
            return;
        }
        String token = Long.toHexString(System.nanoTime());
        expectedLaunchToken = token;
        try {
            ClusterLaunchAccessibilityService.launchProbe(PROBE_DURATION_MS, token);
            append("LAUNCH REQUEST SENT · AccessibilityService "
                    + "(тот же тип Context, что у mNavi). Это ещё НЕ подтверждение запуска.");
            append(ClusterLaunchProtocol.describe());
        } catch (Throwable failure) {
            expectedLaunchToken = null;
            append("LAUNCH API: ERROR · " + describe(failure));
            scheduleVerification(operation, true);
            return;
        }
        scheduleVerification(operation, true);
    }

    private void scheduleVerification(final int operation, final boolean expectProbe) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isCurrent(operation)) {
                    Integer mode = readNaviMode();
                    append("READBACK +0.5с: NaviMode="
                            + (mode == null ? "ERROR" : mode));
                }
            }
        }, 500L);
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
                if (expectProbe && !startEventReceived) {
                    append("РЕЗУЛЬТАТ ЗАПУСКА: БЛОКИРОВАНО — "
                            + "onCreate/onResume Activity на displayId=2 не получен за 3 секунды.");
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
        return switchNaviModeOnly(naviMode) + " · " + sendRawDimOnly(clusterActive);
    }

    private String switchNaviModeOnly(int naviMode) {
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
        return result.toString();
    }

    private String sendRawDimOnly(boolean clusterActive) {
        StringBuilder result = new StringBuilder();
        Context context = ClusterLaunchAccessibilityService.activeContext(activity);
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

    private static boolean isSupportedTest(int testKind) {
        return testKind >= TEST_NAVI_MODE_3_ONLY
                && testKind <= TEST_FULL_MNAVI_SEQUENCE;
    }

    private static String testTitle(int testKind) {
        switch (testKind) {
            case TEST_NAVI_MODE_3_ONLY:
                return "A · ТОЛЬКО NAVIMODE 3";
            case TEST_OPCODE_8_ONLY:
                return "B · ТОЛЬКО OPCODE 8=[1]";
            case TEST_LAUNCH_WITHOUT_ARM:
                return "C · ЭКРАН БЕЗ ARM";
            case TEST_FULL_MNAVI_SEQUENCE:
                return "D · ПОЛНАЯ ПОСЛЕДОВАТЕЛЬНОСТЬ mNavi";
            default:
                return "НЕИЗВЕСТНЫЙ ТЕСТ";
        }
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
                : "НЕ ВКЛЮЧЕНА — тесты C/D не отправят запрос запуска";
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

    private synchronized void appendJournal(String value) {
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

    private File resolveJournalFile() {
        File current = journalFile;
        if (current != null && current.isFile() && current.length() > 0L) {
            return current;
        }
        File root = activity.getExternalFilesDir("cluster-traces");
        if (root == null || !root.isDirectory()) {
            root = new File(activity.getFilesDir(), "cluster-traces");
        }
        File[] candidates = root.listFiles();
        if (candidates == null) {
            return null;
        }
        File latest = null;
        for (File candidate : candidates) {
            if (!candidate.isFile()
                    || !candidate.getName().startsWith("hudlab-cluster-")
                    || !candidate.getName().endsWith(".txt")
                    || candidate.length() <= 0L) {
                continue;
            }
            if (latest == null || candidate.lastModified() > latest.lastModified()) {
                latest = candidate;
            }
        }
        return latest;
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
