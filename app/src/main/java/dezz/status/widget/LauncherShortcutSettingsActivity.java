/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.LauncherActionsGridConfig;
import dezz.status.widget.launcher.LauncherActionsGridConfigStore;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.LauncherRuleIdPolicy;
import dezz.status.widget.launcher.LauncherRuleIconPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutPicker;
import dezz.status.widget.launcher.panels.PanelContentEditOverlay;
import dezz.status.widget.launcher.panels.PanelElementConfigStore;
import dezz.status.widget.launcher.panels.PanelGridLayout;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.settings.AppleColorPickerDialog;

/** Visual, code-free editor for arbitrary HOME icons. */
public final class LauncherShortcutSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_ADD_NEW = "dezz.status.widget.extra.ADD_HOME_SHORTCUT";

    private Preferences preferences;
    private LauncherShortcutStore store;
    private LauncherActionsGridConfigStore gridStore;
    private PanelElementConfigStore panelElementStore;
    @Nullable private LauncherActionsGridConfig gridConfig;
    @Nullable private PanelElementConfigStore.Panel panelElements;
    private LinearLayout itemsHost;
    private MaterialSwitch panelVisible;
    private MaterialSwitch tilesVisible;
    private MaterialSwitch addTileVisible;
    private PanelGridLayout previewGrid;
    private PanelContentEditOverlay previewOverlay;
    private FrameLayout previewHost;
    private GridSlider columnsSlider;
    private GridSlider rowsSlider;
    private GridSlider gapSlider;
    private GridSlider labelsScaleSlider;
    private GridSlider addScaleSlider;
    private boolean addHandled;
    private boolean editingLongAction;
    private boolean syncingControls;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new LauncherShortcutStore(preferences);
        gridStore = new LauncherActionsGridConfigStore(preferences);
        panelElementStore = new PanelElementConfigStore(preferences);
        panelElements = panelElementStore.load(LauncherLayoutStore.ACTIONS);
        migrateRuleIcons();
        store.load();
        gridConfig = gridStore.load(store.all());
        setTitle("Иконки HOME");
        View screen = buildScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, screen);
        refresh();
        if (savedInstanceState != null) addHandled = savedInstanceState.getBoolean("addHandled");
        if (!addHandled && getIntent().getBooleanExtra(EXTRA_ADD_NEW, false)) {
            addHandled = true;
            itemsHost.post(this::chooseKindForNew);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // LauncherActivity edits and saves the same grid while this screen is stopped. Always
        // reload before touching controls: saving the object kept before HOME was opened would
        // silently restore stale positions and spans.
        refresh();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean("addHandled", addHandled);
        super.onSaveInstanceState(outState);
    }

    @NonNull
    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), 0, dp(22), dp(30));
        settingsScroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));
        root.addView(settingsScroll, new LinearLayout.LayoutParams(0, match(), 1.04f));

        MaterialButton back = new MaterialButton(this);
        back.setText("←  Назад");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(230), dp(52));
        backLp.bottomMargin = dp(8);
        content.addView(back, backLp);

        TextView title = text(25, true);
        title.setText("Кнопки и умный дом");
        content.addView(title);
        TextView hint = text(15, false);
        hint.setText("Все настройки этой панели собраны здесь. Справа показана та же ячеистая "
                + "компоновка, которую использует HOME: перетаскивайте плитки и тяните за угол, "
                + "чтобы изменить их размер.");
        hint.setAlpha(.75f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(match(), wrap());
        hintLp.bottomMargin = dp(12);
        content.addView(hint, hintLp);

        panelVisible = new MaterialSwitch(this);
        panelVisible.setText("Показывать панель кнопок и умного дома на HOME");
        panelVisible.setTextSize(16);
        panelVisible.setMinHeight(dp(52));
        panelVisible.setChecked(preferences.launcherActionsVisible.get());
        panelVisible.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingControls) preferences.launcherActionsVisible.set(checked);
        });
        content.addView(panelVisible, new LinearLayout.LayoutParams(match(), wrap()));

        tilesVisible = new MaterialSwitch(this);
        tilesVisible.setText("Показывать кнопки и устройства в панели");
        tilesVisible.setTextSize(16);
        tilesVisible.setMinHeight(dp(50));
        tilesVisible.setChecked(showActionTiles());
        tilesVisible.setOnCheckedChangeListener((button, checked) -> {
            if (syncingControls) return;
            PanelElementConfigStore.Panel elements = requirePanelElements();
            elements.setEnabled(PanelElementConfigStore.ACTION_TILES, checked);
            panelElementStore.save(elements);
            renderPreview();
        });
        content.addView(tilesVisible, new LinearLayout.LayoutParams(match(), wrap()));

        addTileVisible = new MaterialSwitch(this);
        addTileVisible.setText("Показывать плитку «Добавить»");
        addTileVisible.setTextSize(16);
        addTileVisible.setMinHeight(dp(50));
        addTileVisible.setChecked(showActionAdd());
        addTileVisible.setOnCheckedChangeListener((button, checked) -> {
            if (syncingControls) return;
            PanelElementConfigStore.Panel elements = requirePanelElements();
            elements.setEnabled(PanelElementConfigStore.ACTION_ADD, checked);
            panelElementStore.save(elements);
            renderPreview();
        });
        content.addView(addTileVisible, new LinearLayout.LayoutParams(match(), wrap()));

        labelsScaleSlider = addGridSlider(content, "Подписи",
                PanelElementConfigStore.MIN_SCALE, PanelElementConfigStore.MAX_SCALE,
                requirePanelElements().scale(PanelElementConfigStore.ACTION_TILES), "%",
                selected -> changeActionElementScale(
                        PanelElementConfigStore.ACTION_TILES, selected));
        addScaleSlider = addGridSlider(content, "Плитка «+»",
                PanelElementConfigStore.MIN_SCALE, PanelElementConfigStore.MAX_SCALE,
                requirePanelElements().scale(PanelElementConfigStore.ACTION_ADD), "%",
                selected -> changeActionElementScale(
                        PanelElementConfigStore.ACTION_ADD, selected));

        addPageButton(content, "Размер и положение всей панели на HOME…", view ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));
        addPageButton(content, "Расположение плиток внутри панели на HOME…", view ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_ACTIONS_CONTENT, true)));

        addSectionTitle(content, "Сетка");
        TextView gridHint = text(14, false);
        gridHint.setText("Столбцы и строки задают точность размещения. Интервал меняет только "
                + "расстояние между плитками. Если выбранный размер не вмещает все элементы, "
                + "останется последняя корректная сетка.");
        gridHint.setAlpha(.72f);
        content.addView(gridHint);
        LauncherActionsGridConfig initial = requireGridConfig();
        columnsSlider = addGridSlider(content, "Столбцы",
                LauncherActionsGridConfig.MIN_COLUMNS,
                LauncherActionsGridConfig.MAX_COLUMNS, initial.columns, "",
                this::changeGridColumns);
        rowsSlider = addGridSlider(content, "Строки",
                LauncherActionsGridConfig.MIN_ROWS,
                LauncherActionsGridConfig.MAX_ROWS, initial.rows, "",
                this::changeGridRows);
        gapSlider = addGridSlider(content, "Интервал", 0,
                LauncherActionsGridConfig.MAX_GAP_PX, initial.gapPx, " px",
                this::changeGridGap);

        addSectionTitle(content, "Плитки");
        MaterialButton add = new MaterialButton(this);
        add.setText("+  Добавить иконку");
        add.setAllCaps(false);
        add.setOnClickListener(v -> chooseKindForNew());
        content.addView(add, new LinearLayout.LayoutParams(match(), dp(56)));

        itemsHost = new LinearLayout(this);
        itemsHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(match(), wrap());
        hostLp.topMargin = dp(12);
        content.addView(itemsHost, hostLp);

        ScrollView previewScroll = new ScrollView(this);
        previewScroll.setFillViewport(true);
        LinearLayout previewColumn = new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setPadding(dp(20), dp(2), dp(8), dp(24));
        previewScroll.addView(previewColumn, new ScrollView.LayoutParams(match(), wrap()));
        root.addView(previewScroll, new LinearLayout.LayoutParams(0, match(), .96f));

        TextView previewTitle = text(23, true);
        previewTitle.setText("Предпросмотр сетки");
        previewTitle.setTextColor(Color.rgb(105, 165, 255));
        previewColumn.addView(previewTitle);
        TextView previewHint = text(14, false);
        previewHint.setText("Это редактируемая схема реальной панели. Нажмите плитку для её "
                + "настройки. Плитку «Добавить» тоже можно перемещать и растягивать.");
        previewHint.setAlpha(.72f);
        LinearLayout.LayoutParams previewHintLp =
                new LinearLayout.LayoutParams(match(), wrap());
        previewHintLp.bottomMargin = dp(10);
        previewColumn.addView(previewHint, previewHintLp);

        previewHost = new FrameLayout(this);
        previewHost.setPadding(dp(7), dp(7), dp(7), dp(7));
        previewGrid = new PanelGridLayout(this);
        previewHost.addView(previewGrid, new FrameLayout.LayoutParams(match(), match()));
        previewOverlay = new PanelContentEditOverlay(this);
        previewOverlay.setModel(previewModel(), previewListener());
        previewOverlay.setEditing(true);
        previewHost.addView(previewOverlay, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost,
                new LinearLayout.LayoutParams(match(), previewHeight(initial.rows)));
        return root;
    }

    private void refresh() {
        store.load();
        gridConfig = gridStore.load(store.all());
        panelElements = panelElementStore.load(LauncherLayoutStore.ACTIONS);
        syncGlobalControls();
        renderPreview();
        itemsHost.removeAllViews();
        List<LauncherShortcutStore.Shortcut> values = store.all();
        if (values.isEmpty()) {
            TextView empty = text(17, false);
            empty.setText("Иконок пока нет. Нажмите «+», чтобы добавить первую.");
            itemsHost.addView(empty);
            return;
        }
        for (LauncherShortcutStore.Shortcut value : values) itemsHost.addView(buildRow(value));
    }

    @NonNull
    private View buildRow(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setOnClickListener(v -> showItemMenu(shortcut));

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(10), dp(10), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut));
        row.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(18, true);
        name.setText(shortcut.title);
        TextView type = text(13, false);
        type.setAlpha(.7f);
        type.setText(typeLabel(shortcut) + "\n" + placementLabel(shortcut.id));
        type.setMaxLines(3);
        labels.addView(name);
        labels.addView(type);
        row.addView(labels, new LinearLayout.LayoutParams(0, wrap(), 1f));

        MaterialSwitch visible = new MaterialSwitch(this);
        visible.setText("На HOME");
        visible.setContentDescription("Показывать «" + shortcut.title + "»");
        visible.setChecked(shortcut.enabled);
        visible.setOnCheckedChangeListener((button, checked) ->
                setShortcutVisible(shortcut.id, checked));
        MaterialButton edit = compactButton("✎");
        edit.setOnClickListener(v -> showItemMenu(shortcut));
        row.addView(visible, new LinearLayout.LayoutParams(dp(124), dp(52)));
        row.addView(edit);
        block.addView(row, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout sizeHeading = new LinearLayout(this);
        sizeHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeTitle = text(14, false);
        sizeTitle.setText("Размер иконки");
        TextView sizeValue = text(14, true);
        sizeValue.setGravity(Gravity.END);
        sizeValue.setText(shortcut.iconSizePx + " px");
        sizeHeading.addView(sizeTitle, new LinearLayout.LayoutParams(0, wrap(), 1f));
        sizeHeading.addView(sizeValue, new LinearLayout.LayoutParams(dp(90), wrap()));
        block.addView(sizeHeading, new LinearLayout.LayoutParams(match(), wrap()));
        SeekBar iconSize = new SeekBar(this);
        iconSize.setMax(LauncherShortcutStore.MAX_ICON_SIZE_PX
                - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        iconSize.setProgress(Math.max(0,
                Math.min(LauncherShortcutStore.MAX_ICON_SIZE_PX
                                - LauncherShortcutStore.MIN_ICON_SIZE_PX,
                        shortcut.iconSizePx - LauncherShortcutStore.MIN_ICON_SIZE_PX)));
        iconSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int selected = shortcut.iconSizePx;
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                selected = LauncherShortcutStore.MIN_ICON_SIZE_PX + progress;
                sizeValue.setText(selected + " px");
                if (fromUser) {
                    ViewGroup.LayoutParams params = icon.getLayoutParams();
                    params.width = Math.max(dp(36), Math.min(dp(72), selected));
                    params.height = params.width;
                    icon.setLayoutParams(params);
                    updatePreviewIconSize(shortcut.id, selected);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                setShortcutIconSize(shortcut.id, selected);
            }
        });
        block.addView(iconSize, new LinearLayout.LayoutParams(match(), dp(42)));
        card.addView(block, new MaterialCardView.LayoutParams(match(), wrap()));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(match(), wrap());
        cardLp.bottomMargin = dp(9);
        card.setLayoutParams(cardLp);
        return card;
    }

    @NonNull
    private LauncherActionsGridConfig requireGridConfig() {
        LauncherActionsGridConfig current = gridConfig;
        if (current != null) return current;
        store.load();
        current = gridStore.load(store.all());
        gridConfig = current;
        return current;
    }

    @NonNull
    private PanelElementConfigStore.Panel requirePanelElements() {
        PanelElementConfigStore.Panel current = panelElements;
        if (current != null) return current;
        current = panelElementStore.load(LauncherLayoutStore.ACTIONS);
        panelElements = current;
        return current;
    }

    private boolean showActionTiles() {
        return requirePanelElements().isEnabled(PanelElementConfigStore.ACTION_TILES);
    }

    private boolean showActionAdd() {
        return requirePanelElements().isEnabled(PanelElementConfigStore.ACTION_ADD);
    }

    private void syncGlobalControls() {
        syncingControls = true;
        try {
            if (panelVisible != null) {
                panelVisible.setChecked(preferences.launcherActionsVisible.get());
            }
            if (tilesVisible != null) tilesVisible.setChecked(showActionTiles());
            if (addTileVisible != null) addTileVisible.setChecked(showActionAdd());
            if (labelsScaleSlider != null) {
                labelsScaleSlider.setValue(requirePanelElements().scale(
                        PanelElementConfigStore.ACTION_TILES));
            }
            if (addScaleSlider != null) {
                addScaleSlider.setValue(requirePanelElements().scale(
                        PanelElementConfigStore.ACTION_ADD));
            }
            LauncherActionsGridConfig current = requireGridConfig();
            if (columnsSlider != null) columnsSlider.setValue(current.columns);
            if (rowsSlider != null) rowsSlider.setValue(current.rows);
            if (gapSlider != null) gapSlider.setValue(current.gapPx);
        } finally {
            syncingControls = false;
        }
    }

    private int changeGridColumns(int selected) {
        LauncherActionsGridConfig current = requireGridConfig();
        if (!current.setGridSize(selected, current.rows)) return current.columns;
        persistGridAndRefresh(true);
        return current.columns;
    }

    private int changeGridRows(int selected) {
        LauncherActionsGridConfig current = requireGridConfig();
        if (!current.setGridSize(current.columns, selected)) return current.rows;
        persistGridAndRefresh(true);
        return current.rows;
    }

    private int changeGridGap(int selected) {
        LauncherActionsGridConfig current = requireGridConfig();
        current.gapPx = Math.max(0,
                Math.min(LauncherActionsGridConfig.MAX_GAP_PX, selected));
        persistGridAndRefresh(false);
        return current.gapPx;
    }

    private int changeActionElementScale(@NonNull String id, int selected) {
        PanelElementConfigStore.Panel elements = requirePanelElements();
        elements.setScale(id, selected);
        panelElementStore.save(elements);
        renderPreview();
        return elements.scale(id);
    }

    private void persistGridAndRefresh(boolean mirrorAllSpans) {
        LauncherActionsGridConfig current = requireGridConfig();
        gridStore.save(current);
        if (mirrorAllSpans) mirrorAllGridSpansToLegacy();
        renderPreview();
        if (itemsHost != null) renderRows();
    }

    @NonNull
    private PanelContentEditOverlay.Model previewModel() {
        return new PanelContentEditOverlay.Model() {
            @Override public int columns() {
                return requireGridConfig().columns;
            }

            @Override public int rows() {
                return requireGridConfig().rows;
            }

            @NonNull
            @Override public List<PanelContentEditOverlay.Item> items() {
                List<PanelContentEditOverlay.Item> result = new ArrayList<>();
                LauncherActionsGridConfig current = requireGridConfig();
                if (showActionTiles()) {
                    for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
                        if (!shortcut.enabled) continue;
                        LauncherActionsGridConfig.Placement placement =
                                current.placement(shortcut.id);
                        if (placement == null) continue;
                        result.add(new PanelContentEditOverlay.Item(shortcut.id,
                                shortcut.title + " · " + shortcut.iconSizePx + " px",
                                placement.column, placement.row,
                                placement.columnSpan, placement.rowSpan));
                    }
                }
                LauncherActionsGridConfig.Placement add =
                        current.placement(LauncherActionsGridConfig.ADD_TILE_ID);
                if (showActionAdd() && add != null) {
                    result.add(new PanelContentEditOverlay.Item(
                            LauncherActionsGridConfig.ADD_TILE_ID, "Добавить",
                            add.column, add.row, add.columnSpan, add.rowSpan));
                }
                return result;
            }

            @Override public boolean setPlacement(@NonNull String id, int column, int row,
                                                  int columnSpan, int rowSpan) {
                return requireGridConfig().setPlacement(id, column, row, columnSpan, rowSpan);
            }
        };
    }

    @NonNull
    private PanelContentEditOverlay.Listener previewListener() {
        return new PanelContentEditOverlay.Listener() {
            @Override public void onPlacementChanged(@NonNull String id, boolean finished) {
                applyPreviewPlacements();
                if (!finished) return;
                gridStore.save(requireGridConfig());
                mirrorGridSpanToLegacy(id);
                renderRows();
            }

            @Override public void onItemClicked(@NonNull String id) {
                if (LauncherActionsGridConfig.ADD_TILE_ID.equals(id)) {
                    chooseKindForNew();
                    return;
                }
                LauncherShortcutStore.Shortcut shortcut = findShortcut(id);
                if (shortcut != null) showItemMenu(shortcut);
            }
        };
    }

    private void renderPreview() {
        if (previewGrid == null || previewHost == null) return;
        LauncherActionsGridConfig current = requireGridConfig();
        previewGrid.removeAllViews();
        previewGrid.setGridSize(current.columns, current.rows);
        previewGrid.setCellGapPx(current.gapPx);
        if (showActionTiles()) {
            for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
                if (!shortcut.enabled) continue;
                LauncherActionsGridConfig.Placement placement = current.placement(shortcut.id);
                if (placement == null) continue;
                View tile = buildPreviewTile(shortcut, false);
                tile.setTag(shortcut.id);
                previewGrid.addView(tile, new PanelGridLayout.LayoutParams(
                        placement.column, placement.row,
                        placement.columnSpan, placement.rowSpan));
            }
        }
        LauncherActionsGridConfig.Placement addPlacement =
                current.placement(LauncherActionsGridConfig.ADD_TILE_ID);
        if (showActionAdd() && addPlacement != null) {
            LauncherShortcutStore.Shortcut add = new LauncherShortcutStore.Shortcut();
            add.id = LauncherActionsGridConfig.ADD_TILE_ID;
            add.title = "Добавить";
            add.icon = "apps";
            add.backgroundColor = "#553A465B";
            View tile = buildPreviewTile(add, true);
            tile.setTag(LauncherActionsGridConfig.ADD_TILE_ID);
            previewGrid.addView(tile, new PanelGridLayout.LayoutParams(
                    addPlacement.column, addPlacement.row,
                    addPlacement.columnSpan, addPlacement.rowSpan));
        }
        ViewGroup.LayoutParams raw = previewHost.getLayoutParams();
        if (raw != null) {
            raw.height = previewHeight(current.rows);
            previewHost.setLayoutParams(raw);
        }
        if (previewOverlay != null) {
            previewOverlay.setEditing(true);
            previewOverlay.invalidate();
        }
    }

    @NonNull
    private View buildPreviewTile(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                  boolean addTile) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        try {
            card.setCardBackgroundColor(Color.parseColor(shortcut.backgroundColor));
        } catch (IllegalArgumentException ignored) {
            card.setCardBackgroundColor(Color.argb(180, 34, 39, 51));
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(5), dp(5), dp(5), dp(5));
        ImageView icon = new ImageView(this);
        if (addTile) {
            icon.setImageResource(R.drawable.ic_add);
            icon.setColorFilter(Color.WHITE);
        } else {
            icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut));
        }
        int contentScale = requirePanelElements().scale(addTile
                ? PanelElementConfigStore.ACTION_ADD
                : PanelElementConfigStore.ACTION_TILES);
        int iconSize = addTile
                ? Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                shortcut.iconSizePx * contentScale / 100)
                : Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                Math.min(LauncherShortcutStore.MAX_ICON_SIZE_PX, shortcut.iconSizePx));
        content.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        if (shortcut.showTitle || addTile) {
            TextView title = text(12f * contentScale / 100f, true);
            title.setText(addTile ? "+  Добавить" : shortcut.title);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            try {
                title.setTextColor(Color.parseColor(shortcut.textColor));
            } catch (IllegalArgumentException ignored) {
                title.setTextColor(Color.WHITE);
            }
            content.addView(title, new LinearLayout.LayoutParams(match(), wrap()));
        }
        card.addView(content, new MaterialCardView.LayoutParams(match(), match()));
        return card;
    }

    private void applyPreviewPlacements() {
        if (previewGrid == null) return;
        LauncherActionsGridConfig current = requireGridConfig();
        previewGrid.setGridSize(current.columns, current.rows);
        previewGrid.setCellGapPx(current.gapPx);
        for (LauncherActionsGridConfig.Placement placement : current.placements()) {
            previewGrid.updatePlacement(placement.id, placement.column, placement.row,
                    placement.columnSpan, placement.rowSpan);
        }
        if (previewOverlay != null) previewOverlay.invalidate();
    }

    private void setShortcutVisible(@NonNull String id, boolean visible) {
        LauncherShortcutStore.Shortcut latest = findShortcut(id);
        if (latest == null || latest.enabled == visible) return;
        latest.enabled = visible;
        store.upsert(latest);
        refresh();
    }

    private void setShortcutIconSize(@NonNull String id, int requestedSize) {
        LauncherShortcutStore.Shortcut latest = findShortcut(id);
        if (latest == null) return;
        int size = Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                Math.min(LauncherShortcutStore.MAX_ICON_SIZE_PX, requestedSize));
        if (latest.iconSizePx == size) return;
        latest.iconSizePx = size;
        store.upsert(latest);
        refresh();
    }

    private void updatePreviewIconSize(@NonNull String id, int requestedSize) {
        if (previewGrid == null) return;
        View tile = previewGrid.findViewWithTag(id);
        if (!(tile instanceof ViewGroup) || ((ViewGroup) tile).getChildCount() == 0) return;
        View content = ((ViewGroup) tile).getChildAt(0);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) return;
        View icon = ((ViewGroup) content).getChildAt(0);
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        int size = Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                Math.min(LauncherShortcutStore.MAX_ICON_SIZE_PX, requestedSize));
        params.width = size;
        params.height = size;
        icon.setLayoutParams(params);
    }

    @Nullable
    private LauncherShortcutStore.Shortcut findShortcut(@NonNull String id) {
        store.load();
        for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
            if (shortcut.id.equals(id)) return shortcut;
        }
        return null;
    }

    private void mirrorGridSpanToLegacy(@NonNull String id) {
        if (LauncherActionsGridConfig.ADD_TILE_ID.equals(id)) return;
        LauncherActionsGridConfig.Placement placement = requireGridConfig().placement(id);
        if (placement == null) return;
        LauncherShortcutStore.Shortcut latest = findShortcut(id);
        if (latest == null || latest.columnSpan == placement.columnSpan
                && latest.rowSpan == placement.rowSpan) return;
        latest.columnSpan = placement.columnSpan;
        latest.rowSpan = placement.rowSpan;
        store.upsert(latest);
    }

    private void mirrorAllGridSpansToLegacy() {
        LauncherActionsGridConfig current = requireGridConfig();
        store.load();
        for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
            LauncherActionsGridConfig.Placement placement = current.placement(shortcut.id);
            if (placement == null || shortcut.columnSpan == placement.columnSpan
                    && shortcut.rowSpan == placement.rowSpan) continue;
            shortcut.columnSpan = placement.columnSpan;
            shortcut.rowSpan = placement.rowSpan;
            store.upsert(shortcut);
        }
    }

    private void renderRows() {
        if (itemsHost == null) return;
        itemsHost.removeAllViews();
        List<LauncherShortcutStore.Shortcut> values = store.all();
        if (values.isEmpty()) {
            TextView empty = text(17, false);
            empty.setText("Иконок пока нет. Нажмите «+», чтобы добавить первую.");
            itemsHost.addView(empty);
            return;
        }
        for (LauncherShortcutStore.Shortcut value : values) {
            itemsHost.addView(buildRow(value));
        }
    }

    @NonNull
    private String placementLabel(@NonNull String id) {
        LauncherActionsGridConfig.Placement placement = requireGridConfig().placement(id);
        if (placement == null) return "Положение ещё не назначено";
        return "Ячейка " + (placement.column + 1) + " × " + (placement.row + 1)
                + " · размер " + placement.columnSpan + " × " + placement.rowSpan;
    }

    private int previewHeight(int rows) {
        return Math.max(dp(300), Math.min(dp(1000), dp(76) * Math.max(1, rows)));
    }

    private void addPageButton(@NonNull LinearLayout parent, @NonNull String label,
                               @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(52));
        lp.topMargin = dp(7);
        parent.addView(button, lp);
    }

    private void addSectionTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = text(22, true);
        title.setText(value);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(6);
        parent.addView(title, lp);
    }

    @NonNull
    private GridSlider addGridSlider(@NonNull LinearLayout parent, @NonNull String title,
                                     int minimum, int maximum, int initial,
                                     @NonNull String suffix,
                                     @NonNull IntSelection selection) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(15, false);
        label.setText(title);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        TextView value = text(14, true);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        GridSlider result = new GridSlider(seek, value, minimum, maximum, suffix);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        value.setText(initial + suffix);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) {
                int selected = minimum + progress;
                value.setText(selected + suffix);
                if (!fromUser || syncingControls) return;
                int actual = selection.select(selected);
                if (actual != selected) {
                    result.setValue(actual);
                    value.setText(actual + suffix + " · нет места");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(label, new LinearLayout.LayoutParams(dp(105), dp(44)));
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row.addView(value, new LinearLayout.LayoutParams(dp(112), dp(44)));
        parent.addView(row, new LinearLayout.LayoutParams(match(), dp(44)));
        return result;
    }

    private void chooseKindForNew() {
        editingLongAction = false;
        String[] choices = {"Приложение", "Готовая функция", "Функция автомобиля",
                "Действие устройства / сценарий", "Android Intent"};
        new AlertDialog.Builder(this)
                .setTitle("Что добавить?")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) chooseApplication(null);
                    else if (which == 1) chooseBuiltin(null);
                    else if (which == 2) chooseCarControl(null);
                    else if (which == 3) chooseRule(null);
                    else editIntentTarget(null);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showItemMenu(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        String[] choices = {"Оформление и размер", "Действие по нажатию",
                "Действие по долгому нажатию", "Удалить"};
        new AlertDialog.Builder(this).setTitle(shortcut.title)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) { editingLongAction = false; editAppearance(shortcut.copy()); }
                    else if (which == 1) chooseKindForExisting(shortcut.copy());
                    else if (which == 2) chooseLongKind(shortcut.copy());
                    else confirmDelete(shortcut);
                }).show();
    }

    private void chooseKindForExisting(@NonNull LauncherShortcutStore.Shortcut value) {
        editingLongAction = false;
        String[] choices = {"Приложение", "Готовая функция", "Функция автомобиля",
                "Действие устройства / сценарий", "Android Intent"};
        new AlertDialog.Builder(this).setTitle("Новое действие")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) chooseApplication(value);
                    else if (which == 1) chooseBuiltin(value);
                    else if (which == 2) chooseCarControl(value);
                    else if (which == 3) chooseRule(value);
                    else editIntentTarget(value);
                }).show();
    }

    private void chooseLongKind(@NonNull LauncherShortcutStore.Shortcut value) {
        String[] choices = {"Без действия", "Приложение", "Готовая функция",
                "Функция автомобиля", "Действие устройства / сценарий", "Android Intent"};
        new AlertDialog.Builder(this).setTitle("Долгое нажатие")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        editingLongAction = false;
                        value.hasLongAction = false;
                        value.longTarget = "";
                        value.longPackageName = "";
                        store.upsert(value);
                        refresh();
                        return;
                    }
                    editingLongAction = true;
                    if (which == 1) chooseApplication(value);
                    else if (which == 2) chooseBuiltin(value);
                    else if (which == 3) chooseCarControl(value);
                    else if (which == 4) chooseRule(value);
                    else editIntentTarget(value);
                }).show();
    }

    private void chooseCarControl(@Nullable LauncherShortcutStore.Shortcut existing) {
        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle("Функции автомобиля")
                .setMessage("Проверяю функции, которые поддерживает эта магнитола…")
                .setNegativeButton(android.R.string.cancel, null).create();
        loading.show();
        CarIntegrations.get(this).requestControlCatalog(controls -> {
            if (isFinishing() || isDestroyed()) return;
            if (!loading.isShowing()) return;
            loading.dismiss();
            if (controls.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("Функции пока недоступны")
                        .setMessage("ECARX ещё не ответил или эта сборка запущена не на магнитоле. "
                                + "Включите зажигание и повторите через несколько секунд.")
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            String[] labels = new String[controls.size()];
            for (int index = 0; index < controls.size(); index++) {
                CarControlDescriptor control = controls.get(index);
                labels[index] = control.category + " · " + control.label
                        + (control.availability == CarControlDescriptor.Availability.UNKNOWN
                        ? "  (проверяется)" : "");
            }
            new AlertDialog.Builder(this).setTitle("Выберите функцию автомобиля")
                    .setItems(labels, (dialog, which) ->
                            chooseCarControlBehavior(existing, controls.get(which)))
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void chooseCarControlBehavior(@Nullable LauncherShortcutStore.Shortcut existing,
                                          @NonNull CarControlDescriptor control) {
        if (control.kind == CarControlDescriptor.Kind.RANGE) {
            chooseCarRange(existing, control);
            return;
        }
        if (control.kind == CarControlDescriptor.Kind.ACTION) {
            saveCarAction(existing, control, CarControlCommand.Operation.ACTIVATE, 1);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<CarControlCommand.Operation> operations = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        if (control.kind == CarControlDescriptor.Kind.TOGGLE) {
            labels.add("Переключать Вкл / Выкл");
            operations.add(CarControlCommand.Operation.TOGGLE);
            values.add(0d);
        } else {
            labels.add(control.kind == CarControlDescriptor.Kind.OPTIONS
                    ? "Переключать режимы по кругу" : "Переключать уровни по кругу");
            operations.add(CarControlCommand.Operation.CYCLE);
            values.add(0d);
        }
        for (CarControlDescriptor.Option option : control.options) {
            labels.add("Установить: " + option.label);
            operations.add(CarControlCommand.Operation.SET);
            values.add(option.value);
        }
        new AlertDialog.Builder(this).setTitle(control.label + " — нажатие")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        saveCarAction(existing, control, operations.get(which), values.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseCarRange(@Nullable LauncherShortcutStore.Shortcut existing,
                                @NonNull CarControlDescriptor control) {
        LinearLayout form = dialogForm();
        TextView current = formLabel("");
        form.addView(current);
        SeekBar seek = new SeekBar(this);
        int steps = Math.max(1, (int) Math.round(
                (control.maximum - control.minimum) / control.step));
        seek.setMax(steps);
        double initial = control.minimum + (control.maximum - control.minimum) / 2d;
        if (existing != null && existing.kind == LauncherShortcutStore.Kind.CAR
                && existing.target.equals(control.id)
                && existing.command == CarControlCommand.Operation.SET) {
            initial = existing.commandValue;
        }
        seek.setProgress(Math.max(0, Math.min(steps,
                (int) Math.round((initial - control.minimum) / control.step))));
        Runnable update = () -> {
            double value = control.minimum + seek.getProgress() * control.step;
            current.setText(control.label + ": "
                    + String.format(Locale.ROOT, "%.1f", value) + control.unit);
        };
        update.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) { update.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        form.addView(seek, new LinearLayout.LayoutParams(match(), dp(54)));
        new AlertDialog.Builder(this).setTitle("Целевая температура")
                .setView(form).setPositiveButton("Выбрать", (dialog, which) -> {
                    double value = control.minimum + seek.getProgress() * control.step;
                    saveCarAction(existing, control, CarControlCommand.Operation.SET, value);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void saveCarAction(@Nullable LauncherShortcutStore.Shortcut existing,
                               @NonNull CarControlDescriptor control,
                               @NonNull CarControlCommand.Operation operation, double value) {
        LauncherShortcutStore.Shortcut shortcut = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        if (editingLongAction) {
            shortcut.hasLongAction = true;
            shortcut.longKind = LauncherShortcutStore.Kind.CAR;
            shortcut.longTarget = control.id;
            shortcut.longPackageName = "";
            shortcut.longCommand = operation;
            shortcut.longCommandValue = value;
            editingLongAction = false;
            store.upsert(shortcut);
            refresh();
            Toast.makeText(this, "Долгое нажатие настроено", Toast.LENGTH_SHORT).show();
            return;
        }
        shortcut.kind = LauncherShortcutStore.Kind.CAR;
        shortcut.target = control.id;
        shortcut.packageName = "";
        shortcut.command = operation;
        shortcut.commandValue = value;
        shortcut.title = control.label;
        shortcut.icon = control.iconKey;
        shortcut.iconColor = "#99FFFFFF";
        shortcut.activeIconColor = control.suggestedActiveColor;
        shortcut.useVehicleStateColor = true;
        shortcut.showState = control.kind != CarControlDescriptor.Kind.ACTION;
        editAppearance(shortcut);
    }

    private void chooseRule(@Nullable LauncherShortcutStore.Shortcut existing) {
        String[] sources = {"Новое действие из полного каталога",
                "Ранее настроенное Intent-действие"};
        new AlertDialog.Builder(this).setTitle("Действие устройства / сценарий")
                .setItems(sources, (dialog, which) -> {
                    if (which == 0) {
                        new SmartHomeShortcutPicker(this,
                                selection -> saveCatalogAction(existing, selection))
                                .showConnectorPicker();
                    } else chooseExistingRule(existing);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseExistingRule(@Nullable LauncherShortcutStore.Shortcut existing) {
        final List<IntentActionRule> rules;
        try {
            rules = new IntentActionRuleStore(preferences).loadStrict();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "Настройки Intent-сценариев повреждены", Toast.LENGTH_LONG).show();
            return;
        }
        List<IntentActionRule> enabled = new ArrayList<>();
        for (IntentActionRule rule : rules) if (rule.enabled) enabled.add(rule);
        if (enabled.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет готовых действий")
                    .setMessage("Сначала создайте в визуальном редакторе Intent-действие для HA, MQTT или Sprut.hub, а затем выберите его здесь.")
                    .setPositiveButton("Открыть редактор", (dialog, which) ->
                            startActivity(new Intent(this, IntentScenarioSettingsActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        String[] labels = new String[enabled.size()];
        for (int i = 0; i < enabled.size(); i++) {
            IntentActionRule rule = enabled.get(i);
            String target = rule.accessoryLabel;
            if (!rule.serviceLabel.isEmpty()) target += " · " + rule.serviceLabel;
            if (target.trim().isEmpty()) target = rule.id;
            labels[i] = target;
        }
        new AlertDialog.Builder(this).setTitle("Выберите действие")
                .setItems(labels, (dialog, which) -> {
                    IntentActionRule rule = enabled.get(which);
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (editingLongAction) {
                        saveLongAction(value, LauncherShortcutStore.Kind.RULE, rule.id, "");
                    } else {
                        value.kind = LauncherShortcutStore.Kind.RULE;
                        value.target = rule.id;
                        value.packageName = "";
                        value.title = labels[which];
                        value.stateBinding = stateBindingFor(rule);
                        if (!value.iconCustomized) {
                            value.icon = LauncherRuleIconPolicy.suggest(rule);
                        }
                        value.iconColor = "#FFFFFFFF";
                        editAppearance(value);
                    }
                }).show();
    }

    /**
     * HA1058-era RULE entries had only the generic devices icon and no explicit customization
     * bit. LauncherShortcutStore already identifies those as non-customized; resolve them against
     * the saved rule once, while preserving every explicit user icon.
     */
    private void migrateRuleIcons() {
        final List<IntentActionRule> rules;
        try {
            rules = new IntentActionRuleStore(preferences).loadStrict();
        } catch (IllegalArgumentException ignored) {
            return;
        }
        for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
            if (shortcut.kind != LauncherShortcutStore.Kind.RULE) continue;
            for (IntentActionRule rule : rules) {
                if (shortcut.target.equals(rule.id)) {
                    boolean changed = LauncherRuleIconPolicy.refresh(shortcut, rule);
                    if (shortcut.stateBinding == null) {
                        shortcut.stateBinding = stateBindingFor(rule);
                        changed = true;
                    }
                    if (changed) store.upsert(shortcut);
                    break;
                }
            }
        }
    }

    private void saveCatalogAction(@Nullable LauncherShortcutStore.Shortcut existing,
                                   @NonNull SmartHomeShortcutPicker.Selection selection) {
        try {
            IntentActionRuleStore ruleStore = new IntentActionRuleStore(preferences);
            List<IntentActionRule> rules = new ArrayList<>(ruleStore.loadStrict());
            String reusable = existing == null ? "" : LauncherRuleIdPolicy.reusableId(
                    editingLongAction,
                    existing.kind == LauncherShortcutStore.Kind.RULE, existing.target,
                    existing.hasLongAction,
                    existing.longKind == LauncherShortcutStore.Kind.RULE, existing.longTarget);
            String ruleId = reusable.isEmpty() ? nextLauncherRuleId(rules) : reusable;
            String token = IntentActionRule.newTriggerToken();
            String actionToken = IntentActionRule.newTriggerToken();
            String actionPrefix = "dezz.statuswidget.launcher." + ruleId
                    .replace('-', '_').replace('.', '_');
            IntentActionRule replacement = new IntentActionRule(ruleId, true,
                    IntentActionRule.secureIntentAction(actionPrefix, actionToken), token,
                    selection.command, selection.title, selection.details,
                    selection.command.resourceId);
            int replaceAt = -1;
            for (int index = 0; index < rules.size(); index++) {
                if (rules.get(index).id.equals(ruleId)) {
                    replaceAt = index;
                    break;
                }
            }
            if (replaceAt >= 0) rules.set(replaceAt, replacement);
            else {
                if (rules.size() >= IntentActionRuleStore.MAX_RULES) {
                    throw new IllegalArgumentException("Достигнут лимит действий: "
                            + IntentActionRuleStore.MAX_RULES);
                }
                rules.add(replacement);
            }
            ruleStore.save(rules);
            WidgetService running = WidgetService.getInstance();
            if (running != null) running.applyPreferences();

            LauncherShortcutStore.Shortcut value = existing == null
                    ? new LauncherShortcutStore.Shortcut() : existing;
            if (editingLongAction) {
                saveLongAction(value, LauncherShortcutStore.Kind.RULE, ruleId, "");
                return;
            }
            value.kind = LauncherShortcutStore.Kind.RULE;
            value.target = ruleId;
            value.packageName = "";
            value.title = selection.title;
            value.stateBinding = selection.stateBinding;
            if (!value.iconCustomized) value.icon = selection.iconKey;
            value.iconColor = "#FFFFFFFF";
            editAppearance(value);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось сохранить действие: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private static SourceBinding stateBindingFor(@NonNull IntentActionRule rule) {
        return new SourceBinding(rule.command.connectorType, rule.command.connectorId,
                rule.command.resourceId, "", SourceBinding.PRESENTATION_AUTO, "");
    }

    @NonNull
    private static String nextLauncherRuleId(@NonNull List<IntentActionRule> rules) {
        int suffix = 1;
        while (true) {
            String candidate = "launcher_" + suffix++;
            boolean used = false;
            for (IntentActionRule rule : rules) if (rule.id.equals(candidate)) {
                used = true;
                break;
            }
            if (!used) return candidate;
        }
    }

    private void chooseApplication(@Nullable LauncherShortcutStore.Shortcut existing) {
        List<AppChoice> apps = queryApplications();
        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setVerticalSpacing(dp(8));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(new AppChoiceAdapter(apps));
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle("Выберите приложение")
                .setView(grid).setNegativeButton(android.R.string.cancel, null).create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            picker.dismiss();
            AppChoice app = apps.get(position);
            LauncherShortcutStore.Shortcut value = existing == null
                    ? new LauncherShortcutStore.Shortcut() : existing;
            if (editingLongAction) {
                saveLongAction(value, LauncherShortcutStore.Kind.APP,
                        app.component.flattenToString(), app.component.getPackageName());
            } else {
                value.kind = LauncherShortcutStore.Kind.APP;
                value.target = app.component.flattenToString();
                value.packageName = app.component.getPackageName();
                value.title = app.label;
                value.icon = "app";
                value.iconColor = "none";
                editAppearance(value);
            }
        });
        picker.show();
    }

    private void chooseBuiltin(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Builtin[] actions = LauncherShortcutStore.Builtin.values();
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) labels[i] = actions[i].label;
        new AlertDialog.Builder(this).setTitle("Выберите функцию")
                .setItems(labels, (dialog, which) -> {
                    LauncherShortcutStore.Builtin action = actions[which];
                    LauncherShortcutStore.Shortcut value = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    if (editingLongAction) {
                        saveLongAction(value, LauncherShortcutStore.Kind.BUILTIN, action.key, "");
                    } else {
                        value.kind = LauncherShortcutStore.Kind.BUILTIN;
                        value.target = action.key;
                        value.packageName = "";
                        value.title = action.label;
                        value.icon = action.icon;
                        value.iconColor = "#FFFFFFFF";
                        editAppearance(value);
                    }
                }).show();
    }

    private void editIntentTarget(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Shortcut value = existing == null
                ? new LauncherShortcutStore.Shortcut() : existing;
        LinearLayout form = dialogForm();
        EditText action = field("Действие Intent, например sh.car.parkovka",
                editingLongAction && value.hasLongAction
                        && value.longKind == LauncherShortcutStore.Kind.INTENT
                        ? value.longTarget
                        : value.kind == LauncherShortcutStore.Kind.INTENT ? value.target : "");
        EditText packageName = field("Целевой package (можно оставить пустым)",
                editingLongAction && value.hasLongAction
                        && value.longKind == LauncherShortcutStore.Kind.INTENT
                        ? value.longPackageName
                        : value.kind == LauncherShortcutStore.Kind.INTENT ? value.packageName : "");
        form.addView(action);
        form.addView(packageName);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Android Intent")
                .setView(scrollDialog(form)).setPositiveButton("Далее", null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (action.getText().toString().trim().isEmpty()) {
                        action.setError("Укажите действие");
                        return;
                    }
                    dialog.dismiss();
                    if (editingLongAction) {
                        saveLongAction(value, LauncherShortcutStore.Kind.INTENT,
                                action.getText().toString().trim(), packageName.getText().toString().trim());
                    } else {
                        value.kind = LauncherShortcutStore.Kind.INTENT;
                        value.target = action.getText().toString().trim();
                        value.packageName = packageName.getText().toString().trim();
                        if (existing == null) value.title = value.target;
                        value.icon = "power";
                        value.iconColor = "#FFFFFFFF";
                        editAppearance(value);
                    }
                }));
        dialog.show();
    }

    private void saveLongAction(@NonNull LauncherShortcutStore.Shortcut value,
                                @NonNull LauncherShortcutStore.Kind kind,
                                @NonNull String target, @NonNull String packageName) {
        value.hasLongAction = true;
        value.longKind = kind;
        value.longTarget = target;
        value.longPackageName = packageName;
        editingLongAction = false;
        store.upsert(value);
        refresh();
        Toast.makeText(this, "Долгое нажатие настроено", Toast.LENGTH_SHORT).show();
    }

    private void editAppearance(@NonNull LauncherShortcutStore.Shortcut value) {
        LinearLayout form = dialogForm();
        EditText title = field("Название", value.title);
        form.addView(title);
        LauncherActionsGridConfig openedGrid = requireGridConfig();
        LauncherActionsGridConfig.Placement openedPlacement =
                openedGrid.placement(value.id);

        TextView iconLabel = formLabel("Иконка");
        form.addView(iconLabel);
        Spinner icon = new Spinner(this);
        List<LauncherIconResolver.Preset> presets = LauncherIconResolver.presets();
        String automaticIcon = value.icon;
        icon.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presets));
        int selectedIcon = 0;
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).key.equals(value.icon)) selectedIcon = i;
        icon.setSelection(selectedIcon);
        form.addView(icon, new LinearLayout.LayoutParams(match(), dp(54)));

        ColorSelection background = colorSelection(form, "Цвет плитки",
                value.backgroundColor, AppleColorPickerDialog.Options.standard());
        ColorSelection iconColor = colorSelection(form, "Цвет иконки",
                value.iconColor, AppleColorPickerDialog.Options.noTint());
        ColorSelection textColor = colorSelection(form, "Цвет названия",
                value.textColor, AppleColorPickerDialog.Options.standard());

        ColorSelection activeBackground = colorSelection(null, "Цвет активной плитки",
                value.activeBackgroundColor, AppleColorPickerDialog.Options.standard());
        ColorSelection activeIcon = colorSelection(null, "Цвет активной иконки",
                value.activeIconColor, AppleColorPickerDialog.Options.standard());
        MaterialSwitch vehicleStateColor = new MaterialSwitch(this);
        vehicleStateColor.setText("Цвет уровня задаёт автомобиль");
        vehicleStateColor.setChecked(value.useVehicleStateColor);
        MaterialSwitch showState = new MaterialSwitch(this);
        showState.setText("Показывать текущий режим / уровень");
        showState.setChecked(value.showState);
        if (value.kind == LauncherShortcutStore.Kind.CAR
                || value.kind == LauncherShortcutStore.Kind.RULE) {
            form.addView(activeBackground.button);
            form.addView(activeIcon.button);
            if (value.kind == LauncherShortcutStore.Kind.CAR) form.addView(vehicleStateColor);
            form.addView(showState);
        }

        SeekValue iconSize = seek(form, "Размер иконки",
                LauncherShortcutStore.MIN_ICON_SIZE_PX,
                LauncherShortcutStore.MAX_ICON_SIZE_PX, value.iconSizePx, " px");
        int initialWidth = openedPlacement == null
                ? value.columnSpan : openedPlacement.columnSpan;
        int initialHeight = openedPlacement == null
                ? value.rowSpan : openedPlacement.rowSpan;
        SeekValue width = seek(form, "Ширина плитки", 1, openedGrid.columns,
                initialWidth, " яч.");
        SeekValue height = seek(form, "Высота плитки", 1, openedGrid.rows,
                initialHeight, " яч.");
        MaterialSwitch showTitle = new MaterialSwitch(this);
        showTitle.setText("Показывать название");
        showTitle.setChecked(value.showTitle);
        form.addView(showTitle);
        MaterialSwitch enabled = new MaterialSwitch(this);
        enabled.setText("Иконка включена");
        enabled.setChecked(value.enabled);
        form.addView(enabled);

        new AlertDialog.Builder(this).setTitle("Оформление иконки")
                .setView(scrollDialog(form)).setPositiveButton("Применить", (dialog, which) -> {
                    value.title = title.getText().toString().trim();
                    value.icon = presets.get(icon.getSelectedItemPosition()).key;
                    if (!value.icon.equals(automaticIcon)) value.iconCustomized = true;
                    value.backgroundColor = background.value;
                    value.iconColor = iconColor.value;
                    value.textColor = textColor.value;
                    if (value.kind == LauncherShortcutStore.Kind.CAR
                            || value.kind == LauncherShortcutStore.Kind.RULE) {
                        value.activeBackgroundColor = activeBackground.value;
                        value.activeIconColor = activeIcon.value;
                        if (value.kind == LauncherShortcutStore.Kind.CAR) {
                            value.useVehicleStateColor = vehicleStateColor.isChecked();
                        }
                        value.showState = showState.isChecked();
                    }
                    value.iconSizePx = iconSize.value;
                    value.showTitle = showTitle.isChecked();
                    value.enabled = enabled.isChecked();

                    // Re-read the grid at commit time. The actual HOME editor may have changed
                    // position while this Activity was stopped, and an old dialog object must
                    // never write those coordinates back.
                    store.load();
                    LauncherActionsGridConfig latestGrid = gridStore.load(store.all());
                    LauncherActionsGridConfig.Placement latestPlacement =
                            latestGrid.placement(value.id);
                    boolean spanAccepted = true;
                    if (latestPlacement == null) {
                        // A brand-new shortcut is reconciled immediately after its definition is
                        // saved; these legacy fields seed that first stable placement.
                        value.columnSpan = width.value;
                        value.rowSpan = height.value;
                    } else {
                        boolean unchanged = latestPlacement.columnSpan == width.value
                                && latestPlacement.rowSpan == height.value;
                        spanAccepted = unchanged || latestGrid.setPlacement(value.id,
                                latestPlacement.column, latestPlacement.row,
                                width.value, height.value);
                        LauncherActionsGridConfig.Placement actual =
                                latestGrid.placement(value.id);
                        value.columnSpan = actual == null
                                ? latestPlacement.columnSpan : actual.columnSpan;
                        value.rowSpan = actual == null
                                ? latestPlacement.rowSpan : actual.rowSpan;
                        gridStore.save(latestGrid);
                        gridConfig = latestGrid;
                    }
                    store.upsert(value);
                    refresh();
                    if (!spanAccepted) {
                        Toast.makeText(this,
                                "Для такого размера нет свободного места — сохранён прежний",
                                Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirmDelete(LauncherShortcutStore.Shortcut value) {
        new AlertDialog.Builder(this).setTitle("Удалить «" + value.title + "»?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    store.remove(value.id);
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @NonNull
    private List<AppChoice> queryApplications() {
        PackageManager manager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppChoice> values = new ArrayList<>();
        for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
            if (info.activityInfo == null) continue;
            values.add(new AppChoice(String.valueOf(info.loadLabel(manager)),
                    new ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    HighResolutionAppIconLoader.load(this, info.activityInfo)));
        }
        values.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
        return values;
    }

    @NonNull
    private String typeLabel(LauncherShortcutStore.Shortcut value) {
        switch (value.kind) {
            case APP: return longSuffix("Приложение · " + value.packageName, value);
            case RULE: return longSuffix("Действие устройства · " + value.target, value);
            case INTENT: return longSuffix("Intent · " + value.target, value);
            case CAR: return longSuffix("Автомобиль · " + value.target + " · "
                    + carOperationLabel(value.command, value.commandValue), value);
            case BUILTIN:
            default: return longSuffix("Функция · "
                    + LauncherShortcutStore.Builtin.fromKey(value.target).label, value);
        }
    }

    private String carOperationLabel(CarControlCommand.Operation operation, double value) {
        switch (operation) {
            case CYCLE: return "следующее значение";
            case SET: return "установить " + String.format(Locale.ROOT, "%s", value);
            case ACTIVATE: return "выполнить";
            case TOGGLE:
            default: return "переключить";
        }
    }

    private String longSuffix(String base, LauncherShortcutStore.Shortcut value) {
        return value.hasLongAction ? base + "  ·  долгое нажатие настроено" : base;
    }

    @NonNull
    private ColorSelection colorSelection(@Nullable LinearLayout parent, @NonNull String title,
                                          @NonNull String initial,
                                          @NonNull AppleColorPickerDialog.Options options) {
        MaterialButton button = new MaterialButton(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(62));
        lp.topMargin = dp(5);
        button.setLayoutParams(lp);
        ColorSelection selection = new ColorSelection(initial, button);
        AppleColorPickerDialog.decorateButton(button, title, initial);
        button.setOnClickListener(v -> {
            String original = selection.value;
            AppleColorPickerDialog.show(this, title, original, options,
                    new AppleColorPickerDialog.Listener() {
                        private void apply(@Nullable String selected) {
                            selection.value = selected == null ? original : selected;
                            AppleColorPickerDialog.decorateButton(button, title, selection.value);
                        }

                        @Override public void onPreview(@Nullable String selected) {
                            apply(selected);
                        }

                        @Override public void onSelected(@Nullable String selected) {
                            apply(selected);
                        }
                    });
        });
        if (parent != null) parent.addView(button);
        return selection;
    }

    private SeekValue seek(LinearLayout parent, String label, int min, int max, int current, String suffix) {
        TextView title = formLabel(label + ": " + current + suffix);
        parent.addView(title);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        SeekValue value = new SeekValue(current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.value = min + progress;
                title.setText(label + ": " + value.value + suffix);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        parent.addView(bar, new LinearLayout.LayoutParams(match(), dp(46)));
        return value;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        return form;
    }

    private ScrollView scrollDialog(@NonNull View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(match(), wrap()));
        return scroll;
    }

    private EditText field(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setLayoutParams(new LinearLayout.LayoutParams(match(), dp(58)));
        return input;
    }

    private TextView formLabel(String value) {
        TextView label = text(15, false);
        label.setText(value);
        label.setPadding(0, dp(8), 0, 0);
        return label;
    }

    private MaterialButton compactButton(String label) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextSize(20);
        button.setMinWidth(dp(48));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        return button;
    }

    private TextView text(float size, boolean bold) {
        TextView value = new TextView(this);
        value.setTextSize(size);
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    private final class AppChoiceAdapter extends BaseAdapter {
        private final List<AppChoice> values;
        AppChoiceAdapter(List<AppChoice> values) { this.values = values; }
        @Override public int getCount() { return values.size(); }
        @Override public Object getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout cell = recycled instanceof LinearLayout ? (LinearLayout) recycled
                    : new LinearLayout(LauncherShortcutSettingsActivity.this);
            cell.removeAllViews();
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(5), dp(5), dp(5), dp(5));
            AppChoice app = values.get(position);
            ImageView icon = new ImageView(LauncherShortcutSettingsActivity.this);
            icon.setImageDrawable(app.icon);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
            TextView label = text(12, false);
            label.setText(app.label);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            cell.addView(label, new LinearLayout.LayoutParams(match(), dp(38)));
            return cell;
        }
    }

    private static final class AppChoice {
        final String label;
        final ComponentName component;
        final android.graphics.drawable.Drawable icon;
        AppChoice(String label, ComponentName component, android.graphics.drawable.Drawable icon) {
            this.label = label; this.component = component; this.icon = icon;
        }
    }

    private static final class ColorSelection {
        @NonNull String value;
        @NonNull final MaterialButton button;

        ColorSelection(@NonNull String value, @NonNull MaterialButton button) {
            this.value = value;
            this.button = button;
        }
    }

    private interface IntSelection {
        int select(int value);
    }

    private static final class GridSlider {
        @NonNull final SeekBar seek;
        @NonNull final TextView value;
        final int minimum;
        final int maximum;
        @NonNull final String suffix;

        GridSlider(@NonNull SeekBar seek, @NonNull TextView value,
                   int minimum, int maximum, @NonNull String suffix) {
            this.seek = seek;
            this.value = value;
            this.minimum = minimum;
            this.maximum = maximum;
            this.suffix = suffix;
        }

        void setValue(int selected) {
            int actual = Math.max(minimum, Math.min(maximum, selected));
            seek.setProgress(actual - minimum);
            value.setText(actual + suffix);
        }
    }

    private static final class SeekValue { int value; SeekValue(int value) { this.value = value; } }
    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
