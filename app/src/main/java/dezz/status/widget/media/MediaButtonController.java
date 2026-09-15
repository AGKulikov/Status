/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.shell.PrivilegedShell;

/** Optional MConfig-compatible MEDIA adapter. The passive journal never owns this controller. */
public final class MediaButtonController {
    private static MediaButtonController instance;
    public static synchronized MediaButtonController get(Context context) {
        if (instance == null) instance = new MediaButtonController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final MediaKeyPolicy policy = new MediaKeyPolicy();
    private final ThreadPoolExecutor commands = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> new Thread(task, "natro-media-command"));
    private HandlerThread inputThread;
    private boolean registered;
    private volatile boolean enabled;
    private volatile boolean defaultSource;
    private volatile boolean isolated;
    private volatile long generation;
    private boolean changingRoute;
    private volatile String status = "Штатный путь кнопок";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { receive(intent, () -> {}); }
    };

    void receive(Intent intent, Runnable finished) {
        if (intent == null) { finished.run(); return; }
        final KeyEvent event;
        try { event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT); }
        catch (RuntimeException malformed) { finished.run(); return; }
        if (event == null || (event.getAction() != KeyEvent.ACTION_DOWN
                && event.getAction() != KeyEvent.ACTION_UP)) { finished.run(); return; }
        MediaKeyPolicy.Route route = MediaKeyPolicy.route(intent.getAction(), enabled, defaultSource);
        if (route == MediaKeyPolicy.Route.IGNORE || !policy.admit(event.getKeyCode(),
                event.getAction(), event.getRepeatCount(), event.getDownTime(),
                event.getEventTime(), event.getDeviceId(), event.getScanCode(), event.getSource())) {
            finished.run(); return;
        }
        final long received = SystemClock.uptimeMillis();
        final long owner = generation;
        try {
            commands.execute(() -> {
                long started = SystemClock.uptimeMillis();
                try {
                    if (owner != generation || started - received > 750L) {
                        record(event, "discarded_stale", received, started);
                        return;
                    }
                    if (route == MediaKeyPolicy.Route.AUDIO_MANAGER) {
                        AudioManager audio = context.getSystemService(AudioManager.class);
                        if (audio == null) throw new IllegalStateException("AudioManager unavailable");
                        audio.dispatchMediaKeyEvent(event);
                    } else {
                        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON)
                                .addFlags(0x01000020).addCategory(Intent.CATEGORY_DEFAULT)
                                .putExtra(Intent.EXTRA_KEY_EVENT, event));
                    }
                    record(event, route.name(), received, started);
                } catch (RuntimeException failed) {
                    record(event, "failed_" + failed.getClass().getSimpleName(), received, started);
                } finally { finished.run(); }
            });
        } catch (RejectedExecutionException full) {
            record(event, "queue_full", received, received);
            finished.run();
        }
    }

    private MediaButtonController(Context context) {
        this.context = context;
        preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("media_buttons", Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
        defaultSource = preferences.getBoolean("default_source", false);
        isolated = preferences.getBoolean("disable_default", false);
        reconcileReceiver();
    }

    /** Invoked by the ordinary startup owner, never by a physical-key callback. */
    public void restoreStored() {
        if (isolated) setDisableDefault(true, (success, detail) -> {});
    }

    public boolean isEnabled() { return enabled; }
    public boolean isDefaultSource() { return defaultSource; }
    public boolean isDisableDefault() { return isolated; }
    public String status() { return status; }

    public synchronized void setEnabled(boolean value) {
        enabled = value;
        generation++;
        commands.getQueue().clear();
        preferences.edit().putBoolean("enabled", value).apply();
        reconcileReceiver();
    }

    public synchronized void setDefaultSource(boolean value) {
        defaultSource = value;
        generation++;
        commands.getQueue().clear();
        preferences.edit().putBoolean("default_source", value).apply();
    }

    public interface Result { void done(boolean success, String detail); }

    public synchronized void setDisableDefault(boolean value, Result callback) {
        if (changingRoute) { callback.done(false, "Изменение пути кнопок уже выполняется"); return; }
        changingRoute = true;
        // Register before the input service restarts, including bridge-only/off mode.
        ensureReceiver();
        status = "Применение пути кнопок…";
        String command = "su 0 sh -c " + quote("CLASSPATH=" + quote(context.getApplicationInfo().sourceDir)
                + " app_process /system/bin " + MediaInputPatchMain.class.getName()
                + (value ? " on" : " off"));
        PrivilegedShell.get(context).runCommand(command, (output, error) -> {
            synchronized (MediaButtonController.this) {
                changingRoute = false;
                String expected = "NATRO_MEDIA_ROUTE="
                        + (value ? MediaKeyPolicy.NATRO : MediaKeyPolicy.STOCK);
                boolean success = error == null && output != null && output.contains(expected)
                        && !output.contains("NATRO_MEDIA_ERROR=");
                if (success) {
                    isolated = value;
                    preferences.edit().putBoolean("disable_default", value).apply();
                    status = value ? "Кнопки передаются в Natro" : "Штатный путь восстановлен";
                } else {
                    status = "Не удалось изменить путь кнопок: " + shortError(error, output);
                }
                reconcileReceiver();
                DiagnosticJournal.infoAsync("media-buttons", status);
                callback.done(success, status);
            }
        });
    }

    private void reconcileReceiver() {
        if (enabled) { ensureReceiver(); return; }
        if (registered) {
            context.unregisterReceiver(receiver);
            registered = false;
        }
        if (inputThread != null) { inputThread.quitSafely(); inputThread = null; }
    }
    private void ensureReceiver() {
        if (registered) return;
        inputThread = new HandlerThread("natro-media-input");
        inputThread.start();
        IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        Handler handler = new Handler(inputThread.getLooper());
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, null, handler);
        registered = true;
    }
    private static void record(KeyEvent event, String result, long received, long started) {
        DiagnosticJournal.infoAsync("media-buttons", "code=" + event.getKeyCode()
                + ", action=" + event.getAction() + ", repeat=" + event.getRepeatCount()
                + ", event_uptime=" + event.getEventTime() + ", down_uptime=" + event.getDownTime()
                + ", received_uptime=" + received + ", queue_ms=" + (started - received)
                + ", result=" + result + ", audio_output=unobserved");
    }
    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String shortError(String error, String output) {
        String value = error != null ? error : output == null ? "нет ответа встроенного ADB" : output.trim();
        return value.length() > 240 ? value.substring(value.length() - 240) : value;
    }
}
