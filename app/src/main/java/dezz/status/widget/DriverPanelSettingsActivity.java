/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.driver.DriverControlSpacingPolicy;
import dezz.status.widget.driver.DriverButtonHeightPolicy;
import dezz.status.widget.driver.DriverInformationTileLayoutPolicy;
import dezz.status.widget.driver.DriverPanelLayoutPolicy;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.driver.DriverClimateShortcutView;
import dezz.status.widget.driver.DriverExactCenterFrameLayout;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.InformationShortcutView;
import dezz.status.widget.launcher.InstalledAppCatalog;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.LiveClimateIconPolicy;
import dezz.status.widget.launcher.ShortcutActionPicker;
import dezz.status.widget.launcher.information.StatusBarInformationCatalog;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;
import dezz.status.widget.settings.VectorIconPickerDialog;

/** Visual editor for the unified current-generation Monjaro driver panel. */
public final class DriverPanelSettingsActivity extends AppCompatActivity {
    private interface IntSetter { void set(int value); }
    private interface ShortcutSetter {
        void set(@NonNull LauncherShortcutStore.Shortcut shortcut);
    }

    private Preferences preferences;
    private Preferences.DriverPanelProfile profile;
    private LauncherShortcutStore store;
    private LinearLayout buttonsHost;
    private FrameLayout preview;
    private TextView countLabel;
    private TextView runtimeLabel;
    private MaterialButton addApplication;
    private MaterialButton addFunction;
    private ShortcutActionPicker actionPicker;
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "driver-settings-app-catalog");
        thread.setDaemon(true);
        return thread;
    });
    @Nullable private List<InstalledAppCatalog.App> cachedApps;
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            if (runtimeLabel == null) return;
            String detail = DriverPanelService.getRuntimeDetail();
            runtimeLabel.setText(detail == null || detail.trim().isEmpty()
                    ? "Состояние: панель ещё не запускалась"
                    : "Состояние: " + detail);
            runtimeLabel.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        profile = preferences.activeDriverPanelProfile();
        store = LauncherShortcutStore.forDriverPanel(preferences, profile);
        actionPicker = new ShortcutActionPicker(this, preferences, store, () -> {
            refreshButtons();
            applyPanel();
        });
        setTitle("Панель водителя");
        View content = buildContent();
        setContentView(content);
        SettingsBackNavigation.install(this, content);
        refreshButtons();
        loadApplications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.load();
        refreshButtons();
        runtimeLabel.removeCallbacks(statusRefresh);
        runtimeLabel.post(statusRefresh);
        applyPanel();
    }

    @Override
    protected void onPause() {
        runtimeLabel.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        catalogExecutor.shutdownNow();
        super.onDestroy();
    }

    @NonNull
    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(11, 14, 19));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(10), 0, dp(22), dp(96));
        scroll.addView(settings, new ScrollView.LayoutParams(match(), wrap()));

        title(settings, "Панель водителя");
        hint(settings, "До 10 кнопок поверх любых приложений. Домой, Назад и полный список "
                + "приложений уже добавлены по умолчанию.");

        addSwitch(settings, "Включить панель водителя",
                preferences.driverPanelEnabled.get(), value -> {
                    preferences.driverPanelEnabled.set(value);
                    applyPanel();
                });
        addSwitch(settings, "Показывать справа",
                profile.side.get() == 1, value -> {
                    profile.side.set(value ? 1 : 0);
                    refreshPreview();
                    applyPanel();
                });
        hint(settings, "Панель всегда цельная и закрывает штатную. Добавьте функцию "
                + "«Штатный климат» в любое из 10 мест: перед нажатием наша панель временно "
                + "перестаёт перехватывать касания и имитирует тап в исходном центре кнопки "
                + "на 37,5% высоты экрана.");

        int minimumPanelWidth = DriverPanelLayoutPolicy.referencePanelWidth(
                profile.style == Preferences.DriverPanelStyle.NEW);
        slider(settings, "Ширина панели", minimumPanelWidth, 260,
                Math.max(minimumPanelWidth, profile.widthPx.get()), " px", value -> {
                    profile.widthPx.set(value);
                    refreshPreview();
                    applyPanel();
                });
        slider(settings, "Верхний отступ", 0, 100,
                profile.topPaddingPx.get(), " px", value -> {
                    profile.topPaddingPx.set(value);
                    refreshPreview();
                    applyPanel();
                });
        slider(settings, "Нижний отступ", 0, 100,
                profile.bottomPaddingPx.get(), " px", value -> {
                    profile.bottomPaddingPx.set(value);
                    refreshPreview();
                    applyPanel();
                });
        slider(settings, "Расстояние между кнопками", 0, 30,
                profile.itemGapPx.get(), " px", value -> {
                    profile.itemGapPx.set(value);
                    refreshPreview();
                    applyPanel();
                });
        int minimumDriverRadius = dp(20);
        slider(settings, "Скругление панели", minimumDriverRadius,
                Math.max(60, minimumDriverRadius),
                Math.max(minimumDriverRadius, profile.cornerRadiusPx.get()), " px", value -> {
                    profile.cornerRadiusPx.set(value);
                    refreshPreview();
                    applyPanel();
                });

        MaterialButton background = button("Цвет панели");
        AppleColorPickerDialog.decorateButton(background, "Цвет панели",
                profile.backgroundColor.get());
        background.setIconResource(R.drawable.ic_driver_format_color_fill);
        background.setOnClickListener(view -> AppleColorPickerDialog.show(this,
                "Фон панели", profile.backgroundColor.get(),
                AppleColorPickerDialog.Options.opaque(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {
                        if (value == null) return;
                        profile.backgroundColor.set(value);
                        AppleColorPickerDialog.decorateButton(background,
                                "Цвет панели", value);
                        refreshPreview();
                    }

                    @Override public void onSelected(@Nullable String value) {
                        if (value != null) profile.backgroundColor.set(value);
                        applyPanel();
                    }
                }));
        settings.addView(background, rowParams());

        MaterialButton border = button("Цвет границы панели");
        AppleColorPickerDialog.decorateButton(border, "Цвет границы панели",
                profile.borderColor.get());
        border.setIconResource(R.drawable.ic_driver_border_color);
        border.setOnClickListener(view -> AppleColorPickerDialog.show(this,
                "Граница панели", profile.borderColor.get(),
                AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {
                        if (value == null) return;
                        profile.borderColor.set(value);
                        AppleColorPickerDialog.decorateButton(border,
                                "Цвет границы панели", value);
                        refreshPreview();
                    }

                    @Override public void onSelected(@Nullable String value) {
                        if (value != null) profile.borderColor.set(value);
                        applyPanel();
                    }
                }));
        settings.addView(border, rowParams());
        slider(settings, "Толщина границы панели", 0, 32,
                Math.max(0, Math.min(32, profile.borderWidthPx.get())), " px", value -> {
                    profile.borderWidthPx.set(value);
                    refreshPreview();
                    applyPanel();
                });

        title(settings, "Кнопки");
        countLabel = hint(settings, "");
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addApplication = button("＋ Приложение");
        addFunction = button("＋ Действие");
        addApplication.setOnClickListener(view -> addApplication());
        addFunction.setOnClickListener(view -> actionPicker.showNew());
        addRow.addView(addApplication, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams functionParams = new LinearLayout.LayoutParams(
                0, dp(52), 1f);
        functionParams.leftMargin = dp(10);
        addRow.addView(addFunction, functionParams);
        settings.addView(addRow, rowParams());

        MaterialButton allAppsSettings = button("Общие настройки меню «Все приложения»");
        allAppsSettings.setOnClickListener(view ->
                startActivity(new android.content.Intent(this, AllAppsSettingsActivity.class)));
        settings.addView(allAppsSettings, rowParams());
        MaterialButton favoritesSettings = button("Настроить «Избранное» панели водителя");
        favoritesSettings.setOnClickListener(view -> startActivity(
                new android.content.Intent(this, DriverFavoritesSettingsActivity.class)));
        settings.addView(favoritesSettings, rowParams());

        buttonsHost = new LinearLayout(this);
        buttonsHost.setOrientation(LinearLayout.VERTICAL);
        settings.addView(buttonsHost, new LinearLayout.LayoutParams(match(), wrap()));

        runtimeLabel = hint(settings, "");
        MaterialButton retry = button("Обновить панель поверх приложений");
        retry.setOnClickListener(view -> applyPanel());
        settings.addView(retry, rowParams());

        LinearLayout previewColumn = new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        previewColumn.setPadding(dp(18), dp(8), dp(8), dp(8));
        TextView previewTitle = text("Предпросмотр", 20, Color.WHITE);
        previewTitle.setGravity(Gravity.CENTER);
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(48)));
        TextView previewHint = text("Все кнопки равномерно распределяются по полной высоте",
                13, 0xFF8E8E93);
        previewHint.setGravity(Gravity.CENTER);
        previewColumn.addView(previewHint,
                new LinearLayout.LayoutParams(match(), wrap()));
        preview = new FrameLayout(this);
        GradientDrawable screen = new GradientDrawable();
        screen.setColor(0xFF05070A);
        screen.setCornerRadius(dp(24));
        preview.setBackground(screen);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                dp(250), 0, 1f);
        previewParams.topMargin = dp(14);
        previewParams.bottomMargin = dp(14);
        previewColumn.addView(preview, previewParams);
        TextView fixed = text("Кнопка «Штатный климат» может находиться в любом месте",
                12, 0xFF8E8E93);
        fixed.setGravity(Gravity.CENTER);
        previewColumn.addView(fixed, new LinearLayout.LayoutParams(match(), wrap()));

        root.addView(scroll, new LinearLayout.LayoutParams(0, match(), 3f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), 2f));
        preview.post(this::refreshPreview);
        return root;
    }

    private void refreshButtons() {
        if (buttonsHost == null) return;
        buttonsHost.removeAllViews();
        List<LauncherShortcutStore.Shortcut> shortcuts = store.all();
        int interactive = interactiveCount(shortcuts);
        int readOnly = shortcuts.size() - interactive;
        countLabel.setText(interactive + " кнопок из "
                + LauncherShortcutStore.MAX_DRIVER_PANEL_SHORTCUTS
                + " · " + readOnly + " инфо/разделителей без лимита");
        boolean full = interactive >= LauncherShortcutStore.MAX_DRIVER_PANEL_SHORTCUTS;
        addApplication.setEnabled(!full);
        addFunction.setEnabled(true);
        for (int index = 0; index < shortcuts.size(); index++) {
            buttonsHost.addView(shortcutRow(shortcuts.get(index), index, shortcuts.size()),
                    rowParams());
        }
        refreshPreview();
    }

    @NonNull
    private View shortcutRow(@NonNull LauncherShortcutStore.Shortcut shortcut,
                             int index, int total) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(0xFF1C1C1E);
        card.setStrokeColor(0xFF38383A);
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setContentPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        Drawable drawable = resolveIcon(shortcut);
        if (drawable != null) icon.setImageDrawable(drawable);
        header.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(shortcut.title, 17, Color.WHITE);
        TextView type = text(shortcutType(shortcut), 12, 0xFF8E8E93);
        labels.addView(name);
        labels.addView(type);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, wrap(), 1f);
        labelsParams.leftMargin = dp(12);
        header.addView(labels, labelsParams);

        MaterialButton up = compactButton("↑");
        up.setEnabled(index > 0);
        up.setOnClickListener(view -> {
            if (shortcut.kind == LauncherShortcutStore.Kind.INFO
                    && !shortcut.informationGroup.trim().isEmpty()) {
                store.moveInformationGroupItem(shortcut.id, -1);
            } else {
                store.move(shortcut.id, -1);
            }
            refreshButtons();
            applyPanel();
        });
        header.addView(up, new LinearLayout.LayoutParams(dp(52), dp(46)));
        MaterialButton down = compactButton("↓");
        down.setEnabled(index < total - 1);
        down.setOnClickListener(view -> {
            if (shortcut.kind == LauncherShortcutStore.Kind.INFO
                    && !shortcut.informationGroup.trim().isEmpty()) {
                store.moveInformationGroupItem(shortcut.id, 1);
            } else {
                store.move(shortcut.id, 1);
            }
            refreshButtons();
            applyPanel();
        });
        header.addView(down, new LinearLayout.LayoutParams(dp(52), dp(46)));
        body.addView(header, new LinearLayout.LayoutParams(match(), wrap()));

        TextView sizeValue = text("Размер иконки: " + shortcut.iconSizePx + " px",
                13, 0xFFC7C7CC);
        body.addView(sizeValue, topMargin(dp(8)));
        SeekBar size = new SeekBar(this);
        size.setMax(LauncherShortcutStore.MAX_ICON_SIZE_PX
                - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        size.setProgress(shortcut.iconSizePx - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int selected = shortcut.iconSizePx;
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                selected = LauncherShortcutStore.MIN_ICON_SIZE_PX + progress;
                sizeValue.setText("Размер иконки: " + selected + " px");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                shortcut.iconSizePx = selected;
                store.upsert(shortcut);
                refreshPreview();
                applyPanel();
            }
        });
        body.addView(size, new LinearLayout.LayoutParams(match(), dp(42)));

        if (shortcut.kind != LauncherShortcutStore.Kind.INFO
                && shortcut.kind != LauncherShortcutStore.Kind.DIVIDER) {
            driverButtonHeightSlider(body, shortcut);
        }

        if (shortcut.kind == LauncherShortcutStore.Kind.INFO
                && !shortcut.informationGroup.trim().isEmpty()) {
            // The previous card exposed gapAfterPx here even though a horizontal information
            // row is rendered from informationGroupGapPx. That made the visible control a no-op
            // for exactly the layout it claimed to edit.
            spacingSlider(body, "Расстояние между информационными иконками", 120,
                    shortcut.informationGroupGapPx, selected ->
                            applyInformationGroupSetting(shortcut,
                                    value -> value.informationGroupGapPx = selected));
        } else {
            buttonGapSlider(body, shortcut, true);
            buttonGapSlider(body, shortcut, false);
        }

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton action = compactButton("Нажатие");
        action.setOnClickListener(view -> actionPicker.showPrimary(shortcut));
        controls.addView(action, new LinearLayout.LayoutParams(0, dp(46), 1f));
        MaterialButton appearance = compactButton("Цвет");
        appearance.setOnClickListener(view -> editColors(shortcut));
        LinearLayout.LayoutParams appearanceParams = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        appearanceParams.leftMargin = dp(8);
        controls.addView(appearance, appearanceParams);
        boolean liveClimate = isLiveClimate(shortcut);
        MaterialButton chooseIcon = compactButton(liveClimate ? "Живая" : "Иконка");
        chooseIcon.setOnClickListener(view -> chooseIcon(shortcut));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        iconParams.leftMargin = dp(8);
        controls.addView(chooseIcon, iconParams);
        MaterialButton remove = compactButton("Удалить");
        remove.setTextColor(0xFFFF453A);
        remove.setOnClickListener(view -> {
            store.remove(shortcut.id);
            refreshButtons();
            applyPanel();
        });
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        removeParams.leftMargin = dp(8);
        controls.addView(remove, removeParams);
        body.addView(controls, topMargin(dp(8)));

        if (shortcut.kind == LauncherShortcutStore.Kind.INFO) {
            MaterialButton informationGroup = compactButton(
                    shortcut.informationGroup.trim().isEmpty()
                            ? "＋ Объединить в горизонтальный ряд"
                            : "Горизонтальный ряд: "
                            + shortcut.informationGroup.trim());
            informationGroup.setOnClickListener(
                    view -> editInformationGroup(shortcut));
            body.addView(informationGroup, topMargin(dp(8)));

            MaterialSwitch showIcon = new MaterialSwitch(this);
            showIcon.setText("Показывать значок слева");
            showIcon.setTextColor(Color.WHITE);
            showIcon.setChecked(!"none".equalsIgnoreCase(shortcut.icon));
            showIcon.setOnCheckedChangeListener((button, checked) -> {
                shortcut.icon = checked ? "auto" : "none";
                shortcut.iconCustomized = !checked;
                store.upsert(shortcut);
                refreshButtons();
                applyPanel();
            });
            body.addView(showIcon, topMargin(dp(8)));

            MaterialButton informationAppearance =
                    compactButton("Оформление содержимого");
            informationAppearance.setOnClickListener(
                    view -> editInformationAppearance(shortcut));
            body.addView(informationAppearance, topMargin(dp(8)));
        }

        if (liveClimate) {
            MaterialSwitch extendedClimate = new MaterialSwitch(this);
            extendedClimate.setText(
                    "Расширенная информация: AUTO и пиктограмма обдува");
            extendedClimate.setTextColor(Color.WHITE);
            extendedClimate.setChecked(shortcut.extendedClimateInfo);
            extendedClimate.setOnCheckedChangeListener((button, checked) -> {
                shortcut.extendedClimateInfo = checked;
                store.upsert(shortcut);
                refreshPreview();
                applyPanel();
            });
            body.addView(extendedClimate, topMargin(dp(8)));
            slider(body, "Отступ между основной и расширенной информацией",
                    0, 96, shortcut.climateDetailsGapPx, " px", value -> {
                        shortcut.climateDetailsGapPx = value;
                        store.upsert(shortcut);
                        refreshPreview();
                        applyPanel();
                    });
        }

        MaterialButton longAction = compactButton(shortcut.hasLongAction
                ? "Удержание: " + longActionLabel(shortcut)
                : "Удержание: не назначено");
        longAction.setOnClickListener(view -> actionPicker.showLong(shortcut));
        body.addView(longAction, topMargin(dp(8)));

        MaterialSwitch showTitle = new MaterialSwitch(this);
        showTitle.setText("Показывать подпись на узкой панели");
        showTitle.setTextColor(Color.WHITE);
        showTitle.setChecked(shortcut.showTitle);
        showTitle.setOnCheckedChangeListener((button, checked) -> {
            shortcut.showTitle = checked;
            store.upsert(shortcut);
            refreshPreview();
            applyPanel();
        });
        body.addView(showTitle, topMargin(dp(8)));
        card.addView(body);
        return card;
    }

    private void addApplication() {
        if (interactiveCount(store.all())
                >= LauncherShortcutStore.MAX_DRIVER_PANEL_SHORTCUTS) {
            limitToast();
            return;
        }
        List<InstalledAppCatalog.App> apps = cachedApps;
        if (apps == null) {
            Toast.makeText(this, "Список приложений ещё загружается",
                    Toast.LENGTH_SHORT).show();
            loadApplications();
            return;
        }
        showApplicationPicker(null, apps);
    }

    private void addBuiltin() {
        if (interactiveCount(store.all())
                >= LauncherShortcutStore.MAX_DRIVER_PANEL_SHORTCUTS) {
            limitToast();
            return;
        }
        showBuiltinPicker(null);
    }

    private static int interactiveCount(
            @NonNull List<LauncherShortcutStore.Shortcut> values) {
        int result = 0;
        for (LauncherShortcutStore.Shortcut value : values) {
            if (LauncherShortcutStore.isInteractive(value)) result++;
        }
        return result;
    }

    private void chooseReplacement(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        actionPicker.showPrimary(shortcut);
    }

    private void showBuiltinPicker(@Nullable LauncherShortcutStore.Shortcut existing) {
        LauncherShortcutStore.Builtin[] values = LauncherShortcutStore.Builtin.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].label;
        new AlertDialog.Builder(this)
                .setTitle("Функция")
                .setItems(labels, (dialog, which) -> {
                    LauncherShortcutStore.Builtin action = values[which];
                    LauncherShortcutStore.Shortcut shortcut = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    shortcut.kind = LauncherShortcutStore.Kind.BUILTIN;
                    shortcut.target = action.key;
                    shortcut.packageName = "";
                    shortcut.title = action.label;
                    shortcut.icon = action.icon;
                    shortcut.iconCustomized = false;
                    shortcut.showTitle = false;
                    shortcut.backgroundColor = "#00000000";
                    if (existing == null
                            && action == LauncherShortcutStore.Builtin.STOCK_CLIMATE) {
                        shortcut.iconSizePx = 76;
                    }
                    shortcut.liveClimateIcon = LiveClimateIconPolicy.afterPrimaryActionChange(
                            existing != null,
                            shortcut.liveClimateIcon,
                            shortcut.kind,
                            shortcut.target);
                    store.upsert(shortcut);
                    refreshButtons();
                    applyPanel();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showApplicationPicker(@Nullable LauncherShortcutStore.Shortcut existing,
                                       @NonNull List<InstalledAppCatalog.App> apps) {
        List<String> labels = new ArrayList<>(apps.size());
        for (InstalledAppCatalog.App app : apps) {
            labels.add(app.label + "\n" + app.secondaryLabel());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        new AlertDialog.Builder(this)
                .setTitle("Все приложения · включая системные")
                .setAdapter(adapter, (dialog, which) -> {
                    InstalledAppCatalog.App app = apps.get(which);
                    if (!app.launchable()) {
                        Toast.makeText(this, "У приложения нет доступного экрана",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LauncherShortcutStore.Shortcut shortcut = existing == null
                            ? new LauncherShortcutStore.Shortcut() : existing;
                    shortcut.kind = LauncherShortcutStore.Kind.APP;
                    shortcut.target = app.component.flattenToString();
                    shortcut.packageName = app.packageName;
                    shortcut.title = app.label;
                    shortcut.icon = "app";
                    shortcut.iconCustomized = false;
                    shortcut.showTitle = false;
                    shortcut.backgroundColor = "#00000000";
                    store.upsert(shortcut);
                    refreshButtons();
                    applyPanel();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editColors(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        String[] choices = {"Фон кнопки", "Цвет векторной иконки", "Цвет подписи"};
        new AlertDialog.Builder(this)
                .setTitle("Оформление · " + shortcut.title)
                .setItems(choices, (dialog, which) -> {
                    String current = which == 0 ? shortcut.backgroundColor
                            : which == 1 ? shortcut.iconColor : shortcut.textColor;
                    AppleColorPickerDialog.show(this, choices[which], current,
                            AppleColorPickerDialog.Options.standard(),
                            new AppleColorPickerDialog.Listener() {
                                @Override public void onPreview(@Nullable String value) {
                                    if (value == null) return;
                                    if (which == 0) shortcut.backgroundColor = value;
                                    else if (which == 1) shortcut.iconColor = value;
                                    else shortcut.textColor = value;
                                    store.upsert(shortcut);
                                    refreshPreview();
                                }

                                @Override public void onSelected(@Nullable String value) {
                                    if (value != null) {
                                        if (which == 0) shortcut.backgroundColor = value;
                                        else if (which == 1) shortcut.iconColor = value;
                                        else shortcut.textColor = value;
                                    }
                                    store.upsert(shortcut);
                                    refreshButtons();
                                    applyPanel();
                                }
                            });
                })
                .setNegativeButton("Готово", null)
                .show();
    }

    private void editInformationGroup(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        if (shortcut.informationGroup.trim().isEmpty()) {
            chooseInformationGroup(shortcut);
            return;
        }
        showInformationGroupSettings(shortcut);
    }

    private void chooseInformationGroup(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        List<String> groups = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            String group = value.informationGroup.trim();
            if (value.kind == LauncherShortcutStore.Kind.INFO
                    && !group.isEmpty() && !groups.contains(group)) {
                groups.add(group);
            }
        }
        List<String> choices = new ArrayList<>();
        choices.add("Без группы · отдельная строка");
        for (String group : groups) choices.add("Добавить в «" + group + "»");
        choices.add("＋ Создать новый горизонтальный ряд");
        LinearLayout explanation = new LinearLayout(this);
        explanation.setOrientation(LinearLayout.VERTICAL);
        explanation.setPadding(dp(24), dp(4), dp(24), dp(8));
        TextView hint = new TextView(this);
        hint.setText("Элементы одного ряда располагаются слева направо. "
                + "У ряда свои положение, интервалы, отступы, фон и выравнивание.");
        hint.setTextSize(14);
        explanation.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Горизонтальный ряд")
                // AlertDialog cannot render message + list together reliably on the Geely
                // Android 9 theme: setMessage replaces the list entirely. A custom header keeps
                // the explanation and the actionable choices visible at the same time.
                .setView(explanation)
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        shortcut.informationGroup = "";
                        persistInformationAppearance(shortcut);
                        refreshButtons();
                        return;
                    }
                    if (which <= groups.size()) {
                        assignInformationGroup(shortcut, groups.get(which - 1));
                        showInformationGroupSettings(shortcut);
                        return;
                    }
                    createInformationGroup(shortcut);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void createInformationGroup(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Например: Связь или Статусы");
        new AlertDialog.Builder(this)
                .setTitle("Название горизонтального ряда")
                .setView(input)
                .setPositiveButton("Создать", (dialog, which) -> {
                    String group = input.getText().toString().trim();
                    if (group.isEmpty()) {
                        Toast.makeText(this, "Введите название ряда",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    assignInformationGroup(shortcut, group);
                    showInformationGroupSettings(shortcut);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void assignInformationGroup(
            @NonNull LauncherShortcutStore.Shortcut shortcut,
            @NonNull String rawGroup) {
        String group = rawGroup.trim();
        LauncherShortcutStore.Shortcut representative = null;
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            if (value.kind == LauncherShortcutStore.Kind.INFO
                    && group.equals(value.informationGroup.trim())) {
                representative = value;
                break;
            }
        }
        if (representative != null) copyInformationGroupSettings(
                representative, shortcut);
        shortcut.informationGroup = group;
        store.upsert(shortcut);
        refreshButtons();
        applyPanel();
    }

    private void showInformationGroupSettings(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(72));
        scroll.addView(form, new ScrollView.LayoutParams(match(), wrap()));

        String groupName = shortcut.informationGroup.trim();
        int memberCount = informationGroupMembers(groupName).size();
        hint(form, "Ряд «" + groupName + "» · " + memberCount
                + " элементов. Стрелки карточки меняют порядок слева направо.");

        MaterialButton membership = compactButton("Состав ряда / сменить группу");
        membership.setOnClickListener(view -> chooseInformationGroup(shortcut));
        form.addView(membership, rowParams());

        LinearLayout order = new LinearLayout(this);
        order.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton rowUp = compactButton("↑ Ряд выше");
        rowUp.setOnClickListener(view -> {
            store.moveInformationGroup(groupName,
                    shortcut.informationPlacement, -1);
            refreshButtons();
            applyPanel();
        });
        MaterialButton rowDown = compactButton("↓ Ряд ниже");
        rowDown.setOnClickListener(view -> {
            store.moveInformationGroup(groupName,
                    shortcut.informationPlacement, 1);
            refreshButtons();
            applyPanel();
        });
        order.addView(rowUp, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams downParams =
                new LinearLayout.LayoutParams(0, dp(48), 1f);
        downParams.leftMargin = dp(8);
        order.addView(rowDown, downParams);
        form.addView(order, rowParams());

        MaterialButton placement = compactButton("Положение ряда: "
                + (shortcut.informationPlacement == 1
                ? "снизу панели" : "сверху панели"));
        placement.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Положение горизонтального ряда")
                .setItems(new String[]{"Сверху панели", "Снизу панели"},
                        (dialog, which) -> {
                            applyInformationGroupSetting(shortcut,
                                    value -> value.informationPlacement = which);
                            placement.setText("Положение ряда: "
                                    + (which == 1 ? "снизу панели" : "сверху панели"));
                        })
                .show());
        form.addView(placement, rowParams());

        MaterialButton distribution = compactButton("Ширина элементов: "
                + (shortcut.informationGroupDistribution == 1
                ? "по содержимому" : "одинаковая"));
        distribution.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Распределение в ряду")
                .setItems(new String[]{"Одинаковая ширина", "По содержимому"},
                        (dialog, which) -> {
                            applyInformationGroupSetting(shortcut,
                                    value -> value.informationGroupDistribution = which);
                            distribution.setText("Ширина элементов: "
                                    + (which == 1 ? "по содержимому" : "одинаковая"));
                        })
                .show());
        form.addView(distribution, rowParams());

        MaterialButton horizontal = compactButton("Положение содержимого: "
                + horizontalAlignmentLabel(
                shortcut.informationGroupHorizontalAlignment));
        horizontal.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Положение ряда по горизонтали")
                .setItems(new String[]{"Слева", "По центру", "Справа"},
                        (dialog, which) -> {
                            applyInformationGroupSetting(shortcut, value ->
                                    value.informationGroupHorizontalAlignment = which);
                            horizontal.setText("Положение содержимого: "
                                    + horizontalAlignmentLabel(which));
                        })
                .show());
        form.addView(horizontal, rowParams());

        MaterialButton vertical = compactButton("Выравнивание элементов: "
                + verticalAlignmentLabel(shortcut.informationGroupVerticalAlignment));
        vertical.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Выравнивание элементов ряда")
                .setItems(new String[]{"Сверху", "По центру", "Снизу"},
                        (dialog, which) -> {
                            applyInformationGroupSetting(shortcut, value ->
                                    value.informationGroupVerticalAlignment = which);
                            vertical.setText("Выравнивание элементов: "
                                    + verticalAlignmentLabel(which));
                        })
                .show());
        form.addView(vertical, rowParams());

        spacingSlider(form, "Расстояние между информационными иконками", 120,
                shortcut.informationGroupGapPx, selected ->
                        applyInformationGroupSetting(shortcut,
                                value -> value.informationGroupGapPx = selected));

        addSwitch(form, "Показывать ряд только при подключении ANCS",
                shortcut.informationGroupAncsOnly, checked ->
                        applyInformationGroupSetting(shortcut,
                                value -> value.informationGroupAncsOnly = checked));

        title(form, "Внешние отступы ряда");
        groupSlider(form, shortcut, "Слева",
                shortcut.informationGroupMarginLeftPx,
                (value, selected) -> value.informationGroupMarginLeftPx = selected);
        groupSlider(form, shortcut, "Сверху",
                shortcut.informationGroupMarginTopPx,
                (value, selected) -> value.informationGroupMarginTopPx = selected);
        groupSlider(form, shortcut, "Справа",
                shortcut.informationGroupMarginRightPx,
                (value, selected) -> value.informationGroupMarginRightPx = selected);
        groupSlider(form, shortcut, "Снизу",
                shortcut.informationGroupMarginBottomPx,
                (value, selected) -> value.informationGroupMarginBottomPx = selected);

        title(form, "Внутренние отступы ряда");
        groupSlider(form, shortcut, "Слева",
                shortcut.informationGroupPaddingLeftPx,
                (value, selected) -> value.informationGroupPaddingLeftPx = selected);
        groupSlider(form, shortcut, "Сверху",
                shortcut.informationGroupPaddingTopPx,
                (value, selected) -> value.informationGroupPaddingTopPx = selected);
        groupSlider(form, shortcut, "Справа",
                shortcut.informationGroupPaddingRightPx,
                (value, selected) -> value.informationGroupPaddingRightPx = selected);
        groupSlider(form, shortcut, "Снизу",
                shortcut.informationGroupPaddingBottomPx,
                (value, selected) -> value.informationGroupPaddingBottomPx = selected);

        MaterialButton background = compactButton("Цвет фона ряда");
        AppleColorPickerDialog.decorateButton(background, "Цвет фона ряда",
                shortcut.informationGroupBackgroundColor);
        background.setOnClickListener(view -> AppleColorPickerDialog.show(this,
                "Фон горизонтального ряда",
                shortcut.informationGroupBackgroundColor,
                AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {
                        if (value == null) return;
                        applyInformationGroupSetting(shortcut, member ->
                                member.informationGroupBackgroundColor = value);
                        AppleColorPickerDialog.decorateButton(background,
                                "Цвет фона ряда", value);
                    }

                    @Override public void onSelected(@Nullable String value) {
                        if (value == null) return;
                        applyInformationGroupSetting(shortcut, member ->
                                member.informationGroupBackgroundColor = value);
                    }
                }));
        form.addView(background, rowParams());
        slider(form, "Скругление фона ряда", 0, 120,
                shortcut.informationGroupCornerRadiusPx, " px", selected ->
                        applyInformationGroupSetting(shortcut, value ->
                                value.informationGroupCornerRadiusPx = selected));

        new AlertDialog.Builder(this)
                .setTitle("Горизонтальный ряд · " + groupName)
                .setView(scroll)
                .setPositiveButton("Готово", null)
                .show();
    }

    private interface GroupIntSetter {
        void set(@NonNull LauncherShortcutStore.Shortcut shortcut, int value);
    }

    private void groupSlider(@NonNull LinearLayout form,
                             @NonNull LauncherShortcutStore.Shortcut shortcut,
                             @NonNull String label, int current,
                             @NonNull GroupIntSetter setter) {
        spacingSlider(form, label, 120, current, selected ->
                applyInformationGroupSetting(shortcut,
                        value -> setter.set(value, selected)));
    }

    private void applyInformationGroupSetting(
            @NonNull LauncherShortcutStore.Shortcut source,
            @NonNull ShortcutSetter setter) {
        setter.set(source);
        String group = source.informationGroup.trim();
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            if (value.kind == LauncherShortcutStore.Kind.INFO
                    && group.equals(value.informationGroup.trim())) {
                setter.set(value);
                store.upsert(value);
            }
        }
        refreshPreview();
        applyPanel();
    }

    @NonNull
    private List<LauncherShortcutStore.Shortcut> informationGroupMembers(
            @NonNull String rawGroup) {
        String group = rawGroup.trim();
        List<LauncherShortcutStore.Shortcut> result = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            if (value.kind == LauncherShortcutStore.Kind.INFO
                    && group.equals(value.informationGroup.trim())) result.add(value);
        }
        return result;
    }

    private static void copyInformationGroupSettings(
            @NonNull LauncherShortcutStore.Shortcut source,
            @NonNull LauncherShortcutStore.Shortcut target) {
        target.informationPlacement = source.informationPlacement;
        target.informationGroupGapPx = source.informationGroupGapPx;
        target.informationGroupAncsOnly = source.informationGroupAncsOnly;
        target.informationGroupMarginLeftPx = source.informationGroupMarginLeftPx;
        target.informationGroupMarginTopPx = source.informationGroupMarginTopPx;
        target.informationGroupMarginRightPx = source.informationGroupMarginRightPx;
        target.informationGroupMarginBottomPx = source.informationGroupMarginBottomPx;
        target.informationGroupPaddingLeftPx = source.informationGroupPaddingLeftPx;
        target.informationGroupPaddingTopPx = source.informationGroupPaddingTopPx;
        target.informationGroupPaddingRightPx = source.informationGroupPaddingRightPx;
        target.informationGroupPaddingBottomPx = source.informationGroupPaddingBottomPx;
        target.informationGroupHorizontalAlignment =
                source.informationGroupHorizontalAlignment;
        target.informationGroupVerticalAlignment =
                source.informationGroupVerticalAlignment;
        target.informationGroupDistribution = source.informationGroupDistribution;
        target.informationGroupBackgroundColor =
                source.informationGroupBackgroundColor;
        target.informationGroupCornerRadiusPx =
                source.informationGroupCornerRadiusPx;
    }

    private void editInformationAppearance(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(24));
        scroll.addView(form, new ScrollView.LayoutParams(match(), wrap()));

        MaterialButton text = compactButton("Текст подписи: " + shortcut.title);
        text.setOnClickListener(view -> {
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setText(shortcut.title);
            input.setSelection(input.length());
            new AlertDialog.Builder(this)
                    .setTitle("Текст информационной кнопки")
                    .setView(input)
                    .setPositiveButton("Применить", (dialog, which) -> {
                        String value = input.getText().toString().trim();
                        if (!value.isEmpty()) shortcut.title = value;
                        persistInformationAppearance(shortcut);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        });
        form.addView(text, rowParams());

        MaterialButton placement = compactButton("Расположение: "
                + (shortcut.informationPlacement == 1
                ? "снизу панели" : "сверху панели"));
        placement.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Расположение информационного элемента")
                .setItems(new String[]{"Сверху панели водителя",
                                "Снизу панели водителя"},
                        (dialog, which) -> {
                            shortcut.informationPlacement = which;
                            placement.setText("Расположение: "
                                    + (which == 1 ? "снизу панели" : "сверху панели"));
                            persistInformationAppearance(shortcut);
                        })
                .show());
        form.addView(placement, rowParams());

        boolean phoneCellular = StatusBarInformationCatalog.typeForTarget(shortcut.target)
                == BrickType.PHONE_CELLULAR;
        if (phoneCellular) {
            title(form, "Состав блока оператора и сигнала");
            addSwitch(form, "Показывать шкалу сигнала",
                    shortcut.informationPhoneCellularShowSignal, checked -> {
                        shortcut.informationPhoneCellularShowSignal = checked;
                        persistInformationAppearance(shortcut);
                    });
            addSwitch(form, "Показывать название оператора",
                    shortcut.informationPhoneCellularShowOperator, checked -> {
                        shortcut.informationPhoneCellularShowOperator = checked;
                        shortcut.informationShowValue = checked
                                || shortcut.informationPhoneCellularShowNetworkType;
                        persistInformationAppearance(shortcut);
                    });
            addSwitch(form, "Показывать тип сети (LTE/3G/5G)",
                    shortcut.informationPhoneCellularShowNetworkType, checked -> {
                        shortcut.informationPhoneCellularShowNetworkType = checked;
                        shortcut.informationShowValue = checked
                                || shortcut.informationPhoneCellularShowOperator;
                        persistInformationAppearance(shortcut);
                    });
        } else {
            addSwitch(form, "Показывать значение",
                    shortcut.informationShowValue, checked -> {
                        shortcut.informationShowValue = checked;
                        persistInformationAppearance(shortcut);
                    });
        }

        if (StatusBarInformationCatalog.typeForTarget(shortcut.target) != null) {
            addSwitch(form, "Иконка, цвет, обводка и бейдж как в статусной строке",
                    shortcut.informationUseStatusIconStyle, checked -> {
                        shortcut.informationUseStatusIconStyle = checked;
                        persistInformationAppearance(shortcut);
                    });
        }

        title(form, "Информационная иконка");
        slider(form, "Размер иконки", 12, 320,
                shortcut.informationIconSizePx, " px", value -> {
                    shortcut.informationIconSizePx = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Прозрачность иконки", 0, 255,
                shortcut.informationIconAlpha, "", value -> {
                    shortcut.informationIconAlpha = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Прозрачность обводки", 0, 255,
                shortcut.informationIconOutlineAlpha, "", value -> {
                    shortcut.informationIconOutlineAlpha = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Толщина обводки", 0, 24,
                shortcut.informationIconOutlineWidth, " px", value -> {
                    shortcut.informationIconOutlineWidth = value;
                    persistInformationAppearance(shortcut);
                });

        slider(form, "Размер подписи", 8, 72,
                shortcut.informationLabelTextSizeSp, " sp", value -> {
                    shortcut.informationLabelTextSizeSp = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Размер значения", 8, 96,
                shortcut.informationValueTextSizeSp, " sp", value -> {
                    shortcut.informationValueTextSizeSp = value;
                    persistInformationAppearance(shortcut);
                });

        MaterialButton font = compactButton("Шрифт: "
                + getString(Fonts.findByKey(shortcut.informationFontFamily).labelRes));
        font.setOnClickListener(view -> {
            String[] labels = new String[Fonts.ALL.size()];
            for (int index = 0; index < Fonts.ALL.size(); index++) {
                labels[index] = getString(Fonts.ALL.get(index).labelRes);
            }
            new AlertDialog.Builder(this)
                    .setTitle("Шрифт информационной кнопки")
                    .setItems(labels, (dialog, which) -> {
                        shortcut.informationFontFamily = Fonts.ALL.get(which).key;
                        font.setText("Шрифт: " + labels[which]);
                        persistInformationAppearance(shortcut);
                    })
                    .show();
        });
        form.addView(font, rowParams());

        addSwitch(form, "Жирный текст", shortcut.informationTextBold, checked -> {
            shortcut.informationTextBold = checked;
            persistInformationAppearance(shortcut);
        });
        addSwitch(form, "Курсив", shortcut.informationTextItalic, checked -> {
            shortcut.informationTextItalic = checked;
            persistInformationAppearance(shortcut);
        });

        MaterialButton horizontal = compactButton("По горизонтали: "
                + horizontalAlignmentLabel(shortcut.informationHorizontalAlignment));
        horizontal.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Выравнивание по горизонтали")
                .setItems(new String[]{"Слева", "По центру", "Справа"},
                        (dialog, which) -> {
                            shortcut.informationHorizontalAlignment = which;
                            horizontal.setText("По горизонтали: "
                                    + horizontalAlignmentLabel(which));
                            persistInformationAppearance(shortcut);
                        })
                .show());
        form.addView(horizontal, rowParams());

        MaterialButton vertical = compactButton("По вертикали: "
                + verticalAlignmentLabel(shortcut.informationVerticalAlignment));
        vertical.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Выравнивание по вертикали")
                .setItems(new String[]{"Сверху", "По центру", "Снизу"},
                        (dialog, which) -> {
                            shortcut.informationVerticalAlignment = which;
                            vertical.setText("По вертикали: "
                                    + verticalAlignmentLabel(which));
                            persistInformationAppearance(shortcut);
                        })
                .show());
        form.addView(vertical, rowParams());

        title(form, "Отступы содержимого");
        spacingSlider(form, "Слева", 96, shortcut.informationPaddingLeftPx,
                value -> {
                    shortcut.informationPaddingLeftPx = value;
                    persistInformationAppearance(shortcut);
                });
        spacingSlider(form, "Сверху", 96, shortcut.informationPaddingTopPx,
                value -> {
                    shortcut.informationPaddingTopPx = value;
                    persistInformationAppearance(shortcut);
                });
        spacingSlider(form, "Справа", 96, shortcut.informationPaddingRightPx,
                value -> {
                    shortcut.informationPaddingRightPx = value;
                    persistInformationAppearance(shortcut);
                });
        spacingSlider(form, "Снизу", 96, shortcut.informationPaddingBottomPx,
                value -> {
                    shortcut.informationPaddingBottomPx = value;
                    persistInformationAppearance(shortcut);
                });

        new AlertDialog.Builder(this)
                .setTitle("Информационная кнопка")
                .setView(scroll)
                .setPositiveButton("Готово", null)
                .show();
    }

    private void persistInformationAppearance(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        store.upsert(shortcut);
        refreshPreview();
        applyPanel();
    }

    @NonNull
    private static String horizontalAlignmentLabel(int value) {
        return value <= 0 ? "слева" : value == 1 ? "по центру" : "справа";
    }

    @NonNull
    private static String verticalAlignmentLabel(int value) {
        return value <= 0 ? "сверху" : value == 1 ? "по центру" : "снизу";
    }

    @NonNull
    private static String informationGroupLabel(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        return shortcut.informationGroup.trim().isEmpty()
                ? "Группа: отдельная строка"
                : "Группа: " + shortcut.informationGroup.trim();
    }

    private void chooseIcon(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        VectorIconPickerDialog.Option live = VectorIconPickerDialog.option("live_climate",
                "Живая иконка климата", R.drawable.ic_car_climate);
        VectorIconPickerDialog.Option none = VectorIconPickerDialog.option("none",
                "Без иконки", R.drawable.ic_delete);
        boolean interactive = LauncherShortcutStore.isInteractive(shortcut);
        List<VectorIconPickerDialog.Option> options;
        if (interactive) {
            options = "none".equalsIgnoreCase(shortcut.icon)
                    ? VectorIconPickerDialog.withFirst(live, none)
                    : VectorIconPickerDialog.withFirst(live);
        } else {
            options = "none".equalsIgnoreCase(shortcut.icon)
                    ? VectorIconPickerDialog.withFirst(none)
                    : VectorIconPickerDialog.catalog();
        }
        String selected = interactive && shortcut.liveClimateIcon ? live.key : shortcut.icon;
        VectorIconPickerDialog.show(this, "Иконка · " + shortcut.title, options, selected,
                option -> {
                    if (live.key.equals(option.key)) {
                        shortcut.liveClimateIcon = true;
                        shortcut.iconSizePx = Math.max(shortcut.iconSizePx, 76);
                    } else {
                        shortcut.liveClimateIcon = false;
                        shortcut.icon = option.key;
                        shortcut.iconCustomized = true;
                    }
                    store.upsert(shortcut);
                    refreshButtons();
                    applyPanel();
                });
    }

    private void loadApplications() {
        if (cachedApps != null) return;
        catalogExecutor.execute(() -> {
            List<InstalledAppCatalog.App> loaded = InstalledAppCatalog.load(this);
            runOnUiThread(() -> cachedApps = loaded);
        });
    }

    private void refreshPreview() {
        if (preview == null) return;
        int width = preview.getWidth();
        int height = preview.getHeight();
        if (width <= 0 || height <= 0) {
            preview.post(this::refreshPreview);
            return;
        }
        preview.removeAllViews();
        List<LauncherShortcutStore.Shortcut> enabled = new ArrayList<>();
        int interactiveCount = 0;
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            if (!value.enabled) continue;
            if (LauncherShortcutStore.isInteractive(value)) {
                if (interactiveCount >= DriverPanelLayoutPolicy.MAX_BUTTONS) continue;
                interactiveCount++;
            }
            enabled.add(value);
        }
        int minimumPanelWidth = DriverPanelLayoutPolicy.referencePanelWidth(
                profile.style == Preferences.DriverPanelStyle.NEW);
        int railWidth = Math.max(dp(52), Math.min(dp(100),
                Math.round(Math.max(minimumPanelWidth, profile.widthPx.get()) * .62f)));
        int side = profile.side.get();
        int scaledTop = Math.round(profile.topPaddingPx.get()
                * height / 1080f);
        int scaledBottom = Math.round(profile.bottomPaddingPx.get()
                * height / 1080f);
        DriverPanelLayoutPolicy.Layout layout = DriverPanelLayoutPolicy.calculate(
                height, scaledTop, scaledBottom, interactiveCount,
                false);
        addPreviewSegment(enabled, railWidth, height, layout, side);
    }

    private void addPreviewSegment(List<LauncherShortcutStore.Shortcut> values, int width,
                                   int screenHeight,
                                   @NonNull DriverPanelLayoutPolicy.Layout layout,
                                   int side) {
        if (screenHeight <= 0) return;
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(0, layout.contentTop, 0,
                Math.max(0, screenHeight - layout.contentBottom));
        GradientDrawable background = new GradientDrawable();
        background.setColor(parseColor(profile.backgroundColor.get(),
                0xFF13171C) | 0xFF000000);
        float radius = Math.max(dp(20), profile.cornerRadiusPx.get()) * .62f;
        background.setCornerRadii(panelCornerRadii(radius, side == 1));
        int borderWidth = Math.max(0, profile.borderWidthPx.get());
        if (borderWidth > 0) {
            background.setStroke(Math.max(1, Math.round(borderWidth * .62f)),
                    parseColor(profile.borderColor.get(), Color.TRANSPARENT));
        }
        rail.setBackground(background);
        List<LauncherShortcutStore.Shortcut> topInformation = new ArrayList<>();
        List<LauncherShortcutStore.Shortcut> controls = new ArrayList<>();
        List<LauncherShortcutStore.Shortcut> bottomInformation = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut value : values) {
            if (value.kind == LauncherShortcutStore.Kind.INFO) {
                (value.informationPlacement == 1
                        ? bottomInformation : topInformation).add(value);
            } else {
                controls.add(value);
            }
        }
        addPreviewInformationRows(rail, topInformation, width);
        LinearLayout controlHost = new LinearLayout(this);
        controlHost.setOrientation(LinearLayout.VERTICAL);
        controlHost.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        List<FrameLayout> cells = new ArrayList<>();
        int[] naturalHeights = new int[controls.size()];
        int[] requestedTop = new int[controls.size()];
        int[] requestedBottom = new int[controls.size()];
        int[] configuredHeights = new int[controls.size()];
        for (int index = 0; index < controls.size(); index++) {
            LauncherShortcutStore.Shortcut value = controls.get(index);
            FrameLayout cell = previewShortcutCell(value, width);
            cell.setMinimumHeight(0);
            cell.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            cells.add(cell);
            int configuredHeight = value.kind == LauncherShortcutStore.Kind.DIVIDER
                    ? 0 : value.buttonHeightPx;
            configuredHeights[index] = configuredHeight;
            naturalHeights[index] = DriverButtonHeightPolicy.resolvedHeight(
                    Math.max(1, cell.getMeasuredHeight()), configuredHeight, .62f);
            int top = value.gapBeforePx < 0 ? -1
                    : Math.round(value.gapBeforePx * .62f);
            int bottom = value.gapAfterPx < 0 ? -1
                    : Math.round(value.gapAfterPx * .62f);
            requestedTop[index] = DriverButtonHeightPolicy.spacingRequest(
                    configuredHeight, top);
            requestedBottom[index] = DriverButtonHeightPolicy.spacingRequest(
                    configuredHeight, bottom);
            controlHost.addView(cell, new LinearLayout.LayoutParams(match(), wrap()));
        }
        if (!controls.isEmpty()) {
            rail.addView(controlHost, new LinearLayout.LayoutParams(match(), 0, 1f));
            // Resolve against the real remaining preview height after the information rows have
            // been measured. This is the same policy used by the live overlay, so editing one
            // button can no longer collapse the complete rail only in one of the two surfaces.
            controlHost.post(() -> {
                if (controlHost.getParent() != rail || controlHost.getHeight() <= 0) return;
                DriverControlSpacingPolicy.Layout spacing =
                        DriverControlSpacingPolicy.resolve(controlHost.getHeight(),
                                naturalHeights, requestedTop, requestedBottom);
                for (int index = 0; index < cells.size(); index++) {
                    FrameLayout cell = cells.get(index);
                    LauncherShortcutStore.Shortcut value = controls.get(index);
                    int height = naturalHeights[index];
                    int top = DriverButtonHeightPolicy.internalPadding(
                            configuredHeights[index], value.gapBeforePx,
                            spacing.topPadding[index], .62f, height);
                    int bottom = DriverButtonHeightPolicy.internalPadding(
                            configuredHeights[index], value.gapAfterPx,
                            spacing.bottomPadding[index], .62f, height);
                    cell.setPadding(cell.getPaddingLeft(), top,
                            cell.getPaddingRight(), bottom);
                    if (DriverButtonHeightPolicy.isExplicit(configuredHeights[index])) {
                        ViewGroup.LayoutParams params = cell.getLayoutParams();
                        params.height = height;
                        cell.setLayoutParams(params);
                    }
                }
            });
        }
        addPreviewInformationRows(rail, bottomInformation, width);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, screenHeight,
                Gravity.TOP | (side == 0 ? Gravity.LEFT : Gravity.RIGHT));
        preview.addView(rail, params);
    }

    private void addPreviewInformationRows(
            @NonNull LinearLayout rail,
            @NonNull List<LauncherShortcutStore.Shortcut> information,
            int railWidth) {
        LinkedHashMap<String, List<LauncherShortcutStore.Shortcut>> rows =
                new LinkedHashMap<>();
        for (LauncherShortcutStore.Shortcut value : information) {
            String group = value.informationGroup.trim();
            String key = group.isEmpty() ? "\u0000" + value.id : group;
            rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        for (Map.Entry<String, List<LauncherShortcutStore.Shortcut>> entry : rows.entrySet()) {
            List<LauncherShortcutStore.Shortcut> items = entry.getValue();
            LauncherShortcutStore.Shortcut style = items.get(0);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(previewGroupGravity(style));
            row.setPadding(Math.round(style.informationGroupPaddingLeftPx * .62f),
                    Math.round(style.informationGroupPaddingTopPx * .62f),
                    Math.round(style.informationGroupPaddingRightPx * .62f),
                    Math.round(style.informationGroupPaddingBottomPx * .62f));
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(parseColor(style.informationGroupBackgroundColor,
                    Color.TRANSPARENT));
            surface.setCornerRadius(Math.round(
                    style.informationGroupCornerRadiusPx * .62f));
            row.setBackground(surface);
            int gap = Math.round(style.informationGroupGapPx * .62f);
            int contentHeight = 1;
            for (LauncherShortcutStore.Shortcut item : items) {
                contentHeight = Math.max(contentHeight,
                        previewInformationTileHeight(item));
            }
            int rowHeight = contentHeight
                    + Math.round((style.informationGroupPaddingTopPx
                    + style.informationGroupPaddingBottomPx) * .62f);
            int count = items.size();
            int availableTilesWidth = Math.max(count,
                    railWidth
                            - Math.round((style.informationGroupMarginLeftPx
                            + style.informationGroupMarginRightPx
                            + style.informationGroupPaddingLeftPx
                            + style.informationGroupPaddingRightPx) * .62f)
                            - Math.max(0, count - 1) * gap);
            int equalWidth = 1;
            if (style.informationGroupDistribution == 0) {
                for (LauncherShortcutStore.Shortcut item : items) {
                    equalWidth = Math.max(equalWidth,
                            previewInformationTileWidth(item));
                }
            }
            int[] requestedWidths = new int[count];
            int requestedTotal = 0;
            for (int index = 0; index < count; index++) {
                requestedWidths[index] = style.informationGroupDistribution == 1
                        ? previewInformationTileWidth(items.get(index)) : equalWidth;
                requestedTotal += requestedWidths[index];
            }
            float widthScale = requestedTotal <= availableTilesWidth ? 1f
                    : availableTilesWidth / (float) requestedTotal;
            for (int index = 0; index < items.size(); index++) {
                FrameLayout cell = previewShortcutCell(items.get(index), railWidth);
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        Math.max(1, Math.round(requestedWidths[index] * widthScale)), match());
                cellParams.rightMargin = index + 1 < items.size() ? gap : 0;
                row.addView(cell, cellParams);
            }
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(match(), rowHeight);
            rowParams.leftMargin = Math.round(style.informationGroupMarginLeftPx * .62f);
            rowParams.topMargin = Math.round(style.informationGroupMarginTopPx * .62f);
            rowParams.rightMargin = Math.round(style.informationGroupMarginRightPx * .62f);
            rowParams.bottomMargin =
                    Math.round(style.informationGroupMarginBottomPx * .62f);
            rail.addView(row, rowParams);
        }
    }

    @NonNull
    private FrameLayout previewShortcutCell(
            @NonNull LauncherShortcutStore.Shortcut value, int railWidth) {
        if (value.kind == LauncherShortcutStore.Kind.INFO) {
            LauncherShortcutStore.Shortcut scaled = value.copy();
            scaled.informationIconSizePx = Math.max(1,
                    Math.round(value.informationIconSizePx * .62f));
            scaled.informationIconOutlineWidth = Math.max(0,
                    Math.round(value.informationIconOutlineWidth * .62f));
            scaled.informationLabelTextSizeSp = Math.max(1,
                    Math.round(value.informationLabelTextSizeSp * .62f));
            scaled.informationValueTextSizeSp = Math.max(1,
                    Math.round(value.informationValueTextSizeSp * .62f));
            scaled.informationPaddingLeftPx = Math.round(
                    value.informationPaddingLeftPx * .62f);
            scaled.informationPaddingTopPx = Math.round(
                    value.informationPaddingTopPx * .62f);
            scaled.informationPaddingRightPx = Math.round(
                    value.informationPaddingRightPx * .62f);
            scaled.informationPaddingBottomPx = Math.round(
                    value.informationPaddingBottomPx * .62f);
            FrameLayout cell = new FrameLayout(this);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            InformationShortcutView information = new InformationShortcutView(
                    this, preferences, scaled);
            cell.addView(information, new FrameLayout.LayoutParams(match(), match()));
            return cell;
        }

        View icon;
        if (isLiveClimate(value)) {
            DriverClimateShortcutView climate = new DriverClimateShortcutView(
                    this, CarIntegrations.get(this), value.iconColor,
                    value.extendedClimateInfo,
                    Math.round(value.climateDetailsGapPx * .62f));
            climate.showPreviewSample();
            icon = climate;
        } else {
            ImageView image = new ImageView(this);
            Drawable drawable = resolveIcon(value);
            if (drawable != null) image.setImageDrawable(drawable);
            icon = image;
        }
        int requestedIconSize = value.iconSizePx;
        int iconSize = Math.max(1, Math.min(railWidth,
                Math.round(requestedIconSize * .62f)));
        DriverExactCenterFrameLayout cell = new DriverExactCenterFrameLayout(this);
        cell.addExactlyCentered(icon, iconSize,
                DriverPanelLayoutPolicy.shortcutIconHeight(iconSize, false));
        return cell;
    }

    private int previewInformationTileHeight(
            @NonNull LauncherShortcutStore.Shortcut value) {
        return DriverInformationTileLayoutPolicy.naturalHeight(this, value, .62f);
    }

    private int previewInformationTileWidth(
            @NonNull LauncherShortcutStore.Shortcut value) {
        return DriverInformationTileLayoutPolicy.naturalWidth(this, value, .62f);
    }

    private static int previewGroupGravity(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        int horizontal = shortcut.informationGroupHorizontalAlignment <= 0
                ? Gravity.START : shortcut.informationGroupHorizontalAlignment == 1
                ? Gravity.CENTER_HORIZONTAL : Gravity.END;
        int vertical = shortcut.informationGroupVerticalAlignment <= 0
                ? Gravity.TOP : shortcut.informationGroupVerticalAlignment == 1
                ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM;
        return horizontal | vertical;
    }

    @Nullable
    private Drawable resolveIcon(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        if ("none".equalsIgnoreCase(shortcut.icon)) return null;
        if (shortcut.kind == LauncherShortcutStore.Kind.APP
                && "app".equals(shortcut.icon)) {
            ComponentName component = ComponentName.unflattenFromString(shortcut.target);
            if (component != null) return HighResolutionAppIconLoader.load(this, component);
        }
        return LauncherIconResolver.resolve(this, shortcut);
    }

    @NonNull
    private static String shortcutType(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        switch (shortcut.kind) {
            case APP:
                return "Приложение · " + shortcut.packageName;
            case CAR:
                return "Функция автомобиля";
            case RULE:
                return "Сценарий";
            case PHONE:
                return "Телефон · " + shortcut.target;
            case INTENT:
                return "Системное действие";
            case INFO:
                return "Информация · без нажатия";
            case DIVIDER:
                return "Разделитель";
            case BUILTIN:
            default:
                return "Функция панели · "
                        + LauncherShortcutStore.Builtin.fromKey(shortcut.target).label;
        }
    }

    private static boolean isLiveClimate(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        return shortcut.liveClimateIcon
                && LauncherShortcutStore.isInteractive(shortcut);
    }

    @NonNull
    private static String longActionLabel(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        if (!shortcut.hasLongAction) return "не назначено";
        switch (shortcut.longKind) {
            case APP:
                return "приложение";
            case CAR:
                return "автомобиль";
            case RULE:
                return "умный дом";
            case PHONE:
                return "телефон";
            case INTENT:
                return "Intent";
            case INFO:
                return "информация";
            case DIVIDER:
                return "разделитель";
            case BUILTIN:
            default:
                return LauncherShortcutStore.Builtin.fromKey(shortcut.longTarget).label;
        }
    }

    private void applyPanel() {
        if (preferences.driverPanelEnabled.get()) {
            // Also restores the headless connector/scenario host when the status row itself is
            // disabled. The same reconciliation starts the rail on its ECARX window tier.
            AppRuntimeBootstrap.reconcileServices(this, preferences);
        } else {
            DriverPanelService.stop(this);
            WidgetService running = WidgetService.getInstance();
            if (running != null && !preferences.widgetEnabled.get()) {
                running.applyPreferences();
            }
        }
    }

    private void limitToast() {
        Toast.makeText(this, "На панели можно разместить не более 10 кнопок",
                Toast.LENGTH_SHORT).show();
    }

    private MaterialSwitch addSwitch(LinearLayout host, String label, boolean checked,
                                     java.util.function.Consumer<Boolean> listener) {
        MaterialSwitch value = new MaterialSwitch(this);
        value.setText(label);
        value.setTextColor(Color.WHITE);
        value.setTextSize(16);
        value.setChecked(checked);
        value.setMinHeight(dp(52));
        value.setOnCheckedChangeListener((button, isChecked) -> listener.accept(isChecked));
        host.addView(value, rowParams());
        return value;
    }

    private void slider(LinearLayout host, String label, int min, int max, int current,
                        String suffix, IntSetter setter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label + ": " + current + suffix, 15, 0xFFC7C7CC);
        block.addView(title);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int selected = current;
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                selected = min + progress;
                title.setText(label + ": " + selected + suffix);
                setter.set(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        host.addView(block, rowParams());
    }

    /** Exact physical control height; zero delegates the remaining-height calculation to rail. */
    private void driverButtonHeightSlider(@NonNull LinearLayout host,
                                          @NonNull LauncherShortcutStore.Shortcut shortcut) {
        TextView value = text(shortcut.buttonHeightPx <= 0
                        ? "Высота кнопки: авто"
                        : "Высота кнопки: " + shortcut.buttonHeightPx + " px",
                13, 0xFFC7C7CC);
        host.addView(value, topMargin(dp(6)));
        SeekBar seek = new SeekBar(this);
        int selectable = LauncherShortcutStore.MAX_DRIVER_BUTTON_HEIGHT_PX
                - LauncherShortcutStore.MIN_DRIVER_BUTTON_HEIGHT_PX + 1;
        seek.setMax(selectable);
        seek.setProgress(shortcut.buttonHeightPx <= 0 ? 0
                : shortcut.buttonHeightPx
                - LauncherShortcutStore.MIN_DRIVER_BUTTON_HEIGHT_PX + 1);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int selected = shortcut.buttonHeightPx;

            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                selected = progress == 0 ? 0
                        : LauncherShortcutStore.MIN_DRIVER_BUTTON_HEIGHT_PX + progress - 1;
                value.setText(selected <= 0 ? "Высота кнопки: авто"
                        : "Высота кнопки: " + selected + " px");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                shortcut.buttonHeightPx = selected;
                store.upsert(shortcut);
                refreshPreview();
                applyPanel();
            }
        });
        host.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
    }

    /** Per-control internal padding. -1 keeps the legacy evenly distributed panel layout. */
    private void buttonGapSlider(@NonNull LinearLayout host,
                                 @NonNull LauncherShortcutStore.Shortcut shortcut,
                                 boolean before) {
        String label = before
                ? "Внутренний отступ сверху"
                : "Внутренний отступ снизу";
        int current = before ? shortcut.gapBeforePx : shortcut.gapAfterPx;
        TextView value = text(current < 0
                        ? label + ": авто (равномерный режим)"
                        : label + ": " + current + " px",
                13, 0xFFC7C7CC);
        host.addView(value, topMargin(dp(6)));
        SeekBar seek = new SeekBar(this);
        seek.setMax(81);
        seek.setProgress(current + 1);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int selected = current;

            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                selected = progress - 1;
                value.setText(selected < 0
                        ? label + ": авто (равномерный режим)"
                        : label + ": " + selected + " px");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (before) {
                    shortcut.gapBeforePx = selected;
                } else {
                    shortcut.gapAfterPx = selected;
                }
                store.upsert(shortcut);
                refreshPreview();
                applyPanel();
            }
        });
        host.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
    }

    /** Four-side spacing control with a dedicated zero action usable on a car touchscreen. */
    private void spacingSlider(LinearLayout host, String label, int max, int current,
                               IntSetter setter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label + ": " + current + " px", 15, 0xFFC7C7CC);
        header.addView(title, new LinearLayout.LayoutParams(0, wrap(), 1f));
        MaterialButton zero = compactButton("0");
        zero.setContentDescription(label + ": убрать отступ");
        header.addView(zero, new LinearLayout.LayoutParams(dp(56), dp(40)));
        block.addView(header);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(Math.max(0, Math.min(max, current)));
        zero.setEnabled(seek.getProgress() != 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                title.setText(label + ": " + progress + " px");
                zero.setEnabled(progress != 0);
                setter.set(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        zero.setOnClickListener(view -> seek.setProgress(0));
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        host.addView(block, rowParams());
    }

    private void title(LinearLayout host, String value) {
        TextView title = text(value, 23, Color.WHITE);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = rowParams();
        params.topMargin = dp(14);
        host.addView(title, params);
    }

    private TextView hint(LinearLayout host, String value) {
        TextView hint = text(value, 13, 0xFF8E8E93);
        host.addView(hint, rowParams());
        return hint;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private MaterialButton button(String value) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setCornerRadius(dp(14));
        return button;
    }

    private MaterialButton compactButton(String value) {
        MaterialButton button = button(value);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = margin;
        return params;
    }

    private int parseColor(String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return fallback; }
    }

    private static float[] panelCornerRadii(float radius, boolean panelOnRight) {
        float r = Math.max(0f, radius);
        return panelOnRight
                ? new float[]{r, r, 0f, 0f, 0f, 0f, r, r}
                : new float[]{0f, 0f, r, r, r, r, 0f, 0f};
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
