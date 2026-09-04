/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Reads the real ECARX window inventory without touching Android 9's unstable accessibility tree.
 *
 * <p>The vendor AdaptAPI is optional and loaded only through reflection. Its observer callback
 * supplies no usable frame on the KX11 implementation, so every callback is merely a bounded,
 * debounced signal to fetch a fresh {@code getWindowList()} snapshot. Generic/non-ECARX builds
 * therefore keep their existing launch/focus/accessibility fallback and have no runtime link to
 * the vendor jar.</p>
 */
final class EcarxNavigatorWindowObserver {
    interface Listener {
        void onStateChanged(@NonNull NavigatorWindowFramePolicy.Result result);
    }

    interface ParkingListener {
        void onParkingStateChanged(@NonNull EcarxParkingWindowPolicy.State state);
    }

    private static final String COMPONENT = "navigator-window";
    private static final long CALLBACK_DEBOUNCE_MS = 80L;
    private static final long ABSENCE_CONFIRMATION_MS = 180L;
    private static final long WINDOW_CONFIRMATION_REFRESH_MS = 2_000L;
    private static final long FAILURE_LOG_INTERVAL_MS = 10_000L;
    private static final String UI_INTERACTION =
            "com.ecarx.xui.adaptapi.uiinteraction.UiInteraction";
    private static final String UI_INTERACTION_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IUiInteraction";
    private static final String WINDOW_MANAGER_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IWindowManager";
    private static final String WINDOW_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IWindowManager$IWindow";
    private static final String WINDOW_OBSERVER_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IWindowManager$IWindowObserver";
    private static final String WINDOW_VIEW_OBSERVER_INTERFACE =
            "com.ecarx.xui.adaptapi.uiinteraction.IWindowManager$IWindowViewObserver";
    private static final String[] YANDEX_PACKAGES = {
            "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.yandex.yango"
    };

