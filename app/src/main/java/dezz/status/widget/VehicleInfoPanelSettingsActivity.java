/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

import java.util.List;
import java.util.Locale;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelConfig;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelConfigStore;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelView;
import dezz.status.widget.launcher.vehicle.VehicleDerivedMetrics;
import dezz.status.widget.launcher.panels.PanelEditScheduler;

/** Human-friendly, immediate visual editor for the HOME vehicle-information panel. */
public final class VehicleInfoPanelSettingsActivity extends AppCompatActivity {
    private interface IntChange { void set(int value); }
    private interface TextChange { void set(@NonNull String value); }
    private interface TextValue { String get(); }
    private interface ValueLabel { String format(int value); }

    private static final String[] COLOR_VALUES = {
            "#FFFFFF", "#C7D0DD", "#35B7FF", "#00C853", "#FFB300",
            "#FF5A5F", "#9C6BFF", "#121923", "#000000"
    };
    private static final String[] COLOR_LABELS = {
            "Белый", "Серо-голубой", "Голубой", "Зелёный", "Янтарный",
            "Красный", "Фиолетовый", "Графитовый", "Чёрный"
    };

    private Preferences preferences;
    private VehicleInfoPanelConfigStore store;
    private VehicleInfoPanelConfig config;
    private CarIntegration integration;
    private VehicleInfoPanelView preview;
    private LinearLayout metricList;
    private TextView catalogStatus;
    private PanelEditScheduler editScheduler;
    private boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new VehicleInfoPanelConfigStore(preferences);
        config = store.load();
        integration = CarIntegrations.get(this);
        editScheduler = PanelEditScheduler.onMainThread(
                () -> {
                    if (preview != null) preview.setConfig(config);
                },
                () -> store.save(config));
        setTitle("Данные автомобиля");
        setContentView(buildContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (preview != null) preview.start();
        requestCatalog();
    }

