/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
    private static final long MODE_BUTTON_STABLE_MS = 5_000L;
    private static final long MODE_BUTTON_AUTO_HIDE_MS = 5_000L;
    private static final long FLOATING_CONTRACT_CHECK_MS = 1_000L;
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
    private TextView modeButton;
    private TextView closeButton;
    private TextView dragHandle;
    private TextView resizeHandle;
    private View insetDispatchHost;
    private Drawable floatingBackground;
    private Drawable floatingFrame;
    private View contentRoot;
    private View mapRoot;
    private View mapWithControls;
    private View controlsInsetHost;
    private View controlsEngine;
    private View topNotificationRoot;
    private View guidanceControls;
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
    private long modeButtonVisibleUntilElapsedMs;
    private final int[] originalContentPadding = new int[4];
    private final int[] originalMapRootPadding = new int[4];
    private final int[] originalMapWithControlsPadding = new int[4];
    private final int[] originalControlsInsetHostPadding = new int[4];
    private final int[] originalControlsEnginePadding = new int[4];
    private final int[] originalTopNotificationPadding = new int[4];
    private final int[] originalGuidanceControlsPadding = new int[4];
    private final int[] originalTopNotificationContentPadding = new int[4];
    private boolean originalContentFitsSystemWindows;
    private boolean originalMapRootFitsSystemWindows;
    private boolean originalMapWithControlsFitsSystemWindows;
    private boolean originalControlsInsetHostFitsSystemWindows;
    private boolean originalControlsEngineFitsSystemWindows;
    private boolean originalTopNotificationFitsSystemWindows;
    private boolean originalGuidanceControlsFitsSystemWindows;
    private boolean originalTopNotificationContentFitsSystemWindows;
    private int floatingControlsEngineTop;
    private int floatingGuidanceControlsTop;
    private int floatingTopNotificationContentTop;
    private final Map<View, Integer> paddingtonBaseTopByChild = new IdentityHashMap<>();
    private int reportedPaddingtonOverrideCount = -1;
    private int roundedOutlineWidth = -1;
    private int roundedOutlineHeight = -1;
    private int roundedOutlineRadius = -1;
    private final Runnable hideModeButtons = new Runnable() {
        @Override public void run() {
            try {
                long remaining = modeButtonVisibleUntilElapsedMs - SystemClock.elapsedRealtime();
                if (remaining > 0L) {
                    mainHandler.postDelayed(this, remaining);
                    return;
                }
                updateModeButtons();
            } catch (Throwable failure) {
                reportCallbackFailure("hideModeButtons", failure);
            }
        }
    };
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
                        if (view == controlsEngine) {
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
    }

    void install() {
        if (destroyed || controlLayer != null) return;
        ViewGroup host = findControlHost();
        if (host == null) return;
        controlLayer = new FrameLayout(activity) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    revealModeButton();
                }
                // Returning super's result lets an unhandled event continue to Navigator below.
                return super.dispatchTouchEvent(event);
            }
        };
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

        modeButton = control("◲", "Открыть Навигатор в окне");
        modeButton.setTextSize(32f);
        modeButton.setTypeface(Typeface.DEFAULT_BOLD);
        modeButton.setOnClickListener(view -> restartInMode(!floating, null));
        controlLayer.addView(modeButton);
        revealModeButton();
        mainHandler.post(modeButtonPoller);
        updateControls();
    }

    /** Global MapActivity touch hook: late MapKit views cannot hide or bypass this reveal path. */
    void onMapTouch(MotionEvent event) {
        if (destroyed || event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        // MapActivity may finish onResumeFragments before Conductor installs android.R.id.content.
        // A failed first install used to leave this Activity without controls forever. The global
        // touch hook is a safe second admission point because it runs before Yandex consumes DOWN.
        if (controlLayer == null) install();
        ensureControlLayerAttached();
        revealModeButton();
        if (controlLayer != null) {
            controlLayer.post(() -> {
                try {
                    if (destroyed) return;
                    ensureControlLayerAttached();
                    ensureModeButtonInOverlay();
                    layoutModeButtons();
                    updateModeButtons();
                } catch (Throwable failure) {
                    reportCallbackFailure("mapTouchReattach", failure);
                }
            });
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
                ensureControlLayerAttached();
                if (floating) enforceFloatingWindowContract();
                if (controlLayer != null) controlLayer.bringToFront();
                ensureModeButtonInOverlay();
                updateModeButtons();
            } catch (Throwable failure) {
                reportCallbackFailure("modeButtonPoller", failure);
            } finally {
                if (!destroyed) {
                    long delay = floating
                            ? FLOATING_CONTRACT_CHECK_MS : MODE_BUTTON_STABLE_MS;
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
        mainHandler.removeCallbacks(hideModeButtons);
        window.getDecorView().removeCallbacks(floatingSurfaceCommitter);
        if (Build.VERSION.SDK_INT >= 20 && insetDispatchHost != null) {
            insetDispatchHost.setOnApplyWindowInsetsListener(null);
            insetDispatchHost = null;
        }
        removeFloatingTopInsetGuards();
        clearPaddingtonInsetsOverrides();
        detachFromParent(modeButton);
        modeButton = null;
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
        if (controlsEngine == null) {
            int controlsEngineId = activity.getResources().getIdentifier(
                    "controls_engine_container", "id", activity.getPackageName());
            controlsEngine = controlsEngineId == 0
                    ? null : activity.findViewById(controlsEngineId);
            capturePadding(controlsEngine, originalControlsEnginePadding);
            originalControlsEngineFitsSystemWindows = fitsSystemWindows(controlsEngine);
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
        if (guidanceControls == null) {
            int guidanceControlsId = activity.getResources().getIdentifier(
                    "navi_guidance_controls_touch_container", "id",
                    activity.getPackageName());
            guidanceControls = guidanceControlsId == 0
                    ? null : activity.findViewById(guidanceControlsId);
            capturePadding(guidanceControls, originalGuidanceControlsPadding);
            originalGuidanceControlsFitsSystemWindows = fitsSystemWindows(guidanceControls);
            floatingGuidanceControlsTop = paddingtonBaseTop(guidanceControls);
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
                || mapWithControls != null || controlsInsetHost != null
                || controlsEngine != null || topNotificationRoot != null
                || guidanceControls != null || topNotificationContent != null;
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
        if (paddingtonCount != reportedPaddingtonOverrideCount) {
            reportedPaddingtonOverrideCount = paddingtonCount;
            NavigationBridgeClient.reportDiagnostic(
                    "floating top inset: owned PaddingtonView children=" + paddingtonCount);
        }
        removeFloatingTopInset(contentRoot);
        removeFloatingTopInset(mapRoot);
        removeFloatingTopInset(mapWithControls);
        removeFloatingTopInset(controlsInsetHost);
        setTopPadding(controlsEngine, floatingControlsEngineTop);
        removeFloatingTopInset(topNotificationRoot);
        setTopPadding(guidanceControls, floatingGuidanceControlsTop);
        setTopPadding(topNotificationContent, floatingTopNotificationContentTop);
        setFitsSystemWindows(contentRoot, false);
        setFitsSystemWindows(mapRoot, false);
        setFitsSystemWindows(mapWithControls, false);
        setFitsSystemWindows(controlsInsetHost, false);
        setFitsSystemWindows(controlsEngine, false);
        setFitsSystemWindows(topNotificationRoot, false);
        setFitsSystemWindows(guidanceControls, false);
        setFitsSystemWindows(topNotificationContent, false);
        requestLayout(contentRoot);
        requestLayout(mapRoot);
        requestLayout(mapWithControls);
        requestLayout(controlsInsetHost);
        requestLayout(controlsEngine);
        requestLayout(topNotificationRoot);
        requestLayout(guidanceControls);
        requestLayout(topNotificationContent);
        installFloatingTopInsetGuards();
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
        restorePadding(controlsInsetHost, originalControlsInsetHostPadding);
        restorePadding(controlsEngine, originalControlsEnginePadding);
        restorePadding(topNotificationRoot, originalTopNotificationPadding);
        restorePadding(guidanceControls, originalGuidanceControlsPadding);
        restorePadding(topNotificationContent, originalTopNotificationContentPadding);
        setFitsSystemWindows(contentRoot, originalContentFitsSystemWindows);
        setFitsSystemWindows(mapRoot, originalMapRootFitsSystemWindows);
        setFitsSystemWindows(mapWithControls, originalMapWithControlsFitsSystemWindows);
        setFitsSystemWindows(controlsInsetHost, originalControlsInsetHostFitsSystemWindows);
        setFitsSystemWindows(controlsEngine, originalControlsEngineFitsSystemWindows);
        setFitsSystemWindows(topNotificationRoot, originalTopNotificationFitsSystemWindows);
        setFitsSystemWindows(guidanceControls, originalGuidanceControlsFitsSystemWindows);
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
        // PaddingtonView and top-notification are siblings of MapWithControlsView. Dispatching
        // only to the map leaves the large global status-bar reserve on all upper UI elements.
        dispatchAdjustedInsets(controlsInsetHost != null
                ? controlsInsetHost : controlsEngine, adjusted);
        dispatchAdjustedInsets(topNotificationRoot, adjusted);
        dispatchAdjustedInsets(guidanceControls != null
                && guidanceControls.getParent() instanceof View
                ? (View) guidanceControls.getParent() : guidanceControls, adjusted);
        dispatchAdjustedInsets(topNotificationContent != null
                && topNotificationContent.getParent() instanceof View
                ? (View) topNotificationContent.getParent() : topNotificationContent, adjusted);
        // PaddingtonView 30.3.0 ignores the callback argument and reads raw root insets again.
        // Reassert its captured XML padding synchronously after dispatch; the layout guard keeps
        // the same invariant when Navigator issues a later inset pass of its own.
        setTopPadding(controlsEngine, floatingControlsEngineTop);
        setTopPadding(guidanceControls, floatingGuidanceControlsTop);
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
                    baseTop = paddingtonBaseTop(child);
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
        installFloatingTopInsetGuard(controlsEngine);
        installFloatingTopInsetGuard(guidanceControls);
        installFloatingTopInsetGuard(topNotificationContent);
    }

    private void installFloatingTopInsetGuard(View view) {
        if (view == null) return;
        // remove/add makes this idempotent across the one-second floating contract checks.
        view.removeOnLayoutChangeListener(floatingTopInsetGuard);
        view.addOnLayoutChangeListener(floatingTopInsetGuard);
    }

    private void removeFloatingTopInsetGuards() {
        if (controlsEngine != null) {
            controlsEngine.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
        if (guidanceControls != null) {
            guidanceControls.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
        if (topNotificationContent != null) {
            topNotificationContent.removeOnLayoutChangeListener(floatingTopInsetGuard);
        }
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

        ensureModeButtonInOverlay();
        layoutModeButtons();
        updateModeButtons();
    }

    private void updateModeButtons() {
        if (modeButton == null) return;
        ensureModeButtonInOverlay();
        updateModeButtonSize(modeButton);
        float alpha = profile.modeButtonOpacityPercent / 100f;
        modeButton.setAlpha(alpha);
        modeButton.setText(floating ? "◱" : "◲");
        modeButton.setContentDescription(floating
                ? "Развернуть Навигатор на весь экран"
                : "Открыть Навигатор в окне");
        boolean revealed = SystemClock.elapsedRealtime() <= modeButtonVisibleUntilElapsedMs;
        modeButton.setVisibility(revealed && profile.enabled && profile.modeButtonVisible
                ? View.VISIBLE : View.GONE);
        if (revealed && modeButton.getVisibility() == View.VISIBLE) modeButton.bringToFront();
    }

    private void revealModeButton() {
        modeButtonVisibleUntilElapsedMs = SystemClock.elapsedRealtime()
                + MODE_BUTTON_AUTO_HIDE_MS;
        mainHandler.removeCallbacks(hideModeButtons);
        mainHandler.postDelayed(hideModeButtons, MODE_BUTTON_AUTO_HIDE_MS);
        updateModeButtons();
    }

    private void layoutModeButtons() {
        layoutModeButton(modeButton);
    }

    private void layoutModeButton(TextView button) {
        if (button == null) return;
        // Keep ownership in Natro's stable touch-transparent layer. Screen coordinates are used
        // only to visually continue Navigator's left column; Yandex may freely rebuild its rail
        // without detaching or hiding our button.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(profile.modeButtonSizeDp), dp(profile.modeButtonSizeDp),
                Gravity.START | Gravity.TOP);
        int margin = dp(8);
        params.leftMargin = margin;
        params.topMargin = leftControlColumnNextTop(margin);
        button.setLayoutParams(params);
    }

    /** Keeps the toggle above MapKit while visually aligning it with the stock left rail. */
    private boolean ensureModeButtonInOverlay() {
        TextView button = modeButton;
        FrameLayout layer = controlLayer;
        if (button == null || layer == null) return false;
        if (button.getParent() == layer && button.isAttachedToWindow()) {
            button.bringToFront();
            return true;
        }
        detachFromParent(button);
        try {
            layer.addView(button);
            layoutModeButton(button);
            button.bringToFront();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private View viewByName(String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    private static void detachFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) view.getParent()).removeView(view); }
            catch (RuntimeException ignored) {}
        }
    }

    private int leftControlColumnNextTop(int fallbackMargin) {
        FrameLayout layer = controlLayer;
        if (layer == null || layer.getHeight() <= 0) return dp(116);
        int[] layerLocation = new int[2];
        layer.getLocationOnScreen(layerLocation);
        int bottom = -1;
        String[] anchors = new String[]{
                "guidance_open_voice_search", "alice_fab_container", "alice_fab",
                "guidance_add_road_event", "guidance_refuel_search_map_control",
                "guidance_search_map_control_ghost"
        };
        for (String name : anchors) {
            int id = activity.getResources().getIdentifier(
                    name, "id", activity.getPackageName());
            View anchor = id == 0 ? null : activity.findViewById(id);
            if (anchor == null || anchor.getVisibility() != View.VISIBLE
                    || anchor.getWidth() <= 0 || anchor.getHeight() <= 0) continue;
            int[] location = new int[2];
            anchor.getLocationOnScreen(location);
            int localLeft = location[0] - layerLocation[0];
            if (localLeft > Math.max(dp(160), layer.getWidth() / 4)) continue;
            bottom = Math.max(bottom,
                    location[1] - layerLocation[1] + anchor.getHeight());
        }
        int top = bottom >= 0 ? bottom + dp(8) : dp(116);
        int maximum = Math.max(fallbackMargin,
                layer.getHeight() - dp(profile.modeButtonSizeDp) - fallbackMargin);
        return Math.max(fallbackMargin, Math.min(maximum, top));
    }

    private void updateModeButtonSize(TextView button) {
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params != null) {
            params.width = dp(profile.modeButtonSizeDp);
            params.height = dp(profile.modeButtonSizeDp);
            button.setLayoutParams(params);
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
