/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Bounded, observation-only startup trace for each real Surface lease. No TextureView readback,
 * View reference, visibility change, readiness gate, disk IO or waiting on the UI/draw thread.
 * PixelCopy may itself wait inside the platform, so its invocation has a separate worker too.
 */
public final class MapStartupDiagnostics {
    private static volatile Lease hud, cluster;
    private static final AtomicBoolean COPY_BUSY = new AtomicBoolean();
    private static final AtomicLong TRACE_IDS = new AtomicLong();
    private static volatile long journalEpoch;
    private MapStartupDiagnostics() {}

    private static final class Workers {
        static final Handler TRACE = handler("NatroMapTrace");
        static final Handler COPY = handler("NatroMapPixelCopy");
        private static Handler handler(String name) {
            HandlerThread thread = new HandlerThread(name, android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            return new Handler(thread.getLooper());
        }
    }

    private static Lease current(boolean isCluster) { return isCluster ? cluster : hud; }

    /** Called under the endpoint's lease lock, including resize and cold-recovery generations. */
    static void begin(boolean isCluster, Surface surface, long generation, int width, int height, int dpi) {
        Lease old = current(isCluster);
        Lease next = new Lease(isCluster, surface, generation, width, height, dpi);
        if (old != null && old.surface == surface) next.presentation = old.presentation;
        if (isCluster) cluster = next; else hud = next;
        if (old != null) stop(old, "superseded");
        if (DiagnosticJournal.isEnabled()) Workers.TRACE.post(() -> start(next));
    }

    static void end(boolean isCluster, long generation, String reason) {
        Lease old = current(isCluster);
        if (old == null || old.generation != generation) return;
        if (isCluster) cluster = null; else hud = null;
        stop(old, reason);
    }

    private static void stop(Lease lease, String reason) {
        Capture capture = lease.capture;
        if (capture != null) Workers.TRACE.post(() -> capture.finish(reason));
    }

    /** No worker or pixel allocation while disabled; enabling also covers a surface with no frames. */
    public static void journalChanged(boolean enabled) {
        journalEpoch++;
        Lease[] leases = {hud, cluster};
        for (Lease lease : leases) {
            if (lease == null) continue;
            if (enabled) Workers.TRACE.post(() -> start(lease));
            else stop(lease, "journal_disabled");
        }
    }

    private static void start(Lease lease) {
        start(lease, false);
    }

    private static void start(Lease lease, boolean reconnect) {
        if (current(lease.isCluster) != lease || !DiagnosticJournal.isEnabled()) return;
        Capture previous = lease.capture;
        if (!reconnect && previous != null && previous.epoch == journalEpoch) return;
        if (previous != null) previous.finish(reconnect ? "bridge_reconnect" : "journal_restarted");
        Capture capture = new Capture(lease, journalEpoch);
        lease.capture = capture;
        capture.log("begin", "size=" + lease.width + "x" + lease.height + ", dpi=" + lease.dpi
                + ", surface_id=" + System.identityHashCode(lease.surface)
                + ", observation_late_ms=" + (capture.started - lease.born)
                + ", sample=32x18, window_ms=120000, max_copies=64, diagnostic_only=true");
        capture.tick.run();
    }

    /** Cheap callback counters only. In particular, this method never schedules a task per frame. */
    public static void frame(boolean isCluster, Surface surface, long textureTimestampNs) {
        Lease lease = current(isCluster);
        if (lease == null || lease.surface != surface) return;
        long now = SystemClock.elapsedRealtime();
        if (lease.firstFrameMs < 0) lease.firstFrameMs = now;
        long previous = lease.lastFrameMs;
        if (previous >= 0) lease.maxFrameGapMs = Math.max(lease.maxFrameGapMs, now - previous);
        lease.lastFrameMs = now;
        if (lease.textureTimestampNs == textureTimestampNs) lease.repeatedTimestamps++;
        lease.textureTimestampNs = textureTimestampNs;
        lease.frames++;
        // One nudge for the first callback, never inside TextureView.draw or at display FPS.
        Capture capture = lease.capture;
        if (capture != null && !capture.done && capture.firstUpdateMs < 0 && DiagnosticJournal.isEnabled()) {
            capture.firstUpdateMs = now;
            Workers.TRACE.post(() -> { if (!capture.done) capture.pulse(); });
        }
    }

    /** View properties are captured by their UI owner, not read from a worker. */
    public static void presentation(boolean isCluster, Surface surface, float alpha,
                                    int visibility, int windowVisibility, boolean attached, boolean opaque) {
        Lease lease = current(isCluster);
        if (lease == null || lease.surface != surface) return;
        // Called for state/lifecycle changes, not every frame.
        lease.presentation = "alpha=" + alpha + ", view_visibility=" + visibility
                + ", window_visibility=" + windowVisibility + ", attached=" + attached
                + ", opaque=" + opaque;
    }

