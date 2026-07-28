/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.launcher.LauncherAppCatalog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** One shared editor for the HOME and driver-panel all-applications catalogs. */
public final class AllAppsSettingsActivity extends AppCompatActivity {
    private interface IntSetter { void set(int value); }

    private Preferences preferences;
    private LinearLayout applications;
    private TextView visibleCount;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "all-apps-settings-catalog");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        setTitle("Все приложения");
        View content = buildContent();
        setContentView(content);
        SettingsBackNavigation.install(this, content);
        loadApplications();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    @NonNull
    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(26), dp(18), dp(26), dp(32));
        root.setBackgroundColor(0xFF0B0E13);
        scroll.addView(root, new ScrollView.LayoutParams(match(), wrap()));

        TextView title = text("Меню «Все приложения»", 25, Color.WHITE);
        root.addView(title);
        TextView hint = text("Эти параметры одновременно применяются в лаунчере и в панели "
                + "водителя. По умолчанию системные приложения скрыты, кроме «Телефона»; "
                + "любой системный экран можно включить ниже.", 14, 0xFF9A9AA0);
        LinearLayout.LayoutParams hintParams = rowParams();
        hintParams.bottomMargin = dp(12);
        root.addView(hint, hintParams);

        slider(root, "Столбцы", 3, 8,
                clamp(preferences.launcherAllAppsColumns.get(), 3, 8), "", value -> {
                    preferences.launcherAllAppsColumns.set(value);
                });
        slider(root, "Масштаб иконок", 60, 180,
                clamp(preferences.launcherAllAppsIconScalePercent.get(), 60, 180), "%",
                value -> {
                    preferences.launcherAllAppsIconScalePercent.set(value);
                });
        slider(root, "Расстояние между иконками", 0, 40,
                clamp(preferences.launcherAllAppsGapPx.get(), 0, 40), " px", value -> {
                    preferences.launcherAllAppsGapPx.set(value);
                });

        TextView section = text("Показывать приложения", 21, Color.WHITE);
        LinearLayout.LayoutParams sectionParams = rowParams();
        sectionParams.topMargin = dp(20);
        root.addView(section, sectionParams);
        visibleCount = text("Загрузка…", 13, 0xFF8E8E93);
        root.addView(visibleCount, rowParams());
        applications = new LinearLayout(this);
        applications.setOrientation(LinearLayout.VERTICAL);
        root.addView(applications, rowParams());
        return scroll;
    }

    private void loadApplications() {
        loader.execute(() -> {
            List<LauncherAppCatalog.App> values =
                    LauncherAppCatalog.loadIncludingSystem(this);
            LauncherAppCatalog.ensureDefaultSystemVisibility(this, preferences, values);
            runOnUiThread(() -> renderApplications(values));
        });
    }

    private void renderApplications(@NonNull List<LauncherAppCatalog.App> values) {
        if (isFinishing() || isDestroyed()) return;
        applications.removeAllViews();
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        for (LauncherAppCatalog.App app : values) {
            MaterialCardView card = new MaterialCardView(this);
            card.setCardBackgroundColor(0xFF1C1C1E);
            card.setRadius(dp(14));
            card.setStrokeColor(0xFF38383A);
            card.setStrokeWidth(dp(1));

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(LauncherAppCatalog.loadIcon(this, app));
            row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(app.label, 16, Color.WHITE);
            TextView component = text(app.component.flattenToShortString(),
                    11, 0xFF8E8E93);
            labels.addView(name);
            labels.addView(component);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, wrap(), 1f);
            labelsParams.leftMargin = dp(12);
            row.addView(labels, labelsParams);
            MaterialSwitch visible = new MaterialSwitch(this);
            String key = app.component.flattenToString();
            visible.setText(hidden.contains(key) ? "Скрыто" : "В меню");
            visible.setTextColor(Color.WHITE);
            visible.setChecked(!hidden.contains(key));
            visible.setOnCheckedChangeListener((button, checked) -> {
                Set<String> current = preferences.launcherAllAppsHiddenComponents.get();
                if (checked) current.remove(key);
                else current.add(key);
                preferences.launcherAllAppsHiddenComponents.set(current);
                visible.setText(checked ? "В меню" : "Скрыто");
                updateCount(values);
            });
            row.addView(visible, new LinearLayout.LayoutParams(dp(150), dp(52)));
            card.addView(row);
            applications.addView(card, rowParams());
        }
        updateCount(values);
    }

    private void updateCount(@NonNull List<LauncherAppCatalog.App> values) {
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        int hiddenCurrent = 0;
        for (LauncherAppCatalog.App app : values) {
            if (hidden.contains(app.component.flattenToString())) hiddenCurrent++;
        }
        visibleCount.setText("Отображается " + (values.size() - hiddenCurrent)
                + " из " + values.size());
    }

    private void slider(@NonNull LinearLayout host, @NonNull String label,
                        int minimum, int maximum, int current, @NonNull String suffix,
                        @NonNull IntSetter setter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(label + ": " + current + suffix, 15, 0xFFC7C7CC);
        block.addView(heading);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(current - minimum);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) {
                int value = minimum + progress;
                heading.setText(label + ": " + value + suffix);
                if (fromUser) setter.set(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        host.addView(block, rowParams());
    }

    @NonNull
    private TextView text(@NonNull String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    @NonNull
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