    @NonNull private final Context appContext;
    @NonNull private final Listener listener;
    @Nullable private final ParkingListener parkingListener;
    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @NonNull private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ecarx-window-observer");
                thread.setDaemon(true);
                return thread;
            });
    @NonNull private final Object snapshotLock = new Object();
    @NonNull private final Set<Integer> yandexUids = new HashSet<>();
    @NonNull private final NavigatorWindowSourcePolicy.AbsenceGate absenceGate =
            new NavigatorWindowSourcePolicy.AbsenceGate();
    @NonNull private final EcarxParkingWindowPolicy.FreshSnapshots freshSnapshots =
            new EcarxParkingWindowPolicy.FreshSnapshots();

    private volatile boolean stopped;
    private volatile boolean parkingObservationNeeded;
    private volatile long parkingObservationGeneration;
    @Nullable private ScheduledFuture<?> parkingRefreshTask;
    private volatile int targetDisplayId = Display.DEFAULT_DISPLAY;
    @Nullable private VendorApi api;
    @Nullable private Object observerProxy;
    @NonNull private NavigatorWindowFramePolicy.State lastState =
            NavigatorWindowFramePolicy.State.UNKNOWN;
    @NonNull private String lastEvidence = "";
    @NonNull private String lastParkingEvidence = "";
    private long snapshotGeneration;
    private long confirmationRefreshGeneration;
    private long absenceConfirmationGeneration;
    private long lastFailureLogAt;

    EcarxNavigatorWindowObserver(@NonNull Context context, @NonNull Listener listener) {
        this(context, listener, null);
    }

    EcarxNavigatorWindowObserver(@NonNull Context context, @NonNull Listener listener,
                                 @Nullable ParkingListener parkingListener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.parkingListener = parkingListener;
    }

    void start(int displayId) {
        targetDisplayId = displayId;
        worker.execute(this::initializeAndSeed);
    }

    void setTargetDisplayId(int displayId) {
        if (targetDisplayId == displayId) return;
        targetDisplayId = displayId;
        requestSnapshot("display-changed", 0L);
    }

    /** Rechecks after an app-owned optimistic/focus transition even if the OEM emitted no event. */
    void refresh(@NonNull String reason) {
        requestSnapshot(reason, 0L);
    }

    /** Shares the vendor inventory with navigation; no second Binder channel or window-tree scan. */
    void setParkingObservationNeeded(boolean needed) {
        synchronized (snapshotLock) {
            if (stopped || parkingObservationNeeded == needed) return;
            parkingObservationNeeded = needed;
            parkingObservationGeneration++;
            if (parkingRefreshTask != null) parkingRefreshTask.cancel(false);
            parkingRefreshTask = null;
            if (!needed || parkingListener == null) return;
            // Poll even while hidden: a lost OEM callback must not lose the next opening/closing.
            parkingRefreshTask = worker.scheduleWithFixedDelay(() -> {
                if (stopped || !parkingObservationNeeded) return;
                if (api == null) initializeAndSeed();
                else takeSnapshot("parking-visibility-refresh");
            }, 0L, 1_000L, TimeUnit.MILLISECONDS);
        }
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        synchronized (snapshotLock) {
            snapshotGeneration++;
            confirmationRefreshGeneration++;
            absenceConfirmationGeneration++;
            parkingObservationGeneration++;
            if (parkingRefreshTask != null) parkingRefreshTask.cancel(false);
            parkingRefreshTask = null;
        }
        try {
            worker.execute(this::unregisterSafely);
        } catch (RuntimeException ignored) {
            // A duplicate lifecycle stop may race with executor shutdown; references are cleared
            // below by process teardown in either case.
        }
        worker.shutdown();
    }

    private void initializeAndSeed() {
        if (stopped) return;
        // start() and enabling parking observation can both enqueue initialization.
        if (api != null) {
            takeSnapshot("reseed");
            return;
        }
        try {
            resolveYandexUids();
            api = VendorApi.create(appContext);
            try {
                observerProxy = createObserverProxy(api.windowViewObserverInterface);
                Object registered = api.registerObserver.invoke(api.manager, observerProxy);
                logInfo(
                        "ECARX observer ready registered=" + String.valueOf(registered));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                // A few AdaptAPI variants reject registration but still expose a reliable
                // inventory. Retain a successfully-created proxy so stop() can unregister even
                // if the vendor threw after partially accepting the callback.
                reportFailure("ECARX observer registration unavailable", failure);
            }
            takeSnapshot("initial");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            reportFailure("ECARX observer unavailable", failure);
            publishUnknown();
        }
    }

    @NonNull
    private Object createObserverProxy(@NonNull Class<?> observerInterface) {
        InvocationHandler callback = (proxy, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                switch (name) {
                    case "toString": return "NatroEcarxWindowObserver";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return args != null && args.length == 1 && proxy == args[0];
                    default: return null;
                }
            }
            if (name.startsWith("onWindow")) {
                requestSnapshot("observer-" + name, CALLBACK_DEBOUNCE_MS);
            }
            return null;
        };
        return Proxy.newProxyInstance(observerInterface.getClassLoader(),
                new Class<?>[]{observerInterface}, callback);
    }

    private void requestSnapshot(@NonNull String reason, long delayMs) {
        final long generation;
        synchronized (snapshotLock) {
            if (stopped) return;
            generation = ++snapshotGeneration;
        }
        try {
            worker.schedule(() -> {
                synchronized (snapshotLock) {
                    if (stopped || generation != snapshotGeneration) return;
                }
                takeSnapshot(reason);
            }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            if (!stopped) reportFailure("ECARX snapshot scheduling rejected", failure);
        }
    }

    private void takeSnapshot(@NonNull String reason) {
        VendorApi current = api;
        if (stopped || current == null) return;
        int displayId = targetDisplayId;
        long parkingGeneration = parkingObservationGeneration;
        try {
            NavigatorWindowFramePolicy.Frame displayBounds = displayBounds(displayId);
            Object raw = current.getWindowList.invoke(current.manager);
            // AdaptAPI catches RemoteException and returns the same cached array. A successful
            // KX11 getWindowList() allocates a new array, including for an empty inventory.
            // Keep this identity guard across reconnects so a cached VISIBLE/HIDDEN cannot win.
            if (!freshSnapshots.accept(raw)) {
                throw new IllegalStateException("ECARX window inventory is missing or cached");
            }
            Object[] windows = (Object[]) raw;
            ArrayList<NavigatorWindowFramePolicy.WindowSample> samples =
                    new ArrayList<>(windows.length);
            ArrayList<EcarxParkingWindowPolicy.WindowSample> parkingSamples = new ArrayList<>();
            for (Object window : windows) {
                NavigatorWindowFramePolicy.WindowSample sample = sample(current, window);
                if (sample != null) samples.add(sample);
                if (parkingObservationNeeded) {
                    // A null entry means the inventory was incomplete, not that parking closed.
                    if (window == null) parkingSamples.add(null);
                    else {
                        EcarxParkingWindowPolicy.WindowSample parking = sampleParking(current, window);
                        if (parking != null) parkingSamples.add(parking);
                    }
                }
            }
            if (parkingObservationNeeded) {
                EcarxParkingWindowPolicy.State parkingState = EcarxParkingWindowPolicy.classify(
                        displayBounds(Display.DEFAULT_DISPLAY), Display.DEFAULT_DISPLAY,
                        parkingSamples);
                logParkingEvidence(parkingState, parkingSamples, reason);
                publishParkingState(parkingState, parkingGeneration);
            }
            NavigatorWindowFramePolicy.Result result = NavigatorWindowFramePolicy.classify(
                    displayBounds, displayId, samples);
            // Never publish evidence gathered for a display which ceased to own the overlay
            // while the vendor Binder call was in flight.
            if (displayId != targetDisplayId) return;
            publishResult(reason, result, displayId, displayBounds);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            reportFailure("ECARX window snapshot rejected", failure);
            // The parking refresh loop reconnects on its next tick. Do not retain a dead proxy.
            unregisterSafely();
            publishUnknown();
        }
    }

    @Nullable
    private EcarxParkingWindowPolicy.WindowSample sampleParking(@NonNull VendorApi current,
                                                                @NonNull Object window)
            throws ReflectiveOperationException {
        String packageName = stringValue(current.getPackage.invoke(window));
        if (packageName.isEmpty()) {
            return new EcarxParkingWindowPolicy.WindowSample("", -1, -1, -1, null);
        }
        if (!EcarxParkingWindowPolicy.PARKING_PACKAGE.equals(packageName)) return null;
        int visibility = intValue(current.getVisibility.invoke(window));
        // Hidden windows need no geometry. Some vendor versions cannot parse their old frame.
        Object rawFrame = visibility == 0 ? current.getFrame.invoke(window) : null;
        NavigatorWindowFramePolicy.Frame frame = null;
        if (rawFrame instanceof Rect) {
            Rect rect = (Rect) rawFrame;
            frame = new NavigatorWindowFramePolicy.Frame(rect.left, rect.top, rect.right, rect.bottom);
        }
        return new EcarxParkingWindowPolicy.WindowSample(packageName,
                intValue(current.getDisplayId.invoke(window)), intValue(current.getType.invoke(window)),
                visibility, frame);
    }

    private void logParkingEvidence(@NonNull EcarxParkingWindowPolicy.State state,
                                    @NonNull ArrayList<EcarxParkingWindowPolicy.WindowSample> samples,
                                    @NonNull String reason) {
        StringBuilder evidence = new StringBuilder("parking state=").append(state)
                .append(" display=").append(Display.DEFAULT_DISPLAY);
        for (EcarxParkingWindowPolicy.WindowSample sample : samples) {
            if (sample == null || sample.packageName.isEmpty()) {
                evidence.append(" incomplete-entry");
            } else {
                // No titles, tags or unrelated applications are recorded.
                evidence.append(" window={package=").append(sample.packageName)
                        .append(" display=").append(sample.displayId)
                        .append(" type=").append(sample.type)
                        .append(" visibility=").append(sample.visibility)
                        .append(" frame=").append(sample.frame).append('}');
            }
        }
        String text = evidence.toString();
        if (!text.equals(lastParkingEvidence)) {
            lastParkingEvidence = text;
            logInfo(text + " reason=" + reason);
        }
    }

    private void publishParkingState(@NonNull EcarxParkingWindowPolicy.State state,
                                     long generation) {
        if (parkingListener == null || !parkingObservationNeeded) return;
        mainHandler.post(() -> {
            if (!stopped && parkingObservationNeeded && generation == parkingObservationGeneration) {
                parkingListener.onParkingStateChanged(state);
            }
        });
    }

    @Nullable
    private NavigatorWindowFramePolicy.WindowSample sample(@NonNull VendorApi current,
                                                            @Nullable Object window)
            throws ReflectiveOperationException {
        if (window == null) return null;
        String packageName = stringValue(current.getPackage.invoke(window));
        int displayId = intValue(current.getDisplayId.invoke(window));
        int type = intValue(current.getType.invoke(window));
        int uid = intValue(current.getUid.invoke(window));
        int visibility = intValue(current.getVisibility.invoke(window));
        String identity = stringValue(current.getIdentity.invoke(window));
        String tag = stringValue(current.getTag.invoke(window));
        boolean yandexOwned = StatusBarSurfaceContext.isYandexPackage(packageName)
                || yandexUids.contains(uid)
                || containsExactYandexIdentity(identity)
                || containsExactYandexIdentity(tag);
        if (!yandexOwned) return null;
        Object rawFrame = current.getFrame.invoke(window);
        NavigatorWindowFramePolicy.Frame frame = null;
        if (rawFrame instanceof Rect) {
            Rect rect = (Rect) rawFrame;
            frame = new NavigatorWindowFramePolicy.Frame(
                    rect.left, rect.top, rect.right, rect.bottom);
        }
        return new NavigatorWindowFramePolicy.WindowSample(
                packageName, displayId, type, visibility, true, frame);
    }

    private void publishResult(@NonNull String reason,
                               @NonNull NavigatorWindowFramePolicy.Result result,
                               int displayId,
                               @Nullable NavigatorWindowFramePolicy.Frame displayBounds) {
        String evidence = evidence(result, displayId, displayBounds);
        boolean changed = result.state != lastState || !evidence.equals(lastEvidence);
        lastState = result.state;
        lastEvidence = evidence;
        if (changed) {
            logInfo("state=" + result.state + " reason=" + reason + " " + evidence);
        }
        boolean notifyListener;
        NavigatorWindowSourcePolicy.ObservationAction observationAction =
                absenceGate.observe(result.state);
        if (observationAction == NavigatorWindowSourcePolicy.ObservationAction.RETRY) {
            scheduleAbsenceConfirmation();
            return;
        }
        cancelAbsenceConfirmation();
        switch (result.state) {
            case WINDOWED:
                // Positive geometry is authoritative and repeated WINDOWED publications repair
                // any lower-confidence focus/accessibility transition that raced with the OEM.
                notifyListener = true;
                scheduleConfirmationRefresh();
                break;
            case FULLSCREEN:
                notifyListener = true;
                cancelConfirmationRefresh();
                break;
            case ABSENT:
                // AbsenceGate publishes only the second consecutive inventory absence. During
                // an explicit launch grace WidgetService keeps the bounded optimistic token;
                // otherwise this strong absence clears any stale lifecycle/a11y assertion.
                notifyListener = true;
                cancelConfirmationRefresh();
                break;
            case UNKNOWN:
            default:
                // The listener keeps the optimistic surface token but drops its vendor-only
                // UsageStats protection, so a dead Binder path cannot leave a permanent hide.
                notifyListener = true;
                cancelConfirmationRefresh();
                break;
        }
        if (notifyListener) {
            mainHandler.post(() -> {
                if (!stopped) listener.onStateChanged(result);
            });
        }
    }

    /**
     * Renews WidgetService's bounded vendor lease without replacing a newer OEM callback requery.
     */
    private void scheduleConfirmationRefresh() {
        final long generation;
        synchronized (snapshotLock) {
            if (stopped) return;
            generation = ++confirmationRefreshGeneration;
        }
        try {
            worker.schedule(() -> {
                synchronized (snapshotLock) {
                    if (stopped || generation != confirmationRefreshGeneration) return;
                }
                requestSnapshot("confirmation-lease", 0L);
            }, WINDOW_CONFIRMATION_REFRESH_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            if (!stopped) reportFailure("ECARX confirmation refresh rejected", failure);
        }
    }

    private void cancelConfirmationRefresh() {
        synchronized (snapshotLock) {
            confirmationRefreshGeneration++;
        }
    }

    private void scheduleAbsenceConfirmation() {
        final long generation;
        synchronized (snapshotLock) {
            if (stopped) return;
            generation = ++absenceConfirmationGeneration;
        }
        try {
            worker.schedule(() -> {
                synchronized (snapshotLock) {
                    if (stopped || generation != absenceConfirmationGeneration) return;
                }
                requestSnapshot("absence-confirmation", 0L);
            }, ABSENCE_CONFIRMATION_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            if (!stopped) reportFailure("ECARX absence confirmation rejected", failure);
        }
    }

    private void cancelAbsenceConfirmation() {
        synchronized (snapshotLock) {
            absenceConfirmationGeneration++;
        }
    }

    private void publishUnknown() {
        if (parkingObservationNeeded) {
            logParkingEvidence(EcarxParkingWindowPolicy.State.UNKNOWN, new ArrayList<>(), "unavailable");
        }
        publishParkingState(EcarxParkingWindowPolicy.State.UNKNOWN, parkingObservationGeneration);
        publishResult("unavailable", new NavigatorWindowFramePolicy.Result(
                NavigatorWindowFramePolicy.State.UNKNOWN, null, 0),
                targetDisplayId, null);
    }

    @NonNull
    private String evidence(@NonNull NavigatorWindowFramePolicy.Result result,
                            int displayId,
                            @Nullable NavigatorWindowFramePolicy.Frame displayBounds) {
        StringBuilder text = new StringBuilder()
                .append("display=").append(displayId)
                .append(" bounds=").append(displayBounds == null ? "none" : displayBounds)
                .append(" candidates=").append(result.visibleCandidateCount);
        NavigatorWindowFramePolicy.WindowSample sample = result.evidence;
        if (sample != null) {
            // Package, type and geometry are system window metadata. Identity/tag are used only
            // for exact ownership matching and deliberately never written to the journal.
            text.append(" package=").append(sample.packageName)
                    .append(" type=").append(sample.type)
                    .append(" visibility=").append(sample.visibility)
                    .append(" frame=").append(sample.frame == null ? "none" : sample.frame);
        }
        return text.toString();
    }

    @Nullable
    private NavigatorWindowFramePolicy.Frame displayBounds(int displayId) {
        DisplayManager manager = appContext.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) return null;
        Rect bounds = new Rect();
        try {
            display.getRectSize(bounds);
        } catch (RuntimeException ignored) {
            // Fall through to real metrics on older vendor Display implementations.
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        return bounds.width() > 0 && bounds.height() > 0
                ? new NavigatorWindowFramePolicy.Frame(
                        bounds.left, bounds.top, bounds.right, bounds.bottom)
                : null;
    }

    private void resolveYandexUids() {
        PackageManager manager = appContext.getPackageManager();
        for (String packageName : YANDEX_PACKAGES) {
            try {
                ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
                yandexUids.add(info.uid);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Unified builds legitimately omit one or two candidate packages.
            }
        }
    }

    private static boolean containsExactYandexIdentity(@Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        String normalized = value.toLowerCase(Locale.US);
        for (String packageName : YANDEX_PACKAGES) {
            if (normalized.contains(packageName)) return true;
        }
        return false;
    }

    private void unregisterSafely() {
        VendorApi current = api;
        Object observer = observerProxy;
        api = null;
        observerProxy = null;
        if (current == null || observer == null) return;
        try {
            current.unregisterObserver.invoke(current.manager, observer);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            reportFailure("ECARX observer unregister rejected", failure);
        }
    }

    private void reportFailure(@NonNull String operation, @NonNull Throwable failure) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastFailureLogAt != 0L && now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return;
        lastFailureLogAt = now;
        Throwable cause = failure instanceof InvocationTargetException
                && ((InvocationTargetException) failure).getTargetException() != null
                ? ((InvocationTargetException) failure).getTargetException() : failure;
        logWarn(operation + " " + cause.getClass().getSimpleName());
    }

    private static void logInfo(@NonNull String message) {
        try {
            DiagnosticJournal.info(COMPONENT, message);
        } catch (RuntimeException | LinkageError ignored) {
            // Window observation remains functional even if diagnostics storage is unavailable.
        }
    }

    private static void logWarn(@NonNull String message) {
        try {
            DiagnosticJournal.warn(COMPONENT, message);
        } catch (RuntimeException | LinkageError ignored) {
            // Optional telemetry must never become a foreground-surface transition.
        }
    }

    private static int intValue(@Nullable Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    @NonNull
    private static String stringValue(@Nullable Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static final class VendorApi {
        @NonNull final Object manager;
        @NonNull final Class<?> windowViewObserverInterface;
        @NonNull final Method getWindowList;
        @NonNull final Method registerObserver;
        @NonNull final Method unregisterObserver;
        @NonNull final Method getDisplayId;
        @NonNull final Method getPackage;
        @NonNull final Method getType;
        @NonNull final Method getUid;
        @NonNull final Method getVisibility;
        @NonNull final Method getFrame;
        @NonNull final Method getIdentity;
        @NonNull final Method getTag;

        private VendorApi(@NonNull Object manager,
                          @NonNull Class<?> windowViewObserverInterface,
                          @NonNull Method getWindowList,
                          @NonNull Method registerObserver,
                          @NonNull Method unregisterObserver,
                          @NonNull Method getDisplayId,
                          @NonNull Method getPackage,
                          @NonNull Method getType,
                          @NonNull Method getUid,
                          @NonNull Method getVisibility,
                          @NonNull Method getFrame,
                          @NonNull Method getIdentity,
                          @NonNull Method getTag) {
            this.manager = manager;
            this.windowViewObserverInterface = windowViewObserverInterface;
            this.getWindowList = getWindowList;
            this.registerObserver = registerObserver;
            this.unregisterObserver = unregisterObserver;
            this.getDisplayId = getDisplayId;
            this.getPackage = getPackage;
            this.getType = getType;
            this.getUid = getUid;
            this.getVisibility = getVisibility;
            this.getFrame = getFrame;
            this.getIdentity = getIdentity;
            this.getTag = getTag;
        }

        @NonNull
        static VendorApi create(@NonNull Context context) throws ReflectiveOperationException {
            Class<?> interactionFactory = Class.forName(UI_INTERACTION);
            Object interaction = interactionFactory.getMethod("create", Context.class)
                    .invoke(null, context.getApplicationContext());
            if (interaction == null) throw new IllegalStateException("UiInteraction unavailable");
            Class<?> interactionInterface = Class.forName(UI_INTERACTION_INTERFACE);
            Object manager = interactionInterface.getMethod("getWindowManager")
                    .invoke(interaction);
            if (manager == null) throw new IllegalStateException("WindowManager unavailable");

            Class<?> managerInterface = Class.forName(WINDOW_MANAGER_INTERFACE);
            Class<?> windowInterface = Class.forName(WINDOW_INTERFACE);
            Class<?> baseObserverInterface = Class.forName(WINDOW_OBSERVER_INTERFACE);
            Class<?> viewObserverInterface = Class.forName(WINDOW_VIEW_OBSERVER_INTERFACE);
            return new VendorApi(
                    manager,
                    viewObserverInterface,
                    managerInterface.getMethod("getWindowList"),
                    managerInterface.getMethod("registerWindowObserver", baseObserverInterface),
                    managerInterface.getMethod("unregisterWindowObserver", baseObserverInterface),
                    windowInterface.getMethod("getDisplayId"),
                    windowInterface.getMethod("getPackage"),
                    windowInterface.getMethod("getType"),
                    windowInterface.getMethod("getUID"),
                    windowInterface.getMethod("getViewVisibility"),
                    windowInterface.getMethod("getWindowFrame"),
                    windowInterface.getMethod("getWindowIdentity"),
                    windowInterface.getMethod("getWindowTag"));
        }
    }
}
