/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import dezz.status.widget.launcher.media.MediaPanelConfig;
import dezz.status.widget.launcher.media.MediaPanelConfigStore;
import dezz.status.widget.launcher.media.MediaPanelView;

/** Visual, immediate editor for every element inside the HOME media panel. */
public final class MediaPanelSettingsActivity extends AppCompatActivity {
    private interface IntChange { void set(int value); }
    private interface ColorChange { void set(@NonNull String value); }
    private interface ColorValue { String get(); }
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
    private MediaPanelConfigStore store;
    private MediaPanelConfig config;
    private MediaPanelView preview;
    private LinearLayout elementList;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new MediaPanelConfigStore(preferences);
        config = store.load();
        setTitle("Медиапанель");
        setContentView(buildContent());
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

        addTitle(settings, "Медиапанель");
        addHint(settings, "Включайте элементы, меняйте их порядок и собственный размер. Все изменения сохраняются сразу и видны справа.");
        MaterialSwitch panelVisible = new MaterialSwitch(this);
        panelVisible.setText("Показывать медиапанель на HOME");
        panelVisible.setTextSize(16);
        panelVisible.setMinHeight(dp(50));
        panelVisible.setChecked(preferences.launcherMediaVisible.get());
        panelVisible.setOnCheckedChangeListener((button, checked) ->
                preferences.launcherMediaVisible.set(checked));
        settings.addView(panelVisible, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(settings, "Размер и положение панели на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));

        addTitle(settings, "Элементы и порядок");
        addHint(settings, "Стрелки меняют последовательность. Размер каждого элемента не зависит от остальных; узкая панель автоматически переносит их на следующую строку.");
        elementList = new LinearLayout(this);
        elementList.setOrientation(LinearLayout.VERTICAL);
        settings.addView(elementList, new LinearLayout.LayoutParams(match(), wrap()));
        rebuildElementControls();

        addTitle(settings, "Панель");
        addSlider(settings, "Расстояние между элементами", config.spacingPx, 0, 48,
                value -> config.spacingPx = value, value -> value + " px");
        addSlider(settings, "Внутренний отступ", config.contentPaddingPx, 0, 64,
                value -> config.contentPaddingPx = value, value -> value + " px");
        addSlider(settings, "Скругление", config.cornerRadiusPx, 0, 96,
                value -> config.cornerRadiusPx = value, value -> value + " px");
        addSlider(settings, "Непрозрачность фона", config.backgroundAlpha, 0, 255,
                value -> config.backgroundAlpha = value,
                value -> Math.round(value * 100f / 255f) + "%");
        addColor(settings, "Цвет фона", () -> config.backgroundColor,
                value -> config.backgroundColor = value);
        addColor(settings, "Название", () -> config.titleColor,
                value -> config.titleColor = value);
        addColor(settings, "Исполнитель, альбом, время и приложение",
                () -> config.secondaryColor,
                value -> config.secondaryColor = value);
        addColor(settings, "Кнопки", () -> config.controlColor,
                value -> config.controlColor = value);

        addButton(settings, "Вернуть медиапанель по умолчанию", v ->
                new AlertDialog.Builder(this)
                        .setTitle("Сбросить медиапанель?")
                        .setMessage("Состав, порядок, размеры и оформление вернутся к исходным. Положение панели на HOME сохранится.")
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
        preview = new MediaPanelView(this, store, null);
        preview.setConfig(config);
        preview.setPreviewContent("Название композиции", "Исполнитель", "Яндекс Музыка", true);
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));
        TextView saved = new TextView(this);
        saved.setText("✓ Изменения сохраняются автоматически");
        saved.setTextSize(13);
        saved.setGravity(Gravity.CENTER);
        saved.setAlpha(.72f);
        previewColumn.addView(saved, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(settingsScroll, new LinearLayout.LayoutParams(0, match(), .50f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .50f));
        return root;
    }

    private void rebuildElementControls() {
        if (elementList == null) return;
        elementList.removeAllViews();
        for (MediaPanelConfig.Element element : config.orderedElements()) {
            MediaPanelConfig.Spec spec = MediaPanelConfig.spec(element.id);
            if (spec == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(4), dp(8), dp(7));

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            MaterialSwitch enabled = new MaterialSwitch(this);
            enabled.setText(spec.label);
            enabled.setTextSize(16);
            enabled.setChecked(element.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                config.setEnabled(element.id, checked);
                persistAndPreview();
            });
            heading.addView(enabled, new LinearLayout.LayoutParams(0, dp(48), 1f));
            MaterialButton up = compactButton("↑");
            MaterialButton down = compactButton("↓");
            up.setContentDescription("Поднять " + spec.label);
            down.setContentDescription("Опустить " + spec.label);
            up.setOnClickListener(v -> moveElement(element.id, -1));
            down.setOnClickListener(v -> moveElement(element.id, 1));
            heading.addView(up, new LinearLayout.LayoutParams(dp(54), dp(44)));
            heading.addView(down, new LinearLayout.LayoutParams(dp(54), dp(44)));
            card.addView(heading, new LinearLayout.LayoutParams(match(), wrap()));

            LinearLayout scaleRow = new LinearLayout(this);
            scaleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText("Размер");
            label.setTextSize(14);
            SeekBar scale = new SeekBar(this);
            scale.setMax(155);
            scale.setProgress(element.scalePercent - 45);
            TextView value = new TextView(this);
            value.setGravity(Gravity.END);
            value.setText(element.scalePercent + "%");
            scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    int selected = progress + 45;
                    value.setText(selected + "%");
                    if (user) {
                        config.setScale(element.id, selected);
                        persistAndPreview();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            scaleRow.addView(label, new LinearLayout.LayoutParams(dp(66), wrap()));
            scaleRow.addView(scale, new LinearLayout.LayoutParams(0, dp(40), 1f));
            scaleRow.addView(value, new LinearLayout.LayoutParams(dp(64), wrap()));
            card.addView(scaleRow, new LinearLayout.LayoutParams(match(), wrap()));

            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(35, 120, 170, 230));
            background.setCornerRadius(dp(10));
            card.setBackground(background);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(match(), wrap());
            cardLp.topMargin = dp(6);
            elementList.addView(card, cardLp);
        }
    }

    private void moveElement(@NonNull String id, int direction) {
        config.move(id, direction);
        persistAndPreview();
        rebuildElementControls();
    }

    private void persistAndPreview() {
        config.normalize();
        store.save(config);
        if (preview != null) {
            preview.setConfig(config);
            preview.setPreviewContent("Название композиции", "Исполнитель",
                    "Яндекс Музыка", true);
        }
    }

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
                    String selected = input.getText().toString().trim();
                    try {
                        Color.parseColor(selected);
                        listener.set(selected);
                    } catch (IllegalArgumentException ignored) {
                        new AlertDialog.Builder(this).setMessage("Неверный HEX-цвет")
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateColorButton(@NonNull MaterialButton button, @NonNull String title,
                                   @NonNull String value) {
        button.setText(title + " · " + value.toUpperCase());
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(52));
        lp.topMargin = dp(7);
        parent.addView(button, lp);
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(23);
        title.setTextColor(Color.rgb(105, 165, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(6);
        parent.addView(title, lp);
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = new TextView(this);
        hint.setText(value);
        hint.setTextSize(15);
        hint.setAlpha(.78f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(8);
        parent.addView(hint, lp);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
