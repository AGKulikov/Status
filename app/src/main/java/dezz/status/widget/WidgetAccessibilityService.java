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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.launcher.NavigationCollectionDemand;
import dezz.status.widget.launcher.NavigationCollectionPolicy;
import dezz.status.widget.launcher.NavigationDataRepository;

/**
 * Accessibility service that reports the foreground package on every physical display and reads
 * route summaries from Yandex Maps/Navigator when a build exposes them only in its view tree.
 * Foreground tracking is needed because Geely Monjaro head units
 * run 4 displays in parallel: a user-app switch on display 2 must not change overlay
 * visibility on display 1 (and vice versa), but {@link android.app.usage.UsageStatsManager}
 * doesn't expose display IDs — its events are global. {@link AccessibilityWindowInfo} does.
 * <p>
 * The service is intentionally a thin reporter: it tracks the active package per display
 * and notifies the {@link WidgetService} singleton, which decides what to do based on the
 * display its own overlay window lives on. Disabling this accessibility service falls back
 * to the original single-display behaviour via {@link android.app.usage.UsageStatsManager}.
 */
public class WidgetAccessibilityService extends AccessibilityService {
    private static final String TAG = "WidgetA11yService";
    private static final long NAVIGATION_MISSING_GRACE_MS = 1_500L;
    private static final int MAX_NAVIGATION_NODES = 1_500;
    private static final int MAX_NAVIGATION_DEPTH = 45;
    private static final int BASE_ACCESSIBILITY_EVENTS =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;

    @Nullable
    private static volatile WidgetAccessibilityService instance;

    /** displayId → current foreground package. Updated on every window change event. */
    private final Map<Integer, String> foregroundByDisplay = new HashMap<>();
    private int consecutiveMissingNavigationScans;
    private HandlerThread navigationThread;
    private volatile Handler navigationHandler;
    private NavigationCollectionDemand navigationDemand;
    private volatile boolean serviceConnected;
    private volatile long lastNavigationScanElapsed;
    /** Accessed only on {@link #navigationThread}; prevents event storms postponing a scan. */
    private long nextNavigationScanElapsed;
    private static final class NavigationWindowScan {
        final Set<String> visiblePackages;
        final Set<String> routePackages;

        NavigationWindowScan(Set<String> visiblePackages, Set<String> routePackages) {
            this.visiblePackages = visiblePackages;
            this.routePackages = routePackages;
        }
    }
    private final Runnable navigationScan = new Runnable() {
        @Override
        public void run() {
            nextNavigationScanElapsed = 0L;
            if (!canCollectNavigation()) return;
            NavigationWindowScan scan;
            try {
                scan = scanNavigationWindows();
            } catch (RuntimeException | LinkageError ignored) {
                scheduleNavigationWatchdog(false);
                return;
            }
            lastNavigationScanElapsed = SystemClock.elapsedRealtime();
            boolean accessibilitySourceMissing =
                    NavigationDataRepository.isAccessibilitySourceMissing(
                            WidgetAccessibilityService.this, scan.routePackages);
            if (accessibilitySourceMissing) {
                consecutiveMissingNavigationScans++;
                if (consecutiveMissingNavigationScans >= 2) {
                    NavigationDataRepository.clearIfAccessibilitySourceMissing(
                            WidgetAccessibilityService.this, scan.routePackages);
                    consecutiveMissingNavigationScans = 0;
                }
            } else {
                consecutiveMissingNavigationScans = 0;
            }
            if (accessibilitySourceMissing && consecutiveMissingNavigationScans == 1) {
                // Require two empty scans so a transient window recreation does not flash the
                // route panel off while Navigator rotates or replaces its map surface.
                Handler worker = navigationHandler;
                if (worker != null && canCollectNavigation()) {
                    scheduleNavigationScanOnWorker(
                            SystemClock.elapsedRealtime() + NAVIGATION_MISSING_GRACE_MS);
                }
            } else {
                scheduleNavigationWatchdog(!scan.visiblePackages.isEmpty());
            }
        }
    };

    @Nullable
    public static WidgetAccessibilityService getInstance() {
        return instance;
    }