    @Override
    protected void onStop() {
        if (editScheduler != null) editScheduler.flush();
        if (preview != null) preview.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (editScheduler != null) editScheduler.cancel();
        super.onDestroy();
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

        addTitle(settings, "Данные автомобиля / HUD");
        addHint(settings, "Выберите только нужные показатели. Изменения сохраняются сразу и "
                + "показываются справа на реальных данных автомобиля.");
        MaterialSwitch visible = addSwitch(settings, "Показывать панель на HOME",
                preferences.launcherVehicleInfoVisible.get(), checked ->
                        preferences.launcherVehicleInfoVisible.set(checked));
        visible.setContentDescription("Показывать панель данных автомобиля на домашнем экране");
        addButton(settings, "Размер и положение панели на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));

        addTitle(settings, "Сетка и оформление");
        addSlider(settings, "Колонки", config.columns, 1, 6,
                value -> config.columns = value, value -> String.valueOf(value));
        addSlider(settings, "Непрозрачность фона", config.backgroundAlpha, 0, 255,
                value -> config.backgroundAlpha = value,
                value -> Math.round(value * 100f / 255f) + "%");
        addColor(settings, "Цвет фона", () -> config.backgroundColor,
                value -> config.backgroundColor = value);
        addSlider(settings, "Скругление", config.cornerRadiusPx, 0, 120,
                value -> config.cornerRadiusPx = value, value -> value + " px");
        addSlider(settings, "Внутренний отступ", config.contentPaddingPx, 0, 80,
                value -> config.contentPaddingPx = value, value -> value + " px");
        addSlider(settings, "Расстояние между плитками", config.gapPx, 0, 48,
                value -> config.gapPx = value, value -> value + " px");
        addSwitch(settings, "Показывать названия показателей", config.showLabels, checked -> {
            config.showLabels = checked;
            persistAndPreview();
        });
        addSwitch(settings, "Скрывать панель до первых данных", config.hideUntilFirstSample,
                checked -> {
                    config.hideUntilFirstSample = checked;
                    persistAndPreview();
                });

        addTitle(settings, "Показатели");
        addHint(settings, "Включайте показатели, меняйте порядок стрелками и размер ползунком. "
                + "Кнопка «Настроить» меняет подпись, единицы, точность и цвета.");
        catalogStatus = new TextView(this);
        catalogStatus.setText("Получаем список датчиков автомобиля…");
        catalogStatus.setTextSize(14);
        catalogStatus.setAlpha(.72f);
        settings.addView(catalogStatus, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(settings, "Обновить список датчиков", v -> requestCatalog());
        metricList = new LinearLayout(this);
        metricList.setOrientation(LinearLayout.VERTICAL);
        settings.addView(metricList, new LinearLayout.LayoutParams(match(), wrap()));
        rebuildMetricControls();

        addButton(settings, "Вернуть настройки панели по умолчанию", v ->
                new AlertDialog.Builder(this)
                        .setTitle("Сбросить панель?")
                        .setMessage("Состав, порядок и оформление вернутся к исходным. "
                                + "Положение и размер панели на HOME сохранятся.")
                        .setPositiveButton("Сбросить", (dialog, which) -> {
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
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(38)));

        FrameLayout previewHost = new FrameLayout(this);
        GradientDrawable hostBackground = new GradientDrawable();
        hostBackground.setColor(Color.rgb(8, 12, 18));
        hostBackground.setCornerRadius(dp(22));
        previewHost.setBackground(hostBackground);
        previewHost.setPadding(dp(14), dp(14), dp(14), dp(14));
        preview = new VehicleInfoPanelView(this, integration, store);
        preview.setConfig(config);
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));

        TextView liveHint = new TextView(this);
        liveHint.setText("✓ Автосохранение · значения поступают напрямую из автомобиля");
        liveHint.setTextSize(13);
        liveHint.setGravity(Gravity.CENTER);
        liveHint.setAlpha(.72f);
        previewColumn.addView(liveHint, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(settingsScroll, new LinearLayout.LayoutParams(0, match(), .54f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .46f));
        return root;
    }

    private void requestCatalog() {
        if (catalogStatus != null) catalogStatus.setText("Обновляем список датчиков…");
        integration.requestTelemetryCatalog(values -> runOnUiThread(() -> {
            if (destroyed) return;
            boolean changed = config.mergeCatalog(values);
            if (changed) store.save(config);
            if (catalogStatus != null) {
                catalogStatus.setText(values.isEmpty()
                        ? "Коннектор пока не сообщил доступные датчики. Показан встроенный список."
                        : "Получено показателей от автомобиля: " + values.size());
            }
            rebuildMetricControls();
            if (preview != null) preview.setConfig(config);
        }));
    }

    private void rebuildMetricControls() {
        if (metricList == null) return;
        metricList.removeAllViews();
        List<VehicleInfoPanelConfig.Metric> metrics = config.orderedMetrics();
        for (int index = 0; index < metrics.size(); index++) {
            VehicleInfoPanelConfig.Metric metric = metrics.get(index);
            metricList.addView(buildMetricCard(metric, index, metrics.size()), cardLayoutParams());
        }
    }

    @NonNull
    private View buildMetricCard(@NonNull VehicleInfoPanelConfig.Metric metric, int position,
                                 int count) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(5), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(35, 120, 170, 230));
        background.setCornerRadius(dp(10));
        card.setBackground(background);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        MaterialSwitch enabled = new MaterialSwitch(this);
        enabled.setText(displayName(metric));
        enabled.setTextSize(16);
        enabled.setChecked(metric.enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            metric.enabled = checked;
            persistAndPreview();
        });
        heading.addView(enabled, new LinearLayout.LayoutParams(0, dp(49), 1f));
        MaterialButton up = compactButton("↑");
        MaterialButton down = compactButton("↓");
        up.setEnabled(position > 0);
        down.setEnabled(position + 1 < count);
        up.setContentDescription("Поднять " + displayName(metric));
        down.setContentDescription("Опустить " + displayName(metric));
        up.setOnClickListener(v -> moveMetric(metric.id, -1));
        down.setOnClickListener(v -> moveMetric(metric.id, 1));
        heading.addView(up, new LinearLayout.LayoutParams(dp(52), dp(43)));
        heading.addView(down, new LinearLayout.LayoutParams(dp(52), dp(43)));
        card.addView(heading, new LinearLayout.LayoutParams(match(), wrap()));

