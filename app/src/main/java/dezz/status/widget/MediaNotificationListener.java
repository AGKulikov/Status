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
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.launcher.NavigationDataRepository;

/**
 * Supplies media-session authorization and continuously reads active navigation notifications.
 * Periodic reconciliation is important on head units which throttle notification callbacks while
 * their screen is idle: Navigator's latest ETA is still picked up without pressing Home.
 */
public class MediaNotificationListener extends NotificationListenerService {
    private static final long NAVIGATION_REFRESH_MS = 2_000L;
    private int consecutiveNoRouteScans;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshNavigation = new Runnable() {
        @Override
        public void run() {
            refreshActiveNavigationNotifications();
            handler.postDelayed(this, NAVIGATION_REFRESH_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        refreshActiveNavigationNotifications();
        handler.removeCallbacks(refreshNavigation);
        handler.postDelayed(refreshNavigation, NAVIGATION_REFRESH_MS);
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(refreshNavigation);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && NavigationDataRepository.isSupportedPackage(sbn.getPackageName())) {
            // Reconciliation also handles notification replacement (new key, old key removed).
            refreshActiveNavigationNotifications();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && NavigationDataRepository.isSupportedPackage(sbn.getPackageName())) {
            refreshActiveNavigationNotifications();
            NavigationDataRepository.removeIfSource(this, sbn);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refreshNavigation);
        super.onDestroy();
    }

    private void refreshActiveNavigationNotifications() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException ignored) {
            return;
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
            NavigationDataRepository.NotificationCandidate candidate =
                    NavigationDataRepository.inspectNotification(this, notification);
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
    }
}
