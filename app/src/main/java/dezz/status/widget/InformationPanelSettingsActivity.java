/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
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

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.information.InformationIconPolicy;
import dezz.status.widget.launcher.information.InformationPanelConfig;
import dezz.status.widget.launcher.information.InformationPanelConfigStore;
import dezz.status.widget.launcher.information.InformationPanelView;
import dezz.status.widget.launcher.information.InformationSourcePicker;

/** Visual editor for the read-only HOME “Information” grid. */
public final class InformationPanelSettingsActivity extends AppCompatActivity {
    private Preferences preferences;
    private InformationPanelConfigStore store;
    private InformationPanelConfig config;
    private CarIntegration carIntegration;
    private InformationPanelView preview;
    private LinearLayout itemHost;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new InformationPanelConfigStore(preferences);
        config = store.load();
        carIntegration = CarIntegrations.get(this);
        setTitle("Панель «Информация»");
        setContentView(buildScreen());
        refreshItems();
        applyPreview();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (preview != null) preview.start();
    }

    @Override
    protected void onStop() {
        if (preview != null) preview.stop();
        persist();
        super.onStop();
    }

    @NonNull
    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView controlsScroll = new ScrollView(this);
        LinearLayout controls = column();
        controls.setPadding(0, 0, dp(16), dp(24));
        controlsScroll.addView(controls, new ScrollView.LayoutParams(match(), wrap()));
        TextView title = text("Панель «Информация»", 25, true);
        controls.addView(title);
        TextView hint = text("Объединяет внутренние датчики автомобиля/магнитолы и статусы "
                + "Home Assistant, MQTT и Sprut.hub. Нажатие ничего не выполняет.", 14, false);
        hint.setAlpha(.7f);
        controls.addView(hint, lp(match(), wrap(), 0, 0, 0, dp(12)));

        MaterialSwitch visible = new MaterialSwitch(this);
        visible.setText("Показывать панель на HOME");
        visible.setTextSize(16);
        visible.setChecked(preferences.launcherInformationVisible.get());
        visible.setOnCheckedChangeListener((button, checked) ->
                preferences.launcherInformationVisible.set(checked));
        controls.addView(visible, new LinearLayout.LayoutParams(match(), dp(54)));

        addSeek(controls, "Столбцы", 1, 8, config.columns, value -> {
            config.columns = value;
            changed(true);
        });
        addSeek(controls, "Строки", 1, 12, config.rows, value -> {
            config.rows = value;
            changed(true);
        });
        addSeek(controls, "Отступ внутри, px", 0, 80, config.contentPaddingPx, value -> {
            config.contentPaddingPx = value;
            changed(false);
        });
        addSeek(controls, "Промежуток, px", 0, 48, config.gapPx, value -> {
            config.gapPx = value;
            changed(false);
        });
        addSeek(controls, "Прозрачность фона", 0, 255, config.backgroundAlpha, value -> {
            config.backgroundAlpha = value;
            changed(false);
        });

        MaterialButton background = button("Цвет фона…");
        background.setOnClickListener(v -> editBackgroundColor());
        controls.addView(background, new LinearLayout.LayoutParams(match(), dp(52)));
        MaterialButton add = button("+  Добавить статус из каталога");
        add.setOnClickListener(v -> new InformationSourcePicker(this, carIntegration,
                this::addSource).show());
        controls.addView(add, lp(match(), dp(56), 0, dp(10), 0, 0));
        MaterialButton reset = button("Сбросить панель");
        reset.setOnClickListener(v -> confirmReset());
        controls.addView(reset, lp(match(), dp(50), 0, dp(8), 0, 0));

        ScrollView itemsScroll = new ScrollView(this);
        itemHost = column();
        itemHost.setPadding(dp(8), 0, dp(14), dp(24));
        itemsScroll.addView(itemHost, new ScrollView.LayoutParams(match(), wrap()));

        LinearLayout previewColumn = column();
        previewColumn.setPadding(dp(12), 0, 0, 0);
        TextView previewTitle = text("ЖИВОЙ ПРЕДПРОСМОТР", 14, true);
        previewTitle.setTextColor(Color.rgb(105, 165, 255));
        previewColumn.addView(previewTitle, new LinearLayout.LayoutParams(match(), dp(38)));
        FrameLayout previewHost = new FrameLayout(this);
        previewHost.setBackgroundColor(Color.rgb(8, 13, 21));
        previewHost.setPadding(dp(10), dp(10), dp(10), dp(10));
        preview = new InformationPanelView(this, carIntegration, store);
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
        previewColumn.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));
        TextView saved = text("✓ Сохраняется автоматически · устаревший кэш не показывается",
                12, false);
        saved.setGravity(Gravity.CENTER);
        saved.setAlpha(.68f);
        previewColumn.addView(saved, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(controlsScroll, new LinearLayout.LayoutParams(0, match(), .28f));
        root.addView(itemsScroll, new LinearLayout.LayoutParams(0, match(), .37f));
        root.addView(previewColumn, new LinearLayout.LayoutParams(0, match(), .35f));
        return root;
    }

    private void addSource(@NonNull InformationPanelConfig.Item source) {
        config.add(source);
        persist();
        refreshItems();
        applyPreview();
        InformationPanelConfig.Item added = config.find(source.id);
        if (added != null) editItem(added);
    }

    private void refreshItems() {
        if (itemHost == null) return;
        itemHost.removeAllViews();
        TextView heading = text("СТАТУСЫ В СЕТКЕ", 14, true);
        heading.setTextColor(Color.rgb(105, 165, 255));
        itemHost.addView(heading, new LinearLayout.LayoutParams(match(), dp(38)));
        List<InformationPanelConfig.Item> items = config.mutableItems();
        if (items.isEmpty()) {
            TextView empty = text("Пока ничего нет. Выберите источник в полном каталоге слева.",
                    16, false);
            empty.setAlpha(.72f);
            empty.setPadding(dp(8), dp(18), dp(8), 0);
            itemHost.addView(empty);
            return;
        }
        for (InformationPanelConfig.Item item : items) itemHost.addView(buildItemCard(item));
    }

    @NonNull
    private View buildItemCard(@NonNull InformationPanelConfig.Item item) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setOnClickListener(v -> editItem(item));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(8), dp(9));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(LauncherIconResolver.resolvePreset(this,
                InformationIconPolicy.resolve(item), item.iconColor));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = column();
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(item.displayLabel(), 17, true);
        TextView source = text(sourceSummary(item), 12, false);
        source.setAlpha(.68f);
        source.setMaxLines(3);
        labels.addView(name);
        labels.addView(source);
        row.addView(labels, new LinearLayout.LayoutParams(0, wrap(), 1f));

        MaterialButton edit = compactButton("✎");
        edit.setOnClickListener(v -> editItem(item));
        MaterialButton delete = compactButton("×");
        delete.setOnClickListener(v -> confirmDelete(item));
        row.addView(edit);
        row.addView(delete);
        card.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(9);
        card.setLayoutParams(lp);
        return card;
    }

    private void editItem(@NonNull InformationPanelConfig.Item item) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(22), dp(4), dp(22), dp(12));
        scroll.addView(form, new ScrollView.LayoutParams(match(), wrap()));

        EditText label = field(form, "Своя подпись (пусто = из каталога)",
                item.labelOverride, false);
        EditText unit = field(form, "Своя единица измерения (пусто = из источника)",
                item.unitOverride, false);
        EditText valueColor = field(form, "Цвет значения, HEX", item.valueColor, false);
        EditText labelColor = field(form, "Цвет подписи, HEX", item.labelColor, false);
        EditText iconColor = field(form, "Цвет иконки, HEX", item.iconColor, false);

        TextView iconLabel = text("Иконка", 13, false);
        form.addView(iconLabel);
        Spinner icon = new Spinner(this);
        List<String> iconLabels = new ArrayList<>();
        List<String> iconKeys = new ArrayList<>();
        iconLabels.add("Автоматически по типу");
        iconKeys.add("auto");
        for (LauncherIconResolver.Preset preset : LauncherIconResolver.presets()) {
            iconLabels.add(preset.label);
            iconKeys.add(preset.key);
        }
        icon.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, iconLabels));
        icon.setSelection(Math.max(0, iconKeys.indexOf(item.iconKey)));
        form.addView(icon, new LinearLayout.LayoutParams(match(), dp(52)));

        TextView visibilityLabel = text("Видимость", 13, false);
        form.addView(visibilityLabel);
        Spinner visibility = new Spinner(this);
        String[] visibilityLabels = {
                "Всегда — неизвестное значение показывать как «—»",
                "Только когда есть актуальные данные",
                "Только когда статус активен",
                "Только когда статус неактивен"
        };
        visibility.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, visibilityLabels));
        visibility.setSelection(item.visibility.ordinal());
        form.addView(visibility, new LinearLayout.LayoutParams(match(), dp(52)));

        LinearLayout coordinates = new LinearLayout(this);
        coordinates.setOrientation(LinearLayout.HORIZONTAL);
        EditText column = numericField(coordinates, "Столбец", item.column + 1);
        EditText row = numericField(coordinates, "Строка", item.row + 1);
        EditText columnSpan = numericField(coordinates, "Ширина", item.columnSpan);
        EditText rowSpan = numericField(coordinates, "Высота", item.rowSpan);
        form.addView(coordinates, new LinearLayout.LayoutParams(match(), dp(70)));

        TextView scaleValue = text("Масштаб: " + item.scalePercent + "%", 14, false);
        form.addView(scaleValue);
        SeekBar scale = new SeekBar(this);
        scale.setMax(InformationPanelConfig.MAX_SCALE - InformationPanelConfig.MIN_SCALE);
        scale.setProgress(item.scalePercent - InformationPanelConfig.MIN_SCALE);
        scale.setOnSeekBarChangeListener(new SimpleSeekListener(progress ->
                scaleValue.setText("Масштаб: "
                        + (InformationPanelConfig.MIN_SCALE + progress) + "%")));
        form.addView(scale, new LinearLayout.LayoutParams(match(), dp(48)));

        LinearLayout toggles = column();
        MaterialSwitch enabled = toggle("Показывать статус", item.enabled);
        MaterialSwitch showIcon = toggle("Показывать иконку", item.showIcon);
        MaterialSwitch showLabel = toggle("Показывать подпись", item.showLabel);
        toggles.addView(enabled);
        toggles.addView(showIcon);
        toggles.addView(showLabel);
        form.addView(toggles);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.sourceLabel)
                .setMessage("Источник доступен только для чтения: нажатие на статус не отправляет "
                        + "команду.")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        requireColor(valueColor.getText().toString());
                        requireColor(labelColor.getText().toString());
                        requireColor(iconColor.getText().toString());
                        item.labelOverride = label.getText().toString().trim();
                        item.unitOverride = unit.getText().toString().trim();
                        item.valueColor = valueColor.getText().toString().trim();
                        item.labelColor = labelColor.getText().toString().trim();
                        item.iconColor = iconColor.getText().toString().trim();
                        item.iconKey = iconKeys.get(icon.getSelectedItemPosition());
                        item.visibility = InformationPanelConfig.Visibility.values()[
                                visibility.getSelectedItemPosition()];
                        item.column = positive(column, "Столбец") - 1;
                        item.row = positive(row, "Строка") - 1;
                        item.columnSpan = positive(columnSpan, "Ширина");
                        item.rowSpan = positive(rowSpan, "Высота");
                        item.scalePercent = InformationPanelConfig.MIN_SCALE
                                + scale.getProgress();
                        item.enabled = enabled.isChecked();
                        item.showIcon = showIcon.isChecked();
                        item.showLabel = showLabel.isChecked();
                        changed(true);
                        dialog.dismiss();
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }));
        dialog.show();
    }

    private void changed(boolean refreshList) {
        config.normalize();
        persist();
        applyPreview();
        if (refreshList) refreshItems();
    }

    private void persist() {
        if (store != null && config != null) store.save(config);
    }

    private void applyPreview() {
        if (preview != null) preview.setConfig(config);
    }

    private void editBackgroundColor() {
        EditText color = new EditText(this);
        color.setSingleLine(true);
        color.setText(config.backgroundColor);
        new AlertDialog.Builder(this)
                .setTitle("Цвет фона панели")
                .setView(color)
                .setPositiveButton("Применить", (dialog, which) -> {
                    try {
                        requireColor(color.getText().toString());
                        config.backgroundColor = color.getText().toString().trim();
                        changed(false);
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(@NonNull InformationPanelConfig.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить «" + item.displayLabel() + "»?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    config.remove(item.id);
                    changed(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Сбросить информационную панель?")
                .setMessage("Все выбранные статусы и их расположение будут удалены.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    store.reset();
                    config = store.load();
                    refreshItems();
                    applyPreview();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addSeek(@NonNull LinearLayout parent, @NonNull String label,
                         int minimum, int maximum, int initial,
                         @NonNull IntConsumer changed) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 14, false);
        TextView value = text(String.valueOf(initial), 14, false);
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(52));
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = minimum + progress;
                value.setText(String.valueOf(selected));
                if (fromUser) changed.accept(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(title, new LinearLayout.LayoutParams(0, wrap(), .38f));
        row.addView(seek, new LinearLayout.LayoutParams(0, wrap(), .62f));
        row.addView(value);
        parent.addView(row, new LinearLayout.LayoutParams(match(), dp(54)));
    }

    @NonNull
    private EditText field(@NonNull LinearLayout parent, @NonNull String hint,
                           @NonNull String value, boolean numeric) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        if (numeric) field.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(field, new LinearLayout.LayoutParams(match(), dp(58)));
        return field;
    }

    @NonNull
    private EditText numericField(@NonNull LinearLayout parent, @NonNull String hint, int value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(String.valueOf(value));
        field.setSelectAllOnFocus(true);
        field.setGravity(Gravity.CENTER);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(field, new LinearLayout.LayoutParams(0, match(), 1f));
        return field;
    }

    private static int positive(@NonNull EditText field, @NonNull String name) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + ": укажите число от 1");
        }
    }

    private static void requireColor(@NonNull String raw) {
        String value = raw.trim();
        if (!value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            throw new IllegalArgumentException("Цвет должен быть в формате #RRGGBB");
        }
    }

    @NonNull
    private MaterialSwitch toggle(@NonNull String label, boolean checked) {
        MaterialSwitch value = new MaterialSwitch(this);
        value.setText(label);
        value.setChecked(checked);
        value.setMinHeight(dp(46));
        return value;
    }

    @NonNull
    private MaterialButton button(@NonNull String label) {
        MaterialButton value = new MaterialButton(this);
        value.setText(label);
        value.setAllCaps(false);
        return value;
    }

    @NonNull
    private MaterialButton compactButton(@NonNull String label) {
        MaterialButton value = button(label);
        value.setMinWidth(0);
        value.setMinimumWidth(0);
        value.setPadding(dp(12), 0, dp(12), 0);
        value.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(48)));
        return value;
    }

    @NonNull
    private TextView text(@NonNull String value, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return view;
    }

    @NonNull
    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    @NonNull
    private static String sourceSummary(@NonNull InformationPanelConfig.Item item) {
        String source;
        if (item.sourceKind == InformationPanelConfig.SourceKind.SYSTEM) {
            source = "Магнитола · " + item.sourceId;
        } else if (item.sourceKind == InformationPanelConfig.SourceKind.VEHICLE) {
            source = "Автомобиль · " + item.sourceId;
        } else if (item.binding != null) {
            source = item.binding.connectorType.name() + " · " + item.binding.resourceId;
        } else {
            source = "Источник не настроен";
        }
        return source + "\nячейка " + (item.column + 1) + "×" + (item.row + 1)
                + ", размер " + item.columnSpan + "×" + item.rowSpan
                + (item.enabled ? "" : " · скрыт");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams lp(int width, int height,
                                                int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(width, height);
        value.setMargins(left, top, right, bottom);
        return value;
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }

    private interface IntConsumer { void accept(int value); }

    private static final class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        private final IntConsumer listener;
        SimpleSeekListener(@NonNull IntConsumer listener) { this.listener = listener; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            listener.accept(progress);
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
