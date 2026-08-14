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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.launcher.NavigationCollectionDemand;
import dezz.status.widget.launcher.NavigationCollectionPolicy;
import dezz.status.widget.launcher.NavigationDataRepository;
import dezz.status.widget.launcher.MediaLikeActionPolicy;

/**
 * Supplies media-session authorization and reads navigation notifications when HOME consumes
 * them. Notification callbacks are the primary trigger; a slow watchdog remains for head units
 * which occasionally throttle callbacks while their screen is idle.
 */
public class MediaNotificationListener extends NotificationListenerService {
    private static final long NAVIGATION_MISSING_GRACE_MS = 1_500L;
    private static final Object MEDIA_SESSION_LOCK = new Object();
    private static final Map<String, MediaNotificationSession> MEDIA_SESSIONS =
            new LinkedHashMap<>();
    private static final Map<String, MediaNotificationLikeSnapshot> MEDIA_LIKE_ACTIONS =
            new LinkedHashMap<>();
    private static final Set<MediaLikeObserver> MEDIA_LIKE_OBSERVERS = new HashSet<>();
    private static long mediaLikeGeneration;
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
            } catch (OutOfMemoryError memoryPressure) {
                NavigationDataRepository.releaseDecodedGraphics();
                foundRoute = false;
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
        rebuildMediaSessions();
        if (navigationDemand == null) {
            navigationDemand = new NavigationCollectionDemand(this);
            navigationDemand.start(this::onNavigationDemandChanged);
        }
        if (navigationDemand.isNeeded()) requestNavigationScan(true);
    }

    @Override
    public void onListenerDisconnected() {
        listenerConnected = false;
        clearMediaSessions();
        stopNavigationWorker();
        if (navigationDemand != null) {
            navigationDemand.stop();
            navigationDemand = null;
        }
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        rememberMediaSession(sbn);
        if (sbn != null && NavigationDataRepository.isSupportedPackage(sbn.getPackageName())) {
            // Reconciliation handles replacement and chooses the most complete notification.
            // The worker coalesces rapid ETA updates and keeps RemoteViews inflation off main.
            requestNavigationScan(false);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        forgetMediaSession(sbn);
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
        clearMediaSessions();
        stopNavigationWorker();
        if (navigationDemand != null) {
            navigationDemand.stop();
            navigationDemand = null;
        }
        super.onDestroy();
    }

    /** Media notification tokens remain available even when ECARX hides active-session access. */
    public static List<MediaController> activeMediaNotificationControllers(Context context) {
        List<MediaNotificationSession> sessions;
        synchronized (MEDIA_SESSION_LOCK) {
            sessions = new ArrayList<>(MEDIA_SESSIONS.values());
        }
        Collections.reverse(sessions);
        List<MediaController> result = new ArrayList<>(sessions.size());
        List<MediaSession.Token> seen = new ArrayList<>(sessions.size());
        for (MediaNotificationSession session : sessions) {
            if (seen.contains(session.token)) continue;
            try {
                result.add(new MediaController(context, session.token));
                seen.add(session.token);
            } catch (RuntimeException ignored) {
            }
        }
        return result;
    }

    /** Executes the newest captured action belonging to the represented player notification. */
    public static boolean sendMediaNotificationLike(@Nullable String targetPackage) {
        List<MediaNotificationLikeSnapshot> actions;
        synchronized (MEDIA_SESSION_LOCK) {
            actions = new ArrayList<>(MEDIA_LIKE_ACTIONS.values());
        }
        Collections.reverse(actions);
        String target = targetPackage == null ? "" : targetPackage.trim();
        for (MediaNotificationLikeSnapshot action : actions) {
            if (!target.isEmpty() && !target.equals(action.packageName)) continue;
            try {
                action.pendingIntent.send();
                return true;
            } catch (PendingIntent.CanceledException | RuntimeException ignored) {
            }
        }
        return false;
    }

    /** Receives only invalidation events; callers fetch one immutable package-scoped snapshot. */
    public interface MediaLikeObserver {
        void onMediaLikeActionsChanged();
    }

    public static void addMediaLikeObserver(@NonNull MediaLikeObserver observer) {
        synchronized (MEDIA_SESSION_LOCK) {
            MEDIA_LIKE_OBSERVERS.add(observer);
        }
    }

    public static void removeMediaLikeObserver(@NonNull MediaLikeObserver observer) {
        synchronized (MEDIA_SESSION_LOCK) {
            MEDIA_LIKE_OBSERVERS.remove(observer);
        }
    }

    /** Newest Like action for the represented package, or the newest action when package is empty. */
    @Nullable
    public static MediaNotificationLikeSnapshot latestMediaNotificationLike(
            @Nullable String targetPackage) {
        List<MediaNotificationLikeSnapshot> actions;
        synchronized (MEDIA_SESSION_LOCK) {
            actions = new ArrayList<>(MEDIA_LIKE_ACTIONS.values());
        }
        Collections.reverse(actions);
        String target = targetPackage == null ? "" : targetPackage.trim();
        for (MediaNotificationLikeSnapshot action : actions) {
            if (target.isEmpty() || target.equals(action.packageName)) return action;
        }
        return null;
    }

    private void rebuildMediaSessions() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            clearMediaSessions();
            if (active == null) return;
            for (StatusBarNotification notification : active) rememberMediaSession(notification);
        } catch (RuntimeException ignored) {
        }
    }

    private void rememberMediaSession(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        try {
            Notification notification = sbn.getNotification();
            String key = sbn.getKey();
            if (key == null || key.isEmpty()) return;
            Object token = notification.extras == null ? null
                    : notification.extras.get(NotificationCompat.EXTRA_MEDIA_SESSION);
            boolean mediaNotification = token instanceof MediaSession.Token
                    || Notification.CATEGORY_TRANSPORT.equals(notification.category);
            Boolean ratingActive = token instanceof MediaSession.Token
                    ? mediaSessionLikeState((MediaSession.Token) token) : null;
            MediaNotificationLikeSnapshot like = mediaNotification ? findLikeAction(
                    sbn.getPackageName(), notification.actions, ratingActive) : null;
            boolean likeChanged;
            synchronized (MEDIA_SESSION_LOCK) {
                MediaNotificationLikeSnapshot previous = MEDIA_LIKE_ACTIONS.remove(key);
                if (like != null) {
                    like = new MediaNotificationLikeSnapshot(like.packageName,
                            like.pendingIntent, like.active, ++mediaLikeGeneration);
                    MEDIA_LIKE_ACTIONS.put(key, like);
                }
                likeChanged = previous != null || like != null;
            }
            if (likeChanged) notifyMediaLikeObservers();
            if (!(token instanceof MediaSession.Token)) return;
            synchronized (MEDIA_SESSION_LOCK) {
                // Reinsert so LinkedHashMap order reflects the newest notification update.
                MEDIA_SESSIONS.remove(key);
                MEDIA_SESSIONS.put(key,
                        new MediaNotificationSession((MediaSession.Token) token));
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static void forgetMediaSession(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) return;
        boolean likeChanged;
        synchronized (MEDIA_SESSION_LOCK) {
            MEDIA_SESSIONS.remove(sbn.getKey());
            likeChanged = MEDIA_LIKE_ACTIONS.remove(sbn.getKey()) != null;
        }
        if (likeChanged) notifyMediaLikeObservers();
    }

    private static void clearMediaSessions() {
        boolean likeChanged;
        synchronized (MEDIA_SESSION_LOCK) {
            MEDIA_SESSIONS.clear();
            likeChanged = !MEDIA_LIKE_ACTIONS.isEmpty();
            MEDIA_LIKE_ACTIONS.clear();
        }
        if (likeChanged) notifyMediaLikeObservers();
    }

    @Nullable
    private static MediaNotificationLikeSnapshot findLikeAction(
            @Nullable String packageName, @Nullable Notification.Action[] actions,
            @Nullable Boolean ratingActive) {
        if (actions == null) return null;
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null
                    || !MediaLikeActionPolicy.matchesNotificationAction(action.title)) continue;
            Boolean active = ratingActive != null
                    ? ratingActive : MediaLikeActionPolicy.activeFromTitle(action.title);
            return new MediaNotificationLikeSnapshot(
                    packageName == null ? "" : packageName, action.actionIntent, active, 0L);
        }
        return null;
    }

    @Nullable
    private Boolean mediaSessionLikeState(@NonNull MediaSession.Token token) {
        try {
            MediaMetadata metadata = new MediaController(this, token).getMetadata();
            if (metadata == null) return null;
            return MediaLikeActionPolicy.displayHeart(
                    heartValue(metadata.getRating(MediaMetadata.METADATA_KEY_RATING)),
                    heartValue(metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING)));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Boolean heartValue(@Nullable Rating rating) {
        if (rating == null || rating.getRatingStyle() != Rating.RATING_HEART) return null;
        return rating.hasHeart();
    }

    private static void notifyMediaLikeObservers() {
        List<MediaLikeObserver> observers;
        synchronized (MEDIA_SESSION_LOCK) {
            observers = new ArrayList<>(MEDIA_LIKE_OBSERVERS);
        }
        for (MediaLikeObserver observer : observers) {
            try {
                observer.onMediaLikeActionsChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class MediaNotificationSession {
        @NonNull final MediaSession.Token token;

        MediaNotificationSession(@NonNull MediaSession.Token token) {
            this.token = token;
        }
    }

    public static final class MediaNotificationLikeSnapshot {
        @NonNull public final String packageName;
        @Nullable public final Boolean active;
        public final long generation;
        @NonNull final PendingIntent pendingIntent;

        private MediaNotificationLikeSnapshot(@NonNull String packageName,
                                              @NonNull PendingIntent pendingIntent,
                                              @Nullable Boolean active,
                                              long generation) {
            this.packageName = packageName;
            this.pendingIntent = pendingIntent;
            this.active = active;
            this.generation = generation;
        }
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
        } catch (RuntimeException | OutOfMemoryError ignored) {
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
            } catch (OutOfMemoryError memoryPressure) {
                NavigationDataRepository.releaseDecodedGraphics();
                break;
            } catch (RuntimeException | LinkageError ignored) {
                continue;
            }
            if (candidate != null && NavigationDataRepository.isNotificationCandidateLive(
                    candidate, System.currentTimeMillis())) {
                candidates.add(candidate);
            }
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
