/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import dezz.status.widget.launcher.navigation.NavigationPanelConfig;
import dezz.status.widget.launcher.navigation.NavigationPanelConfigStore;

/**
 * Precise companion controls for the primary, full-size WYSIWYG navigation editor on HOME.
 */
public final class NavigationPanelSettingsActivity extends AppCompatActivity {
    private interface ValueChange { boolean set(int value); }
    private interface ValueRead { int get(); }
    private interface ValueLabel { String format(int value); }

    private Preferences preferences;
    private NavigationPanelConfigStore store;
    private NavigationPanelConfig config;
    private LinearLayout elementHost;
    private boolean resumedOnce;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new NavigationPanelConfigStore(preferences);
        config = store.load();
        setTitle("Сетка навигации");
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce) {
            config = store.load();
            setContentView(buildContent());
        }
        resumedOnce = true;
    }

    @Override
    protected void onStop() {
        store.save(config);
        super.onStop();
    }

    @NonNull
    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(match(), wrap()));

        addTitle(root, "Элементы навигации");
        addHint(root, "Основной редактор открывается прямо на реальном HOME: размеры ячеек "
                + "совпадают с фактическим прямоугольником панели. Перетаскивайте элемент, "
                + "а его выделенный правый нижний угол меняет ширину и высоту.");
        addButton(root, "Редактировать прямо на HOME", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_NAVIGATION_CONTENT, true)));

        MaterialSwitch visible = new MaterialSwitch(this);
        visible.setText("Показывать «Маршрут и избранное» на HOME");
        visible.setTextSize(16);
        visible.setMinHeight(dp(52));
        visible.setChecked(preferences.launcherNavigationVisible.get()
                || preferences.launcherFavoriteRoutesVisible.get());
        visible.setOnCheckedChangeListener((button, checked) -> {
            preferences.launcherNavigationVisible.set(checked);
            preferences.launcherFavoriteRoutesVisible.set(checked);
        });
        root.addView(visible, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(root, "Размер и положение всей панели на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));

        addTitle(root, "Точная сетка");
        addHint(root, "Положение считается от левого верхнего угла. Ширина и высота занимают "
                + "целое число ячеек; масштаб меняет только содержимое внутри прямоугольника.");
        addSlider(root, "Столбцы сетки", 1,
                NavigationPanelConfig.MIN_GRID_COLUMNS,
                NavigationPanelConfig.MAX_GRID_COLUMNS,
                () -> config.gridColumns,
                value -> config.setGridSize(value, config.gridRows),
                String::valueOf, true);
        addSlider(root, "Строки сетки", 1,
                NavigationPanelConfig.MIN_GRID_ROWS,
                NavigationPanelConfig.MAX_GRID_ROWS,
                () -> config.gridRows,
                value -> config.setGridSize(config.gridColumns, value),
                String::valueOf, true);

        addTitle(root, "Точные параметры элементов");
        addHint(root, "Графика стрелки манёвра и графика полос движения — независимые "
                + "элементы: можно включить, разместить и масштабировать каждую отдельно.");
        elementHost = new LinearLayout(this);
        elementHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(elementHost, new LinearLayout.LayoutParams(match(), wrap()));
        rebuildElementList();

        addButton(root, "Настроить кнопки избранных маршрутов…", v ->
                startActivity(new Intent(this, FavoriteRoutesSettingsActivity.class)));
        addButton(root, "Вернуть сетку по умолчанию", v -> new AlertDialog.Builder(this)
                .setTitle("Сбросить сетку навигации?")
                .setMessage("Видимость, положение, размеры и масштаб элементов вернутся "
                        + "к исходным значениям. Размер всей HOME-панели не изменится.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    store.reset();
                    config = store.load();
                    setContentView(buildContent());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        return scroll;
    }

    private void rebuildElementList() {
        if (elementHost == null) return;
        elementHost.removeAllViews();
        for (NavigationPanelConfig.Spec spec : NavigationPanelConfig.SPECS) {
            addElementCard(spec);
        }
    }

    private void addElementCard(@NonNull NavigationPanelConfig.Spec spec) {
        NavigationPanelConfig.Element element = config.element(spec.id);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(112, 70, 82, 104));
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        MaterialSwitch enabled = new MaterialSwitch(this);
        enabled.setText(spec.label);
        enabled.setTextSize(16);
        enabled.setChecked(element.enabled);
        enabled.setMinHeight(dp(48));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (!config.setEnabled(spec.id, checked)) {
                button.setChecked(config.element(spec.id).enabled);
                Toast.makeText(this, "В сетке нет свободного места для элемента",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            store.save(config);
            rebuildElementList();
        });
        card.addView(enabled, new LinearLayout.LayoutParams(match(), wrap()));

        if (element.enabled) {
            addSlider(card, "Столбец", 0, 0, Math.max(0, config.gridColumns - 1),
                    () -> config.element(spec.id).column,
                    value -> config.setPosition(spec.id, value,
                            config.element(spec.id).row),
                    value -> Integer.toString(value + 1), false);
            addSlider(card, "Строка", 0, 0, Math.max(0, config.gridRows - 1),
                    () -> config.element(spec.id).row,
                    value -> config.setPosition(spec.id,
                            config.element(spec.id).column, value),
                    value -> Integer.toString(value + 1), false);
            addSlider(card, "Ширина", 1, 1, config.gridColumns,
                    () -> config.element(spec.id).columnSpan,
                    value -> config.setSpan(spec.id, value,
                            config.element(spec.id).rowSpan),
                    value -> value + " яч.", false);
            addSlider(card, "Высота", 1, 1, config.gridRows,
                    () -> config.element(spec.id).rowSpan,
                    value -> config.setSpan(spec.id,
                            config.element(spec.id).columnSpan, value),
                    value -> value + " яч.", false);
            addSlider(card, "Масштаб", NavigationPanelConfig.MIN_SCALE,
                    NavigationPanelConfig.MIN_SCALE, NavigationPanelConfig.MAX_SCALE,
                    () -> config.element(spec.id).scalePercent,
                    value -> {
                        config.setScale(spec.id, value);
                        return true;
                    }, value -> value + "%", false);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(10);
        elementHost.addView(card, params);
    }

    private void addSlider(@NonNull LinearLayout parent, @NonNull String title,
                           int fallback, int minimum, int maximum,
                           @NonNull ValueRead read, @NonNull ValueChange change,
                           @NonNull ValueLabel formatter, boolean rebuildScreen) {
        int safeMaximum = Math.max(minimum, maximum);
        int current = clamp(read.get(), minimum, safeMaximum);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(14);
        TextView value = new TextView(this);
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(84));
        value.setText(formatter.format(current));
        SeekBar seek = new SeekBar(this);
        seek.setMax(safeMaximum - minimum);
        seek.setProgress(current - minimum);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean changed;

            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (!user) return;
                int requested = minimum + progress;
                if (!change.set(requested)) {
                    int effective = clamp(read.get(), minimum, safeMaximum);
                    value.setText(formatter.format(effective));
                    if (bar.getProgress() != effective - minimum) {
                        bar.setProgress(effective - minimum);
                    }
                    return;
                }
                changed = true;
                store.save(config);
                value.setText(formatter.format(clamp(read.get(), minimum, safeMaximum)));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                changed = false;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!changed) return;
                bar.post(() -> {
                    if (rebuildScreen) setContentView(buildContent());
                    else rebuildElementList();
                });
            }
        });
        row.addView(label, new LinearLayout.LayoutParams(0, wrap(), .30f));
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(44), .70f));
        row.addView(value);
        parent.addView(row, new LinearLayout.LayoutParams(match(), dp(52)));
    }

    private void addButton(@NonNull LinearLayout parent, @NonNull String title,
                           @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), dp(54));
        params.topMargin = dp(7);
        parent.addView(button, params);
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(22);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(20);
        params.bottomMargin = dp(6);
        parent.addView(title, params);
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(14);
        hint.setAlpha(.78f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.bottomMargin = dp(8);
        parent.addView(hint, params);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