    static void dispatch(boolean isCluster, long generation, boolean sent) {
        Lease lease = current(isCluster);
        if (lease == null || lease.generation != generation) return;
        boolean reconnect = sent && !lease.dispatched && lease.firstDispatchMs >= 0;
        lease.dispatched = sent;
        lease.dispatches++;
        if (sent && lease.firstDispatchMs < 0) lease.firstDispatchMs = SystemClock.elapsedRealtime();
        if (reconnect && DiagnosticJournal.isEnabled()) Workers.TRACE.post(() -> start(lease, true));
    }

    static void readiness(boolean isCluster, long generation, boolean ready) {
        Lease lease = current(isCluster);
        if (lease == null || lease.generation != generation) return;
        lease.ready = ready;
        if (ready && lease.firstReadyMs < 0) lease.firstReadyMs = SystemClock.elapsedRealtime();
    }

    static void bridgeLost() {
        for (Lease lease : new Lease[]{hud, cluster}) if (lease != null) {
            lease.dispatched = false; lease.ready = false; lease.disconnects++;
        }
    }

    private static final class Lease {
        final boolean isCluster;
        final Surface surface;
        final long generation, born = SystemClock.elapsedRealtime();
        final int width, height, dpi;
        volatile long frames, firstFrameMs = -1, lastFrameMs = -1, maxFrameGapMs,
                textureTimestampNs = -1, repeatedTimestamps, firstDispatchMs = -1, firstReadyMs = -1;
        volatile boolean dispatched, ready;
        volatile int dispatches, disconnects;
        volatile String presentation = "presentation=not_observed";
        volatile Capture capture;
        Lease(boolean isCluster, Surface surface, long generation, int width, int height, int dpi) {
            this.isCluster = isCluster; this.surface = surface; this.generation = generation;
            this.width = width; this.height = height; this.dpi = dpi;
        }
    }

    private static final class Capture {
        final Lease lease;
        final long traceId = TRACE_IDS.incrementAndGet();
        final long epoch, started = SystemClock.elapsedRealtime();
        final MapStartupFrameStats stats = new MapStartupFrameStats();
        final Runnable tick = this::pulse;
        volatile boolean done;
        volatile long firstUpdateMs = -1;
        boolean pending;
        int copies, failures, copyTimeouts, totalPendingTimeouts;
        long requestMs, nextCopyMs, lastLogMs = -10_000, maxCopyMs;
        String lastState = "none";
        Capture(Lease lease, long epoch) { this.lease = lease; this.epoch = epoch; }

        boolean current() {
            return !done && MapStartupDiagnostics.current(lease.isCluster) == lease && lease.capture == this
                    && epoch == journalEpoch && DiagnosticJournal.isEnabled();
        }

        void pulse() {
            Workers.TRACE.removeCallbacks(tick);
            if (!current()) { finish("cancelled_or_journal_off"); return; }
            long now = SystemClock.elapsedRealtime(), elapsed = now - started;
            if (elapsed >= MapStartupFrameStats.WINDOW_MS || (!pending && copies >= MapStartupFrameStats.MAX_COPIES)) {
                finish("observation_limit"); return;
            }
            if (pending && now - requestMs >= 2_000) {
                if (copyTimeouts == 0) {
                    copyTimeouts++; totalPendingTimeouts++; stats.gap();
                    event("copy_pending_timeout", "wait_ms=" + (now - requestMs));
                }
                // Keep the single copy slot/buffer until its real callback. Never grow a stuck FIFO.
            } else if (!pending && now >= nextCopyMs) {
                nextCopyMs = now + MapStartupFrameStats.intervalMs(elapsed);
                if (!lease.surface.isValid()) event("surface_invalid", "");
                else if (COPY_BUSY.compareAndSet(false, true)) requestCopy(now);
                else {
                    // Offset the competing display's next attempt: equal timer phases must not
                    // let one profile monopolize the global copy slot for the whole window.
                    nextCopyMs = now + 250L;
                    event("copy_worker_busy", "");
                }
            }
            if (now - lastLogMs >= 10_000) log("heartbeat", snapshot(now));
            long delay = !pending && nextCopyMs > now ? Math.min(500L, nextCopyMs - now) : 500L;
            Workers.TRACE.postDelayed(tick, Math.min(delay, MapStartupFrameStats.WINDOW_MS - elapsed));
        }

        void requestCopy(long now) {
            pending = true; requestMs = now; copies++; copyTimeouts = 0;
            // The source Surface stays owned by the endpoint/View. Diagnostics never releases it.
            Workers.COPY.post(() -> {
                Bitmap bitmap = null;
                try {
                    if (!current()) { Workers.TRACE.post(() -> completed(null, -1, "cancelled")); return; }
                    bitmap = Bitmap.createBitmap(MapStartupFrameStats.WIDTH,
                            MapStartupFrameStats.HEIGHT, Bitmap.Config.ARGB_8888);
                    Bitmap target = bitmap;
                    PixelCopy.request(lease.surface, target,
                            result -> completed(target, result, ""), Workers.TRACE);
                } catch (RuntimeException | OutOfMemoryError failure) {
                    Bitmap target = bitmap;
                    String reason = failure.getClass().getSimpleName();
                    Workers.TRACE.post(() -> completed(target, -1, reason));
                }
            });
        }