    /**
     * @param displayId numeric display ID (matches {@link android.view.Display#getDisplayId()}).
     * @return foreground package on that display, or {@code null} if we haven't seen one
     *         (display absent / no window event observed yet).
     */
    @Nullable
    public String getForegroundPackageOnDisplay(int displayId) {
        synchronized (foregroundByDisplay) {
            return foregroundByDisplay.get(displayId);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        serviceConnected = false;
        if (navigationDemand != null) {
            navigationDemand.stop();
            navigationDemand = null;
        }
        stopNavigationWorker();
        NavigationDataRepository.clearIfAccessibilitySourceMissing(this,
                Collections.emptySet());
        instance = null;
        synchronized (foregroundByDisplay) {
            foregroundByDisplay.clear();
        }
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            // Falling out of accessibility-driven tracking — let WidgetService refresh the
            // tracking pipeline (which falls back to UsageStatsManager polling).
            widget.onForegroundTrackingPathChanged();
        }
        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Initial seed: walk all windows currently known to the accessibility framework and
        // remember the per-display foreground packages. Otherwise the first event-driven
        // update would have to wait for a real window change.
        serviceConnected = true;
        if (navigationDemand == null) {
            navigationDemand = new NavigationCollectionDemand(this);
            navigationDemand.start(this::onNavigationDemandChanged);
        }
        updateNavigationEventSubscription(navigationDemand.isNeeded());
        seedFromCurrentWindows();
        if (navigationDemand.isNeeded()) requestNavigationScan(true);
        Log.i(TAG, "Connected. Seeded " + foregroundByDisplay.size() + " display(s).");
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundTrackingPathChanged();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        boolean windowChanged = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (windowChanged) {
            // The event itself carries one package, but we want a coherent snapshot of every
            // display, not only the one which changed.
            seedFromCurrentWindows();
            WidgetService widget = WidgetService.getInstance();
            if (widget != null) widget.onForegroundDisplayMapUpdated();
        }

        CharSequence packageName = event.getPackageName();
        if (canCollectNavigation() && (windowChanged || NavigationDataRepository.isYandexPackage(
                packageName == null ? null : packageName.toString()))) {
            // Debouncing coalesces the many TYPE_WINDOW_CONTENT_CHANGED events emitted while
            // Navigator updates distance/ETA. The actual read uses the complete tree, so a
            // maneuver distance such as "500 м" cannot overwrite the full remaining route.
            requestNavigationScan(false);
        }
    }

    @Override
    public void onInterrupt() {
        // No-op — we don't drive any feedback streams.
    }

