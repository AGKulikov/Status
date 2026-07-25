/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.DriverFavoriteBlocksStore;
import dezz.status.widget.launcher.ShortcutActionPicker;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Editor for the independent mixed-content Favorites drawer on the driver rail. */
public final class DriverFavoritesSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private LauncherShortcutStore store;
    private DriverFavoriteBlocksStore blockStore;
    private ShortcutActionPicker picker;
    private LinearLayout rows;
    private LinearLayout blockTabs;
    private TextView count;
    @NonNull private String selectedBlockId = DriverFavoriteBlocksStore.DEFAULT_BLOCK_ID;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = LauncherShortcutStore.forDriverFavorites(preferences);
        blockStore = new DriverFavoriteBlocksStore(preferences);
        picker = new ShortcutActionPicker(this, preferences, store, this::changed);
        setTitle("Избранное панели водителя");
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

        root.addView(text("Избранное панели водителя", 25, Color.WHITE));
        root.addView(text("Создайте до 10 компактных блоков. Каждый блок назначается своей "
                + "кнопке панели водителя, раскрывается от неё без заголовка и использует цвет "
                + "панели. Внутри можно смешивать приложения, быстрые действия автомобиля "
                + "(например сервисный режим дворников), кнопки климата и живые устройства "
                + "умного дома.",
                14, 0xFF8E8E93), rowParams());
        blockTabs = new LinearLayout(this);
        blockTabs.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.addView(blockTabs, new HorizontalScrollView.LayoutParams(
                wrap(), dp(50)));
        root.addView(tabsScroll, rowParams());
        LinearLayout blockActions = new LinearLayout(this);
        MaterialButton addBlock = compact("＋ Новый блок");
        addBlock.setOnClickListener(view -> createBlock());
        blockActions.addView(addBlock, weighted());
        MaterialButton blockAppearance = compact("Сетка и границы");
        blockAppearance.setOnClickListener(view -> editBlock());
        blockActions.addView(blockAppearance, weightedMargin());
        MaterialButton deleteBlock = compact("Удалить блок");
        deleteBlock.setTextColor(0xFFFF453A);
        deleteBlock.setOnClickListener(view -> deleteBlock());
        blockActions.addView(deleteBlock, weightedMargin());
        root.addView(blockActions, rowParams());
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
        blockStore.load();
        renderBlockTabs();
        DriverFavoriteBlocksStore.Block block = blockStore.find(selectedBlockId);
        selectedBlockId = block.id;
        List<LauncherShortcutStore.Shortcut> values =
                blockStore.items(block.id, store);
        count.setText(block.title + " · " + values.size() + " элементов · "
                + block.columns + " столбцов");
        rows.removeAllViews();
        if (values.isEmpty()) {
            rows.addView(text("Элементов пока нет.", 16, 0xFF8E8E93), rowParams());
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            rows.addView(row(values.get(index), index, values.size()), rowParams());
        }
    }

    private void renderBlockTabs() {
        if (blockTabs == null) return;
        blockTabs.removeAllViews();
        List<DriverFavoriteBlocksStore.Block> blocks = blockStore.blocks();
        boolean exists = false;
        for (DriverFavoriteBlocksStore.Block block : blocks) {
            if (block.id.equals(selectedBlockId)) exists = true;
        }
        if (!exists && !blocks.isEmpty()) selectedBlockId = blocks.get(0).id;
        for (DriverFavoriteBlocksStore.Block block : blocks) {
            MaterialButton tab = compact(block.title);
            tab.setAlpha(block.id.equals(selectedBlockId) ? 1f : .58f);
            tab.setOnClickListener(view -> {
                selectedBlockId = block.id;
                refresh();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    wrap(), dp(46));
            params.rightMargin = dp(8);
            blockTabs.addView(tab, params);
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
        MaterialButton place = compact("Позиция");
        place.setOnClickListener(view -> editPlacement(shortcut));
        head.addView(place, new LinearLayout.LayoutParams(dp(110), dp(46)));
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
        assignNewItemsToSelectedBlock();
        refresh();
        applyPanel();
    }

    private void assignNewItemsToSelectedBlock() {
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            if (!value.collectionId.isEmpty()) continue;
            value.collectionId = selectedBlockId;
            value.gridColumn = 0;
            value.gridRow = 0;
            if ("#B5222733".equalsIgnoreCase(value.backgroundColor)) {
                value.backgroundColor = "#00000000";
            }
            value.showTitle = false;
            store.upsert(value);
        }
        blockStore.items(selectedBlockId, store);
    }

    private void createBlock() {
        DriverFavoriteBlocksStore.Block created = blockStore.create();
        if (created == null) {
            Toast.makeText(this, "Можно создать не более 10 блоков",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        selectedBlockId = created.id;
        editBlock();
    }

    private void deleteBlock() {
        if (DriverFavoriteBlocksStore.DEFAULT_BLOCK_ID.equals(selectedBlockId)) {
            Toast.makeText(this, "Основной блок удалить нельзя", Toast.LENGTH_SHORT).show();
            return;
        }
        DriverFavoriteBlocksStore.Block selected = blockStore.find(selectedBlockId);
        new AlertDialog.Builder(this)
                .setTitle("Удалить «" + selected.title + "»?")
                .setMessage("Будут удалены только элементы этого блока. Остальные блоки "
                        + "и кнопки панели сохранятся.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    blockStore.remove(selected.id, store);
                    selectedBlockId = DriverFavoriteBlocksStore.DEFAULT_BLOCK_ID;
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editBlock() {
        DriverFavoriteBlocksStore.Block block = blockStore.find(selectedBlockId);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(12));
        EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT);
        title.setText(block.title);
        title.setHint("Название блока");
        form.addView(title, rowParams());
        TextView columnsLabel = text("", 14, 0xFFC7C7CC);
        SeekBar columns = seek(block.columns - 1,
                DriverFavoriteBlocksStore.MAX_COLUMNS - 1,
                value -> columnsLabel.setText("Столбцов: " + (value + 1)));
        form.addView(columnsLabel, rowParams());
        form.addView(columns, new LinearLayout.LayoutParams(match(), dp(44)));
        TextView cellLabel = text("", 14, 0xFFC7C7CC);
        SeekBar cell = seek(block.cellSizePx - 64, 116,
                value -> cellLabel.setText("Размер ячейки: " + (value + 64) + " px"));
        form.addView(cellLabel, rowParams());
        form.addView(cell, new LinearLayout.LayoutParams(match(), dp(44)));
        TextView gapLabel = text("", 14, 0xFFC7C7CC);
        SeekBar gap = seek(block.gapPx, 40,
                value -> gapLabel.setText("Интервал: " + value + " px"));
        form.addView(gapLabel, rowParams());
        form.addView(gap, new LinearLayout.LayoutParams(match(), dp(44)));

        form.addView(text("ВЕРТИКАЛЬНЫЕ ГРАНИЦЫ", 13, 0xFF8E8E93), rowParams());
        List<MaterialSwitch> vertical = new java.util.ArrayList<>();
        for (int boundary = 0; boundary < DriverFavoriteBlocksStore.MAX_COLUMNS - 1;
             boundary++) {
            MaterialSwitch divider = new MaterialSwitch(this);
            divider.setText("После столбца " + (boundary + 1));
            divider.setChecked(block.hasVerticalDividerAfter(boundary));
            final int selectedBoundary = boundary;
            divider.setTag(selectedBoundary);
            vertical.add(divider);
            form.addView(divider, new LinearLayout.LayoutParams(match(), dp(46)));
        }
        int rowsUsed = blockStore.usedRows(block, blockStore.items(block.id, store));
        form.addView(text("ГОРИЗОНТАЛЬНЫЕ ГРАНИЦЫ", 13, 0xFF8E8E93), rowParams());
        List<MaterialSwitch> horizontal = new java.util.ArrayList<>();
        for (int boundary = 0; boundary < Math.max(1, rowsUsed - 1); boundary++) {
            MaterialSwitch divider = new MaterialSwitch(this);
            divider.setText("После строки " + (boundary + 1));
            divider.setChecked(block.hasHorizontalDividerAfter(boundary));
            divider.setTag(boundary);
            horizontal.add(divider);
            form.addView(divider, new LinearLayout.LayoutParams(match(), dp(46)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this)
                .setTitle("Сетка блока")
                .setView(scroll)
                .setPositiveButton("Применить", (dialog, which) -> {
                    block.title = title.getText().toString().trim();
                    block.columns = columns.getProgress() + 1;
                    block.cellSizePx = cell.getProgress() + 64;
                    block.gapPx = gap.getProgress();
                    for (MaterialSwitch divider : vertical) {
                        int boundary = (Integer) divider.getTag();
                        block.setVerticalDividerAfter(boundary, divider.isChecked());
                    }
                    for (MaterialSwitch divider : horizontal) {
                        int boundary = (Integer) divider.getTag();
                        block.setHorizontalDividerAfter(boundary, divider.isChecked());
                    }
                    blockStore.upsert(block);
                    blockStore.items(block.id, store);
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editPlacement(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        DriverFavoriteBlocksStore.Block block = blockStore.find(selectedBlockId);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(8));
        TextView columnLabel = text("", 14, 0xFFC7C7CC);
        SeekBar column = seek(Math.max(0, shortcut.gridColumn),
                Math.max(0, block.columns - 1),
                value -> columnLabel.setText("Столбец: " + (value + 1)));
        form.addView(columnLabel, rowParams());
        form.addView(column, new LinearLayout.LayoutParams(match(), dp(44)));
        TextView rowLabel = text("", 14, 0xFFC7C7CC);
        SeekBar row = seek(Math.max(0, shortcut.gridRow),
                DriverFavoriteBlocksStore.MAX_ROWS - 1,
                value -> rowLabel.setText("Строка: " + (value + 1)));
        form.addView(rowLabel, rowParams());
        form.addView(row, new LinearLayout.LayoutParams(match(), dp(44)));
        new AlertDialog.Builder(this)
                .setTitle("Позиция · " + shortcut.title)
                .setView(form)
                .setPositiveButton("Переместить", (dialog, which) -> {
                    boolean moved = blockStore.setPlacement(block, store, shortcut.id,
                            column.getProgress(), row.getProgress(),
                            shortcut.columnSpan, shortcut.rowSpan);
                    if (!moved) {
                        Toast.makeText(this, "Эта ячейка занята",
                                Toast.LENGTH_SHORT).show();
                    }
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private SeekBar seek(int progress, int maximum,
                         @NonNull java.util.function.IntConsumer changed) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.max(0, maximum));
        seek.setProgress(Math.max(0, Math.min(maximum, progress)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                changed.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        changed.accept(seek.getProgress());
        return seek;
    }

    private void applyPanel() {
        if (preferences.driverPanelEnabled.get()) DriverPanelService.apply(this);
    }

    @NonNull
    private String type(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        switch (shortcut.kind) {
            case APP: return "Приложение";
            case CAR: return "Автомобиль";
            case ROUTE: return "Избранная точка навигации";
            case RULE: return "Умный дом";
            case INTENT: return "Android Intent";
            case BUILTIN:
            default:
                if (DriverFavoriteBlocksStore.isFavoritesTarget(shortcut.target)) {
                    return "Блок · " + blockStore.find(
                            DriverFavoriteBlocksStore.blockIdFromTarget(shortcut.target)).title;
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
