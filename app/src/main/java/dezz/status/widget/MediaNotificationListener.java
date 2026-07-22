/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.launcher.NavigationCollectionDemand;
import dezz.status.widget.launcher.NavigationCollectionPolicy;
import dezz.status.widget.launcher.NavigationDataRepository;

/**
 * Supplies media-session authorization and reads navigation notifications when HOME consumes
 * them. Notification callbacks are the primary trigger; a slow watchdog remains for head units
 * which occasionally throttle callbacks while their screen is idle.
 */
public class MediaNotificationListener extends NotificationListenerService {
    private static final long NAVIGATION_MISSING_GRACE_MS = 1_500L;
    private int consecutiveNoRouteScans;
    private HandlerThread navigationThread;
    private volatile Handler navigationHandler;
    private NavigationCollectionDemand navigationDemand;
    private volatile boolean listenerConnected;
    private volatile long lastNavigationScanElapsed;
    /** Accessed only on {@link #navigationThread}; prevents callback storms postponing a scan. */
    private long nextNavigationScanElapsed;
    private final Runnable refreshNavigation = new Runnable() {
        @Override
        public void run() {
            nextNavigationScanElapsed = 0L;
            if (!canCollectNavigation()) return;
            boolean foundRoute;
            try {
                foundRoute = refreshActiveNavigationNotifications();
            } catch (RuntimeException | LinkageError ignored) {
                // A third-party RemoteViews/Bundle is untrusted input. Keep the collector thread
                // alive and let the watchdog or Accessibility fallback recover later.
                foundRoute = false;
            }
            lastNavigationScanElapsed = SystemClock.elapsedRealtime();
            Handler worker = navigationHandler;
            if (worker != null && canCollectNavigation()) {
                long delay = !foundRoute && consecutiveNoRouteScans == 1
                        ? NAVIGATION_MISSING_GRACE_MS
                        : NavigationCollectionPolicy.watchdogDelay(foundRoute);
                scheduleNavigationScanOnWorker(SystemClock.elapsedRealtime() + delay);
            }
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;
        if (navigationDemand == null) {
            navigationDemand = new NavigationCollectionDemand(this);
            navigationDemand.start(this::onNavigationDemandChanged);
        }
        if (navigationDemand.isNeeded()) requestNavigationScan(true);
    }

    @Override
    public void onListenerDisconnected() {
        listenerConnected = false;
        stopNavigationWorker();
        if (navigationDemand != null) {
            navigationDemand.stop();
            navigationDemand = null;
        }
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && NavigationDataRepository.isSupportedPackage(sbn.getPackageName())) {
            // Reconciliation handles replacement and chooses the most complete notification.
            // The worker coalesces rapid ETA updates and keeps RemoteViews inflation off main.
            requestNavigationScan(false);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && NavigationDataRepository.isSupportedPackage(sbn.getPackageName())) {
            // Reconcile immediately instead of clearing the old key first: Navigator commonly
            // replaces a notification with a new key, and persisting the replacement before the
            // missing-key check prevents a visible route-off/route-on flash.
            requestNavigationScan(true);
        }
    }

    @Override
    public void onDestroy() {
        listenerConnected = false;
        stopNavigationWorker();
        if (navigationDemand != null) {
            navigationDemand.stop();
            navigationDemand = null;
        }
        super.onDestroy();
    }

    private void ensureNavigationWorker() {
        if (navigationThread != null && navigationThread.isAlive()
                && navigationHandler != null) return;
        navigationThread = new HandlerThread("navigation-notification-collector",
                Process.THREAD_PRIORITY_BACKGROUND);
        navigationThread.start();
        navigationHandler = new Handler(navigationThread.getLooper());
    }

    private void stopNavigationWorker() {
        Handler worker = navigationHandler;
        navigationHandler = null;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        HandlerThread thread = navigationThread;
        navigationThread = null;
        if (thread != null) thread.quitSafely();
        consecutiveNoRouteScans = 0;
        lastNavigationScanElapsed = 0L;
        nextNavigationScanElapsed = 0L;
    }

