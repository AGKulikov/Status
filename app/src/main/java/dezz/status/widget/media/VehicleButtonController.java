/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.shell.PrivilegedShell;

/** One owner for live InputImpl events. Independent of the optional diagnostic recorder. */
public final class VehicleButtonController implements ButtonGestureEngine.Bindings {
    private static VehicleButtonController instance;
    public static synchronized VehicleButtonController get(Context context) {
        if (instance == null) instance = new VehicleButtonController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main, input;
    private final ThreadPoolExecutor actions = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(24), r -> new Thread(r, "natro-button-actions"));
    private final ButtonGestureEngine engine;
    private SharedPreferences storage;
    private volatile Map<String, ?> values = Collections.emptyMap();
    private volatile boolean ready, driveMenu;
    private final java.util.concurrent.atomic.AtomicInteger pendingInputs = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong inputEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean inputResetPending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String status = "Загрузка настроек…";
    private volatile long generation, logGeneration;
    private volatile Process logProcess;
    private boolean listening, permissionPending;
    private final java.util.Set<VehicleButton> patching = java.util.EnumSet.noneOf(VehicleButton.class);
    private final java.util.Map<VehicleButton, Boolean> verifiedDefault = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<VehicleButton, String> defaultDetails = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile ButtonActionExecutor executor;
    private final BroadcastReceiver driveReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            driveMenu = intent != null && intent.getBooleanExtra("is_showing", false);
        }
    };
    private VehicleButtonController(Context context) {
        this.context = context;
        main = new Handler(context.getMainLooper());
        HandlerThread thread = new HandlerThread("natro-button-input"); thread.start();
        input = new Handler(thread.getLooper());
        engine = new ButtonGestureEngine(new ButtonGestureEngine.Scheduler() {
            @Override public Object after(long ms, Runnable r) {
                long epoch = inputEpoch.get();
                Runnable guarded = () -> { if (epoch == inputEpoch.get()) r.run(); else engine.reset(); };
                input.postDelayed(guarded, ms); return guarded;
            }
            @Override public void cancel(Object token) { input.removeCallbacks((Runnable) token); }
        }, this, new ButtonGestureEngine.Output() {
            @Override public void action(String group, String gesture) {
                ButtonBinding binding = binding(group, gesture);
                submit(() -> actionExecutor().execute(binding), group + ":" + gesture + ":" + binding.action.id);
            }
            @Override public void stockSrc() {
                submit(() -> context.sendBroadcast(new Intent("ecarx.intent.action.ECARX_KEY_RSRC_EVENT")
                        .setFlags(0x11000000).addCategory(Intent.CATEGORY_DEFAULT)
                        .putExtra("ecarx.extra.ECARX_KEY_EVENT_TYPE", 210004)), "src:stock");
            }
            @Override public void volume(int direction) {
                int steps = integer("volume_steps", 1);
                submit(() -> {
                    AudioManager audio = context.getSystemService(AudioManager.class);
                    if (audio != null) for (int i = 0; i < steps; i++) audio.adjustVolume(direction, 1);
                }, "dm:volume");
            }
            @Override public void driveMenu(int direction) {
                if (DriveSelectorController.isShowing()) {
                    DriveSelectorController.request(context, direction < 0 ? -1 : 1);
                    return;
                }
                submit(() -> context.sendBroadcast(new Intent("dezz.monjaro.drive_modes."
                        + (direction < 0 ? "PREV_1" : "NEXT_1")).addFlags(0x01000020)), "dm:menu");
            }
            @Override public void starHeld() { submit(() -> actionExecutor().starHeld(), "star:10s"); }
        });
        input.post(() -> {
            storage = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("vehicle_buttons", Context.MODE_PRIVATE);
            values = new HashMap<>(storage.getAll()); ready = true;
            IntentFilter filter = new IntentFilter("dezz.monjaro.drive_modes.ISSHOWING");
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(driveReceiver, filter,
                    null, input, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(driveReceiver, filter, null, input);
            status = "Настройки загружены";
            reconcile();
            if (needed() || bool("temperature.visible") || bool("drive.restore")) submit(() -> actionExecutor(), null);
            if (bool(VehicleButton.STAR.key + ".disable_default")) setDisableDefault(VehicleButton.STAR, true, null);
        });
    }
    private ButtonActionExecutor actionExecutor() {
        if (executor == null) executor = new ButtonActionExecutor(context, this);
        return executor;
    }
    void whenInputReady(Runnable callback) { input.post(callback); }
    public void whenReady(Runnable callback) { input.post(() -> main.post(callback)); }
    public boolean ready() { return ready; }
    public String status() { return status; }
    @Override public boolean enabled(VehicleButton button) { return bool(button.key + ".enabled"); }
    public boolean disabledDefault(VehicleButton button) { return Boolean.TRUE.equals(verifiedDefault.get(button)); }
    public String defaultStatus(VehicleButton button) {
        String detail = defaultDetails.get(button);
        if (detail != null) return detail;
        return bool(button.key + ".disable_default")
                ? "Запрос отключения сохранён; применение ещё не подтверждено"
                : "Штатный путь; состояние обработчика ещё не проверено";
    }
    @Override public boolean knobVolume() { return bool("knob.volume"); }
    @Override public boolean musicActive() {
        // Event-time predicate, only for a DM release. Never poll AudioManager or call it on MAIN.
        AudioManager audio = context.getSystemService(AudioManager.class);
        try { return audio != null && audio.isMusicActive(); }
        catch (RuntimeException unavailable) { return false; }
    }
    @Override public boolean driveMenuShowing() { return DriveSelectorController.isShowing() || driveMenu; }
    @Override public boolean stockMediaSource() { return MediaButtonController.get(context).isDefaultSource(); }
    @Override public int actionId(String group, String gesture) { return binding(group, gesture).action.id; }
    public int volumeSteps() { return integer("volume_steps", 1); }
    public boolean bool(String key) { return Boolean.TRUE.equals(values.get(key)); }
    /** Optional accessibility gestures reuse the same original Natro action dispatcher. */
    public void executeAdbGesture(ButtonAction action) {
        submit(() -> actionExecutor().execute(new ButtonBinding(action.id, "", "", "", "")), "adb-gesture");
    }
    public void callNumber(String number) {
        if (number != null) submit(() -> actionExecutor().callNumber(number), "phone");
    }
    public int integer(String key, int fallback) {
        Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback;
    }
    public String string(String key) { Object value = values.get(key); return value instanceof String ? (String) value : ""; }
    public ButtonBinding binding(String group, String gesture) {
        String key = "binding." + group + "." + gesture + ".";
        return new ButtonBinding(integer(key + "action", 0), string(key + "app"),
                string(key + "command"), string(key + "package"), string(key + "shortcut"));
    }
    public void setEnabled(VehicleButton button, boolean enabled) { put(button.key + ".enabled", enabled); }
    public void setVolume(boolean enabled, int steps) {
        input.post(() -> save(true, "knob.volume", enabled,
                "volume_steps", Math.max(1, Math.min(20, steps))));
    }
    public void put(String key, Object value) {
        input.post(() -> {
            save(!(key.equals("drive.selected") || key.startsWith("temperature.")), key, value);
        });
    }
    public void saveBinding(String group, String gesture, ButtonBinding binding, Runnable done) {
        input.post(() -> {
            String key = "binding." + group + "." + gesture + ".";
            save(true, key + "action", binding.action.id, key + "app", binding.application,
                    key + "command", binding.command, key + "package", binding.packageName,
                    key + "shortcut", binding.shortcutJson);
            if (done != null) main.post(done);
        });
    }
    public java.util.List<ButtonBinding> driverMenuBindings() {
        java.util.List<ButtonBinding> result = new java.util.ArrayList<>();
        for (VehicleButton button : VehicleButton.values()) {
            if (button == VehicleButton.MEDIA || !enabled(button)) continue;
            String[] groups = button == VehicleButton.DM ? new String[]{"knobLeft", "knobRight"} : new String[]{button.key};
            for (String group : groups) for (String gesture : new String[]{"1", "2", "3", "long"}) {
                if (gesture.equals("long") && !button.longPress) continue;
                ButtonBinding binding = binding(group, gesture);
                if (binding.action == ButtonAction.DRIVER_MENU) result.add(binding);
            }
        }
        return result;
    }
    private void save(boolean settingsChanged, Object... pairs) {
        Map<String, Object> next = new HashMap<>(values);
        Map<String, Object> changed = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i]; Object value = pairs[i + 1];
            if (!java.util.Objects.equals(next.get(key), value)) { next.put(key, value); changed.put(key, value); }
        }
        if (changed.isEmpty()) return;
        values = Collections.unmodifiableMap(next);
        RuntimePreferenceWriter.put(storage, changed);
        if (!settingsChanged) return;
        generation++; engine.reset(); reconcile();
        if (executor != null || needed() || bool("drive.restore")) submit(() -> actionExecutor().settingsChanged(), null);
    }
    private boolean needed() {
        for (VehicleButton button : VehicleButton.values())
            if (button != VehicleButton.MEDIA && enabled(button)) return true;
        return knobVolume();
    }
    private void reconcile() {
        if (!needed()) {
            logGeneration++; listening = false; engine.reset();
            Process process = logProcess; logProcess = null; if (process != null) process.destroy();
            status = "Обработка дополнительных кнопок выключена"; return;
        }
        if (context.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            if (permissionPending) return;
            permissionPending = true; status = "Подключение кнопок…";
            PrivilegedShell.get(context).runCommand("pm grant " + MediaButtonController.quote(context.getPackageName())
                    + " android.permission.READ_LOGS", (output, error) -> input.post(() -> {
                        permissionPending = false;
                        if (context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED)
                            reconcile();
                        else status = "Не удалось подключить кнопки: проверьте встроенный ADB";
                    }));
            return;
        }
        if (!listening) {
            listening = true; long owner = ++logGeneration;
            new Thread(() -> readKeys(owner), "natro-button-log").start();
        }
    }
    private static final Pattern KEY = Pattern.compile("\\bkeyCode=([0-9]+)\\b");
    private static final Pattern LOG = Pattern.compile("^\\s*([0-9]+\\.[0-9]+)\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+InputImpl\\s*:\\s*(.*)$");
    private void readKeys(long owner) {
        Process process = null;
        final double started = System.currentTimeMillis() / 1000d;
        try {
            process = new ProcessBuilder("logcat", "-b", "main", "-v", "epoch", "-T", "1",
                    "InputImpl:V", "*:S").redirectErrorStream(true).start();
            if (owner != logGeneration) return;
            logProcess = process; status = "Кнопки подключены";
            DiagnosticJournal.infoAsync("vehicle-buttons", "input_subscription_ready uptime_ms=" + SystemClock.uptimeMillis());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (owner == logGeneration && (line = reader.readLine()) != null) {
                    if (line.length() > 2048) continue;
                    Matcher log = LOG.matcher(line);
                    if (!log.matches()) continue;
                    double eventSeconds = Double.parseDouble(log.group(1));
                    if (eventSeconds < started) continue;
                    String body = log.group(2);
                    boolean down = body.contains("onKeyPressed"), up = body.contains("onKeyReleased");
                    if (down == up) continue;
                    Matcher key = KEY.matcher(body);
                    if (!key.find()) continue;
                    int code = Integer.parseInt(key.group(1));
                    if (VehicleButton.fromCode(code) == null) continue;
                    if (System.currentTimeMillis() - eventSeconds * 1000 > 750) { invalidateInput(); continue; }
                    long received = SystemClock.uptimeMillis();
                    long epoch = inputEpoch.get();
                    if (pendingInputs.incrementAndGet() > 64) {
                        pendingInputs.decrementAndGet();
                        invalidateInput();
                        status = "Слишком много событий кнопок";
                        continue;
                    }
                    input.post(() -> {
                        pendingInputs.decrementAndGet();
                        if (owner != logGeneration) return;
                        if (epoch != inputEpoch.get()) return;
                        if (SystemClock.uptimeMillis() - received > 750) { invalidateInput(); return; }
                        engine.input(code, down);
                        DiagnosticJournal.infoAsync("vehicle-buttons", "code=" + code + ", down=" + down);
                    });
                }
            }
        } catch (Exception failed) {
            if (owner == logGeneration) status = "События кнопок недоступны: " + failed.getClass().getSimpleName();
        } finally {
            if (process != null) process.destroy();
            input.post(() -> {
                if (owner != logGeneration) return;
                logProcess = null; listening = false; engine.reset();
                status = "Ожидание подключения кнопок";
                input.postDelayed(() -> { if (owner == logGeneration) reconcile(); }, 5000);
            });
        }
    }
    private void invalidateInput() {
        inputEpoch.incrementAndGet();
        if (inputResetPending.compareAndSet(false, true)) input.postAtFrontOfQueue(() -> {
            engine.reset(); inputResetPending.set(false);
        });
    }
    private void submit(Runnable action, String detail) {
        long owner = generation, received = SystemClock.uptimeMillis();
        long epoch = inputEpoch.get();
        try { actions.execute(() -> {
            if (owner != generation || (detail != null && SystemClock.uptimeMillis() - received > 750)) return;
            if (detail != null && epoch != inputEpoch.get()) return;
            try {
                if (detail == null) action.run();
                else ButtonActionDeadline.run(received + 750L,
                        () -> owner == generation && epoch == inputEpoch.get(), action);
                if (detail != null) DiagnosticJournal.infoAsync("vehicle-buttons", "dispatch=" + detail + ", effect=unobserved");
            } catch (Exception failed) {
                status = "Действие недоступно: " + failed.getClass().getSimpleName();
                DiagnosticJournal.warn("vehicle-buttons", "dispatch_failed=" + detail + ", reason=" + failed.getClass().getSimpleName());
            }
        }); } catch (java.util.concurrent.RejectedExecutionException full) {
            status = "Очередь действий занята";
        }
    }
    void beginStoredRestore(java.util.List<VehicleButton> buttons, Runnable ready) {
        input.post(() -> {
            buttons.removeIf(b -> b != VehicleButton.MEDIA
                    && (patching.contains(b) || !bool(b.key + ".disable_default")));
            for (VehicleButton b : buttons) if (b != VehicleButton.MEDIA) {
                patching.add(b); defaultDetails.put(b, "Восстановление штатного пути…");
            }
            ready.run();
        });
    }
    void finishStoredRestore(java.util.List<VehicleButton> buttons, String output, String error) {
        input.post(() -> {
            for (VehicleButton b : buttons) if (b != VehicleButton.MEDIA) {
                patching.remove(b);
                boolean ok = error == null && output != null && !output.contains("NATRO_MEDIA_ERROR=")
                        && output.contains("NATRO_MEDIA_ROUTE=" + b.name() + ":disabled");
                if (ok) verifiedDefault.put(b, true); else verifiedDefault.remove(b);
                String detail = ok ? "Маршрут записан и проверен; физический эффект ещё не подтверждён"
                        : "Штатный путь недоступен: " + (error != null ? error
                        : field(output, "NATRO_BUTTON_ERROR=" + b.name() + ":"));
                defaultDetails.put(b, detail);
                DiagnosticJournal.infoAsync("vehicle-buttons", "restore_route button=" + b
                        + ", applied=" + ok + ", detail=" + detail);
            }
        });
    }

    public interface Result { void finished(boolean success, String detail); }
    public void setDisableDefault(VehicleButton button, boolean disabled, Result callback) {
        input.post(() -> {
            if (!patching.add(button)) { result(callback, false, "Изменение уже выполняется"); return; }
            defaultDetails.put(button, "Проверка и применение штатного пути…");
            Result finish = (success, detail) -> input.post(() -> {
                patching.remove(button);
                String resolved = detail == null || detail.trim().isEmpty()
                        ? (success ? "Настройка ★ подтверждена ECARX; проверьте физическое нажатие"
                                : "Применение штатного пути не подтверждено") : detail;
                if (success) {
                    verifiedDefault.put(button, disabled);
                    save(false, button.key + ".disable_default", disabled);
                } else verifiedDefault.remove(button);
                defaultDetails.put(button, resolved);
                DiagnosticJournal.infoAsync("vehicle-buttons", "default_route button=" + button.name()
                        + ", requested_disabled=" + disabled + ", applied=" + success
                        + ", physical_effect=unobserved, detail=" + resolved);
                status = resolved; result(callback, success, resolved);
            });
            if (button == VehicleButton.STAR) {
                try { actions.execute(() -> {
                    try { actionExecutor().setStarDefaultDisabled(disabled, finish); }
                    catch (Exception failed) { finish.finished(false, "Не удалось изменить штатное действие ★"); }
                }); } catch (java.util.concurrent.RejectedExecutionException full) { finish.finished(false, "Очередь действий занята"); }
                return;
            }
            String command = "CLASSPATH=" + MediaButtonController.quote(context.getApplicationInfo().sourceDir)
                    + " app_process /system/bin dezz.status.widget.media.MediaInputPatchMain "
                    + (disabled ? "on " : "off ") + button.name();
            PrivilegedShell.get(context).runCommand(MediaButtonController.rootCommand(command), (output, error) -> {
                String expected = "NATRO_MEDIA_ROUTE=" + button.name() + ":" + (disabled ? "disabled" : "stock");
                boolean success = error == null && output != null && output.contains(expected)
                        && !output.contains("NATRO_MEDIA_ERROR");
                String coverage = field(output, "NATRO_MEDIA_COVERAGE=");
                String restarted = field(output, "NATRO_MEDIA_RESTARTED=");
                finish.finished(success, success ? (disabled ? "Путь отключения записан и проверен" : "Штатный путь записан и проверен")
                        + "; варианты " + coverage + "; сигналов перезапуска XSF: " + restarted
                        + ". Проверьте физическое нажатие."
                        : "Применение не подтверждено: " + shortFailure(error, output));
            });
        });
    }
    private void result(Result callback, boolean success, String detail) {
        if (callback != null) main.post(() -> callback.finished(success, detail));
    }

    private static String field(String output, String prefix) {
        if (output != null) for (String line : output.split("[\\r\\n]+"))
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        return "не подтверждено";
    }
    private static String shortFailure(String error, String output) {
        String value = error == null ? field(output, "NATRO_MEDIA_ERROR=") : error;
        return value.length() > 180 ? value.substring(0, 180) : value;
    }
}
