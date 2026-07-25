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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.driver.DriverPanelLayoutPolicy;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.driver.DriverClimateShortcutView;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.InstalledAppCatalog;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.ShortcutActionPicker;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Visual editor for the unified current-generation Monjaro driver panel. */
public final class DriverPanelSettingsActivity extends AppCompatActivity {
    private interface IntSetter { void set(int value); }

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
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(10), 0, dp(22), dp(28));
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
            store.move(shortcut.id, -1);
            refreshButtons();
            applyPanel();
        });
        header.addView(up, new LinearLayout.LayoutParams(dp(52), dp(46)));
        MaterialButton down = compactButton("↓");
        down.setEnabled(index < total - 1);
        down.setOnClickListener(view -> {
            store.move(shortcut.id, 1);
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

        TextView gapValue = text(shortcut.gapAfterPx < 0
                        ? "Отступ после кнопки: общий"
                        : "Отступ после кнопки: " + shortcut.gapAfterPx + " px",
                13, 0xFFC7C7CC);
        body.addView(gapValue, topMargin(dp(6)));
        SeekBar buttonGap = new SeekBar(this);
        buttonGap.setMax(81);
        buttonGap.setProgress(shortcut.gapAfterPx + 1);
        buttonGap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int selected = shortcut.gapAfterPx;

            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                selected = progress - 1;
                gapValue.setText(selected < 0
                        ? "Отступ после кнопки: общий"
                        : "Отступ после кнопки: " + selected + " px");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                shortcut.gapAfterPx = selected;
                store.upsert(shortcut);
                refreshPreview();
                applyPanel();
            }
        });
        body.addView(buttonGap, new LinearLayout.LayoutParams(match(), dp(42)));

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
        boolean liveClimate = isStockClimate(shortcut);
        MaterialButton chooseIcon = compactButton(liveClimate ? "Живая" : "Иконка");
        chooseIcon.setEnabled(!liveClimate);
        if (!liveClimate) chooseIcon.setOnClickListener(view -> chooseIcon(shortcut));
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
                    compactButton("Текст, шрифт, выравнивание и отступы");
            informationAppearance.setOnClickListener(
                    view -> editInformationAppearance(shortcut));
            body.addView(informationAppearance, topMargin(dp(8)));
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
        slider(form, "Слева", 0, 96, shortcut.informationPaddingLeftPx,
                " px", value -> {
                    shortcut.informationPaddingLeftPx = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Сверху", 0, 96, shortcut.informationPaddingTopPx,
                " px", value -> {
                    shortcut.informationPaddingTopPx = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Справа", 0, 96, shortcut.informationPaddingRightPx,
                " px", value -> {
                    shortcut.informationPaddingRightPx = value;
                    persistInformationAppearance(shortcut);
                });
        slider(form, "Снизу", 0, 96, shortcut.informationPaddingBottomPx,
                " px", value -> {
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

    private void chooseIcon(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        List<LauncherIconResolver.Preset> presets = LauncherIconResolver.presets();
        String[] labels = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) labels[i] = presets.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Иконка · " + shortcut.title)
                .setItems(labels, (dialog, which) -> {
                    LauncherIconResolver.Preset preset = presets.get(which);
                    shortcut.icon = preset.key;
                    shortcut.iconCustomized = true;
                    store.upsert(shortcut);
                    refreshButtons();
                    applyPanel();
                })
                .setNegativeButton("Отмена", null)
                .show();
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
        for (LauncherShortcutStore.Shortcut value : store.all()) {
            if (value.enabled && enabled.size() < DriverPanelLayoutPolicy.MAX_BUTTONS) {
                enabled.add(value);
            }
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
                height, scaledTop, scaledBottom, enabled.size(),
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
        rail.setBackground(background);
        for (LauncherShortcutStore.Shortcut value : values) {
            View icon;
            if (value.kind == LauncherShortcutStore.Kind.BUILTIN
                    && LauncherShortcutStore.Builtin.STOCK_CLIMATE.key.equals(value.target)) {
                DriverClimateShortcutView climate = new DriverClimateShortcutView(
                        this, CarIntegrations.get(this), value.iconColor, true);
                climate.showPreviewSample();
                icon = climate;
            } else {
                ImageView image = new ImageView(this);
                Drawable drawable = resolveIcon(value);
                if (drawable != null) image.setImageDrawable(drawable);
                icon = image;
            }
            int iconSize = Math.max(dp(18), Math.min(width - dp(8),
                    Math.round(value.iconSizePx * .62f)));
            FrameLayout cell = new FrameLayout(this);
            boolean expandedClimate = false;
            cell.addView(icon, new FrameLayout.LayoutParams(iconSize,
                    DriverPanelLayoutPolicy.shortcutIconHeight(iconSize, expandedClimate),
                    Gravity.CENTER));
            rail.addView(cell, new LinearLayout.LayoutParams(match(), 0,
                    DriverPanelLayoutPolicy.shortcutWeight(expandedClimate)));
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, screenHeight,
                Gravity.TOP | (side == 0 ? Gravity.LEFT : Gravity.RIGHT));
        preview.addView(rail, params);
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

    private static boolean isStockClimate(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        return shortcut.kind == LauncherShortcutStore.Kind.BUILTIN
                && LauncherShortcutStore.Builtin.STOCK_CLIMATE.key.equals(shortcut.target);
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
