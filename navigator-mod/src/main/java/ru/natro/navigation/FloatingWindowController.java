/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Activity-owned floating window with no resources or manifest additions. */
final class FloatingWindowController {
    private static final String ACTION_FLOATING = "navi_win/ru.yandex.yandexnavi";
    private static final String EXTRA_WINDOWED = "ddnavwin";
    private static final String EXTRA_FORCE_FULLSCREEN = "ddnavforcewinfull";
    private static final String PREFS = "natro_floating_window_v2";
    private static final int MODE_FULLSCREEN = 0;
    private static final int MODE_FLOATING = 1;
    private static final int MODE_TOGGLE = 2;
    private static final int FLOATING_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    private static final int MUTATED_FLAGS =
            FLOATING_FLAGS | WindowManager.LayoutParams.FLAG_DIM_BEHIND;

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

    private FloatingWindowProfile profile = new FloatingWindowProfile();
    private FrameLayout controlLayer;
    private TextView modeButton;
    private TextView floatingModeButton;
    private TextView closeButton;
    private TextView dragHandle;
    private TextView resizeHandle;
    private ViewGroup modeButtonHost;
    private boolean floating;
    private boolean destroyed;
    private boolean geometryLoaded;

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
    }

    void install() {
        if (destroyed || controlLayer != null) return;
        ViewGroup host = findControlHost();
        if (host == null) return;
        controlLayer = new FrameLayout(activity);
        controlLayer.setClipChildren(false);
        controlLayer.setClipToPadding(false);
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
        modeButton.setOnClickListener(view -> restartInMode(true));

        // In 29.4.2 the return control belongs to the floating header itself.  Reusing the
        // full-screen button leaves it under Navigator's hidden voice-search hierarchy and makes
        // the window impossible to expand again.
        floatingModeButton = control("◱", "Развернуть Навигатор на весь экран");
        floatingModeButton.setTextSize(32f);
        floatingModeButton.setTypeface(Typeface.DEFAULT_BOLD);
        floatingModeButton.setOnClickListener(view -> restartInMode(false));
        controlLayer.addView(floatingModeButton);
        mainHandler.post(modeButtonPoller);
        updateControls();
    }

    void consumeIntent(Intent intent) {
        if (destroyed || intent == null) return;
        boolean forceFull = intent.getBooleanExtra(EXTRA_FORCE_FULLSCREEN, false);
        if (forceFull) {
            setWindowMode(MODE_FULLSCREEN);
            return;
        }
        if (!profile.enabled) return;
        boolean requestsWindow = intent.getBooleanExtra(EXTRA_WINDOWED, false)
                || ACTION_FLOATING.equals(intent.getAction());
        if (requestsWindow) setWindowMode(MODE_FLOATING);
    }

    void applyConfiguration(String rawConfiguration) {
        profile = FloatingWindowProfile.fromConfiguration(rawConfiguration);
        if (!profile.enabled && floating) applyFullscreenAttributes();
        else if (floating) applyFloatingAttributes(false);
        else updateControls();
    }

    void setWindowMode(int mode) {
        if (destroyed || (!profile.enabled && mode != MODE_FULLSCREEN)) return;
        boolean next = mode == MODE_TOGGLE ? !floating : mode == MODE_FLOATING;
        if (next == floating) {
            updateControls();
            return;
        }
        if (next) applyFloatingAttributes(true);
        else applyFullscreenAttributes();
    }

    boolean isFloating() {
        return floating;
    }

    private void restartInMode(boolean nextFloating) {
        if (destroyed || (!profile.enabled && nextFloating)) return;
        Intent restart = new Intent(activity, activity.getClass())
                .addFlags(0x04008000);
        if (nextFloating) restart.putExtra(EXTRA_WINDOWED, true);
        activity.finish();
        activity.startActivity(restart);
    }

    /**
     * The working 29.4.2 mod adds its window controls to the Navigator map root. Keeping the
     * controls inside that root prevents MapWithControlsView from covering them during its late
     * post-resume layout pass. The decor fallback keeps the patch tolerant of resource renames.
     */
    private ViewGroup findControlHost() {
        int rootId = activity.getResources().getIdentifier(
                "map_activity_root", "id", activity.getPackageName());
        View mapRoot = rootId == 0 ? null : activity.findViewById(rootId);
        if (mapRoot instanceof ViewGroup) return (ViewGroup) mapRoot;
        View decor = window.getDecorView();
        return decor instanceof ViewGroup ? (ViewGroup) decor : null;
    }

    /** The 29.4.2 button lives in the row containing Navigator's voice-search control. */
    private ViewGroup findNavigatorButtonHost() {
        String[] anchorNames = {
                "navi_service_open_voice_search",
                "guidance_open_voice_search"
        };
        for (String name : anchorNames) {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            View anchor = id == 0 ? null : activity.findViewById(id);
            if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup) anchor.getParent();
            if (parent.getVisibility() == View.VISIBLE) return parent;
        }
        return null;
    }

    private void attachModeButtonToNavigator() {
        if (destroyed || modeButton == null) return;
        if (floating) {
            detachModeButtonFromNavigator();
            updateModeButtons();
            return;
        }
        ViewGroup host = findNavigatorButtonHost();
        if (host != null && host != modeButtonHost) {
            if (modeButton.getParent() instanceof ViewGroup) {
                ((ViewGroup) modeButton.getParent()).removeView(modeButton);
            }
            int size = dp(profile.modeButtonSizeDp);
            if (host instanceof LinearLayout) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                params.setMargins(dp(4), 0, 0, dp(9));
                host.addView(modeButton, 0, params);
            } else {
                host.addView(modeButton, 0, new ViewGroup.LayoutParams(size, size));
            }
            host.setClipChildren(false);
            host.setClipToPadding(false);
            if (host.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) host.getParent();
                parent.setClipChildren(false);
                parent.setClipToPadding(false);
            }
            modeButtonHost = host;
        }
        updateModeButtons();
    }

    private void detachModeButtonFromNavigator() {
        if (modeButton != null && modeButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) modeButton.getParent()).removeView(modeButton);
        }
        modeButtonHost = null;
    }

    private final Runnable modeButtonPoller = new Runnable() {
        @Override public void run() {
            attachModeButtonToNavigator();
            if (!destroyed) mainHandler.postDelayed(this, 1000L);
        }
    };

    void destroy() {
        destroyed = true;
        mainHandler.removeCallbacks(modeButtonPoller);
        detachModeButtonFromNavigator();
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
        // Exact working 29.4.2 KX11 contract, applied only after onResumeFragments when the
        // Activity window and OEM token are attached. Applying it from onPostCreate is too early.
        attributes.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        attributes.gravity = Gravity.TOP | Gravity.START;
        attributes.flags = (attributes.flags | FLOATING_FLAGS)
                & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        attributes.format = PixelFormat.TRANSLUCENT;
        try {
            window.setAttributes(attributes);
            floating = true;
            applyFloatingDecoration();
            updateControls();
        } catch (RuntimeException failure) {
            floating = false;
            restoreWindowIdentity(attributes);
            try { window.setAttributes(attributes); } catch (RuntimeException ignored) {}
            Toast.makeText(activity,
                    "Оконный режим не разрешён прошивкой ГУ", Toast.LENGTH_SHORT).show();
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
        decor.setBackground(originalBackground);
        decor.setClipToOutline(false);
        decor.setElevation(originalElevation);
        decor.setSystemUiVisibility(originalSystemUi);
        updateControls();
    }

    private void restoreWindowIdentity(WindowManager.LayoutParams attributes) {
        attributes.type = originalType;
        attributes.gravity = originalGravity;
        attributes.format = originalFormat;
        attributes.flags = (attributes.flags & ~MUTATED_FLAGS)
                | (originalFlags & MUTATED_FLAGS);
    }

    private void applyFloatingDecoration() {
        View decor = window.getDecorView();
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor(profile.backgroundColor));
        background.setCornerRadius(dp(profile.cornerRadiusDp));
        if (profile.borderWidthDp > 0) {
            background.setStroke(dp(profile.borderWidthDp),
                    Color.parseColor(profile.borderColor));
        }
        decor.setBackground(background);
        decor.setClipToOutline(profile.cornerRadiusDp > 0);
        decor.setElevation(dp(profile.shadowRadiusDp));
        if (Build.VERSION.SDK_INT >= 28) {
            int shadow = Color.parseColor(profile.shadowColor);
            decor.setOutlineAmbientShadowColor(shadow);
            decor.setOutlineSpotShadowColor(shadow);
        }
        decor.setAlpha(profile.opacityPercent / 100f);
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
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

        FrameLayout.LayoutParams floatingMode = new FrameLayout.LayoutParams(
                dp(profile.modeButtonSizeDp), dp(profile.modeButtonSizeDp),
                floatingModeButtonGravity());
        int modeMargin = dp(8);
        if (profile.modeButtonPosition.startsWith("TOP")) {
            floatingMode.topMargin = modeMargin;
        } else {
            floatingMode.bottomMargin = modeMargin;
        }
        if (profile.modeButtonPosition.endsWith("LEFT")) {
            floatingMode.leftMargin = modeMargin;
            if (profile.closeButtonVisible && profile.modeButtonPosition.startsWith("TOP")) {
                floatingMode.leftMargin += handle + modeMargin;
            }
        } else {
            floatingMode.rightMargin = modeMargin;
            if (profile.resizeHandleVisible && !profile.resizeLocked
                    && profile.modeButtonPosition.startsWith("BOTTOM")) {
                floatingMode.rightMargin += handle + modeMargin;
            }
        }
        floatingModeButton.setLayoutParams(floatingMode);

        updateModeButtons();
    }

    private int floatingModeButtonGravity() {
        int vertical = profile.modeButtonPosition.startsWith("BOTTOM")
                ? Gravity.BOTTOM : Gravity.TOP;
        int horizontal = profile.modeButtonPosition.endsWith("RIGHT")
                ? Gravity.END : Gravity.START;
        return vertical | horizontal;
    }

    private void updateModeButtons() {
        if (modeButton == null || floatingModeButton == null) return;
        ViewGroup.LayoutParams params = modeButton.getLayoutParams();
        if (params != null) {
            params.width = dp(profile.modeButtonSizeDp);
            params.height = dp(profile.modeButtonSizeDp);
            modeButton.setLayoutParams(params);
        }
        modeButton.setAlpha(profile.modeButtonOpacityPercent / 100f);
        modeButton.setVisibility(!floating && profile.enabled && profile.modeButtonVisible
                ? View.VISIBLE : View.GONE);
        modeButton.setText("◲");
        modeButton.setContentDescription("Открыть Навигатор в окне");
        floatingModeButton.setAlpha(profile.modeButtonOpacityPercent / 100f);
        floatingModeButton.setVisibility(floating && profile.enabled
                && profile.modeButtonVisible ? View.VISIBLE : View.GONE);
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
