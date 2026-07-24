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
import android.widget.FrameLayout;
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

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.launcher.navigation.NavigationPanelConfig;
import dezz.status.widget.launcher.navigation.NavigationPanelConfigStore;
import dezz.status.widget.launcher.panels.PanelContentEditOverlay;
import dezz.status.widget.launcher.panels.PanelGridLayout;

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
    private PanelGridLayout previewGrid;
    private PanelContentEditOverlay previewOverlay;
    private FrameLayout previewHost;
    private boolean resumedOnce;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new NavigationPanelConfigStore(preferences);
        config = store.load();
        setTitle("Сетка навигации");
        showContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce) {
            config = store.load();
            showContent();
        }
        resumedOnce = true;
    }

    private void showContent() {
        View screen = buildContent();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.install(this, screen);
    }

    @Override
    protected void onStop() {
        store.save(config);
        super.onStop();
    }

    @NonNull
    private View buildContent() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.HORIZONTAL);
        screen.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), 0, dp(22), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(match(), wrap()));
        screen.addView(scroll, new LinearLayout.LayoutParams(0, match(), 1.04f));

        addTitle(root, "Элементы навигации");
        addHint(root, "Редактируйте сетку прямо в предпросмотре справа или на реальном HOME. "
                + "Перетаскивайте элемент, а любой из четырёх выделенных углов меняет ширину "
                + "и высоту. Оба редактора сохраняют одну и ту же сетку.");
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
                    showContent();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        LinearLayout previewColumn = new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setPadding(dp(10), 0, 0, dp(24));
        TextView previewTitle = new TextView(this);
        previewTitle.setText("ЖИВОЙ РЕДАКТОР СЕТКИ");
        previewTitle.setTextSize(14);
        previewTitle.setTextColor(getColor(R.color.settings_accent));
        previewTitle.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(38)));

        TextView previewHint = new TextView(this);
        previewHint.setText("Тащите элементы. Потяните выбранный элемент за любой "
                + "из четырёх углов, чтобы изменить его размер.");
        previewHint.setTextSize(14);
        previewHint.setAlpha(.75f);
        LinearLayout.LayoutParams previewHintParams =
                new LinearLayout.LayoutParams(match(), wrap());
        previewHintParams.bottomMargin = dp(10);
        previewColumn.addView(previewHint, previewHintParams);

        previewHost = new FrameLayout(this);
        GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setColor(Color.rgb(8, 12, 18));
        previewBackground.setCornerRadius(dp(22));
        previewHost.setBackground(previewBackground);
        previewHost.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewGrid = new PanelGridLayout(this);
        previewHost.addView(previewGrid, new FrameLayout.LayoutParams(match(), match()));
        previewOverlay = new PanelContentEditOverlay(this);
        previewOverlay.setModel(previewModel(), previewListener());
        previewOverlay.setEditing(true);
        previewHost.addView(previewOverlay, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));

        TextView saved = new TextView(this);
        saved.setText("✓ Изменения сохраняются автоматически");
        saved.setTextSize(13);
        saved.setGravity(Gravity.CENTER);
        saved.setAlpha(.72f);
        previewColumn.addView(saved, new LinearLayout.LayoutParams(match(), dp(42)));
        screen.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .96f));
        renderPreview();
        return screen;
    }

    @NonNull
    private PanelContentEditOverlay.Model previewModel() {
        return new PanelContentEditOverlay.Model() {
            @Override public int columns() {
                return config.gridColumns;
            }

            @Override public int rows() {
                return config.gridRows;
            }

            @NonNull
            @Override public List<PanelContentEditOverlay.Item> items() {
                List<PanelContentEditOverlay.Item> result = new ArrayList<>();
                for (NavigationPanelConfig.Element element : config.enabledElements()) {
                    NavigationPanelConfig.Spec spec = NavigationPanelConfig.spec(element.id);
                    result.add(new PanelContentEditOverlay.Item(element.id,
                            spec == null ? element.id : spec.label,
                            element.column, element.row,
                            element.columnSpan, element.rowSpan));
                }
                return result;
            }

            @Override public boolean setPlacement(@NonNull String id, int column, int row,
                                                  int columnSpan, int rowSpan) {
                return config.setPlacement(id, column, row, columnSpan, rowSpan);
            }
        };
    }

    @NonNull
    private PanelContentEditOverlay.Listener previewListener() {
        return (id, finished) -> {
            applyPreviewPlacements();
            if (!finished) return;
            store.save(config);
            rebuildElementList();
        };
    }

    private void renderPreview() {
        if (previewGrid == null) return;
        previewGrid.removeAllViews();
        previewGrid.setGridSize(config.gridColumns, config.gridRows);
        previewGrid.setCellGapPx(dp(5));
        for (NavigationPanelConfig.Element element : config.enabledElements()) {
            TextView sample = new TextView(this);
            sample.setText(previewText(element.id));
            sample.setTextColor(Color.WHITE);
            sample.setTextSize(Math.max(10f,
                    Math.min(25f, 15f * element.scalePercent / 100f)));
            sample.setGravity(Gravity.CENTER);
            sample.setMaxLines(3);
            sample.setPadding(dp(5), dp(5), dp(5), dp(5));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(105, 42, 59, 82));
            background.setCornerRadius(dp(12));
            sample.setBackground(background);
            sample.setTag(element.id);
            previewGrid.addView(sample, new PanelGridLayout.LayoutParams(
                    element.column, element.row, element.columnSpan, element.rowSpan));
        }
        if (previewOverlay != null) {
            previewOverlay.setEditing(true);
            previewOverlay.invalidate();
        }
    }

    private void applyPreviewPlacements() {
        if (previewGrid == null) return;
        previewGrid.setGridSize(config.gridColumns, config.gridRows);
        for (NavigationPanelConfig.Element element : config.enabledElements()) {
            previewGrid.updatePlacement(element.id, element.column, element.row,
                    element.columnSpan, element.rowSpan);
        }
        if (previewOverlay != null) previewOverlay.invalidate();
    }

    @NonNull
    private static String previewText(@NonNull String id) {
        if (NavigationPanelConfig.ARRIVAL.equals(id)) return "12:45\nПрибытие";
        if (NavigationPanelConfig.DURATION.equals(id)) return "24 мин";
        if (NavigationPanelConfig.DISTANCE.equals(id)) return "18 км";
        if (NavigationPanelConfig.MANEUVER_IMAGE.equals(id)) return "↱";
        if (NavigationPanelConfig.MANEUVER_DISTANCE.equals(id)) return "350 м";
        if (NavigationPanelConfig.MANEUVER.equals(id)) return "Поверните направо";
        if (NavigationPanelConfig.TRIP_INFO.equals(id)) return "18 км · 24 мин";
        if (NavigationPanelConfig.COMBINED.equals(id)) {
            return "↱  350 м\nПоверните направо";
        }
        if (NavigationPanelConfig.SPEED_LIMIT.equals(id)) return "60";
        if (NavigationPanelConfig.TRAFFIC_LIGHT.equals(id)) return "●  ●  ●";
        if (NavigationPanelConfig.LANES_IMAGE.equals(id)) return "↑  ↑  ↗";
        if (NavigationPanelConfig.LANE_INFO.equals(id)) return "Держитесь правее";
        if (NavigationPanelConfig.JAM_PROGRESS.equals(id)) return "Пробки · 3";
        if (NavigationPanelConfig.RAINBOW_IMAGE.equals(id)) return "Маршрут";
        if (NavigationPanelConfig.INACTIVE.equals(id)) return "Избранные места";
        return id;
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
            renderPreview();
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
                renderPreview();
                value.setText(formatter.format(clamp(read.get(), minimum, safeMaximum)));
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                changed = false;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!changed) return;
                bar.post(() -> {
                    if (rebuildScreen) showContent();
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
        title.setTextColor(getColor(R.color.settings_accent));
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
