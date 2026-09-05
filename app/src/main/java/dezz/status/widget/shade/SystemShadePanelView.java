/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.Preferences;
import dezz.status.widget.LongPressFeedback;
import dezz.status.widget.driver.DriverPanelActionExecutor;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.LauncherShortcutStore;

/** Live content of the replacement shade. Geometry and action storage are independent from HOME. */
final class SystemShadePanelView extends FrameLayout {
    interface Listener { void onActionExecuted(); }

    private final Context context;
    private final SystemShadeConfig config;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
    private final LauncherMediaController mediaController;
    private TextView clock;
    private TextView date;
    private TextView mediaTitle;
    private TextView mediaArtist;
    private ImageButton mediaPlay;

    private final Runnable timeTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            if (clock != null) clock.setText(clockFormat.format(now));
            if (date != null) date.setText(dateFormat.format(now));
            handler.postDelayed(this, 1_000L - (System.currentTimeMillis() % 1_000L));
        }
    };

    SystemShadePanelView(@NonNull Context context, @NonNull Preferences preferences,
                         @NonNull SystemShadeConfig config, @NonNull Listener listener) {
        super(context);
        this.context = context;
        this.config = config.copy();
        this.listener = listener;
        setClipChildren(false);
        setBackground(background(config.panelColor, config.panelOpacityPercent,
                config.panelCornerRadiusPx));
        LauncherShortcutStore shortcuts = LauncherShortcutStore.forSystemShade(preferences);
        DriverPanelActionExecutor executor = new DriverPanelActionExecutor(context, preferences,
                new DriverPanelActionExecutor.Host() {
                    @Override public void showAllApps(View anchor) { launchHome(); }
                    @Override public void showFavorites(@NonNull String panelId, View anchor) {
                        launchHome();
                    }
                    @Override public void triggerStockClimate() {
                        DriverPanelService.triggerStockClimate(context);
                    }
                    @Override public dezz.status.widget.car.CarControlState carControlState(
                            @NonNull String controlId) { return null; }
                });
        for (SystemShadeConfig.Element element : this.config.snapshot()) {
            if (!element.visible) continue;
            View child = createElement(element, shortcuts.all(), executor);
            LayoutParams params = new LayoutParams(element.width, element.height);
            params.leftMargin = element.x;
            params.topMargin = element.y;
            addView(child, params);
        }
        mediaController = new LauncherMediaController(context, preferences, this::renderMedia);
    }

    void start() {
        handler.removeCallbacks(timeTick);
        timeTick.run();
        mediaController.start();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        mediaController.stop();
    }

    private View createElement(SystemShadeConfig.Element element,
                               List<LauncherShortcutStore.Shortcut> shortcuts,
                               DriverPanelActionExecutor executor) {
        switch (element.kind) {
            case CLOCK:
                clock = label(element, Gravity.CENTER_VERTICAL | Gravity.END);
                return clock;
            case DATE:
                date = label(element, Gravity.CENTER_VERTICAL | Gravity.END);
                return date;
            case MEDIA:
                return media(element);
            case VOLUME:
                return slider(element, false);
            case BRIGHTNESS:
                return slider(element, true);
            case ACTIONS:
            default:
                return actions(element, shortcuts, executor);
        }
    }

    private View slider(SystemShadeConfig.Element element, boolean brightness) {
        LinearLayout row = box(element, LinearLayout.HORIZONTAL);
        TextView icon = label(element, Gravity.CENTER);
        icon.setText(brightness ? "☀" : "🔊");
        icon.setTextSize(element.iconSizePx * 0.45f);
        row.addView(icon, new LinearLayout.LayoutParams(element.iconSizePx + element.paddingPx,
                LayoutParams.MATCH_PARENT));
        SeekBar bar = new SeekBar(context);
        bar.setMax(100);
        if (brightness) {
            int current = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            bar.setProgress(Math.round(current * 100f / 255f));
        } else {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int max = audio == null ? 1 : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int current = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            bar.setProgress(Math.round(current * 100f / max));
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (brightness) setBrightness(progress); else setVolume(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        return row;
    }

    private View media(SystemShadeConfig.Element element) {
        LinearLayout root = box(element, LinearLayout.VERTICAL);
        mediaTitle = label(element, Gravity.CENTER_VERTICAL);
        mediaTitle.setText("Музыка не воспроизводится");
        mediaTitle.setSingleLine(true);
        mediaArtist = label(element, Gravity.CENTER_VERTICAL);
        mediaArtist.setTextSize(Math.max(12, element.textSizeSp - 5));
        mediaArtist.setAlpha(.72f);
        mediaArtist.setSingleLine(true);
        root.addView(mediaTitle, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(mediaArtist, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        controls.addView(mediaButton("media_previous", () -> mediaController.previous(), element));
        mediaPlay = mediaButton("media", () -> mediaController.playPause(), element);
        controls.addView(mediaPlay);
        controls.addView(mediaButton("media_next", () -> mediaController.next(), element));
        root.addView(controls, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.15f));
        return root;
    }

    private ImageButton mediaButton(String icon, Runnable command, SystemShadeConfig.Element e) {
        ImageButton button = new ImageButton(context);
        button.setImageDrawable(LauncherIconResolver.resolvePreset(context, icon, e.textColor));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(e.gapPx, e.gapPx, e.gapPx, e.gapPx);
        button.setOnClickListener(view -> command.run());
        button.setContentDescription(icon);
        button.setLayoutParams(new LinearLayout.LayoutParams(e.iconSizePx * 2, e.iconSizePx * 2));
        return button;
    }

    private View actions(SystemShadeConfig.Element element,
                         List<LauncherShortcutStore.Shortcut> shortcuts,
                         DriverPanelActionExecutor executor) {
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(element.columns);
        grid.setPadding(element.paddingPx, element.paddingPx, element.paddingPx, element.paddingPx);
        for (LauncherShortcutStore.Shortcut shortcut : shortcuts) {
            if (!shortcut.enabled || shortcut.kind == LauncherShortcutStore.Kind.DIVIDER) continue;
            LinearLayout tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(element.gapPx, element.gapPx, element.gapPx, element.gapPx);
            tile.setBackground(background(shortcut.backgroundColor, element.opacityPercent,
                    element.cornerRadiusPx));
            ImageButton icon = new ImageButton(context);
            icon.setImageDrawable(LauncherIconResolver.resolve(context, shortcut));
            icon.setBackgroundColor(Color.TRANSPARENT);
            icon.setClickable(false);
            tile.addView(icon, new LinearLayout.LayoutParams(element.iconSizePx, element.iconSizePx));
            if (shortcut.showTitle) {
                TextView title = label(element, Gravity.CENTER);
                title.setText(shortcut.title);
                title.setTextSize(Math.max(10, element.textSizeSp - 4));
                title.setSingleLine(true);
                tile.addView(title, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT));
            }
            tile.setOnClickListener(view -> {
                executor.execute(shortcut, view);
                if (config.hapticFeedback) view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                listener.onActionExecuted();
            });
            tile.setOnLongClickListener(view -> {
                boolean handled = executor.executeLong(shortcut, view);
                if (handled) LongPressFeedback.play(view);
                return handled;
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            params.setMargins(element.gapPx / 2, element.gapPx / 2,
                    element.gapPx / 2, element.gapPx / 2);
            grid.addView(tile, params);
        }
        return grid;
    }

    private void renderMedia(LauncherMediaController.Snapshot snapshot) {
        if (mediaTitle != null) mediaTitle.setText(snapshot.title);
        if (mediaArtist != null) mediaArtist.setText(snapshot.artist);
        if (mediaPlay != null) mediaPlay.setImageDrawable(LauncherIconResolver.resolvePreset(
                context, snapshot.playing ? "media_pause" : "media", "#FFFFFFFF"));
    }

    private TextView label(SystemShadeConfig.Element e, int gravity) {
        TextView value = new TextView(context);
        value.setTextColor(parse(e.textColor, Color.WHITE));
        value.setTextSize(e.textSizeSp);
        value.setGravity(gravity);
        return value;
    }

    private LinearLayout box(SystemShadeConfig.Element e, int orientation) {
        LinearLayout value = new LinearLayout(context);
        value.setOrientation(orientation);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(e.paddingPx, e.paddingPx, e.paddingPx, e.paddingPx);
        value.setBackground(background(e.backgroundColor, e.opacityPercent, e.cornerRadiusPx));
        return value;
    }

    private void setVolume(int percent) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                Math.round(max * Math.max(0, Math.min(100, percent)) / 100f), 0);
    }

    private void setBrightness(int percent) {
        if (!Settings.System.canWrite(context)) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(permission);
            return;
        }
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                Math.round(255f * Math.max(1, Math.min(100, percent)) / 100f));
    }

    private void launchHome() {
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private static GradientDrawable background(String raw, int opacityPercent, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        int color = parse(raw, Color.TRANSPARENT);
        int sourceAlpha = Color.alpha(color);
        drawable.setColor((color & 0x00FFFFFF) | (Math.round(sourceAlpha
                * Math.max(0, Math.min(100, opacityPercent)) / 100f) << 24));
        drawable.setCornerRadius(Math.max(0, radius));
        return drawable;
    }

    private static int parse(String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