        LinearLayout second = new LinearLayout(this);
        second.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeTitle = new TextView(this);
        sizeTitle.setText("Размер");
        sizeTitle.setTextSize(14);
        SeekBar scale = new SeekBar(this);
        scale.setMax(165);
        scale.setProgress(metric.scalePercent - 55);
        TextView scaleValue = new TextView(this);
        scaleValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        scaleValue.setText(metric.scalePercent + "%");
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = progress + 55;
                scaleValue.setText(selected + "%");
                if (user) {
                    metric.scalePercent = selected;
                    persistAndPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        MaterialButton details = new MaterialButton(this);
        details.setText("Настроить");
        details.setAllCaps(false);
        details.setOnClickListener(v -> showMetricDialog(metric));
        second.addView(sizeTitle, new LinearLayout.LayoutParams(dp(62), wrap()));
        second.addView(scale, new LinearLayout.LayoutParams(0, dp(40), 1f));
        second.addView(scaleValue, new LinearLayout.LayoutParams(dp(58), wrap()));
        second.addView(details, new LinearLayout.LayoutParams(dp(116), dp(42)));
        card.addView(second, new LinearLayout.LayoutParams(match(), wrap()));

        TextView summary = new TextView(this);
        summary.setText(metricSummary(metric));
        summary.setTextSize(12);
        summary.setAlpha(.64f);
        summary.setSingleLine(true);
        card.addView(summary, new LinearLayout.LayoutParams(match(), wrap()));
        return card;
    }

    private void moveMetric(@NonNull String id, int direction) {
        if (!config.moveMetric(id, direction)) return;
        persistAndPreview();
        rebuildMetricControls();
    }

