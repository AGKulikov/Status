/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Reject confirmed white startup frames; unavailable readback has a bounded fallback. */
public final class MapStartupPresentation {
    private static final long COPY_TIMEOUT_MS = 400;
    private static final ThreadPoolExecutor copy = new ThreadPoolExecutor(2, 2, 10,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), r -> {
                Thread t = new Thread(r, "natro-map-bootstrap-copy"); t.setDaemon(true); return t;
            });
    private static class CallbackThread {
        static final Handler handler;
        static { HandlerThread t = new HandlerThread("natro-map-bootstrap-result"); t.start(); handler = new Handler(t.getLooper()); }
    }
    static { copy.allowCoreThreadTimeOut(true); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private long epoch, started, sampleSerial, timestamp = Long.MIN_VALUE;
    private int differentFrames;
    private boolean sampling, finished, lastCopied, lastWhite;
    private Surface surface;
    private Runnable reveal;
    private Runnable copyTimeout;
    private final Runnable timeout = this::deadlineReached;
    private final Runnable sample = this::sample;

    public void reset() {
        epoch++; main.removeCallbacks(timeout); main.removeCallbacks(sample);
        cancelCopyTimeout();
        surface = null; reveal = null; differentFrames = 0;
        timestamp = Long.MIN_VALUE; sampling = false; finished = false;
        lastCopied = false; lastWhite = false;
    }
    public void onFrame(Surface current, long frameTimestamp, Runnable show) {
        if (surface != null && surface != current) reset();
        if (finished) return;
        if (surface == null) {
            surface = current; reveal = show; started = SystemClock.uptimeMillis();
            main.postDelayed(timeout, MapBootstrapPolicy.MAX_WAIT_MS);
            main.postDelayed(sample, MapBootstrapPolicy.SETTLE_MS);
        }
        if (surface != current) return;
        // Compare timestamps only within this Surface, never with uptime or another display.
        if (timestamp != frameTimestamp) { timestamp = frameTimestamp; differentFrames++; }
    }
    private void sample() {
        if (finished || sampling || surface == null || !surface.isValid()) return;
        final long owner = epoch;
        final long attempt = ++sampleSerial;
        final Surface target = surface;
        sampling = true;
        copyTimeout = () -> completed(owner, attempt, false, false);
        main.postDelayed(copyTimeout, COPY_TIMEOUT_MS);
        try {
            copy.execute(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
                    final Bitmap result = bitmap;
                    PixelCopy.request(target, result, status -> {
                        boolean white = false;
                        boolean copied = status == PixelCopy.SUCCESS;
                        try {
                            if (copied) {
                                int[] pixels = new int[32 * 18];
                                result.getPixels(pixels, 0, 32, 0, 0, 32, 18);
                                white = MapBootstrapPolicy.opaqueWhite(pixels);
                            }
                        } catch (RuntimeException failed) { copied = false; }
                        finally { result.recycle(); }
                        completed(owner, attempt, copied, white);
                    }, CallbackThread.handler);
                } catch (RuntimeException | OutOfMemoryError unavailable) {
                    if (bitmap != null) bitmap.recycle();
                    completed(owner, attempt, false, false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            completed(owner, attempt, false, false);
        }
    }
    private void completed(long owner, long attempt, boolean copied, boolean white) {
        main.post(() -> {
            if (owner != epoch || attempt != sampleSerial || !sampling || finished) return;
            cancelCopyTimeout();
            sampling = false;
            lastCopied = copied;
            lastWhite = copied && white;
            long elapsed = SystemClock.uptimeMillis() - started;
            if (MapBootstrapPolicy.mayReveal(elapsed, differentFrames, copied, white))
                finish(copied ? "sample_accepted" : "readback_unavailable");
            else main.postDelayed(sample, elapsed >= MapBootstrapPolicy.MAX_WAIT_MS ? 200 : 50);
        });
    }
    private void deadlineReached() {
        if (finished || reveal == null) return;
        if (lastCopied && lastWhite) {
            // A deadline cannot turn a positively identified white buffer into a usable map.
            // Sampling continues without waiting for tiles/ACK. Each attempt has its own
            // watchdog, so broken PixelCopy cannot turn this into an unavailable-readback gate.
            DiagnosticJournal.infoAsync("map-startup", "presentation=white_frame_held");
            if (!sampling) { main.removeCallbacks(sample); sample(); }
        } else {
            finish("bounded_fallback");
        }
    }
    private void cancelCopyTimeout() {
        if (copyTimeout != null) main.removeCallbacks(copyTimeout);
        copyTimeout = null;
    }
    private void finish(String reason) {
        if (finished || reveal == null) return;
        finished = true; main.removeCallbacks(timeout); main.removeCallbacks(sample);
        cancelCopyTimeout();
        Runnable show = reveal; reveal = null;
        if (surface == null || !surface.isValid()) return;
        DiagnosticJournal.infoAsync("map-startup", "presentation=" + reason + ", wait_ms="
                + (SystemClock.uptimeMillis() - started) + ", different_frames=" + differentFrames);
        show.run();
    }
}
