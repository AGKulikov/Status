/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.IdentityHashMap;
import java.util.Map;

/** Activity-owned floating window with no resources or manifest additions. */
final class FloatingWindowController {
    private static final String ACTION_FLOATING = "navi_win/ru.yandex.yandexnavi";
    private static final String EXTRA_WINDOWED = "ddnavwin";
    private static final String EXTRA_FORCE_FULLSCREEN = "ddnavforcewinfull";
    // v2 could persist 1920×720 after Navigator reapplied fullscreen flags. A separate namespace
    // resets that poisoned geometry once; subsequent window moves remain remembered.
    private static final String PREFS = "natro_floating_window_v3";
    private static final int MODE_FULLSCREEN = 0;
    private static final int MODE_FLOATING = 1;
    private static final int MODE_TOGGLE = 2;
    private static final long MODE_BUTTON_REBIND_MS = 5_000L;
    private static final long FLOATING_CONTRACT_CHECK_MS = 1_000L;
    private static final String STOCK_RECT_CONTROL_CLASS =
            "ru.yandex.yandexmaps.common.views.controls.MapControlsFrameLayoutRect";
    // Exact 29.4.2 KX11 window lane: 0x20 | 0x200 | 0x40000.
    private static final int FLOATING_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    private static final int MUTATED_FLAGS =
            FLOATING_FLAGS
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;

    private final Activity activity;
    private final Window window;
    private final SharedPreferences preferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int originalType;
    private final int originalFlags;
    private final int originalGravity;
    private final int originalFormat;
    private final int originalSystemUi;
    private final Drawable originalBackground;
    private final float originalElevation;
    private final float originalDimAmount;
    private final int originalStatusBarColor;
    private final int originalNavigationBarColor;
    private final ViewOutlineProvider originalOutlineProvider;
    private final boolean originalClipToOutline;