    /**
     * Refreshes {@link #foregroundByDisplay} from the live list of accessibility windows.
     * <p>
     * On API 30+ we use {@link #getWindowsOnAllDisplays()} which returns a
     * {@code SparseArray<List<AccessibilityWindowInfo>>} keyed by display ID. Below that we
     * fall back to {@link #getWindows()} (single-display only) — the per-display behaviour
     * matters only on multi-display devices, which all run Android 10+/Auto so this fallback
     * is just for completeness.
     */
    private void seedFromCurrentWindows() {
        Map<Integer, String> next = new HashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            for (int i = 0; i < all.size(); i++) {
                int displayId = all.keyAt(i);
                String pkg = topApplicationPackage(all.valueAt(i));
                if (pkg != null) next.put(displayId, pkg);
            }
        } else {
            List<AccessibilityWindowInfo> windows = getWindows();
            String pkg = topApplicationPackage(windows);
            if (pkg != null) next.put(android.view.Display.DEFAULT_DISPLAY, pkg);
        }
        synchronized (foregroundByDisplay) {
            foregroundByDisplay.clear();
            foregroundByDisplay.putAll(next);
        }
    }

    private void ensureNavigationWorker() {
        if (navigationThread != null && navigationThread.isAlive()
                && navigationHandler != null) return;
        navigationThread = new HandlerThread("navigation-accessibility-collector",
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
        consecutiveMissingNavigationScans = 0;
        lastNavigationScanElapsed = 0L;
        nextNavigationScanElapsed = 0L;
    }

    private void onNavigationDemandChanged(boolean needed) {
        if (!serviceConnected) return;
        updateNavigationEventSubscription(needed);
        if (needed) {
            ensureNavigationWorker();
            requestNavigationScan(true);
        } else {
            stopNavigationWorker();
            // Only discard our own fallback source. Notification/broadcast sources retain their
            // independent lifecycle and can still serve another enabled consumer.
            NavigationDataRepository.clearIfAccessibilitySourceMissing(
                    WidgetAccessibilityService.this, Collections.emptySet());
        }
    }

    /**
     * Content-change events are extremely frequent while the map animates. Subscribe to them
     * only while a launcher/navigation consumer exists; foreground-app tracking keeps the two
     * inexpensive window lifecycle events at all times.
     */
    private void updateNavigationEventSubscription(boolean needed) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int desired = BASE_ACCESSIBILITY_EVENTS
                | (needed ? AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED : 0);
        if (info.eventTypes == desired) return;
        info.eventTypes = desired;
        try {
            setServiceInfo(info);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not update navigation accessibility events", failure);
        }
    }

    private boolean canCollectNavigation() {
        NavigationCollectionDemand demand = navigationDemand;
        return serviceConnected && demand != null && demand.isNeeded();
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

    private void scheduleNavigationWatchdog(boolean navigationSurfaceObserved) {
        scheduleNavigationScanOnWorker(SystemClock.elapsedRealtime()
                + NavigationCollectionPolicy.watchdogDelay(navigationSurfaceObserved));
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
        worker.removeCallbacks(navigationScan);
        nextNavigationScanElapsed = Math.max(now, deadlineElapsed);
        worker.postDelayed(navigationScan, nextNavigationScanElapsed - now);
    }

    /**
     * Reads text and content descriptions from every visible Yandex window. Selecting the largest
     * duration/distance in {@link dezz.status.widget.launcher.NavigationDataParser} filters out
     * the shorter next-maneuver values commonly present in the same tree.
     */
    private NavigationWindowScan scanNavigationWindows() {
        Map<String, Set<String>> valuesByPackage = new HashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all != null) {
                for (int i = 0; i < all.size(); i++) {
                    collectNavigationWindows(all.valueAt(i), valuesByPackage);
                }
            }
        } else {
            collectNavigationWindows(getWindows(), valuesByPackage);
        }

        // Some firmware returns an empty getWindows() list until touch exploration is active,
        // while getRootInActiveWindow() still works. Use it as a final fallback.
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            try {
                collectNavigationRoot(activeRoot, valuesByPackage);
            } finally {
                activeRoot.recycle();
            }
        }

        Set<String> routePackages = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : valuesByPackage.entrySet()) {
            if (!canCollectNavigation()) break;
            if (NavigationDataRepository.updateFromText(this, entry.getKey(),
                    new ArrayList<>(entry.getValue()))) {
                routePackages.add(entry.getKey());
            }
        }
        return new NavigationWindowScan(new HashSet<>(valuesByPackage.keySet()), routePackages);
    }

    private static boolean collectNavigationWindows(@Nullable List<AccessibilityWindowInfo> windows,
            Map<String, Set<String>> valuesByPackage) {
        if (windows == null) return false;
        boolean found = false;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            AccessibilityNodeInfo root;
            try {
                root = window.getRoot();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (root == null) continue;
            try {
                found |= collectNavigationRoot(root, valuesByPackage);
            } finally {
                root.recycle();
            }
        }
        return found;
    }

    private static boolean collectNavigationRoot(AccessibilityNodeInfo root,
            Map<String, Set<String>> valuesByPackage) {
        CharSequence packageName = root.getPackageName();
        String pkg = packageName == null ? "" : packageName.toString();
        if (!NavigationDataRepository.isYandexPackage(pkg)) return false;
        Set<String> values = valuesByPackage.get(pkg);
        if (values == null) {
            values = new LinkedHashSet<>();
            valuesByPackage.put(pkg, values);
        }
        collectNavigationNode(root, values, 0, new int[] {0});
        return true;
    }

    private static void collectNavigationNode(AccessibilityNodeInfo node, Set<String> values,
            int depth, int[] visited) {
        if (node == null || depth > MAX_NAVIGATION_DEPTH
                || visited[0]++ >= MAX_NAVIGATION_NODES) return;
        addNavigationText(values, node.getText());
        addNavigationText(values, node.getContentDescription());
        int childCount = Math.min(node.getChildCount(), MAX_NAVIGATION_NODES - visited[0]);
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (child == null) continue;
            try {
                collectNavigationNode(child, values, depth + 1, visited);
            } finally {
                child.recycle();
            }
        }
    }

    private static void addNavigationText(Set<String> values, @Nullable CharSequence value) {
        if (value == null) return;
        String text = value.toString().replace('\n', ' ').trim();
        if (!text.isEmpty()) values.add(text);
    }

    /**
     * Picks the topmost application window (not system / IME / accessibility overlay) from a
     * list of {@link AccessibilityWindowInfo}, and returns its package name via the root
     * AccessibilityNodeInfo. Higher layer = newer, so we walk in descending z-order.
     */
    @Nullable
    private static String topApplicationPackage(@Nullable List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        AccessibilityWindowInfo best = null;
        int bestLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            int layer = w.getLayer();
            if (layer > bestLayer) {
                bestLayer = layer;
                best = w;
            }
        }
        if (best == null) return null;
        android.view.accessibility.AccessibilityNodeInfo root = best.getRoot();
        if (root == null) return null;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } finally {
            root.recycle();
        }
    }
}
