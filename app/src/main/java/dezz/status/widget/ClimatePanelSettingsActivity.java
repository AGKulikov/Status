/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.climate.ClimatePanelConfig;
import dezz.status.widget.launcher.climate.ClimatePanelConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelView;
import dezz.status.widget.climate.ClimatePanelService;
import dezz.status.widget.shell.PrivilegedShell;

/** Code-free, immediate editor for the HOME and always-on climate surfaces. */
public final class ClimatePanelSettingsActivity extends AppCompatActivity {
    private interface BoolChange { void set(boolean value); }
    private interface IntChange { void set(int value); }
    private interface ColorChange { void set(@NonNull String value); }

    private static final String[] COLOR_VALUES = {
            "#35B7FF", "#00C853", "#FFB300", "#FF5A5F", "#9C6BFF",
            "#FFFFFF", "#B7C1CE", "#141A24", "#000000"
    };
    private static final String[] COLOR_LABELS = {
            "Голубой", "Зелёный", "Янтарный", "Красный", "Фиолетовый",
            "Белый", "Серо-голубой", "Графитовый", "Чёрный"
    };

    private Preferences preferences;
    private ClimatePanelConfigStore store;
    private ClimatePanelConfig config;
    private ClimatePanelView preview;
    private LinearLayout elementHost;
    private LinearLayout compactModeSettings;
    private LinearLayout reservedModeSettings;
    private MaterialSwitch permanentPanelSwitch;
    private TextView compactPositionInfo;
    private TextView runtimeStatusInfo;
    private boolean overlayPermissionRequestInFlight;
    private boolean liveUpdatePosted;
    private final Runnable liveUpdate = () -> {
        liveUpdatePosted = false;
        persistAndPreview();
    };
    private final Runnable runtimeStatusRefresh = new Runnable() {
        @Override public void run() {
            if (runtimeStatusInfo == null) return;
            String detail = ClimatePanelService.getRuntimeDetail();
            if (detail == null || detail.trim().isEmpty()) {
                detail = preferences != null && preferences.climatePanelEnabled.get()
                        ? "Ожидание запуска" : "Панель выключена";
            }
            runtimeStatusInfo.setText("Состояние: " + detail);
            runtimeStatusInfo.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new ClimatePanelConfigStore(preferences);
        config = store.load();
        setTitle("Климатическая панель");
        setContentView(buildContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (preview != null) preview.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The compact button can be dragged while this screen is not in the foreground. Always
        // show the persisted coordinates rather than the values captured during onCreate().
        updateCompactPositionInfo();
        if (preferences.climatePanelEnabled.get() && Settings.canDrawOverlays(this)) {
            overlayPermissionRequestInFlight = false;
            applyClimatePanel();
        }
        if (runtimeStatusInfo != null) {
            runtimeStatusInfo.removeCallbacks(runtimeStatusRefresh);
            runtimeStatusInfo.post(runtimeStatusRefresh);
        }
    }

    @Override
    protected void onPause() {
        if (runtimeStatusInfo != null) {
            runtimeStatusInfo.removeCallbacks(runtimeStatusRefresh);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        flushLiveUpdate();
        if (preview != null) preview.stop();
        super.onStop();
    }

    @NonNull
    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(10), 0, dp(22), dp(28));
        settingsScroll.addView(settings, new ScrollView.LayoutParams(match(), wrap()));

        addTitle(settings, "Панель климата");
        addHint(settings, "Все изменения сохраняются сразу. Справа показан живой вид панели с текущими значениями автомобиля.");

        addTitle(settings, "Постоянная панель в приложениях");
        addHint(settings, "Работает независимо от основного виджета и HOME. Выберите маленькую плавающую кнопку или постоянно занятую полосу экрана.");
        permanentPanelSwitch = addSwitch(settings, "Включить постоянную климатическую панель",
                preferences.climatePanelEnabled.get(), value -> {
                    preferences.climatePanelEnabled.set(value);
                    if (value && !Settings.canDrawOverlays(this)) {
                        requestOverlayPermission();
                    } else {
                        applyClimatePanel();
                    }
                });
        runtimeStatusInfo = new TextView(this);
        runtimeStatusInfo.setTextSize(14);
        runtimeStatusInfo.setAlpha(.82f);
        settings.addView(runtimeStatusInfo, new LinearLayout.LayoutParams(match(), dp(34)));

        TextView modeTitle = settingLabel("Режим отображения");
        settings.addView(modeTitle, new LinearLayout.LayoutParams(match(), wrap()));
        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(RadioGroup.VERTICAL);
        RadioButton compact = modeButton("Оверлей с маленькой кнопкой");
        RadioButton reserved = modeButton("Закреплённая панель с резервированием места");
        compact.setId(View.generateViewId());
        reserved.setId(View.generateViewId());
        mode.addView(compact, new RadioGroup.LayoutParams(match(), dp(44)));
        mode.addView(reserved, new RadioGroup.LayoutParams(match(), dp(44)));
        mode.check(preferences.climatePanelMode.get() == 1 ? reserved.getId() : compact.getId());
        mode.setOnCheckedChangeListener((group, checkedId) -> {
            int selected = checkedId == reserved.getId() ? 1 : 0;
            preferences.climatePanelMode.set(selected);
            updateModeSettings(selected);
            applyClimatePanel();
        });
        settings.addView(mode, new LinearLayout.LayoutParams(match(), wrap()));

        addSlider(settings, "Экран (Display ID)", preferences.climatePanelDisplayId.get(), 0, 7,
                value -> preferences.climatePanelDisplayId.set(value),
                value -> Integer.toString(value));
        addHint(settings, "Обычно основной дисплей — 0. На некоторых магнитолах центральный экран может иметь другой ID.");

        compactModeSettings = new LinearLayout(this);
        compactModeSettings.setOrientation(LinearLayout.VERTICAL);
        addTitle(compactModeSettings, "Оверлей с маленькой кнопкой");
        addHint(compactModeSettings, "Кнопка остаётся поверх приложений. Нажмите её, чтобы открыть климатическую панель заданного размера.");
        addSlider(compactModeSettings, "Ширина раскрытой панели",
                preferences.climateOverlayWidth.get(), 320, 2560,
                value -> preferences.climateOverlayWidth.set(value), value -> value + " px");
        addSlider(compactModeSettings, "Высота раскрытой панели",
                preferences.climateOverlayHeight.get(), 160, 1200,
                value -> preferences.climateOverlayHeight.set(value), value -> value + " px");
        addSlider(compactModeSettings, "Размер маленькой кнопки",
                preferences.climateButtonSize.get(), 48, 180,
                value -> preferences.climateButtonSize.set(value), value -> value + " px");
        addSwitch(compactModeSettings, "Заблокировать перемещение кнопки",
                preferences.climateButtonLocked.get(), value -> {
                    preferences.climateButtonLocked.set(value);
                    updateCompactPositionInfo();
                    applyClimatePanel();
                });
        compactPositionInfo = new TextView(this);
        compactPositionInfo.setTextSize(14);
        compactPositionInfo.setAlpha(.8f);
        compactModeSettings.addView(compactPositionInfo,
                new LinearLayout.LayoutParams(match(), dp(38)));
        addButton(compactModeSettings, "Вернуть кнопку в исходное положение", v -> {
            preferences.climateButtonX.set(40);
            preferences.climateButtonY.set(300);
            updateCompactPositionInfo();
            applyClimatePanel();
        });
        settings.addView(compactModeSettings, new LinearLayout.LayoutParams(match(), wrap()));

        reservedModeSettings = new LinearLayout(this);
        reservedModeSettings.setOrientation(LinearLayout.VERTICAL);
        addTitle(reservedModeSettings, "Панель с резервированием места");
        addHint(reservedModeSettings, "Другие приложения получают уменьшенную рабочую область и не рисуют содержимое под климатической панелью.");
        addEdgeSelector(reservedModeSettings);
        addSlider(reservedModeSettings, "Размер занятой полосы",
                preferences.climatePanelExtent.get(), 80, 600,
                value -> preferences.climatePanelExtent.set(value), value -> value + " px");
        addHint(reservedModeSettings, "Если прошивка не поддерживает системное резервирование, сервис безопасно вернёт полную область экрана.");
        settings.addView(reservedModeSettings, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(settings, "Отключить постоянную панель и вернуть полный экран", v -> {
            if (permanentPanelSwitch != null && permanentPanelSwitch.isChecked()) {
                permanentPanelSwitch.setChecked(false);
            } else {
                preferences.climatePanelEnabled.set(false);
                ClimatePanelService.stopAndRestore(this);
            }
        });
        addHint(settings, "Перед удалением приложения сначала используйте эту кнопку: сохранённые системные отступы будут восстановлены точно.");
        updateCompactPositionInfo();
        updateModeSettings(preferences.climatePanelMode.get());

        addTitle(settings, "Панель внутри HOME");
        MaterialSwitch visible = addSwitch(settings, "Показывать панель на HOME",
                preferences.launcherClimateVisible.get(),
                value -> preferences.launcherClimateVisible.set(value));
        visible.setChecked(preferences.launcherClimateVisible.get());

        addButton(settings, "Размер и положение на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));
        addHint(settings, "В редакторе HOME панель перемещается целиком. Маркер в правом нижнем углу меняет её ширину и высоту в пикселях.");

        addTitle(settings, "Оформление");
        addColor(settings, "Фон", () -> config.backgroundColor,
                value -> config.backgroundColor = value);
        addSlider(settings, "Непрозрачность фона", config.backgroundAlpha, 0, 255,
                value -> config.backgroundAlpha = value,
                value -> Math.round(value * 100f / 255f) + "%");
        addSlider(settings, "Скругление", config.cornerRadiusPx, 0, 96,
                value -> config.cornerRadiusPx = value, value -> value + " px");
        addSlider(settings, "Общий масштаб панели", config.scalePercent, 60, 160,
                value -> config.scalePercent = value, value -> value + "%");
        addColor(settings, "Активный цвет", () -> config.accentColor,
                value -> config.accentColor = value);
        addColor(settings, "Неактивные иконки", () -> config.inactiveColor,
                value -> config.inactiveColor = value);
        addColor(settings, "Текст", () -> config.textColor,
                value -> config.textColor = value);
        addSwitch(settings, "Показывать заголовок", config.showTitle,
                value -> { config.showTitle = value; persistAndPreview(); });
        addSwitch(settings, "Цвет уровня от автомобиля", config.useVehicleStateColors,
                value -> { config.useVehicleStateColors = value; persistAndPreview(); });
        addHint(settings, "При включённом цвете уровня подогревы и вентиляция меняют оттенок вместе с реальным уровнем.");

        addTitle(settings, "Что показывать");
        addHint(settings, "Включайте элементы, меняйте их порядок стрелками и настраивайте размер каждого отдельно. Неподдерживаемые автомобилем функции скрываются автоматически.");
        elementHost = new LinearLayout(this);
        elementHost.setOrientation(LinearLayout.VERTICAL);
        settings.addView(elementHost, new LinearLayout.LayoutParams(match(), wrap()));
        rebuildElementEditor();

        addButton(settings, "Вернуть оформление по умолчанию", v ->
                new AlertDialog.Builder(this)
                        .setTitle("Сбросить климатическую панель?")
                        .setMessage("Оформление и выбор элементов вернутся к исходным. Размер и положение на HOME сохранятся.")
                        .setPositiveButton("Сбросить", (dialog, which) -> {
                            flushLiveUpdate();
                            store.reset();
                            config = store.load();
                            recreate();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        LinearLayout previewColumn = new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setPadding(dp(10), 0, 0, 0);
        TextView previewTitle = new TextView(this);
        previewTitle.setText("ЖИВОЙ ПРЕДПРОСМОТР");
        previewTitle.setTextSize(14);
        previewTitle.setTextColor(Color.rgb(105, 165, 255));
        previewTitle.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(38)));

        FrameLayout previewHost = new FrameLayout(this);
        GradientDrawable hostBackground = new GradientDrawable();
        hostBackground.setColor(Color.rgb(8, 12, 18));
        hostBackground.setCornerRadius(dp(22));
        previewHost.setBackground(hostBackground);
        previewHost.setPadding(dp(14), dp(14), dp(14), dp(14));
        preview = new ClimatePanelView(this, CarIntegrations.get(this), store);
        preview.setEditorPreviewMode(true);
        preview.setConfig(config);
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));
        TextView saved = new TextView(this);
        saved.setText("✓ Изменения сохраняются автоматически");
        saved.setTextSize(13);
        saved.setGravity(Gravity.CENTER);
        saved.setAlpha(.72f);
        previewColumn.addView(saved, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(settingsScroll, new LinearLayout.LayoutParams(0, match(), .48f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .52f));
        return root;
    }

    private void persistAndPreview() {
        config.normalize();
        store.save(config);
        if (preview != null) preview.setConfig(config);
        applyClimatePanel();
    }

    private void applyClimatePanel() {
        ClimatePanelService.apply(this);
    }

    private void requestOverlayPermission() {
        if (overlayPermissionRequestInFlight) return;
        overlayPermissionRequestInFlight = true;
        PrivilegedShell.Request request = PrivilegedShell.Request
                .forPackage(getPackageName())
                .withOverlay()
                .build();
        PrivilegedShell.get(this).ensurePrivileges(request, result -> {
            if (isFinishing() || isDestroyed()) return;
            if (Settings.canDrawOverlays(this)) {
                overlayPermissionRequestInFlight = false;
                applyClimatePanel();
                Toast.makeText(this, "Разрешение поверх приложений получено",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (RuntimeException error) {
                overlayPermissionRequestInFlight = false;
                Toast.makeText(this,
                        "Не удалось открыть системную настройку. Разрешите приложению показ поверх других окон.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateModeSettings(int mode) {
        if (compactModeSettings != null) {
            compactModeSettings.setVisibility(mode == 1 ? View.GONE : View.VISIBLE);
        }
        if (reservedModeSettings != null) {
            reservedModeSettings.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void updateCompactPositionInfo() {
        if (compactPositionInfo == null) return;
        compactPositionInfo.setText("Положение кнопки: X = " + preferences.climateButtonX.get()
                + " px, Y = " + preferences.climateButtonY.get() + " px"
                + (preferences.climateButtonLocked.get() ? " · закреплено" : " · можно перемещать"));
    }

    private void addEdgeSelector(@NonNull LinearLayout parent) {
        TextView label = settingLabel("Сторона экрана");
        parent.addView(label, new LinearLayout.LayoutParams(match(), wrap()));
        String[] labels = {"Снизу", "Сверху", "Слева", "Справа"};
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int initial = Math.max(0, Math.min(labels.length - 1,
                preferences.climatePanelEdge.get()));
        spinner.setSelection(initial, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                if (preferences.climatePanelEdge.get() == position) return;
                preferences.climatePanelEdge.set(position);
                applyClimatePanel();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(52));
        lp.bottomMargin = dp(5);
        parent.addView(spinner, lp);
    }

    @NonNull
    private RadioButton modeButton(@NonNull String text) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextSize(15);
        return button;
    }

    @NonNull
    private TextView settingLabel(@NonNull String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16);
        label.setPadding(0, dp(6), 0, dp(3));
        return label;
    }

    private void rebuildElementEditor() {
        if (elementHost == null) return;
        elementHost.removeAllViews();
        java.util.List<ClimatePanelConfig.Element> ordered = config.orderedElements();
        for (int index = 0; index < ordered.size(); index++) {
            ClimatePanelConfig.Element element = ordered.get(index);
            final int position = index;
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(16));
            card.setCardElevation(0);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(Color.argb(48, 255, 255, 255));
            card.setCardBackgroundColor(Color.argb(28, 255, 255, 255));

            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(dp(12), dp(6), dp(8), dp(8));
            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            MaterialSwitch enabled = new MaterialSwitch(this);
            enabled.setText(element.label);
            enabled.setTextSize(15);
            enabled.setChecked(config.isElementEnabled(element.id));
            enabled.setOnCheckedChangeListener((button, checked) -> {
                config.setElementEnabled(element.id, checked);
                persistAndPreview();
            });
            header.addView(enabled, new LinearLayout.LayoutParams(0, dp(48), 1f));
            MaterialButton up = orderButton("↑", "Переместить выше");
            MaterialButton down = orderButton("↓", "Переместить ниже");
            up.setEnabled(position > 0);
            down.setEnabled(position < ordered.size() - 1);
            up.setOnClickListener(v -> moveElement(element.id, -1));
            down.setOnClickListener(v -> moveElement(element.id, 1));
            header.addView(up, new LinearLayout.LayoutParams(dp(48), dp(42)));
            header.addView(down, new LinearLayout.LayoutParams(dp(48), dp(42)));
            block.addView(header, new LinearLayout.LayoutParams(match(), dp(50)));

            LinearLayout scaleRow = new LinearLayout(this);
            scaleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView scaleLabel = new TextView(this);
            scaleLabel.setText("Размер");
            scaleLabel.setTextSize(14);
            TextView scaleValue = new TextView(this);
            scaleValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            scaleValue.setMinWidth(dp(64));
            int currentScale = config.elementScalePercent(element.id);
            scaleValue.setText(currentScale + "%");
            SeekBar scale = new SeekBar(this);
            scale.setMax(110);
            scale.setProgress(currentScale - 70);
            scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    int selected = progress + 70;
                    scaleValue.setText(selected + "%");
                    if (user) {
                        config.setElementScalePercent(element.id, selected);
                        scheduleLiveUpdate();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    flushLiveUpdate();
                }
            });
            scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(dp(68), dp(42)));
            scaleRow.addView(scale, new LinearLayout.LayoutParams(0, dp(42), 1f));
            scaleRow.addView(scaleValue, new LinearLayout.LayoutParams(dp(72), dp(42)));
            block.addView(scaleRow, new LinearLayout.LayoutParams(match(), dp(44)));
            card.addView(block, new MaterialCardView.LayoutParams(match(), wrap()));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(match(), wrap());
            cardLp.topMargin = dp(6);
            elementHost.addView(card, cardLp);
        }
    }

    private void moveElement(@NonNull String id, int direction) {
        if (!config.moveElement(id, direction)) return;
        persistAndPreview();
        rebuildElementEditor();
    }

    private void scheduleLiveUpdate() {
        View scheduler = preview != null ? preview : elementHost;
        if (scheduler == null) {
            persistAndPreview();
            return;
        }
        if (liveUpdatePosted) scheduler.removeCallbacks(liveUpdate);
        liveUpdatePosted = true;
        scheduler.postDelayed(liveUpdate, 40L);
    }

    private void flushLiveUpdate() {
        if (!liveUpdatePosted) return;
        View scheduler = preview != null ? preview : elementHost;
        if (scheduler != null) scheduler.removeCallbacks(liveUpdate);
        liveUpdatePosted = false;
        persistAndPreview();
    }

    @NonNull
    private MaterialButton orderButton(@NonNull String symbol, @NonNull String description) {
        MaterialButton button = new MaterialButton(this);
        button.setText(symbol);
        button.setTextSize(20);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(description);
        return button;
    }

    private MaterialSwitch addSwitch(@NonNull LinearLayout parent, @NonNull String label,
                                     boolean checked, @NonNull BoolChange listener) {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText(label);
        control.setTextSize(16);
        control.setMinHeight(dp(50));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> listener.set(value));
        parent.addView(control, new LinearLayout.LayoutParams(match(), wrap()));
        return control;
    }

    private interface ValueLabel { String format(int value); }

    private void addSlider(@NonNull LinearLayout parent, @NonNull String title, int initial,
                           int minimum, int maximum, @NonNull IntChange listener,
                           @NonNull ValueLabel formatter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(16);
        TextView value = new TextView(this);
        value.setGravity(Gravity.END);
        value.setText(formatter.format(initial));
        heading.addView(label, new LinearLayout.LayoutParams(0, wrap(), 1f));
        heading.addView(value, new LinearLayout.LayoutParams(dp(104), wrap()));
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = progress + minimum;
                value.setText(formatter.format(selected));
                if (user) {
                    listener.set(selected);
                    scheduleLiveUpdate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                flushLiveUpdate();
            }
        });
        block.addView(heading);
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(38)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(7);
        parent.addView(block, lp);
    }

    private interface ColorValue { String get(); }

    private void addColor(@NonNull LinearLayout parent, @NonNull String title,
                          @NonNull ColorValue current, @NonNull ColorChange listener) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        updateColorButton(button, title, current.get());
        button.setOnClickListener(v -> showColorChooser(title, current.get(), selected -> {
            listener.set(selected);
            persistAndPreview();
            updateColorButton(button, title, selected);
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(50));
        lp.topMargin = dp(6);
        parent.addView(button, lp);
    }

    private void showColorChooser(@NonNull String title, @NonNull String current,
                                  @NonNull ColorChange listener) {
        String[] labels = new String[COLOR_LABELS.length + 1];
        for (int index = 0; index < COLOR_LABELS.length; index++) {
            labels[index] = COLOR_LABELS[index] + "   " + COLOR_VALUES[index];
        }
        labels[labels.length - 1] = "Свой HEX-цвет…";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    if (which < COLOR_VALUES.length) listener.set(COLOR_VALUES[which]);
                    else showHexDialog(title, current, listener);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showHexDialog(@NonNull String title, @NonNull String current,
                               @NonNull ColorChange listener) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setText(current);
        input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle(title + " · HEX")
                .setMessage("Формат: #RRGGBB")
                .setView(input)
                .setPositiveButton("Применить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    try {
                        Color.parseColor(value);
                        if (!value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new IllegalArgumentException();
                        listener.set(value.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, "Неверный HEX-цвет", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateColorButton(@NonNull MaterialButton button, @NonNull String title,
                                   @NonNull String value) {
        button.setText(title + "   " + value);
        try {
            int color = Color.parseColor(value);
            button.setIconTint(android.content.res.ColorStateList.valueOf(color));
            button.setIconResource(R.drawable.ic_popup_light);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void addButton(@NonNull LinearLayout parent, @NonNull String label,
                           @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(52));
        lp.topMargin = dp(7);
        parent.addView(button, lp);
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(22);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(5);
        parent.addView(title, lp);
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(14);
        hint.setAlpha(.75f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(7);
        parent.addView(hint, lp);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