        void completed(Bitmap bitmap, int result, String error) {
            pending = false;
            COPY_BUSY.set(false);
            long now = SystemClock.elapsedRealtime(), copyMs = now - requestMs;
            maxCopyMs = Math.max(maxCopyMs, copyMs);
            try {
                // An old generation's callback may release its own bitmap only, never update a successor.
                if (!current()) return;
                if (now - started >= MapStartupFrameStats.WINDOW_MS) { finish("observation_limit"); return; }
                if (result != PixelCopy.SUCCESS || bitmap == null) {
                    failures++; stats.gap();
                    event("copy_" + resultName(result), "error=" + error + ", copy_ms=" + copyMs);
                    return;
                }
                int[] pixels = new int[MapStartupFrameStats.WIDTH * MapStartupFrameStats.HEIGHT];
                bitmap.getPixels(pixels, 0, MapStartupFrameStats.WIDTH, 0, 0,
                        MapStartupFrameStats.WIDTH, MapStartupFrameStats.HEIGHT);
                MapStartupFrameStats.Sample sample = MapStartupFrameStats.classify(pixels);
                stats.accept(sample, now - started);
                event(sample.kind, sample.detail() + ", copy_ms=" + copyMs
                        + ", sample_request_ms=" + (requestMs - lease.born));
            } catch (RuntimeException | OutOfMemoryError failure) {
                failures++; stats.gap(); event("analysis_failed", "error=" + failure.getClass().getSimpleName());
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }

        void event(String state, String detail) {
            long now = SystemClock.elapsedRealtime();
            if (!state.equals(lastState) || now - lastLogMs >= 5_000) {
                log("sample", "state=" + state + ", previous=" + lastState + ", " + detail
                        + ", " + snapshot(now));
            }
            lastState = state;
        }

        String snapshot(long now) {
            return "frames=" + lease.frames + ", first_update_ms=" + (firstUpdateMs < 0 ? -1 : firstUpdateMs - started)
                    + ", generation_first_update_ms=" + offset(lease.firstFrameMs)
                    + ", last_update_age_ms=" + (lease.lastFrameMs < 0 ? -1 : now - lease.lastFrameMs)
                    + ", max_update_gap_ms=" + lease.maxFrameGapMs
                    + ", texture_timestamp_ns=" + lease.textureTimestampNs
                    + ", repeated_timestamps=" + lease.repeatedTimestamps
                    + ", dispatched=" + lease.dispatched + ", first_dispatch_ms=" + offset(lease.firstDispatchMs)
                    + ", dispatches=" + lease.dispatches + ", mapkit_ready=" + lease.ready
                    + ", first_mapkit_ready_ms=" + offset(lease.firstReadyMs)
                    + ", disconnects=" + lease.disconnects + ", " + lease.presentation;
        }

        long offset(long time) { return time < 0 ? -1 : time - lease.born; }

        void log(String stage, String detail) {
            lastLogMs = SystemClock.elapsedRealtime();
            DiagnosticJournal.infoAsync("map-startup", "profile=" + (lease.isCluster ? "cluster" : "hud")
                    + ", generation=" + lease.generation + ", trace_id=" + traceId + ", trace_epoch=" + epoch
                    + ", stage=" + stage + ", elapsed_ms=" + (lastLogMs - started)
                    + ", lease_age_ms=" + (lastLogMs - lease.born)
                    + ", mono_ms=" + lastLogMs + ", " + detail);
        }

        void finish(String reason) {
            if (done) return;
            done = true;
            Workers.TRACE.removeCallbacks(tick);
            log("end", "reason=" + reason + ", last_state=" + lastState + ", " + stats.summary()
                    + ", copies=" + copies + ", copy_failures=" + failures
                    + ", pending_timeouts=" + totalPendingTimeouts
                    + ", copy_pending=" + pending + ", max_copy_ms=" + maxCopyMs
                    + ", physical_display_verified=false, " + snapshot(SystemClock.elapsedRealtime()));
        }
    }

    private static String resultName(int result) {
        switch (result) {
            case PixelCopy.ERROR_SOURCE_NO_DATA: return "source_no_data";
            case PixelCopy.ERROR_SOURCE_INVALID: return "source_invalid";
            case PixelCopy.ERROR_DESTINATION_INVALID: return "destination_invalid";
            case PixelCopy.ERROR_TIMEOUT: return "timeout";
            case PixelCopy.ERROR_UNKNOWN: return "unknown";
            default: return "exception";
        }
    }
}
