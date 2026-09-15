/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import dezz.status.widget.WidgetAccessibilityService;

/** The reference's acquire-A2DP transaction, with one owner and a bounded volume-restore path. */
public final class ButtonBluetoothMedia {
    private static volatile ButtonBluetoothMedia instance;
    static synchronized ButtonBluetoothMedia get(Context context) {
        if (instance == null) instance = new ButtonBluetoothMedia(context.getApplicationContext());
        return instance;
    }
    public static void windowChanged(int type, String packageName, String className) {
        ButtonBluetoothMedia current = instance;
        if (current != null && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && "com.ecarx.multimedia".equals(packageName))
            current.worker.post(() -> current.window(className));
    }
    private final Context context;
    private final Handler main, worker;
    private final AudioManager audio;
    private long generation;
    private volatile boolean running;
    private boolean muted, acquiring, finishing;
    private int previousVolume = -1;
    private ButtonBluetoothMedia(Context context) {
        this.context = context; main = new Handler(context.getMainLooper());
        HandlerThread thread = new HandlerThread("natro-bt-acquire"); thread.start();
        worker = new Handler(thread.getLooper()); audio = context.getSystemService(AudioManager.class);
    }
    void start() { worker.post(() -> {
        if (running) return;
        if (WidgetAccessibilityService.getInstance() == null) {
            toast("Включите службу специальных возможностей Natro для BT медиа");
            context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        running = true; muted = false; acquiring = false; finishing = false; previousVolume = -1;
        long owner = ++generation;
        try {
            Intent target = new Intent().setClassName("com.ecarx.multimedia", "com.tethys.media.WelcomeActivity")
                    .setFlags(268468224);
            try { context.startActivity(target); }
            catch (RuntimeException oldFirmware) {
                context.startActivity(target.setClassName("com.ecarx.multimedia", "com.ecarx.multimedia.MainActivity"));
            }
            worker.postDelayed(() -> { if (valid(owner)) finish(owner); }, 12000);
        } catch (RuntimeException failed) { finish(owner); toast("Штатный плеер недоступен"); }
    }); }
    private boolean valid(long owner) { return running && !finishing && owner == generation; }
    private void window(String className) {
        if (!running || finishing) return;
        long owner = generation;
        if (!muted && audio != null) {
            try {
                previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                muted = true;
            } catch (RuntimeException unavailable) { finish(owner); return; }
        }
        if (!acquiring && ("com.ecarx.multimedia.MainActivity".equals(className)
                || "com.tethys.media.MainActivity".equals(className))) {
            acquiring = true;
            worker.postDelayed(() -> acquire(owner, 0), 500);
        }
    }
    private void acquire(long owner, int attempt) {
        if (!valid(owner)) return;
        try {
            context.sendOrderedBroadcast(new Intent("net.easyconn.a2dp.acquire").setPackage("com.ecarx.multimedia"),
                    null, new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (!valid(owner)) return;
                    if (getResultCode() == Activity.RESULT_OK || attempt >= 3)
                        worker.postDelayed(() -> finish(owner), 500);
                    else worker.postDelayed(() -> acquire(owner, attempt + 1), 500);
                }
            }, worker, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException unavailable) { finish(owner); }
    }
    private void finish(long owner) {
        if (!valid(owner)) return;
        finishing = true;
        final int restore = previousVolume; previousVolume = -1;
        main.post(() -> {
            if (!running || owner != generation) return;
            WidgetAccessibilityService service = WidgetAccessibilityService.getInstance();
            try { if (service != null) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
            catch (RuntimeException ignored) { }
        });
        // Keep admission closed until restoration, even if MAIN is stalled or a late acquire replies.
        worker.postDelayed(() -> {
            try { if (restore >= 0 && audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0); }
            catch (RuntimeException failed) { toast("Не удалось восстановить громкость после BT медиа"); }
            finally { running = false; finishing = false; }
        }, 500);
    }
    private void toast(String message) { main.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show()); }
}
