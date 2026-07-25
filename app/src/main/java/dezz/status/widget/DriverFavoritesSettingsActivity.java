/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.driver.DriverFavoritesPanelConfig;
import dezz.status.widget.driver.DriverFavoritesPanelStore;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.ShortcutActionPicker;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Editor for the independent mixed-content Favorites drawer on the driver rail. */
public final class DriverFavoritesSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private DriverFavoritesPanelStore panelStore;
    private DriverFavoritesPanelConfig panel;
    private LauncherShortcutStore store;
    private ShortcutActionPicker picker;
    private LinearLayout rows;
    private TextView count;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        panelStore = new DriverFavoritesPanelStore(preferences);
        panel = panelStore.resolve(preferences.driverFavoritesSelectedPanelId.get());
        preferences.driverFavoritesSelectedPanelId.set(panel.id);
        store = LauncherShortcutStore.forDriverFavorites(preferences, panel.id);
        picker = new ShortcutActionPicker(this, preferences, store, this::changed);
        setTitle("Избранное водителя · " + panel.title);
        View content = buildContent();
        setContentView(content);
        SettingsBackNavigation.install(this, content);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.load();
        refresh();
    }

    @NonNull
    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(32));
        root.setBackgroundColor(0xFF0B0E13);
        scroll.addView(root, new ScrollView.LayoutParams(match(), wrap()));

        root.addView(text("Избранное водителя · " + panel.title, 25, Color.WHITE));
        root.addView(text("Можно создать любое количество независимых компактных панелей. "
                + "Каждая вызывающая кнопка привязывается к выбранной панели, а автоматизация "
                + "может адресно показать или скрыть её без нажатия.",
                14, 0xFF8E8E93), rowParams());

        LinearLayout panelActions = new LinearLayout(this);
        MaterialButton select = compact("Выбрать");
        select.setOnClickListener(view -> choosePanel());
        panelActions.addView(select, weighted());
        MaterialButton addPanel = compact("＋ Новая");
        addPanel.setOnClickListener(view -> createPanel());
        panelActions.addView(addPanel, weightedMargin());
        MaterialButton rename = compact("Название");
        rename.setOnClickListener(view -> renamePanel());
        panelActions.addView(rename, weightedMargin());
        if (panelStore.load().size() > 1) {
            MaterialButton removePanel = compact("Удалить");
            removePanel.setTextColor(0xFFFF453A);
            removePanel.setOnClickListener(view -> removePanel());
            panelActions.addView(removePanel, weightedMargin());
        }
        root.addView(panelActions, rowParams());

        root.addView(text("Видимая сетка", 19, Color.WHITE), rowParams());
        settingSlider(root, "Столбцы", DriverFavoritesPanelConfig.MIN_COLUMNS,
                DriverFavoritesPanelConfig.MAX_COLUMNS, panel.columns, "",
                value -> panel.columns = value);
        settingSlider(root, "Видимые строки", DriverFavoritesPanelConfig.MIN_VISIBLE_ROWS,
                DriverFavoritesPanelConfig.MAX_VISIBLE_ROWS, panel.visibleRows, "",
                value -> panel.visibleRows = value);
        settingSlider(root, "Размер ячейки", DriverFavoritesPanelConfig.MIN_CELL_SIZE_PX,
                DriverFavoritesPanelConfig.MAX_CELL_SIZE_PX, panel.cellSizePx, " px",
                value -> panel.cellSizePx = value);
        settingSlider(root, "Расстояние между ячейками", 0,
                DriverFavoritesPanelConfig.MAX_GAP_PX, panel.gapPx, " px",
                value -> panel.gapPx = value);

        MaterialSwitch border = new MaterialSwitch(this);
        border.setText("Показывать границы ячеек");
        border.setTextColor(Color.WHITE);
        border.setChecked(panel.borderEnabled);
        border.setOnCheckedChangeListener((button, checked) -> {
            panel.borderEnabled = checked;
            savePanel();
        });
        root.addView(border, rowParams());
        settingSlider(root, "Толщина границы", 0,
                DriverFavoritesPanelConfig.MAX_BORDER_WIDTH_PX,
                panel.borderWidthPx, " px", value -> panel.borderWidthPx = value);
        MaterialButton borderColor = button("Цвет границы ячеек");
        AppleColorPickerDialog.decorateButton(borderColor, "Цвет границы",
                panel.borderColor);
        borderColor.setOnClickListener(view -> AppleColorPickerDialog.show(this,
                "Цвет границы ячеек", panel.borderColor,
                AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {
                        if (value == null) return;
                        panel.borderColor = value;
                        AppleColorPickerDialog.decorateButton(borderColor,
                                "Цвет границы", value);
                    }

                    @Override public void onSelected(@Nullable String value) {
                        if (value != null) panel.borderColor = value;
                        savePanel();
                    }
                }));
        root.addView(borderColor, rowParams());
        root.addView(text("Фон этой панели всегда совпадает с фоном панели водителя. "
                + "Если элементов больше видимой сетки, список прокручивается.",
                13, 0xFF8E8E93), rowParams());

        root.addView(text("Ячейки · без ограничения количества", 19, Color.WHITE), rowParams());
        count = text("", 14, 0xFFC7C7CC);
        root.addView(count, rowParams());
        MaterialButton add = button("＋ Добавить элемент");
        add.setOnClickListener(view -> picker.showNew());
        root.addView(add, rowParams());
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows, rowParams());
        return scroll;
    }

    private void refresh() {
        if (rows == null) return;
        List<LauncherShortcutStore.Shortcut> values = store.all();
        count.setText(values.size() + " элементов · без лимита");
        rows.removeAllViews();
        if (values.isEmpty()) {
            rows.addView(text("Элементов пока нет.", 16, 0xFF8E8E93), rowParams());
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            rows.addView(row(values.get(index), index, values.size()), rowParams());
        }
    }

    @NonNull
    private View row(@NonNull LauncherShortcutStore.Shortcut shortcut,
                     int index, int total) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(0xFF1C1C1E);
        card.setStrokeColor(0xFF38383A);
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(16));
        card.setContentPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        Drawable drawable = LauncherIconResolver.resolve(this, shortcut);
        if (drawable != null) icon.setImageDrawable(drawable);
        head.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(shortcut.title, 17, Color.WHITE));
        labels.addView(text(type(shortcut), 12, 0xFF8E8E93));
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, wrap(), 1f);
        labelsParams.leftMargin = dp(12);
        head.addView(labels, labelsParams);
        MaterialButton up = compact("↑");
        up.setEnabled(index > 0);
        up.setOnClickListener(view -> { store.move(shortcut.id, -1); changed(); });
        head.addView(up, new LinearLayout.LayoutParams(dp(52), dp(46)));
        MaterialButton down = compact("↓");
        down.setEnabled(index < total - 1);
        down.setOnClickListener(view -> { store.move(shortcut.id, 1); changed(); });
        head.addView(down, new LinearLayout.LayoutParams(dp(52), dp(46)));
        body.addView(head);

        TextView sizeLabel = text("Размер иконки: " + shortcut.iconSizePx + " px",
                13, 0xFFC7C7CC);
        body.addView(sizeLabel, rowParams());
        SeekBar size = new SeekBar(this);
        size.setMax(LauncherShortcutStore.MAX_ICON_SIZE_PX
                - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        size.setProgress(shortcut.iconSizePx - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int selected = shortcut.iconSizePx;
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) {
                selected = LauncherShortcutStore.MIN_ICON_SIZE_PX + progress;
                sizeLabel.setText("Размер иконки: " + selected + " px");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                shortcut.iconSizePx = selected;
                store.upsert(shortcut);
                applyPanel();
            }
        });
        body.addView(size, new LinearLayout.LayoutParams(match(), dp(42)));

        LinearLayout actions = new LinearLayout(this);
        MaterialButton tap = compact("Нажатие");
        tap.setOnClickListener(view -> picker.showPrimary(shortcut));
        actions.addView(tap, weighted());
        MaterialButton hold = compact(shortcut.hasLongAction ? "Удержание ✓" : "Удержание");
        hold.setOnClickListener(view -> picker.showLong(shortcut));
        actions.addView(hold, weightedMargin());
        MaterialButton appearance = compact("Вид");
        appearance.setOnClickListener(view -> editAppearance(shortcut));
        actions.addView(appearance, weightedMargin());
        MaterialButton remove = compact("Удалить");
        remove.setTextColor(0xFFFF453A);
        remove.setOnClickListener(view -> {
            store.remove(shortcut.id);
            changed();
        });
        actions.addView(remove, weightedMargin());
        body.addView(actions, rowParams());

        MaterialSwitch title = new MaterialSwitch(this);
        title.setText("Показывать подпись");
        title.setTextColor(Color.WHITE);
        title.setChecked(shortcut.showTitle);
        title.setOnCheckedChangeListener((button, checked) -> {
            shortcut.showTitle = checked;
            store.upsert(shortcut);
            applyPanel();
        });
        body.addView(title, rowParams());
        card.addView(body);
        return card;
    }

    private void editAppearance(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        String[] choices = {"Иконка", "Фон", "Цвет иконки", "Цвет подписи"};
        new AlertDialog.Builder(this).setTitle("Оформление · " + shortcut.title)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        List<LauncherIconResolver.Preset> presets = LauncherIconResolver.presets();
                        String[] labels = new String[presets.size()];
                        for (int i = 0; i < presets.size(); i++) labels[i] = presets.get(i).label;
                        new AlertDialog.Builder(this).setTitle("Иконка")
                                .setItems(labels, (d, selected) -> {
                                    shortcut.icon = presets.get(selected).key;
                                    shortcut.iconCustomized = true;
                                    store.upsert(shortcut);
                                    changed();
                                }).show();
                        return;
                    }
                    String current = which == 1 ? shortcut.backgroundColor
                            : which == 2 ? shortcut.iconColor : shortcut.textColor;
                    AppleColorPickerDialog.show(this, choices[which], current,
                            AppleColorPickerDialog.Options.standard(),
                            new AppleColorPickerDialog.Listener() {
                                private void set(@Nullable String color) {
                                    if (color == null) return;
                                    if (which == 1) shortcut.backgroundColor = color;
                                    else if (which == 2) shortcut.iconColor = color;
                                    else shortcut.textColor = color;
                                    store.upsert(shortcut);
                                }
                                @Override public void onPreview(@Nullable String color) {
                                    set(color);
                                }
                                @Override public void onSelected(@Nullable String color) {
                                    set(color);
                                    changed();
                                }
                            });
                }).setNegativeButton("Готово", null).show();
    }

    private void changed() {
        refresh();
        applyPanel();
    }

    private void choosePanel() {
        List<DriverFavoritesPanelConfig> panels = panelStore.load();
        String[] labels = new String[panels.size()];
        for (int index = 0; index < panels.size(); index++) {
            DriverFavoritesPanelConfig value = panels.get(index);
            labels[index] = value.title + " · " + value.columns + " × "
                    + value.visibleRows + " [" + value.id + "]";
        }
        new AlertDialog.Builder(this).setTitle("Избранное водителя")
                .setItems(labels, (dialog, which) -> openPanel(panels.get(which).id))
                .setNegativeButton("Отмена", null).show();
    }

    private void createPanel() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Например: Климат и поездка");
        new AlertDialog.Builder(this).setTitle("Новая панель избранного")
                .setView(name)
                .setPositiveButton("Создать", (dialog, which) -> {
                    DriverFavoritesPanelConfig created =
                            panelStore.create(name.getText().toString());
                    openPanel(created.id);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void renamePanel() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(panel.title);
        name.setSelection(name.length());
        new AlertDialog.Builder(this).setTitle("Название панели")
                .setView(name)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    panel.title = name.getText().toString();
                    savePanel();
                    recreate();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void removePanel() {
        new AlertDialog.Builder(this).setTitle("Удалить панель?")
                .setMessage(panel.title + "\nЯчейки сохранятся в резервном документе.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    panelStore.remove(panel.id);
                    openPanel(panelStore.load().get(0).id);
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void openPanel(@NonNull String id) {
        preferences.driverFavoritesSelectedPanelId.set(id);
        recreate();
    }

    private void savePanel() {
        panelStore.upsert(panel);
        applyPanel();
    }

    private interface IntSetter {
        void set(int value);
    }

    private void settingSlider(@NonNull LinearLayout host, @NonNull String title,
                               int minimum, int maximum, int current,
                               @NonNull String suffix, @NonNull IntSetter setter) {
        TextView label = text(title + ": " + current + suffix, 14, 0xFFC7C7CC);
        host.addView(label, rowParams());
        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.max(0, maximum - minimum));
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, current - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int selected = current;

            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) {
                selected = minimum + progress;
                label.setText(title + ": " + selected + suffix);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                setter.set(selected);
                savePanel();
            }
        });
        host.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
    }

    private void applyPanel() {
        if (preferences.driverPanelEnabled.get()) DriverPanelService.apply(this);
    }

    @NonNull
    private String type(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        switch (shortcut.kind) {
            case APP: return "Приложение";
            case CAR: return "Автомобиль";
            case RULE: return "Умный дом";
            case INTENT: return "Android Intent";
            case INFO: return "Информация · без нажатия";
            case DIVIDER: return "Разделитель";
            case BUILTIN:
            default:
                if (LauncherShortcutStore.isDriverFavoritesTarget(shortcut.target)) {
                    DriverFavoritesPanelConfig target = panelStore.resolve(
                            LauncherShortcutStore.driverFavoritesPanelId(shortcut.target));
                    return "Избранное водителя · " + target.title;
                }
                return LauncherShortcutStore.Builtin.fromKey(shortcut.target).label;
        }
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
    private MaterialButton button(@NonNull String value) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setCornerRadius(dp(14));
        return button;
    }

    @NonNull
    private MaterialButton compact(@NonNull String value) {
        MaterialButton button = button(value);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(8);
        return params;
    }

    @NonNull
    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(46), 1f);
    }

    @NonNull
    private LinearLayout.LayoutParams weightedMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(7);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
