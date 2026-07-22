/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Compatibility endpoint for navigation data broadcasts used by Yandex/MConfig builds. */
public final class YandexNavigationReceiver extends BroadcastReceiver {
    /**
     * Bitmap decode/down-sampling and crash-safe PNG writes must never run on the receiver's main
     * thread. A single worker deliberately preserves update/clear order across all navigation
     * channels (a pool could resurrect an image after its later CLEAR broadcast).
     */
    private static final int MAX_PENDING_BROADCASTS = 8;
    private static final ThreadPoolExecutor WORKER = createWorker();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) applicationContext = context;
        Context targetContext = applicationContext;
        Intent snapshot;
        PendingResult pendingResult;
        try {
            snapshot = snapshotForWorker(intent);
            pendingResult = goAsync();
        } catch (RuntimeException ignored) {
            return;
        }
        NavigationTask task = new NavigationTask(targetContext, snapshot, pendingResult);
        try {
            WORKER.execute(task);
        } catch (RuntimeException error) {
            // The process may be shutting down and reject new tasks. Never leak a PendingResult.
            task.finish();
        }
    }

    private static Intent snapshotForWorker(Intent source) {
        String action = source.getAction();
        // Explicit CLEAR actions need no payload. Do not retain arbitrary extras/bitmaps while a
        // preceding image is being encoded by the serial worker.
        return isExplicitClearAction(action) ? new Intent(action) : new Intent(source);
    }

    private static boolean isExplicitClearAction(String action) {
        return NavigationDataRepository.ACTION_MONJARO_NAVIGATION_ENDED.equals(action)
                || NavigationDataRepository.ACTION_DEBUG_NAVIGATION_ENDED.equals(action)
                || NavigationDataRepository.ACTION_YANDEX_LANES_BITMAP_CLEAR.equals(action)
                || NavigationDataRepository.ACTION_YANDEX_JAM_IMAGE_CLEAR.equals(action)
                || NavigationDataRepository.ACTION_MONJARO_RAINBOW_IMAGE_CLEAR.equals(action)
                || NavigationDataRepository.ACTION_DEBUG_RAINBOW_IMAGE_CLEAR.equals(action);
    }

    private static ThreadPoolExecutor createWorker() {
        ThreadPoolExecutor result = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_BROADCASTS), runnable -> {
                    Thread thread = new Thread(runnable, "navigation-broadcast-worker");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    thread.setDaemon(true);
                    return thread;
                });
        result.allowCoreThreadTimeOut(true);
        // Bound retained Intent/Bitmap memory. Coalescing preserves channel order and never loses
        // a semantic CLEAR merely because unrelated bitmap updates filled the queue.
        result.setRejectedExecutionHandler(YandexNavigationReceiver::handleOverflow);
        return result;
    }

    private static void handleOverflow(Runnable newest, ThreadPoolExecutor executor) {
        if (!(newest instanceof NavigationTask)) return;
        NavigationTask incoming = (NavigationTask) newest;
        BlockingQueue<Runnable> queue = executor.getQueue();
        for (int attempt = 0; attempt < 3 && !executor.isShutdown(); attempt++) {
            NavigationTask candidate = selectCoalescingCandidate(queue, incoming);
            if (candidate != null && queue.remove(candidate)) candidate.finish();
            if (queue.offer(incoming)) return;
            if (candidate == null) break;
        }
        // A queue containing only unrelated CLEAR operations is more important than a normal
        // update. Never block the exported receiver's main thread behind PNG encode/fsync; only a
        // ninth unrelated CLEAR (normally possible only under a malformed flood) can reach here.
        incoming.finish();
    }

    private static NavigationTask selectCoalescingCandidate(BlockingQueue<Runnable> queue,
            NavigationTask incoming) {
        NavigationTask oldestNormal = null;
        for (Runnable value : queue) {
            if (!(value instanceof NavigationTask)) continue;
            NavigationTask queued = (NavigationTask) value;
            if (incoming.supersedes(queued)) return queued;
            if (!queued.isClear() && oldestNormal == null) oldestNormal = queued;
        }
        // CLEAR must enter the queue; for normal updates this also keeps all queued CLEARs.
        return oldestNormal;
    }

    private static final class NavigationTask implements Runnable {
        private final Context context;
        private final Intent intent;
        private PendingResult pendingResult;

        NavigationTask(Context context, Intent intent, PendingResult pendingResult) {
            this.context = context;
            this.intent = intent;
            this.pendingResult = pendingResult;
        }

        @Override public void run() {
            try {
                NavigationDataRepository.updateFromYandexBroadcast(context, intent);
            } catch (RuntimeException ignored) {
                // This receiver is exported for mHUD/MConfig. A malformed Parcelable/extra from
                // another process is invalid input, not a reason to terminate our app process.
            } finally {
                finish();
            }
        }

        boolean isClear() {
            String action = intent.getAction();
            if (isExplicitClearAction(action)) return true;
            if ((NavigationDataRepository.ACTION_MONJARO_NAVIGATION_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_NAVIGATION_UPDATE.equals(action))
                    && !intent.getBooleanExtra("route_active", true)) return true;
            if (NavigationDataRepository.ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_TRAFFIC_LIGHT_UPDATE.equals(action)) {
                String color = intent.getStringExtra("tl_color");
                return color == null || color.trim().isEmpty();
            }
            return false;
        }

        boolean supersedes(NavigationTask older) {
            if (!isClear()) {
                return !older.isClear() && coalescingKey().equals(older.coalescingKey());
            }
            if (clearsEverything()) return true;
            if (!channel().equals(older.channel())) return false;
            return clearsWholeChannel() || coalescingKey().equals(older.coalescingKey());
        }

        private boolean clearsEverything() {
            String action = intent.getAction();
            return NavigationDataRepository.ACTION_MONJARO_NAVIGATION_ENDED.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_NAVIGATION_ENDED.equals(action)
                    || ((NavigationDataRepository.ACTION_MONJARO_NAVIGATION_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_NAVIGATION_UPDATE.equals(action))
                    && !intent.getBooleanExtra("route_active", true));
        }

        private boolean clearsWholeChannel() {
            String action = intent.getAction();
            if (NavigationDataRepository.ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_TRAFFIC_LIGHT_UPDATE.equals(action)) {
                String id = intent.getStringExtra("tl_id");
                return (id == null || id.trim().isEmpty()) && !intent.hasExtra("tl_position");
            }
            return isClear();
        }

        private String channel() {
            String action = intent.getAction();
            if (NavigationDataRepository.ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_TRAFFIC_LIGHT_UPDATE.equals(action)) {
                return "traffic";
            }
            if (NavigationDataRepository.ACTION_YANDEX_LANES_BITMAP.equals(action)
                    || NavigationDataRepository.ACTION_YANDEX_LANES_BITMAP_CLEAR.equals(action)) {
                return "lanes_bitmap";
            }
            if (NavigationDataRepository.ACTION_YANDEX_JAM_IMAGE.equals(action)
                    || NavigationDataRepository.ACTION_YANDEX_JAM_IMAGE_CLEAR.equals(action)) {
                return "jam";
            }
            if (NavigationDataRepository.ACTION_MONJARO_RAINBOW_IMAGE.equals(action)
                    || NavigationDataRepository.ACTION_MONJARO_RAINBOW_IMAGE_CLEAR.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_RAINBOW_IMAGE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_RAINBOW_IMAGE_CLEAR.equals(action)) {
                return "rainbow";
            }
            return action == null ? "" : action;
        }

        private String coalescingKey() {
            String action = intent.getAction();
            if (NavigationDataRepository.ACTION_MONJARO_NAVIGATION_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_NAVIGATION_UPDATE.equals(action)) {
                return "navigation_update";
            }
            if (NavigationDataRepository.ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE.equals(action)
                    || NavigationDataRepository.ACTION_DEBUG_TRAFFIC_LIGHT_UPDATE.equals(action)) {
                String id = intent.getStringExtra("tl_id");
                if (id != null && !id.trim().isEmpty()) return "traffic:id:" + id.trim();
                Object position = intent.hasExtra("tl_position") && intent.getExtras() != null
                        ? intent.getExtras().get("tl_position") : null;
                String source = intent.getStringExtra("tl_source");
                return position == null ? "traffic:single"
                        : "traffic:pos:" + position + ":" + (source == null ? "" : source);
            }
            return channel();
        }

        synchronized void finish() {
            PendingResult value = pendingResult;
            pendingResult = null;
            if (value != null) {
                try { value.finish(); }
                catch (RuntimeException ignored) { }
            }
        }
    }
}
