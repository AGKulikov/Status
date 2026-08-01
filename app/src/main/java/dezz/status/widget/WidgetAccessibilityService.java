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
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
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
import dezz.status.widget.diagnostics.ActionRecorder;
import dezz.status.widget.diagnostics.DiagnosticJournal;

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
    private static final long FRAMEWORK_FAILURE_LOG_INTERVAL_MS = 10_000L;
    private static final int MAX_NAVIGATION_NODES = 1_500;
    private static final int MAX_NAVIGATION_DEPTH = 45;
    private static final int BASE_ACCESSIBILITY_EVENTS =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;

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
    private long lastFrameworkFailureLogElapsed;
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
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                reportFrameworkFailure("navigation window scan", failure);
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
     * Performs Android's global Back action for the driver-panel button.
     *
     * @return {@code true} when the enabled accessibility service accepted the request.
     */
    public static boolean performGlobalBack() {
        WidgetAccessibilityService current = instance;
        if (current == null) return false;
        new Handler(Looper.getMainLooper()).post(
                () -> current.performGlobalAction(GLOBAL_ACTION_BACK));
        return true;
    }

    /** Opens Android's system recent-applications surface. */
    public static boolean performGlobalRecents() {
        return performGlobalRecents(success -> {});
    }

    public interface GlobalActionCallback {
        void onFinished(boolean success);
    }

    /**
     * Opens Recents and reports whether the OEM accessibility service accepted the action.
     * Callers can safely fall back to keyevent 187 when an ECARX build returns false.
     */
    public static boolean performGlobalRecents(@NonNull GlobalActionCallback callback) {
        WidgetAccessibilityService current = instance;
        if (current == null) return false;
        new Handler(Looper.getMainLooper()).post(() -> {
            boolean accepted;
            try {
                accepted = current.performGlobalAction(GLOBAL_ACTION_RECENTS);
            } catch (RuntimeException failure) {
                accepted = false;
            }
            callback.onFinished(accepted);
        });
        return true;
    }

    /**
     * Injects one short screen tap. The driver panel temporarily marks its own windows
     * NOT_TOUCHABLE before calling this method, so input is delivered to the covered OEM button
     * instead of recursively activating our proxy icon.
     */
    public interface TapCallback {
        void onFinished(boolean success);
    }

    public static boolean performTap(int x, int y, @NonNull TapCallback callback) {
        WidgetAccessibilityService current = instance;
        if (current == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Handler main = new Handler(Looper.getMainLooper());
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dispatchTap(current, main, x, y, callback);
        }
        // Existing callers enter from the main thread. Keep the public helper safe for a future
        // worker-thread caller, but do not claim a second injection is required merely because an
        // already accepted gesture is later cancelled by an ECARX window transition.
        main.post(() -> {
            if (!dispatchTap(current, main, x, y, callback)) {
                callback.onFinished(false);
            }
        });
        return true;
    }

    /**
     * @return {@code true} only when Android accepted this exact gesture for dispatch.
     */
    private static boolean dispatchTap(@NonNull WidgetAccessibilityService current,
                                       @NonNull Handler main,
                                       int x,
                                       int y,
                                       @NonNull TapCallback callback) {
        try {
            Path path = new Path();
            path.moveTo(Math.max(0, x), Math.max(0, y));
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, 80L))
                    .build();
            return current.dispatchGesture(gesture,
                    new GestureResultCallback() {
                        @Override public void onCompleted(
                                GestureDescription gestureDescription) {
                            callback.onFinished(true);
                        }

                        @Override public void onCancelled(
                                GestureDescription gestureDescription) {
                            callback.onFinished(false);
                        }
                    }, main);
        } catch (RuntimeException error) {
            return false;
        }
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
        boolean windowTraversalAllowed = supportsSafeWindowTraversal();
        updateNavigationEventSubscription(
                navigationDemand.isNeeded() && windowTraversalAllowed);
        if (windowTraversalAllowed) {
            seedFromCurrentWindowsSafely("service connection");
            if (navigationDemand.isNeeded()) requestNavigationScan(true);
        } else {
            // Android 9/ECARX can terminate the client process in native framework code while
            // unparcelling Navigator's rapidly replaced AccessibilityWindowInfo tree. A Java
            // catch cannot contain that failure. Foreground tracking therefore uses only the
            // small event package below, while navigation content continues through the normal
            // broadcast/notification contracts and never asks this firmware for a window tree.
            Log.i(TAG, "Android 9 safe mode: accessibility window traversal disabled");
        }
        Log.i(TAG, "Connected. Seeded " + foregroundByDisplay.size() + " display(s).");
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundTrackingPathChanged();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            handleAccessibilityEvent(event);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            // ECARX may invalidate an AccessibilityWindowInfo while Navigator replaces its
            // transparent splash/map windows. A stale framework object is a failed sample, not
            // a reason to terminate the Status Widget process.
            reportFrameworkFailure("accessibility event", failure);
        }
    }

    private void handleAccessibilityEvent(@NonNull AccessibilityEvent event) {
        int type = event.getEventType();
        CharSequence packageValue = event.getPackageName();
        String eventPackage = packageValue == null ? "" : packageValue.toString().trim();
        if (ActionRecorder.isRecording()) {
            CharSequence classValue = event.getClassName();
            ActionRecorder.record(ActionRecorder.SOURCE_ACCESSIBILITY,
                    AccessibilityEvent.eventTypeToString(type),
                    ActionRecorder.object(
                            "package", packageValue == null ? "" : packageValue.toString(),
                            "class", classValue == null ? "" : classValue.toString(),
                            "window_id", event.getWindowId(),
                            "action", event.getAction(),
                            "content_change_types", event.getContentChangeTypes()));
        }
        boolean windowChanged = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (windowChanged) {
            boolean foregroundChanged;
            if (supportsSafeWindowTraversal()) {
                // Android 10+ can provide a coherent framework snapshot. Android 9 deliberately
                // takes the event-only path below and never calls getWindows()/getRoot().
                foregroundChanged = seedFromCurrentWindowsSafely("window transition");
            } else {
                foregroundChanged = publishAndroidNineForegroundEvent(type, eventPackage);
            }
            if (foregroundChanged) {
                WidgetService widget = WidgetService.getInstance();
                if (widget != null) widget.onForegroundDisplayMapUpdated();
            }
        }

        if (canCollectNavigation() && (windowChanged || NavigationDataRepository.isYandexPackage(
                eventPackage))) {
            // Debouncing coalesces the many TYPE_WINDOW_CONTENT_CHANGED events emitted while
            // Navigator updates distance/ETA. The actual read uses the complete tree, so a
            // maneuver distance such as "500 м" cannot overwrite the full remaining route.
            requestNavigationScan(false);
        }
    }

    /**
     * API 28 has no reliable display id on AccessibilityEvent. It is nevertheless safer to retain
     * one event-derived default-display package than to ask the ECARX framework to materialize
     * Navigator's complete window tree in the Status Widget process.
     */
    private boolean publishAndroidNineForegroundEvent(int eventType,
                                                       @NonNull String packageName) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || packageName.isEmpty() || packageName.equals(getPackageName())) {
            return false;
        }
        synchronized (foregroundByDisplay) {
            String previous = foregroundByDisplay.put(
                    android.view.Display.DEFAULT_DISPLAY, packageName);
            return !packageName.equals(previous);
        }
    }

    /** Android 9 ECARX window traversal is a native-process hazard and cannot be caught in Java. */
    private static boolean supportsSafeWindowTraversal() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    @Override
    public void onInterrupt() {
        // No-op — we don't drive any feedback streams.
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int displayId = keyDisplayId(event);
        ActionRecorder.record(ActionRecorder.SOURCE_STEERING_KEY, "KEY_EVENT",
                ActionRecorder.object(
                        "key_code", event.getKeyCode(),
                        "scan_code", event.getScanCode(),
                        "action", event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP",
                        "repeat", event.getRepeatCount(),
                        "long_press", event.isLongPress(),
                        "device_id", event.getDeviceId(),
                        "source", event.getSource(),
                        "display_id", displayId,
                        "down_time", event.getDownTime(),
                        "event_time", event.getEventTime()));
        DiagnosticJournal.debug("key",
                "keyCode=" + event.getKeyCode() + " action=" + event.getAction()
                        + " repeat=" + event.getRepeatCount() + " display=" + displayId);
        // Observing must never consume a steering-wheel or hardware key.
        return false;
    }

    /**
     * ECARX exposes an InputEvent display id on some builds, but it is hidden from the public
     * Android SDK used to compile the APK. Reflection keeps the extra diagnostic when available
     * without making steering-key capture depend on that OEM extension.
     */
    private static int keyDisplayId(@NonNull KeyEvent event) {
        try {
            Object value = event.getClass().getMethod("getDisplayId").invoke(event);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
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
    private boolean seedFromCurrentWindowsSafely(@NonNull String reason) {
        try {
            Map<Integer, String> next = new HashMap<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.util.SparseArray<List<AccessibilityWindowInfo>> all =
                        getWindowsOnAllDisplays();
                if (all == null) return false;
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
            // Publish only a complete snapshot. If Android invalidates one of Navigator's
            // windows halfway through the read, the last coherent display map remains active.
            synchronized (foregroundByDisplay) {
                foregroundByDisplay.clear();
                foregroundByDisplay.putAll(next);
            }
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            reportFrameworkFailure(reason, failure);
            return false;
        }
    }

    private void reportFrameworkFailure(@NonNull String operation, @NonNull Throwable failure) {
        if (failure instanceof OutOfMemoryError) return;
        long now = SystemClock.elapsedRealtime();
        if (lastFrameworkFailureLogElapsed != 0L
                && now - lastFrameworkFailureLogElapsed < FRAMEWORK_FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFrameworkFailureLogElapsed = now;
        String detail = operation + " rejected " + failure.getClass().getSimpleName();
        try { Log.w(TAG, detail); }
        catch (RuntimeException | LinkageError ignored) {}
        try { DiagnosticJournal.warn("navigator-window", detail); }
        catch (RuntimeException | LinkageError ignored) {}
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
        boolean collectFromWindows = needed && supportsSafeWindowTraversal();
        updateNavigationEventSubscription(collectFromWindows);
        if (collectFromWindows) {
            ensureNavigationWorker();
            requestNavigationScan(true);
        } else {
            stopNavigationWorker();
            if (!needed) {
                // Only discard our own fallback source. Notification/broadcast sources retain
                // their independent lifecycle and can still serve another enabled consumer.
                NavigationDataRepository.clearIfAccessibilitySourceMissing(
                        WidgetAccessibilityService.this, Collections.emptySet());
            }
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
        int desiredFlags = info.flags
                | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        if (info.eventTypes == desired && info.flags == desiredFlags) return;
        info.eventTypes = desired;
        info.flags = desiredFlags;
        try {
            setServiceInfo(info);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not update navigation accessibility events", failure);
        }
    }

    private boolean canCollectNavigation() {
        NavigationCollectionDemand demand = navigationDemand;
        return supportsSafeWindowTraversal()
                && serviceConnected && demand != null && demand.isNeeded();
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
            try {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                int layer = w.getLayer();
                if (layer > bestLayer) {
                    bestLayer = layer;
                    best = w;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // A window can disappear between getWindows() and this read.
            }
        }
        if (best == null) return null;
        android.view.accessibility.AccessibilityNodeInfo root;
        try {
            root = best.getRoot();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        if (root == null) return null;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } finally {
            try { root.recycle(); }
            catch (RuntimeException | LinkageError ignored) {}
        }
    }
}
