/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.InstalledAppCatalog;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.shell.PrivilegedShell;

/**
 * Owns the old-style driver rail and the overlay all-apps drawer.
 *
 * <p>The rail is always one continuous window. Its movable climate shortcut uses the already
 * normalized live climate state and temporarily removes the whole rail from input hit-testing
 * while an accessibility gesture taps the covered OEM climate coordinate.</p>
 */
final class DriverPanelOverlayController implements DriverPanelActionExecutor.Host {
    interface StatusListener {
        void onStatus(@NonNull String status, @NonNull String detail);
    }

    private static final String TAG = "DriverPanelOverlay";
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;

    private final Context appContext;
    private final Preferences preferences;
    private final StatusListener statusListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "driver-panel-app-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private final DriverPanelActionExecutor actions;

    private final List<AttachedWindow> panelWindows = new ArrayList<>();
    @Nullable private AttachedWindow drawerWindow;
    @Nullable private GridView drawerGrid;
    private int attachedType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private int applyGeneration;
    private int proxyTapGeneration;
    private boolean navigationHidden;

    DriverPanelOverlayController(@NonNull Context context,
                                 @NonNull Preferences preferences,
                                 @NonNull StatusListener statusListener) {
        this.appContext = context.getApplicationContext();
        this.preferences = preferences;
        this.statusListener = statusListener;
        this.actions = new DriverPanelActionExecutor(appContext, preferences, this);
    }

    int getAttachedWindowType() {
        return attachedType;
    }

