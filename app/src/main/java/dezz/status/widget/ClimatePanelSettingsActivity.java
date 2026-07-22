/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
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

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.climate.ClimatePanelConfig;
import dezz.status.widget.launcher.climate.ClimatePanelConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelView;

/** Code-free, immediate editor for the independent HOME climate panel. */
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
    protected void onStop() {
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
        addSlider(settings, "Масштаб элементов", config.scalePercent, 60, 160,
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
        addHint(settings, "Неподдерживаемые конкретным автомобилем функции скрываются автоматически.");
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            addSwitch(settings, element.label, config.isElementEnabled(element.id), value -> {
                config.setElementEnabled(element.id, value);
                persistAndPreview();
            });
        }

        addButton(settings, "Вернуть оформление по умолчанию", v ->
                new AlertDialog.Builder(this)
                        .setTitle("Сбросить климатическую панель?")
                        .setMessage("Оформление и выбор элементов вернутся к исходным. Размер и положение на HOME сохранятся.")
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
                    persistAndPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