    private void onNavigationDemandChanged(boolean needed) {
        if (!listenerConnected) return;
        if (needed) {
            ensureNavigationWorker();
            requestNavigationScan(true);
        } else {
            stopNavigationWorker();
        }
    }

    private boolean canCollectNavigation() {
        NavigationCollectionDemand demand = navigationDemand;
        return listenerConnected && demand != null && demand.isNeeded();
    }

    private void requestNavigationScan(boolean immediate) {
        if (!canCollectNavigation()) return;
        ensureNavigationWorker();
        Handler worker = navigationHandler;
        if (worker == null) return;
        worker.post(() -> {
            if (!canCollectNavigation()) return;
            long now = SystemClock.elapsedRealtime();
            long deadline = immediate ? now : now + NavigationCollectionPolicy.eventDelay(
                    now, lastNavigationScanElapsed);
            scheduleNavigationScanOnWorker(deadline);
        });
    }

    /** Called only on the navigation worker, so scheduling metadata cannot race a running scan. */
    private void scheduleNavigationScanOnWorker(long deadlineElapsed) {
        Handler worker = navigationHandler;
        if (worker == null || !canCollectNavigation()) return;
        long now = SystemClock.elapsedRealtime();
        if (nextNavigationScanElapsed > now
                && nextNavigationScanElapsed <= deadlineElapsed) {
            return;
        }
        worker.removeCallbacks(refreshNavigation);
        nextNavigationScanElapsed = Math.max(now, deadlineElapsed);
        worker.postDelayed(refreshNavigation, nextNavigationScanElapsed - now);
    }

    /** Runs only on {@link #navigationThread}; RemoteViews inflation must never block UI/overlay. */
    private boolean refreshActiveNavigationNotifications() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (active == null) active = new StatusBarNotification[0];
        Set<String> activeKeys = new HashSet<>();
        List<NavigationDataRepository.NotificationCandidate> candidates = new ArrayList<>();
        for (StatusBarNotification notification : active) {
            if (notification == null
                    || !NavigationDataRepository.isSupportedPackage(notification.getPackageName())) {
                continue;
            }
            activeKeys.add(notification.getKey());
            NavigationDataRepository.NotificationCandidate candidate;
            try {
                candidate = NavigationDataRepository.inspectNotification(this, notification);
            } catch (RuntimeException | LinkageError ignored) {
                continue;
            }
            if (candidate != null) candidates.add(candidate);
        }
        // Completeness comes first: a stale Navigator notice containing only one value must not
        // hide a complete current route from Maps. At equal completeness standalone Navigator
        // wins, followed by Maps/Yango/Google, then the newest notification.
        candidates.sort((left, right) -> {
            int completeness = Integer.compare(right.fieldCount(), left.fieldCount());
            if (completeness != 0) return completeness;
            int priority = Integer.compare(
                    NavigationDataRepository.notificationPriority(right.packageName),
                    NavigationDataRepository.notificationPriority(left.packageName));
            return priority != 0 ? priority : Long.compare(right.postTime, left.postTime);
        });
        if (!canCollectNavigation()) return false;
        boolean foundRoute = !candidates.isEmpty();
        if (foundRoute) NavigationDataRepository.persistNotification(this, candidates.get(0));
        if (foundRoute) {
            consecutiveNoRouteScans = 0;
        } else if (++consecutiveNoRouteScans >= 2) {
            // One custom RemoteViews inflation can fail while Navigator rebuilds a notification.
            // Require a second poll before treating a still-present notification as route-ended.
            NavigationDataRepository.clearNotificationSourceIfNoRoute(this);
            consecutiveNoRouteScans = 2;
        }
        NavigationDataRepository.clearIfNotificationSourceMissing(this, activeKeys);
        return foundRoute;
    }
}