    void applyPreferences() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::applyPreferences);
            return;
        }
        int generation = ++applyGeneration;
        detachPanel();
        if (!preferences.driverPanelEnabled.get()) {
            dismissAllApps();
            statusListener.onStatus("stopped", "Панель водителя выключена");
            return;
        }
        if (navigationHidden) {
            statusListener.onStatus("hidden", "Штатная панель скрыта активным приложением");
            return;
        }

        Display display = defaultDisplay();
        if (display == null) {
            statusListener.onStatus("error", "Основной дисплей не найден");
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        LauncherShortcutStore store = LauncherShortcutStore.forDriverPanel(preferences);
        List<LauncherShortcutStore.Shortcut> enabled = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
            if (shortcut.enabled && enabled.size() < DriverPanelLayoutPolicy.MAX_BUTTONS) {
                enabled.add(shortcut);
            }
        }
        DriverPanelLayoutPolicy.Layout geometry = DriverPanelLayoutPolicy.calculate(
                metrics.heightPixels,
                preferences.driverPanelTopPaddingPx.get(),
                preferences.driverPanelBottomPaddingPx.get(),
                enabled.size(),
                false);

        RuntimeException failure = null;
        for (int type : DriverPanelWindowTypePolicy.candidates()) {
            if (generation != applyGeneration) return;
            try {
                attachForType(display, type, enabled, geometry);
                attachedType = type;
                String mode = type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        ? "обычный overlay" : "системный ECARX";
                String pocket = "кнопки используют всю высоту; климат открывается прокси-кнопкой";
                statusListener.onStatus("active",
                        "Старая панель · " + enabled.size() + " кнопок · "
                                + mode + " · " + pocket);
                return;
            } catch (RuntimeException error) {
                failure = error;
                detachPanel();
                Log.w(TAG, "Window type " + type + " rejected", error);
            }
        }
        statusListener.onStatus("error", failure == null
                ? "Не удалось добавить панель"
                : "WindowManager отклонил панель: " + failure.getClass().getSimpleName());
    }

    void setNavigationHidden(boolean hidden) {
        if (navigationHidden == hidden) return;
        navigationHidden = hidden;
        applyPreferences();
    }

    void raise() {
        if (!preferences.driverPanelEnabled.get() || navigationHidden) return;
        applyPreferences();
    }

    void destroy() {
        applyGeneration++;
        dismissAllApps();
        detachPanel();
        catalogExecutor.shutdownNow();
    }

    @Override
    public void showAllApps() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::showAllApps);
            return;
        }
        if (drawerWindow != null) {
            dismissAllApps();
            return;
        }
        Display display = defaultDisplay();
        if (display == null) return;
        // Use the rail's successfully attached ECARX system layer. The drawer is added later on
        // the same layer, so it stays above floating navigation/application windows as well as
        // above the rail itself. Portable builds naturally keep TYPE_APPLICATION_OVERLAY here.
        Context context = windowContext(display, attachedType);
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return;

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.argb(247, 10, 13, 18));
        int railInset = Math.max(100, preferences.driverPanelWidthPx.get()) + 24;
        if (preferences.driverPanelSide.get() == 0) {
            root.setPadding(railInset, 24, 24, 24);
        } else {
            root.setPadding(24, 24, railInset, 24);
        }

        TextView title = new TextView(context);
        title.setText("Все приложения · включая системные");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 72, Gravity.TOP | Gravity.START);
        root.addView(title, titleParams);

        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_driver_close);
        close.setColorFilter(Color.WHITE);
        close.setBackground(rippleBackground(Color.argb(45, 255, 255, 255), 18));
        close.setContentDescription("Закрыть список приложений");
        close.setOnClickListener(view -> dismissAllApps());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(72, 72,
                Gravity.TOP | Gravity.END);
        root.addView(close, closeParams);

        GridView grid = new GridView(context);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(12);
        grid.setVerticalSpacing(12);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, 12, 0, 24);
        grid.setAdapter(new AppsAdapter(context, Collections.emptyList(),
                this::dismissAllApps));
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gridParams.topMargin = 84;
        root.addView(grid, gridParams);

        WindowManager.LayoutParams params = fullScreenParams(attachedType);
        try {
            manager.addView(root, params);
            drawerWindow = new AttachedWindow(root, params, manager);
            drawerGrid = grid;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not show all-apps drawer", error);
            Toast.makeText(appContext, "Не удалось открыть список приложений",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final int generation = ++applyGeneration;
        catalogExecutor.execute(() -> {
            List<InstalledAppCatalog.App> apps = InstalledAppCatalog.load(appContext);
            mainHandler.post(() -> {
                if (drawerGrid == null || drawerWindow == null
                        || generation != applyGeneration) return;
                drawerGrid.setAdapter(new AppsAdapter(drawerGrid.getContext(), apps,
                        this::dismissAllApps));
            });
        });
    }

    @Override
    public void triggerStockClimate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::triggerStockClimate);
            return;
        }
        dismissAllApps();
        Display display = defaultDisplay();
        if (display == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        DriverPanelLayoutPolicy.TapTarget target =
                DriverPanelLayoutPolicy.stockClimateTapTarget(
                        metrics.widthPixels, metrics.heightPixels,
                        preferences.driverPanelSide.get() == 1);
        int generation = ++proxyTapGeneration;

        // Keep the panel visually stable but remove it from input hit-testing for the duration of
        // the injected gesture. That makes the event land on the covered OEM climate icon.
        setPanelTouchable(false);
        Runnable restore = () -> mainHandler.postDelayed(() -> {
            if (generation != proxyTapGeneration) return;
            setPanelTouchable(true);
        }, 90L);
        mainHandler.postDelayed(() -> {
            if (generation == proxyTapGeneration) setPanelTouchable(true);
        }, 1_200L);
        if (WidgetAccessibilityService.performTap(target.x, target.y, success -> {
            if (generation != proxyTapGeneration) return;
            if (success) restore.run();
            else fallbackStockClimateTap(target, generation);
        })) return;
        fallbackStockClimateTap(target, generation);
    }

    private void fallbackStockClimateTap(@NonNull DriverPanelLayoutPolicy.TapTarget target,
                                         int generation) {
        // Rare fallback for head units where accessibility was disabled. Removing the window is
        // preferable to dispatching into our own overlay while privileged-shell discovery runs.
        detachPanel();
        PrivilegedShell.get(appContext).runCommand(
                "input tap " + target.x + " " + target.y, (output, error) -> {
                    if (generation != proxyTapGeneration) return;
                    applyPreferences();
                    if (error != null) {
                        Toast.makeText(appContext,
                                "Включите спецвозможности для кнопки штатного климата",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void dismissAllApps() {
        applyGeneration++;
        AttachedWindow drawer = drawerWindow;
        drawerWindow = null;
        drawerGrid = null;
        if (drawer != null) drawer.remove();
    }

    private void attachForType(@NonNull Display display, int type,
                               @NonNull List<LauncherShortcutStore.Shortcut> shortcuts,
                               @NonNull DriverPanelLayoutPolicy.Layout geometry) {
        Context context = windowContext(display, type);
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) throw new IllegalStateException("WindowManager unavailable");
        attachSegment(context, manager, type, geometry.contentTop,
                geometry.contentBottom - geometry.contentTop, shortcuts);
    }

    private void attachSegment(@NonNull Context context, @NonNull WindowManager manager,
                               int type, int y, int height,
                               @NonNull List<LauncherShortcutStore.Shortcut> shortcuts) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackground(panelBackground());
        int gap = Math.max(0, preferences.driverPanelItemGapPx.get());
        for (LauncherShortcutStore.Shortcut shortcut : shortcuts) {
            View button = shortcutButton(context, shortcut);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            itemParams.setMargins(4, gap / 2, 4, gap - gap / 2);
            root.addView(button, itemParams);
        }
        WindowManager.LayoutParams params = segmentParams(type, y, height);
        manager.addView(root, params);
        panelWindows.add(new AttachedWindow(root, params, manager));
    }

    @NonNull
    private View shortcutButton(@NonNull Context context,
                                @NonNull LauncherShortcutStore.Shortcut shortcut) {
        FrameLayout button = new FrameLayout(context);
        button.setClickable(true);
        button.setFocusable(false);
        button.setClipChildren(false);
        button.setClipToPadding(false);
        button.setContentDescription(shortcut.title);
        int background = safeColor(shortcut.backgroundColor, Color.TRANSPARENT);
        button.setBackground(rippleBackground(background, 14));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int requested = Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                Math.min(LauncherShortcutStore.MAX_ICON_SIZE_PX, shortcut.iconSizePx));
        View icon;
        if (shortcut.kind == LauncherShortcutStore.Kind.BUILTIN
                && LauncherShortcutStore.Builtin.STOCK_CLIMATE.key.equals(shortcut.target)) {
            icon = new DriverClimateShortcutView(context, CarIntegrations.get(appContext),
                    shortcut.iconColor);
        } else {
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable resolved;
            if (shortcut.kind == LauncherShortcutStore.Kind.APP
                    && "app".equals(shortcut.icon)) {
                android.content.ComponentName component =
                        android.content.ComponentName.unflattenFromString(shortcut.target);
                resolved = component == null ? null
                        : HighResolutionAppIconLoader.load(context, component);
            } else {
                resolved = LauncherIconResolver.resolve(context, shortcut);
            }
            if (resolved != null) image.setImageDrawable(resolved);
            icon = image;
        }
        content.addView(icon, new LinearLayout.LayoutParams(requested, requested));

        if (shortcut.showTitle) {
            TextView label = new TextView(context);
            label.setText(shortcut.title);
            label.setTextColor(safeColor(shortcut.textColor, Color.WHITE));
            label.setTextSize(11);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.setMargins(2, 2, 2, 0);
            content.addView(label, labelParams);
        }
        button.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        button.setOnClickListener(view -> actions.execute(shortcut));
        return button;
    }

    @NonNull
    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(safeColor(preferences.driverPanelBackgroundColor.get(),
                Color.argb(238, 19, 23, 28)));
        background.setCornerRadius(Math.max(0,
                preferences.driverPanelCornerRadiusPx.get()));
        return background;
    }

    @NonNull
    private WindowManager.LayoutParams segmentParams(int type, int y, int height) {
        int width = Math.max(80, Math.min(320, preferences.driverPanelWidthPx.get()));
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, Math.max(1, height), type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | (preferences.driverPanelSide.get() == 0
                ? Gravity.LEFT : Gravity.RIGHT);
        params.x = 0;
        params.y = Math.max(0, y);
        params.setTitle("Status Widget driver panel");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        return params;
    }

    @NonNull
    private static WindowManager.LayoutParams fullScreenParams(int type) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setTitle("Status Widget all applications");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        return params;
    }

    @Nullable
    private Display defaultDisplay() {
        DisplayManager manager = (DisplayManager) appContext.getSystemService(
                Context.DISPLAY_SERVICE);
        return manager == null ? null : manager.getDisplay(DISPLAY_ID);
    }

    @NonNull
    private Context windowContext(@NonNull Display display, int type) {
        Context displayContext = appContext.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return displayContext.createWindowContext(type, null);
            } catch (RuntimeException ignored) {
            }
        }
        return displayContext;
    }

    private void detachPanel() {
        for (int index = panelWindows.size() - 1; index >= 0; index--) {
            panelWindows.get(index).remove();
        }
        panelWindows.clear();
    }

    private void setPanelTouchable(boolean touchable) {
        for (AttachedWindow window : panelWindows) {
            if (touchable) {
                window.params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                window.params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            try {
                window.manager.updateViewLayout(window.view, window.params);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static int safeColor(@Nullable String raw, int fallback) {
        try {
            return Color.parseColor(raw);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }

    @NonNull
    private static Drawable rippleBackground(int color, int radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(color);
        content.setCornerRadius(radius);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(
                Color.argb(75, 255, 255, 255)), content, mask);
    }

    private static final class AttachedWindow {
        final View view;
        final WindowManager.LayoutParams params;
        final WindowManager manager;

        AttachedWindow(View view, WindowManager.LayoutParams params, WindowManager manager) {
            this.view = view;
            this.params = params;
            this.manager = manager;
        }

        void remove() {
            try {
                manager.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class AppsAdapter extends BaseAdapter {
        private final Context context;
        private final List<InstalledAppCatalog.App> apps;
        private final Runnable close;

        AppsAdapter(Context context, List<InstalledAppCatalog.App> apps, Runnable close) {
            this.context = context;
            this.apps = apps;
            this.close = close;
        }

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            InstalledAppCatalog.App app = apps.get(position);
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(8, 10, 8, 10);
            tile.setMinimumHeight(126);
            tile.setAlpha(app.launchable() ? 1f : .45f);
            tile.setBackground(rippleBackground(Color.argb(50, 255, 255, 255), 16));

            ImageView icon = new ImageView(context);
            Drawable drawable = InstalledAppCatalog.loadIcon(context, app);
            if (drawable != null) icon.setImageDrawable(drawable);
            tile.addView(icon, new LinearLayout.LayoutParams(58, 58));

            TextView label = new TextView(context);
            label.setText(app.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = 6;
            tile.addView(label, labelParams);
            tile.setContentDescription(app.label + ". " + app.secondaryLabel());
            tile.setOnClickListener(view -> {
                if (!app.launchable()) {
                    Toast.makeText(context, "У приложения нет доступного экрана",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    close.run();
                    context.startActivity(InstalledAppCatalog.launchIntent(app));
                } catch (RuntimeException error) {
                    Toast.makeText(context, "Не удалось открыть " + app.label,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return tile;
        }
    }
}