    private void showMetricDialog(@NonNull VehicleInfoPanelConfig.Metric metric) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(4), dp(22), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(match(), wrap()));

        addHint(body, "Оставьте поле пустым, чтобы использовать название или единицу, "
                + "которые сообщает автомобиль.");
        EditText title = addInput(body, "Название на панели", metric.labelOverride,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText unit = addInput(body, "Единица измерения", metric.unitOverride,
                InputType.TYPE_CLASS_TEXT);
        watch(title, value -> metric.labelOverride = value);
        watch(unit, value -> metric.unitOverride = value);

        addSection(body, "Цвета");
        addColor(body, "Значение", () -> metric.valueColor,
                value -> metric.valueColor = value);
        addColor(body, "Название", () -> metric.labelColor,
                value -> metric.labelColor = value);

        if (VehicleDerivedMetrics.REFILL_FUEL_ID.equals(metric.id)) {
            addSection(body, "Расчёт дозаправки");
            addHint(body, "Остаток приходит из автомобиля. В автоматическом режиме объём бака "
                    + "берётся из AdaptAPI, а если прошивка его не сообщает — используется "
                    + "резервное значение 64 л.");
            addSwitch(body, "Показывать только на передаче P", metric.refillOnlyInPark,
                    checked -> {
                        metric.refillOnlyInPark = checked;
                        persistAndPreview();
                    });
            addSwitch(body, "Получать объём бака из автомобиля (резерв 64 л)",
                    metric.refillAutomaticCapacity, checked -> {
                        metric.refillAutomaticCapacity = checked;
                        persistAndPreview();
                    });
            EditText tank = addInput(body, "Ручной объём бака, л",
                    plainNumber(metric.refillManualCapacityLitres),
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            watchNumber(tank, value -> {
                if (value >= 1d && value <= 250d) metric.refillManualCapacityLitres = value;
            });
        } else if (VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID.equals(metric.id)) {
            addSection(body, "Предупреждение о скорости");
            addHint(body, "Текущая скорость приходит из ECARX, лимит — из активной "
                    + "навигации. Порог добавляется к лимиту, например 60 + 10 км/ч.");
            addSlider(body, "Допуск сверх лимита", metric.speedLimitThresholdKmh, 0, 20,
                    value -> metric.speedLimitThresholdKmh = value,
                    value -> "+" + value + " км/ч");
            addSwitch(body, "Только при активном маршруте",
                    metric.speedLimitOnlyActiveRoute, checked -> {
                        metric.speedLimitOnlyActiveRoute = checked;
                        persistAndPreview();
                    });
            addSwitch(body, "Мигать при превышении", metric.speedLimitBlink, checked -> {
                metric.speedLimitBlink = checked;
                persistAndPreview();
            });
            addSwitch(body, "Белый фон при превышении",
                    metric.speedLimitWhiteBackground, checked -> {
                        metric.speedLimitWhiteBackground = checked;
                        persistAndPreview();
                    });
            addColor(body, "Цвет превышения", () -> metric.warningColor,
                    value -> metric.warningColor = value);
        } else if (VehicleDerivedMetrics.TURN_SIGNALS_ID.equals(metric.id)) {
            addSection(body, "Комбинированный индикатор");
            addHint(body, "← левый · → правый · ↔ аварийная сигнализация. Тёмная фаза "
                    + "мигания автоматически сглаживается.");
        } else if (VehicleDerivedMetrics.AUTO_HOLD_ID.equals(metric.id)) {
            addSection(body, "Источник Auto Hold");
            addHint(body, "Принимается совместимый broadcast plus.monjaro.AUTOHOLD. "
                    + "Состояние сохраняется только для текущей загрузки магнитолы.");
        }

        addSection(body, "Дополнительно");
        addHint(body, "Формула отображения: значение × множитель + смещение. "
                + "Обычно эти поля менять не нужно.");
        addSlider(body, "Знаков после запятой", metric.decimals, 0, 4,
                value -> metric.decimals = value, String::valueOf);
        EditText multiplier = addInput(body, "Множитель", plainNumber(metric.multiplier),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText offset = addInput(body, "Смещение", plainNumber(metric.offset),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        watchNumber(multiplier, value -> metric.multiplier = value);
        watchNumber(offset, value -> metric.offset = value);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(displayName(metric))
                .setView(scroll)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Сбросить оформление", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    metric.labelOverride = "";
                    metric.unitOverride = "";
                    metric.multiplier = 1d;
                    metric.offset = 0d;
                    metric.decimals = suggestedDecimals(metric.fallbackUnit);
                    metric.valueColor = "#FFFFFF";
                    metric.labelColor = "#AEB9C8";
                    metric.warningColor = "#FF3B30";
                    persistAndPreview();
                    dialog.dismiss();
                    rebuildMetricControls();
                    showMetricDialog(metric);
                }));
        dialog.setOnDismissListener(ignored -> rebuildMetricControls());
        dialog.show();
    }

    private void persistAndPreview() {
        editScheduler.request();
    }

    @NonNull
    private MaterialSwitch addSwitch(@NonNull LinearLayout parent, @NonNull String title,
                                     boolean initial, @NonNull BoolChange change) {
        MaterialSwitch value = new MaterialSwitch(this);
        value.setText(title);
        value.setTextSize(16);
        value.setMinHeight(dp(48));
        value.setChecked(initial);
        value.setOnCheckedChangeListener((button, checked) -> change.set(checked));
        parent.addView(value, new LinearLayout.LayoutParams(match(), wrap()));
        return value;
    }

    private interface BoolChange { void set(boolean value); }

    private void addSlider(@NonNull LinearLayout parent, @NonNull String title, int initial,
                           int minimum, int maximum, @NonNull IntChange change,
                           @NonNull ValueLabel formatter) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(16);
        TextView value = new TextView(this);
        value.setText(formatter.format(initial));
        value.setGravity(Gravity.END);
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
                    change.set(selected);
                    persistAndPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(heading);
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(38)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(6);
        parent.addView(block, lp);
    }

    private void addColor(@NonNull LinearLayout parent, @NonNull String title,
                          @NonNull TextValue current, @NonNull TextChange change) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        updateColorButton(button, title, current.get());
        button.setOnClickListener(v -> showColorChooser(title, current.get(), selected -> {
            change.set(selected);
            persistAndPreview();
            updateColorButton(button, title, selected);
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(50));
        lp.topMargin = dp(5);
        parent.addView(button, lp);
    }

    private void showColorChooser(@NonNull String title, @NonNull String current,
                                  @NonNull TextChange selected) {
        String[] labels = new String[COLOR_LABELS.length + 1];
        for (int index = 0; index < COLOR_LABELS.length; index++) {
            labels[index] = COLOR_LABELS[index] + "   " + COLOR_VALUES[index];
        }
        labels[labels.length - 1] = "Свой HEX-цвет…";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    if (which < COLOR_VALUES.length) selected.set(COLOR_VALUES[which]);
                    else showHexDialog(title, current, selected);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showHexDialog(@NonNull String title, @NonNull String current,
                               @NonNull TextChange selected) {
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
                    String value = input.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (value.matches("#[0-9A-F]{6}")) selected.set(value);
                    else Toast.makeText(this, "Неверный HEX-цвет", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateColorButton(@NonNull MaterialButton button, @NonNull String title,
                                   @NonNull String value) {
        button.setText(title + " · " + value.toUpperCase(Locale.ROOT));
        try {
            button.setStrokeWidth(dp(2));
            button.setStrokeColor(ColorStateList.valueOf(Color.parseColor(value)));
        } catch (IllegalArgumentException ignored) {
            button.setStrokeWidth(0);
        }
    }

    @NonNull
    private EditText addInput(@NonNull LinearLayout parent, @NonNull String hint,
                              @NonNull String initial, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setText(initial);
        input.setInputType(inputType);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(54));
        lp.topMargin = dp(5);
        parent.addView(input, lp);
        return input;
    }

    private void watch(@NonNull EditText input, @NonNull TextChange change) {
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable value) {
                change.set(value.toString());
                persistAndPreview();
            }
        });
    }

    private void watchNumber(@NonNull EditText input, @NonNull DoubleChange change) {
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable value) {
                try {
                    double parsed = Double.parseDouble(value.toString().replace(',', '.'));
                    if (!Double.isFinite(parsed)) return;
                    change.set(parsed);
                    persistAndPreview();
                } catch (NumberFormatException ignored) {
                    // Intermediate values such as an empty field or just '-' remain editable.
                }
            }
        });
    }

    private interface DoubleChange { void set(double value); }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }

    @NonNull
    private MaterialButton compactButton(@NonNull String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextSize(20);
        button.setMinWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void addButton(@NonNull LinearLayout parent, @NonNull String label,
                           @NonNull View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(51));
        lp.topMargin = dp(6);
        parent.addView(button, lp);
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(23);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(21);
        lp.bottomMargin = dp(5);
        parent.addView(title, lp);
    }

    private void addSection(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(17);
        lp.bottomMargin = dp(3);
        parent.addView(title, lp);
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(14);
        hint.setAlpha(.76f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(7);
        parent.addView(hint, lp);
    }

    @NonNull
    private LinearLayout.LayoutParams cardLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(6);
        return lp;
    }

    @NonNull
    private static String displayName(@NonNull VehicleInfoPanelConfig.Metric metric) {
        return metric.labelOverride.trim().isEmpty()
                ? metric.fallbackLabel : metric.labelOverride.trim();
    }

    @NonNull
    private static String metricSummary(@NonNull VehicleInfoPanelConfig.Metric metric) {
        if (VehicleDerivedMetrics.REFILL_FUEL_ID.equals(metric.id)) {
            String capacity = metric.refillAutomaticCapacity
                    ? "объём бака из авто, резерв 64 л"
                    : "бак " + plainNumber(metric.refillManualCapacityLitres) + " л";
            return capacity + (metric.refillOnlyInPark ? " · только P" : "");
        }
        if (VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID.equals(metric.id)) {
            return "Допуск: +" + metric.speedLimitThresholdKmh + " км/ч"
                    + (metric.speedLimitBlink ? " · мигание" : "");
        }
        if (VehicleDerivedMetrics.TURN_SIGNALS_ID.equals(metric.id)) {
            return "Левый · правый · аварийная сигнализация";
        }
        if (VehicleDerivedMetrics.AUTO_HOLD_ID.equals(metric.id)) {
            return "Broadcast plus.monjaro.AUTOHOLD";
        }
        String unit = metric.unitOverride.trim().isEmpty()
                ? metric.fallbackUnit : metric.unitOverride.trim();
        return unit.isEmpty() ? "Без единицы измерения" : "Единица: " + unit;
    }

    @NonNull
    private static String plainNumber(double value) {
        if (value == Math.rint(value)) return Long.toString(Math.round(value));
        return Double.toString(value);
    }

    private static int suggestedDecimals(@Nullable String unit) {
        String value = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        return value.contains("°") || value.contains("bar") || value.contains("бар") ? 1 : 0;
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