    private FloatingWindowProfile profile = new FloatingWindowProfile();
    private FrameLayout controlLayer;
    private ViewGroup modeButton;
    private ModeToggleIconView modeButtonIcon;
    private boolean modeButtonCreationAttempted;
    private TextView closeButton;
    private TextView dragHandle;
    private TextView resizeHandle;
    private View insetDispatchHost;
    private Drawable floatingBackground;
    private Drawable floatingFrame;
    private View contentRoot;
    private View mapRoot;
    private View mapWithControls;
    private View activityControllerRoot;
    private View controlsInsetHost;
    private View controlsEngine;
    private View topNotificationRoot;
    private View guidanceControls;
    private View guidanceInsetHost;
    private View guidanceVisualRoot;
    private View topNotificationContent;
    private Drawable originalContentBackground;
    private Drawable originalMapRootBackground;
    private Drawable originalMapWithControlsBackground;
    private Drawable transparentContentBackground;
    private Drawable transparentMapRootBackground;
    private Drawable transparentMapWithControlsBackground;
    private String appliedConfigurationRaw;
    private boolean floating;
    private boolean floatingIdentityRejected;
    private boolean destroyed;
    private boolean geometryLoaded;
    private boolean transparentLayersCaptured;
    private final int[] originalContentPadding = new int[4];
    private final int[] originalMapRootPadding = new int[4];
    private final int[] originalMapWithControlsPadding = new int[4];
    private final int[] originalActivityControllerRootPadding = new int[4];
    private final int[] originalControlsInsetHostPadding = new int[4];
    private final int[] originalControlsEnginePadding = new int[4];
    private final int[] originalTopNotificationPadding = new int[4];
    private final int[] originalGuidanceControlsPadding = new int[4];
    private final int[] originalGuidanceInsetHostPadding = new int[4];
    private final int[] originalGuidanceVisualRootPadding = new int[4];
    private final int[] originalTopNotificationContentPadding = new int[4];
    private boolean originalContentFitsSystemWindows;
    private boolean originalMapRootFitsSystemWindows;
    private boolean originalMapWithControlsFitsSystemWindows;
    private boolean originalActivityControllerRootFitsSystemWindows;
    private boolean originalControlsInsetHostFitsSystemWindows;
    private boolean originalControlsEngineFitsSystemWindows;
    private boolean originalTopNotificationFitsSystemWindows;
    private boolean originalGuidanceControlsFitsSystemWindows;
    private boolean originalGuidanceInsetHostFitsSystemWindows;
    private boolean originalGuidanceVisualRootFitsSystemWindows;
    private boolean originalTopNotificationContentFitsSystemWindows;
    private int floatingControlsEngineTop;
    private int floatingGuidanceControlsTop;
    private int floatingTopNotificationContentTop;
    private final Map<View, Integer> paddingtonBaseTopByChild = new IdentityHashMap<>();
    private int reportedPaddingtonOverrideCount = -1;
    private final int mapTouchSlopSquared;
    private boolean mapGestureInProgress;
    private boolean mapTapCandidate;
    private float mapTouchDownX;
    private float mapTouchDownY;
    private boolean floatingPreDrawGuardInstalled;
    private int roundedOutlineWidth = -1;
    private int roundedOutlineHeight = -1;
    private int roundedOutlineRadius = -1;
    private final View.OnApplyWindowInsetsListener modeAwareInsetsListener =
            new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(
                        View view, WindowInsets insets) {
                    try {
                        if (!floating || Build.VERSION.SDK_INT < 20) {
                            return view.onApplyWindowInsets(insets);
                        }
                        // A bounded Navigator window has no status bar inside its own map viewport.
                        // Keep left/right/bottom safe areas intact, but do not make Navigator reserve
                        // the head unit's global status-bar height a second time.
                        WindowInsets adjusted = insets.replaceSystemWindowInsets(
                                insets.getSystemWindowInsetLeft(),
                                0,
                                insets.getSystemWindowInsetRight(),
                                insets.getSystemWindowInsetBottom());
                        return view.onApplyWindowInsets(adjusted);
                    } catch (Throwable failure) {
                        reportCallbackFailure("modeAwareInsets", failure);
                        return insets;
                    }
                }
            };
    private final View.OnApplyWindowInsetsListener floatingPaddingtonInsetsListener =
            new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(
                        View view, WindowInsets insets) {
                    try {
                        Integer captured = paddingtonBaseTopByChild.get(view);
                        int baseTop = captured == null ? 0 : captured;
                        if (floating) {
                            // PaddingtonView's stock listener ignores the supplied WindowInsets and
                            // reads the global root inset again. Own the child listener while this is
                            // a bounded window so a late stock dispatch cannot put the gap back.
                            setTopPadding(view, baseTop);
                            return zeroTop(insets);
                        }
                        // Mode changes recreate MapActivity, but retain stock-equivalent behaviour if
                        // a rejected window transition restores this same Activity in place.
                        int topInset = insets == null ? 0 : insets.getSystemWindowInsetTop();
                        setTopPadding(view, baseTop + Math.max(0, topInset));
                        return insets;
                    } catch (Throwable failure) {
                        reportCallbackFailure("paddingtonInsets", failure);
                        return insets;
                    }
                }
            };
    private final View.OnLayoutChangeListener floatingTopInsetGuard =
            new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int left, int top, int right,
                        int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    try {
                        if (!floating) return;
                        if (view == activityControllerRoot || view == guidanceVisualRoot) {
                            setTopPadding(view, 0);
                        } else if (view == controlsEngine) {
                            setTopPadding(view, floatingControlsEngineTop);
                        } else if (view == guidanceControls) {
                            setTopPadding(view, floatingGuidanceControlsTop);
                        } else if (view == topNotificationContent) {
                            setTopPadding(view, floatingTopNotificationContentTop);
                        }
                    } catch (Throwable failure) {
                        reportCallbackFailure("floatingTopInsetGuard", failure);
                    }
                }
            };
    private final ViewTreeObserver.OnPreDrawListener floatingTopInsetPreDrawGuard = () -> {
        try {
            if (floating) {
                // PaddingtonView.onAttachedToWindow writes the raw root inset directly and does
                // not invoke its child listener. Reassert the four common zero-base roots at the
                // final UI boundary as well, so a late attach/inset pass cannot reach the screen.
                setTopPadding(controlsEngine, 0);
                setTopPadding(activityControllerRoot, 0);
                setTopPadding(guidanceControls, 0);
                setTopPadding(guidanceVisualRoot, 0);
            }
        } catch (Throwable failure) {
            reportCallbackFailure("floatingTopInsetPreDraw", failure);
        }
        return true;
    };
    private final ViewOutlineProvider roundedOutlineProvider = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            int radius = Math.max(0, Math.round(profile.cornerRadiusDp
                    * activity.getResources().getDisplayMetrics().density));
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    };

    private final Runnable floatingSurfaceCommitter = new Runnable() {
        @Override public void run() {
            if (destroyed || !floating || activity.isFinishing()) return;
            WindowManager.LayoutParams attributes = window.getAttributes();
            try {
                // setFormat commits the 2038 identity. A following setLayout guarantees that the
                // compositor also receives the bounded frame instead of retaining SplashAppTheme's
                // original full-screen surface.
                window.setLayout(attributes.width, attributes.height);
                window.setFormat(PixelFormat.TRANSLUCENT);
                enforceTransparentLayers();
                applyRoundedClip();
                reportCommittedFrame();
            } catch (Throwable failure) {
                reportCallbackFailure("floatingSurfaceCommitter", failure);
            }
        }
    };

    FloatingWindowController(Activity activity) {
        this.activity = activity;
        window = activity.getWindow();
        preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        WindowManager.LayoutParams initial = window.getAttributes();
        originalType = initial.type;
        originalFlags = initial.flags;
        originalGravity = initial.gravity;
        originalFormat = initial.format;
        View decor = window.getDecorView();
        originalSystemUi = decor.getSystemUiVisibility();
        originalBackground = decor.getBackground();
        originalElevation = decor.getElevation();
        originalDimAmount = initial.dimAmount;
        originalStatusBarColor = window.getStatusBarColor();
        originalNavigationBarColor = window.getNavigationBarColor();
        originalOutlineProvider = decor.getOutlineProvider();
        originalClipToOutline = decor.getClipToOutline();
        int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        mapTouchSlopSquared = touchSlop * touchSlop;
    }

    void install() {
        if (destroyed || controlLayer != null) return;
        ViewGroup host = findControlHost();
        if (host == null) return;
        // This full-size layer owns only the three visible Natro controls below. Keeping the
        // layer itself non-clickable makes every other point fall through to Navigator without a
        // side effect before MapActivity.dispatchTouchEvent has completed.
        controlLayer = new FrameLayout(activity);
        controlLayer.setClipChildren(false);
        controlLayer.setClipToPadding(false);
        controlLayer.setClickable(false);
        controlLayer.setFocusable(false);
        controlLayer.setElevation(dp(100));
        host.addView(controlLayer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dragHandle = control("⋮⋮", "Переместить окно Навигатора");
        dragHandle.setOnTouchListener(new DragTouchListener());
        controlLayer.addView(dragHandle);

        resizeHandle = control("◢", "Изменить размер окна Навигатора");
        resizeHandle.setOnTouchListener(new ResizeTouchListener());
        controlLayer.addView(resizeHandle);

        closeButton = control("×", "Закрыть Навигатор");
        closeButton.setOnClickListener(view -> activity.finish());
        controlLayer.addView(closeButton);

        ensureModeButtonCreated();
        holdModeButtonHidden();
        mainHandler.post(modeButtonPoller);
        updateControls();
    }

    /**
     * Observes the complete stock gesture but never mutates Navigator's hierarchy inside it.
     * Rebinding after ACTION_DOWN used to run before MapActivity's own dispatcher and broke its
     * tap-to-toggle state machine. Only a completed, unmoved single-pointer tap schedules work,
     * and Handler.post guarantees that work runs after the current stock dispatch returns.
     */
    void onMapTouch(MotionEvent event) {
        if (destroyed || event == null) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mapGestureInProgress = true;
                mapTapCandidate = event.getPointerCount() == 1;
                mapTouchDownX = event.getX();
                mapTouchDownY = event.getY();
                return;
            case MotionEvent.ACTION_POINTER_DOWN:
                mapTapCandidate = false;
                return;
            case MotionEvent.ACTION_MOVE:
                if (mapTapCandidate) {
                    float deltaX = event.getX() - mapTouchDownX;
                    float deltaY = event.getY() - mapTouchDownY;
                    if (deltaX * deltaX + deltaY * deltaY > mapTouchSlopSquared) {
                        mapTapCandidate = false;
                    }
                }
                return;
            case MotionEvent.ACTION_CANCEL:
                mapGestureInProgress = false;
                mapTapCandidate = false;
                return;
            case MotionEvent.ACTION_UP:
                boolean completedTap = mapGestureInProgress && mapTapCandidate
                        && event.getPointerCount() == 1;
                mapGestureInProgress = false;
                mapTapCandidate = false;
                if (!completedTap) return;
                mainHandler.post(() -> {
                    try {
                        if (destroyed || activity.isFinishing()) return;
                        // MapActivity may finish onResumeFragments before Conductor installs
                        // android.R.id.content. A completed stock tap is the safe second admission
                        // point because Navigator has already consumed the whole gesture.
                        if (controlLayer == null) install();
                        ensureControlLayerAttached();
                        updateModeButtons();
                    } catch (Throwable failure) {
                        reportCallbackFailure("mapTouchReattach", failure);
                    }
                });
                return;
            default:
                return;
        }
    }

    void consumeIntent(Intent intent) {
        if (destroyed || intent == null) return;
        boolean requestedFloating = requestsFloating(intent);
        if (requestedFloating && floatingIdentityRejected) {
            updateControls();
            return;
        }
        if (requestedFloating == floating) {
            if (floating) enforceFloatingWindowContract();
            updateControls();
            return;
        }
        // The working 29.4.2 mod enters its overlay from onResumeFragments, after AppCompat and
        // MapKit have finished creating the Activity window. Applying type 2038 from the first
        // instruction of onCreate gives Android 9 an Activity window without a valid app token
        // and crashes the KX11 process before our Java exception guard can recover it.
        if (requestedFloating) applyFloatingAttributes(true);
        else applyFullscreenAttributes();
    }

    boolean requestsFloating(Intent intent) {
        if (intent == null || intent.getBooleanExtra(EXTRA_FORCE_FULLSCREEN, false)) return false;
        return profile.enabled && (intent.getBooleanExtra(EXTRA_WINDOWED, false)
                || ACTION_FLOATING.equals(intent.getAction()));
    }

    void restartInMode(boolean nextFloating, Intent source) {
        if (destroyed || activity.isFinishing() || (!profile.enabled && nextFloating)) return;
        Intent restart = source == null
                ? new Intent(activity, activity.getClass())
                : new Intent(source).setClass(activity, activity.getClass());
        restart.removeExtra(EXTRA_WINDOWED);
        restart.removeExtra(EXTRA_FORCE_FULLSCREEN);
        if (nextFloating) restart.putExtra(EXTRA_WINDOWED, true);
        else restart.putExtra(EXTRA_FORCE_FULLSCREEN, true);
        // Same hand-off flags as the working 29.4.2 implementation. The replacement Activity
        // consumes ddnavwin at the end of onResumeFragments, not before Activity.onCreate.
        restart.addFlags(0x04008000);
        activity.finish();
        activity.startActivity(restart);
    }

    void applyConfiguration(String rawConfiguration) {
        // The provider supplies the profile synchronously before the Activity enters floating
        // mode, then the authenticated Binder session publishes the same snapshot again. ECARX
        // Android 9 cannot safely accept repeated type-2038 setAttributes transactions for an
        // already attached Activity window: the surface is removed a few seconds later without
        // a Java exception. Treat an identical wire snapshot as an idempotent delivery.
        if (rawConfiguration != null && rawConfiguration.equals(appliedConfigurationRaw)) return;
        appliedConfigurationRaw = rawConfiguration;
        FloatingWindowProfile next = FloatingWindowProfile.fromConfiguration(rawConfiguration);
        boolean windowContractChanged = !profile.sameWindowContract(next);
        profile = next;
        // HUD-map, route and traffic settings share this wire document. They must not cause a
        // second type-2038 setAttributes transaction when the floating-window block itself did
        // not change; the 2.4.5 road log shows that transaction immediately before Navigator's
        // Binder session disappeared. Controls may still be refreshed without touching WindowManager.
        if (!windowContractChanged) {
            updateControls();
            return;
        }
        if (!profile.enabled && floating) restartInMode(false, null);
        else if (floating) applyFloatingAttributes(false);
        else updateControls();
    }

    void setWindowMode(int mode) {
        if (destroyed || (!profile.enabled && mode != MODE_FULLSCREEN)) return;
        boolean next = mode == MODE_TOGGLE ? !floating : mode == MODE_FLOATING;
        if (next == floating) {
            if (floating) enforceFloatingWindowContract();
            updateControls();
            return;
        }
        restartInMode(next, null);
    }

    boolean isFloating() {
        return floating;
    }

    /** Keep controls as a sibling above the complete map tree, including its late-added views. */
    private ViewGroup findControlHost() {
        View content = window.getDecorView().findViewById(android.R.id.content);
        if (content instanceof ViewGroup) return (ViewGroup) content;
        int rootId = activity.getResources().getIdentifier(
                "map_activity_root", "id", activity.getPackageName());
        View mapRoot = rootId == 0 ? null : activity.findViewById(rootId);
        if (mapRoot instanceof ViewGroup) return (ViewGroup) mapRoot;
        View decor = window.getDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    private final Runnable modeButtonPoller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try {
                // Never reparent either the Natro layer or the stock-rail child while Android is
                // dispatching one gesture. The next poll is at most one second away in a window.
                if (!mapGestureInProgress) {
                    ensureControlLayerAttached();
                    if (floating) enforceFloatingWindowContract();
                    if (controlLayer != null) controlLayer.bringToFront();
                    updateModeButtons();
                }
            } catch (Throwable failure) {
                reportCallbackFailure("modeButtonPoller", failure);
            } finally {
                if (!destroyed) {
                    long delay = floating
                            ? FLOATING_CONTRACT_CHECK_MS : MODE_BUTTON_REBIND_MS;
                    mainHandler.postDelayed(this, delay);
                }
            }
        }
    };

    /** Natro callbacks are guests in Navigator's main process and may never crash its looper. */
    private void reportCallbackFailure(String stage, Throwable failure) {
        NatroEntryPoint.reportFailure("FloatingWindowController." + stage, failure);
    }

    /** Reparents the overlay if Navigator replaced its content root during a late fragment pass. */
    private void ensureControlLayerAttached() {
        FrameLayout layer = controlLayer;
        if (destroyed || layer == null) return;
        ViewGroup host = findControlHost();
        if (host == null) return;
        ViewGroup parent = layer.getParent() instanceof ViewGroup
                ? (ViewGroup) layer.getParent() : null;
        if (parent != host || !layer.isAttachedToWindow()) {
            if (parent != null) {
                try { parent.removeView(layer); } catch (RuntimeException ignored) {}
            }
            if (layer.getParent() == null) {
                host.addView(layer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        layer.setVisibility(View.VISIBLE);
        layer.setElevation(dp(100));
        layer.bringToFront();
    }

    void destroy() {
        destroyed = true;
        mainHandler.removeCallbacks(modeButtonPoller);
        window.getDecorView().removeCallbacks(floatingSurfaceCommitter);
        if (Build.VERSION.SDK_INT >= 20 && insetDispatchHost != null) {
            insetDispatchHost.setOnApplyWindowInsetsListener(null);
            insetDispatchHost = null;
        }
        removeFloatingTopInsetPreDrawGuard();
        removeFloatingTopInsetGuards();
        clearPaddingtonInsetsOverrides();
        detachFromParent(modeButton);
        modeButton = null;
        modeButtonIcon = null;
        ViewGroup parent = controlLayer == null ? null : (ViewGroup) controlLayer.getParent();
        if (parent != null) parent.removeView(controlLayer);
        controlLayer = null;
    }

    private void applyFloatingAttributes(boolean entering) {
        DisplayMetrics screen = realDisplayMetrics();
        WindowManager.LayoutParams attributes = window.getAttributes();
        int defaultWidth = Math.max(dp(200), screen.widthPixels * profile.widthPercent / 100);
        int defaultHeight = Math.max(dp(200), screen.heightPixels * profile.heightPercent / 100);
        if (entering || !geometryLoaded) {
            attributes.width = profile.rememberGeometry
                    ? preferences.getInt("width", defaultWidth) : defaultWidth;
            attributes.height = profile.rememberGeometry
                    ? preferences.getInt("height", defaultHeight) : defaultHeight;
            attributes.x = profile.rememberGeometry
                    ? preferences.getInt("x", screen.widthPixels * profile.leftPercent / 100)
                    : screen.widthPixels * profile.leftPercent / 100;
            attributes.y = profile.rememberGeometry
                    ? preferences.getInt("y", screen.heightPixels * profile.topPercent / 100)
                    : screen.heightPixels * profile.topPercent / 100;
            geometryLoaded = true;
        }
        clampGeometry(attributes, screen);
        // Match the working 29.4.2 lifecycle: this method is called only from the tail of
        // onResumeFragments, after the Activity token and MapKit content are attached.
        attributes.type = floatingWindowType();
        attributes.gravity = Gravity.TOP | Gravity.START;
        attributes.flags = (attributes.flags
                | FLOATING_FLAGS
                | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                & ~(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        attributes.format = PixelFormat.TRANSLUCENT;
        attributes.dimAmount = 0f;
        try {
            floatingIdentityRejected = false;
            floating = true;
            applyFloatingDecoration();
            // PhoneWindow.setFormat dispatches the already-mutated LayoutParams. The reference
            // uses this ordering and does not call setAttributes before the overlay token exists.
            window.setFormat(PixelFormat.TRANSLUCENT);
            if (!entering) window.setAttributes(attributes);
            View decor = window.getDecorView();
            decor.removeCallbacks(floatingSurfaceCommitter);
            decor.postOnAnimation(floatingSurfaceCommitter);
            updateControls();
            NavigationBridgeClient.reportDiagnostic("floating window applied type="
                    + attributes.type + ", format=" + attributes.format + ", bounds="
                    + attributes.width + "x" + attributes.height + "@"
                    + attributes.x + "," + attributes.y + ", flags=0x"
                    + Integer.toHexString(attributes.flags));
        } catch (RuntimeException failure) {
            floating = false;
            floatingIdentityRejected = true;
            restoreWindowIdentity(attributes);
            try { window.setAttributes(attributes); } catch (RuntimeException ignored) {}
            Toast.makeText(activity,
                    "Оконный режим не разрешён прошивкой ГУ", Toast.LENGTH_SHORT).show();
            NavigationBridgeClient.reportDiagnostic("floating window rejected: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void applyFullscreenAttributes() {
        if (floating && profile.rememberGeometry) saveGeometry();
        WindowManager.LayoutParams attributes = window.getAttributes();
        restoreWindowIdentity(attributes);
        attributes.width = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.height = WindowManager.LayoutParams.MATCH_PARENT;
        attributes.x = 0;
        attributes.y = 0;
        try { window.setAttributes(attributes); } catch (RuntimeException ignored) {}
        floating = false;
        View decor = window.getDecorView();
        decor.setAlpha(1f);
        window.setBackgroundDrawable(originalBackground);
        decor.setBackground(originalBackground);
        floatingBackground = null;
        floatingFrame = null;
        if (controlLayer != null) controlLayer.setBackground(null);
        decor.setOutlineProvider(originalOutlineProvider);
        decor.setClipToOutline(originalClipToOutline);
        decor.setElevation(originalElevation);
        decor.setSystemUiVisibility(originalSystemUi);
        window.setStatusBarColor(originalStatusBarColor);
        window.setNavigationBarColor(originalNavigationBarColor);
        restoreTransparentLayers();
        removeFloatingTopInsetPreDrawGuard();
        requestNavigatorInsets();
        updateControls();
    }

    private void restoreWindowIdentity(WindowManager.LayoutParams attributes) {
        attributes.type = originalType;
        attributes.gravity = originalGravity;
        attributes.format = originalFormat;
        attributes.dimAmount = originalDimAmount;
        attributes.flags = (attributes.flags & ~MUTATED_FLAGS)
                | (originalFlags & MUTATED_FLAGS);
    }

    private void applyFloatingDecoration() {
        View decor = window.getDecorView();
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setCornerRadius(Math.max(0, Math.round(profile.cornerRadiusDp
                * activity.getResources().getDisplayMetrics().density)));
        // The working 29.4.2 contract uses a transparent activity/decor surface. An opaque
        // background leaves a full-screen black plane behind the resized map on KX11 even when
        // WindowManager reports the expected floating bounds.
        floatingBackground = background;
        window.setBackgroundDrawable(floatingBackground);
        decor.setBackground(floatingBackground);
        captureTransparentLayers(decor);
        installModeAwareInsetDispatch();
        requestNavigatorInsets();
        enforceTransparentLayers();
        window.setStatusBarColor(Color.TRANSPARENT);

        // The classes12 hook selects MapKit's own movable TextureView before this Activity is
        // created in floating mode. Keep clipping disabled until WindowManager commits the
        // bounded translucent surface, then clip the normal view hierarchy safely.
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setCornerRadius(dp(profile.cornerRadiusDp));
        if (profile.borderWidthDp > 0) {
            frame.setStroke(dp(profile.borderWidthDp),
                    Color.parseColor(profile.borderColor));
        }
        floatingFrame = frame;
        if (controlLayer != null) controlLayer.setBackground(floatingFrame);
        decor.setOutlineProvider(originalOutlineProvider);
        decor.setClipToOutline(false);
        decor.setElevation(0f);
        decor.setAlpha(profile.opacityPercent / 100f);
        // Lay this bounded surface through the global status-bar area. The real system bar remains
        // outside the window; only Navigator's duplicated internal top reservation is removed.
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /**
     * The reviewed APK theme makes the Activity translucent before launch, as in working 29.4.2.
     * Navigator can still assign opaque drawable backgrounds during its late layout passes, so
     * keep the content and both map containers transparent while the bounded window is active.
     */
    private void captureTransparentLayers(View decor) {
        // MapWithControlsView can be installed after the first onResumeFragments callback. Resolve
        // each missing root independently on every bounded-window contract pass, while retaining
        // the original state of roots already captured.
        if (contentRoot == null) {
            contentRoot = decor.findViewById(android.R.id.content);
            originalContentBackground = backgroundOf(contentRoot);
            capturePadding(contentRoot, originalContentPadding);
            originalContentFitsSystemWindows = fitsSystemWindows(contentRoot);
        }
        if (mapRoot == null) {
            int mapRootId = activity.getResources().getIdentifier(
                    "map_activity_root", "id", activity.getPackageName());
            mapRoot = mapRootId == 0 ? null : activity.findViewById(mapRootId);
            originalMapRootBackground = backgroundOf(mapRoot);
            capturePadding(mapRoot, originalMapRootPadding);
            originalMapRootFitsSystemWindows = fitsSystemWindows(mapRoot);
        }
        if (mapWithControls == null) {
            int mapViewId = activity.getResources().getIdentifier(
                    "activity_search_map_view", "id", activity.getPackageName());
            mapWithControls = mapViewId == 0 ? null : activity.findViewById(mapViewId);
            originalMapWithControlsBackground = backgroundOf(mapWithControls);
            capturePadding(mapWithControls, originalMapWithControlsPadding);
            originalMapWithControlsFitsSystemWindows = fitsSystemWindows(mapWithControls);
        }
        if (activityControllerRoot == null) {
            int controllerRootId = activity.getResources().getIdentifier(
                    "activity_container_controller", "id", activity.getPackageName());
            activityControllerRoot = controllerRootId == 0
                    ? null : activity.findViewById(controllerRootId);
            capturePadding(activityControllerRoot, originalActivityControllerRootPadding);
            originalActivityControllerRootFitsSystemWindows =
                    fitsSystemWindows(activityControllerRoot);
        }
        if (controlsEngine == null) {
            int controlsEngineId = activity.getResources().getIdentifier(
                    "controls_engine_container", "id", activity.getPackageName());
            controlsEngine = controlsEngineId == 0
                    ? null : activity.findViewById(controlsEngineId);
            capturePadding(controlsEngine, originalControlsEnginePadding);
            originalControlsEngineFitsSystemWindows = fitsSystemWindows(controlsEngine);
            // maps_activity.xml gives this exact PaddingtonView child zero base top padding.
            // Do not infer it from a live value that may already include the KX11 root inset.
            if (controlsEngine != null) paddingtonBaseTopByChild.put(controlsEngine, 0);
        }
        if (controlsInsetHost == null && controlsEngine != null
                && controlsEngine.getParent() instanceof View) {
            // maps_activity.xml wraps the complete controls engine in PaddingtonView. That
            // sibling of MapWithControlsView owns Navigator's real safe-area padding, so fixing
            // only the map cannot move the maneuver card and the right-side controls upward.
            controlsInsetHost = (View) controlsEngine.getParent();
            capturePadding(controlsInsetHost, originalControlsInsetHostPadding);
            originalControlsInsetHostFitsSystemWindows = fitsSystemWindows(controlsInsetHost);
        }
        if (topNotificationRoot == null) {
            int notificationId = activity.getResources().getIdentifier(
                    "maps_activity_top_notification_container", "id",
                    activity.getPackageName());
            topNotificationRoot = notificationId == 0
                    ? null : activity.findViewById(notificationId);
            capturePadding(topNotificationRoot, originalTopNotificationPadding);
            originalTopNotificationFitsSystemWindows = fitsSystemWindows(topNotificationRoot);
        }
        // The active-route controller is added late and can be replaced without recreating the
        // Activity. Re-resolve it on every bounded-window pass instead of retaining a detached
        // free-drive or previous-Guidance subtree.
        View nextGuidanceControls = viewByName("navi_guidance_controls_touch_container");
        View nextGuidanceInsetHost = nextGuidanceControls != null
                && nextGuidanceControls.getParent() instanceof View
                ? (View) nextGuidanceControls.getParent() : null;
        if (nextGuidanceControls != guidanceControls
                || nextGuidanceInsetHost != guidanceInsetHost) {
            if (guidanceControls != null) {
                guidanceControls.removeOnLayoutChangeListener(floatingTopInsetGuard);
            }
            guidanceControls = nextGuidanceControls;
            guidanceInsetHost = nextGuidanceInsetHost;
            capturePadding(guidanceControls, originalGuidanceControlsPadding);
            capturePadding(guidanceInsetHost, originalGuidanceInsetHostPadding);
            originalGuidanceControlsFitsSystemWindows = fitsSystemWindows(guidanceControls);
            originalGuidanceInsetHostFitsSystemWindows = fitsSystemWindows(guidanceInsetHost);
            // navi_guidance_integration_controller.xml also declares an exact zero base padding.
            // Reflection/fallback against a live child is too late on KX11: the stock
            // PaddingtonView may already have written the global status-bar height into it.
            floatingGuidanceControlsTop = 0;
            if (guidanceControls != null) {
                paddingtonBaseTopByChild.put(guidanceControls, 0);
            }
        }
        // CarGuidanceController is attached after the integration container and its stock root has
        // no id. Resolve the one parent that contains both upper Guidance widgets. On KX11 this
        // root (or the outer Conductor host) can receive the global status-bar inset even after
        // the surrounding PaddingtonView child has been restored to its XML padding.
        View nextGuidanceVisualRoot = activeGuidanceVisualRoot();
        if (nextGuidanceVisualRoot != guidanceVisualRoot) {
            if (guidanceVisualRoot != null) {
                guidanceVisualRoot.removeOnLayoutChangeListener(floatingTopInsetGuard);
                restorePadding(guidanceVisualRoot, originalGuidanceVisualRootPadding);
                setFitsSystemWindows(guidanceVisualRoot,
                        originalGuidanceVisualRootFitsSystemWindows);
            }
            guidanceVisualRoot = nextGuidanceVisualRoot;
            capturePadding(guidanceVisualRoot, originalGuidanceVisualRootPadding);
            originalGuidanceVisualRootFitsSystemWindows =
                    fitsSystemWindows(guidanceVisualRoot);
            if (guidanceVisualRoot != null) {
                NavigationBridgeClient.reportDiagnostic(
                        "floating Guidance root captured outerTop="
                                + (activityControllerRoot == null ? -1
                                : activityControllerRoot.getPaddingTop())
                                + ", visualTop=" + guidanceVisualRoot.getPaddingTop());
            }
        }
        if (topNotificationContent == null) {
            int topNotificationContentId = activity.getResources().getIdentifier(
                    "top_notification_container", "id", activity.getPackageName());
            topNotificationContent = topNotificationContentId == 0
                    ? null : activity.findViewById(topNotificationContentId);
            capturePadding(topNotificationContent, originalTopNotificationContentPadding);
            originalTopNotificationContentFitsSystemWindows =
                    fitsSystemWindows(topNotificationContent);
            floatingTopNotificationContentTop = paddingtonBaseTop(topNotificationContent);
        }
        if (controlsEngine != null && floatingControlsEngineTop == 0) {
            // This is zero in maps_activity.xml. Keep the reflective/fallback lookup here so the
            // contract remains correct if the stock layout later introduces a base top padding.
            floatingControlsEngineTop = paddingtonBaseTop(controlsEngine);
        }
        if (transparentContentBackground == null) {
            transparentContentBackground = new ColorDrawable(Color.TRANSPARENT);
        }
        if (transparentMapRootBackground == null) {
            transparentMapRootBackground = new ColorDrawable(Color.TRANSPARENT);
        }
        if (transparentMapWithControlsBackground == null) {
            transparentMapWithControlsBackground = new ColorDrawable(Color.TRANSPARENT);
        }
        transparentLayersCaptured = contentRoot != null || mapRoot != null
                || mapWithControls != null || activityControllerRoot != null
                || controlsInsetHost != null
                || controlsEngine != null || topNotificationRoot != null
                || guidanceInsetHost != null || guidanceControls != null
                || guidanceVisualRoot != null
                || topNotificationContent != null;
    }

    private void enforceTransparentLayers() {
        View decor = window.getDecorView();
        captureTransparentLayers(decor);
        if (floatingBackground != null && decor.getBackground() != floatingBackground) {
            window.setBackgroundDrawable(floatingBackground);
            decor.setBackground(floatingBackground);
        }
        setBackground(contentRoot, transparentContentBackground);
        setBackground(mapRoot, transparentMapRootBackground);
        setBackground(mapWithControls, transparentMapWithControlsBackground);
        int paddingtonCount = neutralizePaddingtonTree(controlsInsetHost != null
                ? controlsInsetHost : controlsEngine);
        paddingtonCount += neutralizePaddingtonTree(topNotificationRoot);
        // The Conductor host owns every late free-drive/Guidance controller. Scanning it avoids
        // retaining a stale subtree and catches PaddingtonView instances created after route
        // transitions. Keep the named Guidance fallback for early layouts without that host.
        paddingtonCount += neutralizePaddingtonTree(activityControllerRoot);
        View exactGuidanceInsetRoot = guidanceInsetHost != null
                ? guidanceInsetHost : guidanceControls;
        // The KX11 Guidance router is not guaranteed to be a descendant of the generic
        // activity_container_controller returned by this Activity. Always own the specifically
        // resolved route PaddingtonView when it is outside that tree; a one-time padding write is
        // otherwise undone by its next stock inset callback.
        if (exactGuidanceInsetRoot != null && (activityControllerRoot == null
                || !isDescendantOf(exactGuidanceInsetRoot, activityControllerRoot))) {
            paddingtonCount += neutralizePaddingtonTree(exactGuidanceInsetRoot);
        }
        if (paddingtonCount != reportedPaddingtonOverrideCount) {
            reportedPaddingtonOverrideCount = paddingtonCount;
            NavigationBridgeClient.reportDiagnostic(
                    "floating top inset: owned PaddingtonView children=" + paddingtonCount);
        }
        removeFloatingTopInset(contentRoot);
        removeFloatingTopInset(mapRoot);
        removeFloatingTopInset(mapWithControls);
        removeFloatingTopInset(activityControllerRoot);
        removeFloatingTopInset(controlsInsetHost);
        setTopPadding(controlsEngine, floatingControlsEngineTop);
        removeFloatingTopInset(topNotificationRoot);
        removeFloatingTopInset(guidanceInsetHost);
        setTopPadding(guidanceControls, floatingGuidanceControlsTop);
        removeFloatingTopInset(guidanceVisualRoot);
        setTopPadding(topNotificationContent, floatingTopNotificationContentTop);
        setFitsSystemWindows(contentRoot, false);
        setFitsSystemWindows(mapRoot, false);
        setFitsSystemWindows(mapWithControls, false);
        setFitsSystemWindows(activityControllerRoot, false);
        setFitsSystemWindows(controlsInsetHost, false);
        setFitsSystemWindows(controlsEngine, false);
        setFitsSystemWindows(topNotificationRoot, false);
        setFitsSystemWindows(guidanceInsetHost, false);
        setFitsSystemWindows(guidanceControls, false);
        setFitsSystemWindows(guidanceVisualRoot, false);
        setFitsSystemWindows(topNotificationContent, false);
        requestLayout(contentRoot);
        requestLayout(mapRoot);
        requestLayout(mapWithControls);
        requestLayout(activityControllerRoot);
        requestLayout(controlsInsetHost);
        requestLayout(controlsEngine);
        requestLayout(topNotificationRoot);
        requestLayout(guidanceInsetHost);
        requestLayout(guidanceControls);
        requestLayout(guidanceVisualRoot);
        requestLayout(topNotificationContent);
        installFloatingTopInsetGuards();
        installFloatingTopInsetPreDrawGuard();
        dispatchFloatingInsetsToNavigatorRoots();
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.dimAmount != 0f) attributes.dimAmount = 0f;
    }

    private void restoreTransparentLayers() {
        if (!transparentLayersCaptured) return;
        if (contentRoot != null) contentRoot.setBackground(originalContentBackground);
        if (mapRoot != null) mapRoot.setBackground(originalMapRootBackground);
        if (mapWithControls != null) {
            mapWithControls.setBackground(originalMapWithControlsBackground);
        }
        restorePadding(contentRoot, originalContentPadding);
        restorePadding(mapRoot, originalMapRootPadding);
        restorePadding(mapWithControls, originalMapWithControlsPadding);
        restorePadding(activityControllerRoot, originalActivityControllerRootPadding);
        restorePadding(controlsInsetHost, originalControlsInsetHostPadding);
        restorePadding(controlsEngine, originalControlsEnginePadding);
        restorePadding(topNotificationRoot, originalTopNotificationPadding);
        restorePadding(guidanceInsetHost, originalGuidanceInsetHostPadding);
        restorePadding(guidanceControls, originalGuidanceControlsPadding);
        restorePadding(guidanceVisualRoot, originalGuidanceVisualRootPadding);
        restorePadding(topNotificationContent, originalTopNotificationContentPadding);
        setFitsSystemWindows(contentRoot, originalContentFitsSystemWindows);
        setFitsSystemWindows(mapRoot, originalMapRootFitsSystemWindows);
        setFitsSystemWindows(mapWithControls, originalMapWithControlsFitsSystemWindows);
        setFitsSystemWindows(activityControllerRoot,
                originalActivityControllerRootFitsSystemWindows);
        setFitsSystemWindows(controlsInsetHost, originalControlsInsetHostFitsSystemWindows);
        setFitsSystemWindows(controlsEngine, originalControlsEngineFitsSystemWindows);
        setFitsSystemWindows(topNotificationRoot, originalTopNotificationFitsSystemWindows);
        setFitsSystemWindows(guidanceInsetHost, originalGuidanceInsetHostFitsSystemWindows);
        setFitsSystemWindows(guidanceControls, originalGuidanceControlsFitsSystemWindows);
        setFitsSystemWindows(guidanceVisualRoot,
                originalGuidanceVisualRootFitsSystemWindows);
        setFitsSystemWindows(topNotificationContent,
                originalTopNotificationContentFitsSystemWindows);
        removeFloatingTopInsetGuards();
    }

    /** Makes only the bounded window ignore the head unit's global status-bar top inset. */
    private void installModeAwareInsetDispatch() {
        if (Build.VERSION.SDK_INT < 20) return;
        // Intercept at DecorView before AppCompat/Navigator can turn the top inset into a second
        // reserved strip inside the already bounded floating window.
        View host = window.getDecorView();
        if (host == null) return;
        if (insetDispatchHost != null && insetDispatchHost != host) {
            insetDispatchHost.setOnApplyWindowInsetsListener(null);
        }
        insetDispatchHost = host;
        // Navigator can replace DecorView's listener after onResumeFragments. Reinstall our
        // mode-aware listener on each bounded-window contract pass instead of trusting identity.
        insetDispatchHost.setOnApplyWindowInsetsListener(modeAwareInsetsListener);
    }

    private void requestNavigatorInsets() {
        if (Build.VERSION.SDK_INT >= 20 && insetDispatchHost != null) {
            insetDispatchHost.requestApplyInsets();
        }
    }

    /** Sends zero-top insets to both the map and its independent controls subtree on Android 9. */
    private void dispatchFloatingInsetsToNavigatorRoots() {
        if (!floating || Build.VERSION.SDK_INT < 23) return;
        View decor = window.getDecorView();
        WindowInsets raw = decor == null ? null : decor.getRootWindowInsets();
        if (raw == null) return;
        WindowInsets adjusted = raw.replaceSystemWindowInsets(
                raw.getSystemWindowInsetLeft(), 0,
                raw.getSystemWindowInsetRight(), raw.getSystemWindowInsetBottom());
        dispatchAdjustedInsets(mapWithControls != null ? mapWithControls
                : mapRoot != null ? mapRoot : contentRoot, adjusted);
        dispatchAdjustedInsets(activityControllerRoot, adjusted);
        // PaddingtonView and top-notification are siblings of MapWithControlsView. Dispatching
        // only to the map leaves the large global status-bar reserve on all upper UI elements.
        dispatchAdjustedInsets(controlsInsetHost != null
                ? controlsInsetHost : controlsEngine, adjusted);
        dispatchAdjustedInsets(topNotificationRoot, adjusted);
        dispatchAdjustedInsets(guidanceInsetHost != null
                ? guidanceInsetHost : guidanceControls, adjusted);
        dispatchAdjustedInsets(topNotificationContent != null
                && topNotificationContent.getParent() instanceof View
                ? (View) topNotificationContent.getParent() : topNotificationContent, adjusted);
        // PaddingtonView 30.3.0 ignores the callback argument and reads raw root insets again.
        // Reassert its captured XML padding synchronously after dispatch; the layout guard keeps
        // the same invariant when Navigator issues a later inset pass of its own.
        setTopPadding(controlsEngine, floatingControlsEngineTop);
        setTopPadding(activityControllerRoot, 0);
        setTopPadding(guidanceControls, floatingGuidanceControlsTop);
        setTopPadding(guidanceVisualRoot, 0);
        setTopPadding(topNotificationContent, floatingTopNotificationContentTop);
    }

    /** Replaces every live PaddingtonView child listener in the bounded controls subtrees. */
    private int neutralizePaddingtonTree(View root) {
        if (!floating || Build.VERSION.SDK_INT < 20 || root == null) return 0;
        int count = 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (root.getClass().getName().endsWith(".PaddingtonView")
                    && group.getChildCount() == 1) {
                View child = group.getChildAt(0);
                Integer baseTop = paddingtonBaseTopByChild.get(child);
                if (baseTop == null) {
                    // Both named full-screen map-control children have zero XML top padding.
                    // Prefer that structural fact over a live padding value already polluted by
                    // the vendor root inset. Other PaddingtonView children retain their own base.
                    baseTop = child == controlsEngine || child == guidanceControls
                            ? 0 : paddingtonBaseTop(child);
                    paddingtonBaseTopByChild.put(child, baseTop);
                }
                // ViewCompat uses the platform listener on API 28. Setting ours directly replaces
                // the stock wrapper which otherwise reads getRootView() and restores the gap.
                child.setOnApplyWindowInsetsListener(floatingPaddingtonInsetsListener);
                setTopPadding(child, baseTop);
                count++;
            }
            int childCount = group.getChildCount();
            for (int index = 0; index < childCount; index++) {
                count += neutralizePaddingtonTree(group.getChildAt(index));
            }
        }
        return count;
    }

    private void clearPaddingtonInsetsOverrides() {
        if (Build.VERSION.SDK_INT >= 20) {
            for (View child : paddingtonBaseTopByChild.keySet()) {
                if (child != null) child.setOnApplyWindowInsetsListener(null);
            }
        }
        paddingtonBaseTopByChild.clear();
        reportedPaddingtonOverrideCount = -1;
    }

    private static WindowInsets zeroTop(WindowInsets insets) {
        if (insets == null || Build.VERSION.SDK_INT < 20) return insets;
        return insets.replaceSystemWindowInsets(
                insets.getSystemWindowInsetLeft(), 0,
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
    }

    private static void dispatchAdjustedInsets(View target, WindowInsets adjusted) {
        if (target != null && target.isAttachedToWindow()) {
            target.dispatchApplyWindowInsets(adjusted);
        }
    }

    private static Drawable backgroundOf(View view) {
        return view == null ? null : view.getBackground();
    }

    private static void setBackground(View view, Drawable background) {
        if (view != null && background != null && view.getBackground() != background) {
            view.setBackground(background);
        }
    }

    private static void capturePadding(View view, int[] target) {
        if (view == null) return;
        target[0] = view.getPaddingLeft();
        target[1] = view.getPaddingTop();
        target[2] = view.getPaddingRight();
        target[3] = view.getPaddingBottom();
    }

    private static void removeFloatingTopInset(View view) {
        setTopPadding(view, 0);
    }

    private static void setTopPadding(View view, int top) {
        if (view != null && view.getPaddingTop() != top) {
            view.setPadding(view.getPaddingLeft(), top,
                    view.getPaddingRight(), view.getPaddingBottom());
        }
    }

    /** Reads PaddingtonView's captured XML padding; fallback subtracts the live root inset. */
    private static int paddingtonBaseTop(View child) {
        if (child == null) return 0;
        Object parent = child.getParent();
        if (parent != null && parent.getClass().getName().endsWith(".PaddingtonView")) {
            try {
                java.lang.reflect.Field baseTop = parent.getClass().getDeclaredField("b");
                baseTop.setAccessible(true);
                return Math.max(0, baseTop.getInt(parent));
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        int top = child.getPaddingTop();
        if (Build.VERSION.SDK_INT >= 23 && child.isAttachedToWindow()) {
            WindowInsets insets = child.getRootWindowInsets();
            if (insets != null) top -= insets.getSystemWindowInsetTop();
        }
        return Math.max(0, top);
    }

    private void installFloatingTopInsetGuards() {
        installFloatingTopInsetGuard(activityControllerRoot);
        installFloatingTopInsetGuard(controlsEngine);
        installFloatingTopInsetGuard(guidanceControls);
        installFloatingTopInsetGuard(guidanceVisualRoot);
        installFloatingTopInsetGuard(topNotificationContent);
    }

    private void installFloatingTopInsetGuard(View view) {
        if (view == null) return;
        // remove/add makes this idempotent across the one-second floating contract checks.
        view.removeOnLayoutChangeListener(floatingTopInsetGuard);
        view.addOnLayoutChangeListener(floatingTopInsetGuard);
    }

    private void removeFloatingTopInsetGuards() {
        if (activityControllerRoot != null) {
            activityControllerRoot.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
        if (controlsEngine != null) {
            controlsEngine.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
        if (guidanceControls != null) {
            guidanceControls.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
        if (guidanceVisualRoot != null) {
            guidanceVisualRoot.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
        if (topNotificationContent != null) {
            topNotificationContent.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
    }

    private void installFloatingTopInsetPreDrawGuard() {
        if (floatingPreDrawGuardInstalled) return;
        View decor = window.getDecorView();
        if (decor == null) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnPreDrawListener(floatingTopInsetPreDrawGuard);
        floatingPreDrawGuardInstalled = true;
    }

    private void removeFloatingTopInsetPreDrawGuard() {
        if (!floatingPreDrawGuardInstalled) return;
        View decor = window.getDecorView();
        ViewTreeObserver observer = decor == null ? null : decor.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(floatingTopInsetPreDrawGuard);
        }
        floatingPreDrawGuardInstalled = false;
    }

    private static void restorePadding(View view, int[] source) {
        if (view != null) view.setPadding(source[0], source[1], source[2], source[3]);
    }

    private static boolean fitsSystemWindows(View view) {
        return view != null && view.getFitsSystemWindows();
    }

    private static void setFitsSystemWindows(View view, boolean value) {
        if (view != null && view.getFitsSystemWindows() != value) {
            view.setFitsSystemWindows(value);
        }
    }

    private static void requestLayout(View view) {
        if (view != null) view.requestLayout();
    }

    private void reportCommittedFrame() {
        View decor = window.getDecorView();
        int[] location = new int[2];
        decor.getLocationOnScreen(location);
        NavigationBridgeClient.reportDiagnostic("floating surface committed decor="
                + decor.getWidth() + "x" + decor.getHeight() + "@"
                + location[0] + "," + location[1] + ", transparent roots="
                + (contentRoot != null) + "/" + (mapRoot != null) + "/"
                + (mapWithControls != null) + "/" + (controlsInsetHost != null)
                + ", movableMap="
                + NatroEntryPoint.usesMovableMap(activity) + ", rounded="
                + decor.getClipToOutline());
    }

    /** Clips only the TextureView-backed floating launch; SurfaceView clipping stays forbidden. */
    private void applyRoundedClip() {
        View decor = window.getDecorView();
        boolean enabled = profile.cornerRadiusDp > 0
                && NatroEntryPoint.usesMovableMap(activity);
        if (!enabled) {
            if (decor.getClipToOutline()) decor.setClipToOutline(false);
            if (decor.getOutlineProvider() != originalOutlineProvider) {
                decor.setOutlineProvider(originalOutlineProvider);
            }
            roundedOutlineWidth = -1;
            roundedOutlineHeight = -1;
            roundedOutlineRadius = -1;
            return;
        }
        boolean changed = false;
        if (decor.getOutlineProvider() != roundedOutlineProvider) {
            decor.setOutlineProvider(roundedOutlineProvider);
            changed = true;
        }
        int radius = Math.max(0, Math.round(profile.cornerRadiusDp
                * activity.getResources().getDisplayMetrics().density));
        if (roundedOutlineWidth != decor.getWidth()
                || roundedOutlineHeight != decor.getHeight()
                || roundedOutlineRadius != radius) {
            roundedOutlineWidth = decor.getWidth();
            roundedOutlineHeight = decor.getHeight();
            roundedOutlineRadius = radius;
            changed = true;
        }
        if (changed) decor.invalidateOutline();
        if (!decor.getClipToOutline()) decor.setClipToOutline(true);
    }

    /**
     * Navigator applies its own immersive flags again after late resume/layout callbacks. Keep
     * the floating contract alive without touching the remembered geometry or restarting the
     * activity every time that happens.
     */
    private void enforceFloatingWindowContract() {
        if (destroyed || !floating) return;
        WindowManager.LayoutParams attributes = window.getAttributes();
        int expectedFlags = (attributes.flags
                | FLOATING_FLAGS
                | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                & ~(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        boolean changed = attributes.gravity != (Gravity.TOP | Gravity.START)
                || attributes.flags != expectedFlags
                || attributes.format != PixelFormat.TRANSLUCENT
                || attributes.dimAmount != 0f;
        if (changed) {
            attributes.gravity = Gravity.TOP | Gravity.START;
            attributes.flags = expectedFlags;
            attributes.format = PixelFormat.TRANSLUCENT;
            attributes.dimAmount = 0f;
            try {
                window.setAttributes(attributes);
                window.setFormat(PixelFormat.TRANSLUCENT);
            }
            catch (RuntimeException ignored) { return; }
        }
        View decor = window.getDecorView();
        int floatingSystemUi = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (decor.getSystemUiVisibility() != floatingSystemUi) {
            decor.setSystemUiVisibility(floatingSystemUi);
        }
        installModeAwareInsetDispatch();
        enforceTransparentLayers();
        requestNavigatorInsets();
        if (controlLayer != null && floatingFrame != null
                && controlLayer.getBackground() != floatingFrame) {
            controlLayer.setBackground(floatingFrame);
        }
        applyRoundedClip();
    }

    private int floatingWindowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    private void updateControls() {
        if (controlLayer == null) return;
        controlLayer.bringToFront();
        int handle = dp(36);
        int margin = dp(8);

        FrameLayout.LayoutParams drag = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, handle, Gravity.TOP | Gravity.START);
        drag.leftMargin = dp(56);
        drag.rightMargin = dp(56);
        dragHandle.setLayoutParams(drag);
        dragHandle.setVisibility(floating && profile.dragHandleVisible
                && !profile.movementLocked ? View.VISIBLE : View.GONE);

        FrameLayout.LayoutParams resize = new FrameLayout.LayoutParams(
                handle, handle, Gravity.BOTTOM | Gravity.END);
        resizeHandle.setLayoutParams(resize);
        resizeHandle.setVisibility(floating && profile.resizeHandleVisible
                && !profile.resizeLocked ? View.VISIBLE : View.GONE);

        FrameLayout.LayoutParams close = new FrameLayout.LayoutParams(
                handle, handle, Gravity.TOP | Gravity.START);
        close.leftMargin = margin;
        close.topMargin = margin;
        closeButton.setLayoutParams(close);
        closeButton.setVisibility(floating && profile.closeButtonVisible
                ? View.VISIBLE : View.GONE);

        updateModeButtons();
    }

    private void updateModeButtons() {
        if (!ensureModeButtonCreated()) return;
        if (modeButtonIcon != null) modeButtonIcon.setExpand(floating);
        modeButton.setContentDescription(floating
                ? "Развернуть Навигатор на весь экран"
                : "Открыть Навигатор в окне");
        if (!profile.enabled || !profile.modeButtonVisible) {
            holdModeButtonHidden();
            return;
        }
        if (mapGestureInProgress) return;
        ensureModeButtonAttachedToStockRail();
    }

    /**
     * Creates Navigator's own rectangular control shell by name, so the injected dex keeps no
     * verifier-time dependency on an obfuscated app class. The inner icon is drawn as vectors at
     * the final 48 dp size; no bitmap or whole-layer downscaling is involved.
     */
    private boolean ensureModeButtonCreated() {
        if (modeButton != null) return true;
        if (modeButtonCreationAttempted) return false;
        modeButtonCreationAttempted = true;
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> controlClass = Class.forName(STOCK_RECT_CONTROL_CLASS, true, loader);
            Object instance = controlClass
                    .getConstructor(Context.class, AttributeSet.class)
                    .newInstance(activity, null);
            if (!(instance instanceof ViewGroup)) {
                throw new IllegalStateException("stock rectangular control is not a ViewGroup");
            }
            modeButton = (ViewGroup) instance;
            int padding = navigatorDimension("control_rect_padding", 4);
            modeButton.setPaddingRelative(padding, padding, padding, padding);
            modeButton.setClickable(true);
            modeButton.setFocusable(true);
            modeButton.setWillNotDraw(false);
            modeButton.setOnClickListener(view -> {
                try {
                    restartInMode(!floating, null);
                } catch (Throwable failure) {
                    reportCallbackFailure("modeButtonClick", failure);
                }
            });

            modeButtonIcon = new ModeToggleIconView(activity);
            int iconSize = navigatorDimension("control_rect_size", 48);
            modeButton.addView(modeButtonIcon,
                    new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
            return true;
        } catch (Throwable failure) {
            reportCallbackFailure("createStockModeButton", failure);
            modeButton = null;
            modeButtonIcon = null;
            return false;
        }
    }

    /**
     * The hidden overlay is only a lifecycle-safe parking place while Conductor is replacing the
     * free-drive/Guidance tree. It must never draw a coordinate-based duplicate on the map.
     */
    private void holdModeButtonHidden() {
        ViewGroup button = modeButton;
        FrameLayout layer = controlLayer;
        if (button == null || layer == null) return;
        button.setVisibility(View.GONE);
        if (button.getParent() == layer) return;
        detachFromParent(button);
        try {
            layer.addView(button, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP));
        } catch (RuntimeException failure) {
            reportCallbackFailure("parkModeButton", failure);
        }
    }

    /** Attaches one button after the exact stock road-event + voice pair. */
    private boolean ensureModeButtonAttachedToStockRail() {
        if (destroyed || !profile.enabled || !profile.modeButtonVisible
                || !ensureModeButtonCreated()) {
            holdModeButtonHidden();
            return false;
        }
        StockControlRail rail = findStockModeButtonRail();
        if (rail == null) {
            holdModeButtonHidden();
            return false;
        }
        ViewGroup button = modeButton;
        int targetIndex = rail.voiceIndex + 1;
        if (button.getParent() != rail.container
                || rail.container.indexOfChild(button) != targetIndex) {
            detachFromParent(button);
            try {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = rail.spacingPx;
                rail.container.addView(button, targetIndex, params);
            } catch (RuntimeException failure) {
                reportCallbackFailure("attachStockModeButton", failure);
                holdModeButtonHidden();
                return false;
            }
        }
        // Alpha/scale/translation/visibility are intentionally not animated here: the enclosing
        // FluidContainer/NaviServiceControlsRow owns the same lifecycle as the two stock buttons.
        button.setVisibility(View.VISIBLE);
        return true;
    }

    private StockControlRail findStockModeButtonRail() {
        View root = window.getDecorView();
        StockControlRail guidance = findStockModeButtonRail(root,
                resourceId("guidance_add_road_event"),
                resourceId("guidance_open_voice_search"),
                resourceId("navi_guidance_controls_container"),
                resourceId("navi_guidance_special_visibility_controls_container"));
        StockControlRail service = findStockModeButtonRail(root,
                resourceId("navi_service_add_road_event"),
                resourceId("navi_service_open_voice_search"),
                resourceId("navi_service_vanishing_controls"), 0);
        if (guidance == null) return service;
        if (service == null) return guidance;
        return guidance.score >= service.score ? guidance : service;
    }

    private StockControlRail findStockModeButtonRail(
            View root, int roadEventId, int voiceId, int ownerId, int alternateOwnerId) {
        if (root == null || roadEventId == 0 || voiceId == 0 || ownerId == 0) return null;
        StockControlRail best = null;
        if (root.getId() == voiceId && root.getParent() instanceof LinearLayout) {
            LinearLayout candidate = (LinearLayout) root.getParent();
            int voiceIndex = candidate.indexOfChild(root);
            View roadEventSlot = voiceIndex > 0 ? candidate.getChildAt(voiceIndex - 1) : null;
            if (candidate.isAttachedToWindow()
                    && candidate.getOrientation() == LinearLayout.VERTICAL && voiceIndex > 0
                    && containsViewId(roadEventSlot, roadEventId)
                    && hasAncestorId(candidate, ownerId, alternateOwnerId)) {
                int spacing = bottomMargin(roadEventSlot);
                if (spacing <= 0) spacing = navigatorDimension("control_rect_padding", 4);
                int score = (candidate.isShown() ? 4 : 0)
                        + (root.isShown() ? 4 : 0)
                        + (roadEventSlot != null && roadEventSlot.isShown() ? 4 : 0)
                        + (candidate.getWidth() > 0 && candidate.getHeight() > 0 ? 2 : 0)
                        + (modeButton != null && modeButton.getParent() == candidate ? 1 : 0);
                best = new StockControlRail(candidate, voiceIndex, spacing, score);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                StockControlRail child = findStockModeButtonRail(group.getChildAt(index),
                        roadEventId, voiceId, ownerId, alternateOwnerId);
                if (child != null && (best == null || child.score > best.score)) best = child;
            }
        }
        return best;
    }

    private static boolean containsViewId(View root, int id) {
        if (root == null || id == 0) return false;
        if (root.getId() == id) return true;
        return root instanceof ViewGroup && ((ViewGroup) root).findViewById(id) != null;
    }

    private static boolean hasAncestorId(View view, int id, int alternateId) {
        Object current = view;
        while (current instanceof View) {
            int currentId = ((View) current).getId();
            if (currentId == id || (alternateId != 0 && currentId == alternateId)) return true;
            current = ((View) current).getParent();
        }
        return false;
    }

    private static int bottomMargin(View view) {
        ViewGroup.LayoutParams params = view == null ? null : view.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).bottomMargin : 0;
    }

    private int resourceId(String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private int navigatorDimension(String name, int fallbackDp) {
        int id = activity.getResources().getIdentifier(name, "dimen", activity.getPackageName());
        return id == 0 ? dp(fallbackDp) : activity.getResources().getDimensionPixelSize(id);
    }

    private int navigatorColor(String name, int fallback) {
        int id = activity.getResources().getIdentifier(name, "color", activity.getPackageName());
        if (id == 0) return fallback;
        try {
            return activity.getResources().getColor(id);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private View viewByName(String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : bestLiveViewById(window.getDecorView(), id, null, Integer.MIN_VALUE);
    }

    /** Chooses the visible topmost controller when Conductor temporarily retains duplicate ids. */
    private static View bestLiveViewById(View root, int id, View best, int bestScore) {
        if (root == null) return best;
        if (root.getId() == id) {
            int score = (root.isAttachedToWindow() ? 16 : 0)
                    + (root.isShown() ? 16 : 0)
                    + (root.getWidth() > 0 && root.getHeight() > 0 ? 8 : 0)
                    + (root.getAlpha() > 0.01f ? 4 : 0)
                    + (root.getVisibility() == View.VISIBLE ? 2 : 0);
            // Children are visited in drawing order, so an equal later candidate is topmost.
            if (score >= bestScore) {
                best = root;
                bestScore = score;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View candidate = bestLiveViewById(group.getChildAt(index), id, best, bestScore);
                if (candidate != best) {
                    best = candidate;
                    bestScore = liveViewScore(candidate);
                }
            }
        }
        return best;
    }

    private static int liveViewScore(View view) {
        if (view == null) return Integer.MIN_VALUE;
        return (view.isAttachedToWindow() ? 16 : 0)
                + (view.isShown() ? 16 : 0)
                + (view.getWidth() > 0 && view.getHeight() > 0 ? 8 : 0)
                + (view.getAlpha() > 0.01f ? 4 : 0)
                + (view.getVisibility() == View.VISIBLE ? 2 : 0);
    }

    /** Finds the id-less CarGuidance root without confusing it with free-drive speed controls. */
    private View activeGuidanceVisualRoot() {
        int maneuverId = resourceId("contextmaneuverview");
        int speedGroupId = activity.getResources().getIdentifier(
                "speed_group", "id", activity.getPackageName());
        if (maneuverId == 0 || speedGroupId == 0) return null;

        // speed_group remains visible even when ContextManeuverView is legitimately GONE between
        // instructions, so it is the reliable primary key among retained Conductor trees.
        View speedGroup = viewByName("speed_group");
        ViewGroup candidate = speedGroup != null && speedGroup.isAttachedToWindow()
                && speedGroup.getParent() instanceof ViewGroup
                ? (ViewGroup) speedGroup.getParent() : null;
        if (candidate != null && candidate.findViewById(maneuverId) != null) return candidate;

        View maneuver = viewByName("contextmaneuverview");
        candidate = maneuver != null && maneuver.isAttachedToWindow()
                && maneuver.getParent() instanceof ViewGroup
                ? (ViewGroup) maneuver.getParent() : null;
        // The pair is the exact car_guidance_controller.xml signature. Do not require this late
        // child router to be below the separately resolved side-controls tree: regional Conductor
        // hosts may mount those siblings while retaining the same stock guidance layout.
        return candidate != null && candidate.findViewById(speedGroupId) != null
                ? candidate : null;
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        Object current = child;
        while (current instanceof View) {
            if (current == ancestor) return true;
            current = ((View) current).getParent();
        }
        return false;
    }

    private static void detachFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) view.getParent()).removeView(view); }
            catch (RuntimeException ignored) {}
        }
    }

    private TextView control(String text, String description) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(20f);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB8000000);
        background.setCornerRadius(dp(18));
        view.setBackground(background);
        view.setElevation(dp(8));
        return view;
    }

    private void updateGeometry(int x, int y, int width, int height) {
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.x = x;
        attributes.y = y;
        attributes.width = width;
        attributes.height = height;
        clampGeometry(attributes, realDisplayMetrics());
        try { window.setAttributes(attributes); } catch (RuntimeException ignored) {}
        window.getDecorView().invalidateOutline();
    }

    private void clampGeometry(WindowManager.LayoutParams attributes, DisplayMetrics screen) {
        attributes.width = Math.max(dp(200), Math.min(screen.widthPixels, attributes.width));
        attributes.height = Math.max(dp(200), Math.min(screen.heightPixels, attributes.height));
        attributes.x = Math.max(0, Math.min(
                screen.widthPixels - attributes.width, attributes.x));
        attributes.y = Math.max(0, Math.min(
                screen.heightPixels - attributes.height, attributes.y));
    }

    private void saveGeometry() {
        WindowManager.LayoutParams attributes = window.getAttributes();
        preferences.edit()
                .putInt("x", attributes.x)
                .putInt("y", attributes.y)
                .putInt("width", attributes.width)
                .putInt("height", attributes.height)
                .apply();
    }

    private DisplayMetrics realDisplayMetrics() {
        DisplayMetrics result = new DisplayMetrics();
        try {
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(result);
        } catch (RuntimeException ignored) {
            result.setTo(activity.getResources().getDisplayMetrics());
        }
        return result;
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value
                * activity.getResources().getDisplayMetrics().density));
    }

    private static final class StockControlRail {
        final LinearLayout container;
        final int voiceIndex;
        final int spacingPx;
        final int score;

        StockControlRail(LinearLayout container, int voiceIndex, int spacingPx, int score) {
            this.container = container;
            this.voiceIndex = voiceIndex;
            this.spacingPx = spacingPx;
            this.score = score;
        }
    }

    /** Final-size vector icon placed inside Navigator's own control shell. */
    private final class ModeToggleIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean expand;

        ModeToggleIconView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setStrokeJoin(Paint.Join.MITER);
            setDuplicateParentStateEnabled(true);
            setClickable(false);
            setFocusable(false);
        }

        void setExpand(boolean value) {
            if (expand == value) return;
            expand = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0f) return;
            float left = (getWidth() - size) * .5f;
            float top = (getHeight() - size) * .5f;
            paint.setColor(navigatorColor("icons_primary", Color.WHITE));
            paint.setStrokeWidth(Math.max(dp(2), size * .065f));

            if (expand) {
                float edge = size * .22f;
                float arm = size * .24f;
                drawCorner(canvas, left + edge, top + edge, arm, arm);
                drawCorner(canvas, left + size - edge, top + edge, -arm, arm);
                drawCorner(canvas, left + edge, top + size - edge, arm, -arm);
                drawCorner(canvas, left + size - edge, top + size - edge, -arm, -arm);
            } else {
                float innerLow = size * .42f;
                float innerHigh = size * .58f;
                float arm = size * .20f;
                drawCorner(canvas, left + innerLow, top + innerLow, -arm, -arm);
                drawCorner(canvas, left + innerHigh, top + innerLow, arm, -arm);
                drawCorner(canvas, left + innerLow, top + innerHigh, -arm, arm);
                drawCorner(canvas, left + innerHigh, top + innerHigh, arm, arm);
            }
        }

        private void drawCorner(Canvas canvas, float x, float y, float horizontal,
                                float vertical) {
            canvas.drawLine(x, y, x + horizontal, y, paint);
            canvas.drawLine(x, y, x, y + vertical, paint);
        }
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startX;
        private int startY;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (!floating || profile.movementLocked) return false;
            WindowManager.LayoutParams attributes = window.getAttributes();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = attributes.x;
                    startY = attributes.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateGeometry(
                            startX + Math.round(event.getRawX() - downX),
                            startY + Math.round(event.getRawY() - downY),
                            attributes.width,
                            attributes.height);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (profile.rememberGeometry) saveGeometry();
                    return true;
                default:
                    return false;
            }
        }
    }

    private final class ResizeTouchListener implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startWidth;
        private int startHeight;
        private float aspect;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (!floating || profile.resizeLocked) return false;
            WindowManager.LayoutParams attributes = window.getAttributes();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startWidth = attributes.width;
                    startHeight = attributes.height;
                    aspect = startHeight == 0 ? 1f : (float) startWidth / startHeight;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int width = startWidth + Math.round(event.getRawX() - downX);
                    int height = startHeight + Math.round(event.getRawY() - downY);
                    if (profile.aspectRatioLocked && aspect > 0f) {
                        height = Math.round(width / aspect);
                    }
                    updateGeometry(attributes.x, attributes.y, width, height);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (profile.rememberGeometry) saveGeometry();
                    return true;
                default:
                    return false;
            }
        }
    }
}
