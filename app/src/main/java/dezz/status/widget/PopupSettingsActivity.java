/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.scenario.ScenarioPresets;

/** Visual overlay geometry and tile catalog; pixel/grid math is shown directly to the user. */
public final class PopupSettingsActivity extends AppCompatActivity {
    private Preferences prefs;
    private PopupItemConfigStore store;
    private List<PopupItemConfig> items = new ArrayList<>();
    private LinearLayout host;
    private TextView geometryPreview;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        store = new PopupItemConfigStore(prefs);
        setContentView(buildScreen());
    }

    @Override protected void onResume() {
        super.onResume();
        items = new ArrayList<>(store.load());
        render();
        updateGeometryPreview();
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(44));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Плавающий оверлей", 24), weighted());
        page.addView(header, matchWrap());

        CheckBox enabled = new CheckBox(this);
        enabled.setText("Использовать плавающий оверлей");
        enabled.setChecked(prefs.popupEnabled.get());
        enabled.setOnCheckedChangeListener((v, value) -> { prefs.popupEnabled.set(value); apply(); });
        page.addView(enabled, topMargin(8));
        page.addView(label("Позиция сохраняется после перетаскивания. Если все плитки скрыты "
                + "сценариями, пустой оверлей исчезает автоматически."), topMargin(5));

        page.addView(heading("Размер и сетка", 20), topMargin(20));
        geometryPreview = label("");
        page.addView(geometryPreview, topMargin(6));
        addPreferenceSlider(page, "Ширина оверлея", 200, 1600, prefs.popupWidth.get(),
                value -> prefs.popupWidth.set(value), " px");
        addPreferenceSlider(page, "Высота оверлея", 160, 1200, prefs.popupHeight.get(),
                value -> prefs.popupHeight.set(value), " px");
        addPreferenceSlider(page, "Столбцов в сетке", 1, 8, prefs.popupColumns.get(),
                value -> prefs.popupColumns.set(value), "");
        addPreferenceSlider(page, "Строк в сетке", 1, 8, prefs.popupRows.get(),
                value -> prefs.popupRows.set(value), "");
        addPreferenceSlider(page, "Промежуток между ячейками", 0, 60, prefs.popupCellGap.get(),
                value -> prefs.popupCellGap.set(value), " px");
        addPreferenceSlider(page, "Отступ сетки слева", 0, 100, prefs.popupPaddingLeft.get(),
                value -> prefs.popupPaddingLeft.set(value), " px");
        addPreferenceSlider(page, "Отступ сетки сверху", 0, 100, prefs.popupPaddingTop.get(),
                value -> prefs.popupPaddingTop.set(value), " px");
        addPreferenceSlider(page, "Отступ сетки справа", 0, 100, prefs.popupPaddingRight.get(),
                value -> prefs.popupPaddingRight.set(value), " px");
        addPreferenceSlider(page, "Отступ сетки снизу", 0, 100, prefs.popupPaddingBottom.get(),
                value -> prefs.popupPaddingBottom.set(value), " px");
        addPreferenceSlider(page, "Прозрачность фона", 0, 255, prefs.popupBackgroundAlpha.get(),
                value -> prefs.popupBackgroundAlpha.set(value), " / 255");
        addPreferenceSlider(page, "Скругление оверлея", 0, 120, prefs.popupCornerRadius.get(),
                value -> prefs.popupCornerRadius.set(value), " px");

        page.addView(heading("Плитки", 20), topMargin(24));
        page.addView(label("Выберите источник. После выбора откроется общий визуальный редактор."),
                topMargin(5));
        LinearLayout add = row();
        Button ha = button("+ HA");
        ha.setOnClickListener(v -> startActivity(new Intent(this, HomeAssistantSettingsActivity.class)));
        add.addView(ha, weighted());
        Button mqtt = button("+ MQTT");
        mqtt.setOnClickListener(v -> addMqtt());
        add.addView(mqtt, weighted());
        Button sprut = button("+ Sprut");
        sprut.setOnClickListener(v -> startActivity(new Intent(this, SprutHubSettingsActivity.class)));
        add.addView(sprut, weighted());
        Button builtin = button("+ Штатный");
        builtin.setOnClickListener(v -> addBuiltin());
        add.addView(builtin, weighted());
        page.addView(add, topMargin(10));

        host = column();
        page.addView(host, topMargin(10));
        return scroll;
    }

    private void addPreferenceSlider(LinearLayout parent, String title, int min, int max,
                                     int current, IntConsumer setter, String suffix) {
        LinearLayout labels = row();
        labels.addView(label(title), weighted());
        TextView value = label(current + suffix);
        labels.addView(value);
        parent.addView(labels, topMargin(8));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(clamp(current, min, max) - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = min + progress;
                value.setText(selected + suffix);
                setter.accept(selected);
                updateGeometryPreview();
                apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        parent.addView(seek, matchWrap());
    }

    private void updateGeometryPreview() {
        if (geometryPreview == null) return;
        int columns = Math.max(1, prefs.popupColumns.get());
        int rows = Math.max(1, prefs.popupRows.get());
        int usableWidth = Math.max(1, prefs.popupWidth.get() - prefs.popupPaddingLeft.get()
                - prefs.popupPaddingRight.get() - prefs.popupCellGap.get() * (columns - 1));
        int usableHeight = Math.max(1, prefs.popupHeight.get() - prefs.popupPaddingTop.get()
                - prefs.popupPaddingBottom.get() - prefs.popupCellGap.get() * (rows - 1));
        geometryPreview.setText("Оверлей: " + prefs.popupWidth.get() + "×" + prefs.popupHeight.get()
                + " px · сетка: " + columns + "×" + rows + "\nРазмер одной ячейки: примерно "
                + (usableWidth / columns) + "×" + (usableHeight / rows) + " px");
    }

    private void render() {
        if (host == null) return;
        host.removeAllViews();
        if (items.isEmpty()) {
            host.addView(label("Плиток пока нет."), topMargin(10));
            return;
        }
        for (PopupItemConfig item : items) host.addView(card(item), topMargin(10));
    }

    private View card(PopupItemConfig item) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor());
        background.setStroke(dp(1), 0x557F7F7F);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout description = column();
        description.addView(heading(item.name, 18));
        description.addView(label(sourceLabel(item)));
        top.addView(description, weighted());
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Показывать");
        enabled.setChecked(item.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> { item.enabled = value; persist(); });
        top.addView(enabled);
        card.addView(top, matchWrap());
        card.addView(label("Сетка: " + item.columnSpan + "×" + item.rowSpan + " · иконка: "
                + item.icon + " · " + (item.actionBinding != null && item.actionBinding.isBound()
                ? "управление назначено" : "только отображение")), topMargin(5));
        LinearLayout actions = row();
        Button up = button("↑"); up.setOnClickListener(v -> move(item, -1)); actions.addView(up);
        Button down = button("↓"); down.setOnClickListener(v -> move(item, 1)); actions.addView(down);
        Button edit = button("Оформление");
        edit.setOnClickListener(v -> startActivity(VisualBrickEditorActivity.intent(this,
                VisualBrickEditorActivity.SURFACE_POPUP, item.id)));
        actions.addView(edit, weighted());
        Button delete = button("Удалить"); delete.setOnClickListener(v -> confirmDelete(item));
        actions.addView(delete, weighted());
        card.addView(actions, topMargin(7));
        return card;
    }

    private void addMqtt() {
        LinearLayout form = column();
        form.setPadding(dp(18), dp(4), dp(18), 0);
        EditText name = field(form, "Название плитки", "MQTT");
        EditText topic = field(form, "Topic состояния", "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Новая MQTT-плитка")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton("Далее", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (text(topic).isEmpty()) {
                        Toast.makeText(this, "Укажите topic состояния", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        PopupItemConfig item = PopupItemConfig.create(uniqueId("mqtt_popup"), items.size());
                        item.name = text(name).isEmpty() ? "MQTT" : text(name);
                        item.title = item.name;
                        item.sourceBinding = new SourceBinding(ConnectorType.MQTT,
                                SourceBinding.DEFAULT_CONNECTOR_ID, text(topic), "",
                                SourceBinding.PRESENTATION_RAW, "");
                        item.displayRules = ScenarioPresets.raw();
                        item.actionBinding = ActionBinding.unbound();
                        items.add(item);
                        persist();
                        dialog.dismiss();
                        startActivity(VisualBrickEditorActivity.intent(this,
                                VisualBrickEditorActivity.SURFACE_POPUP, item.id));
                    } catch (Exception error) {
                        Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void addBuiltin() {
        BrickType[] types = BrickType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = friendlyBrick(types[i]);
        new AlertDialog.Builder(this).setTitle("Штатный элемент")
                .setItems(labels, (d, which) -> {
                    BrickType type = types[which];
                    try {
                        PopupItemConfig item = PopupItemConfig.create(uniqueId("builtin"), items.size());
                        item.type = PopupItemConfig.TYPE_BUILTIN;
                        item.builtinId = type.automationId();
                        item.name = labels[which];
                        item.title = labels[which];
                        item.sourceBinding = SourceBinding.unbound();
                        item.actionBinding = ActionBinding.unbound();
                        items.add(item);
                        persist();
                        startActivity(VisualBrickEditorActivity.intent(this,
                                VisualBrickEditorActivity.SURFACE_POPUP, item.id));
                    } catch (Exception error) {
                        Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void move(PopupItemConfig item, int delta) {
        int from = items.indexOf(item), to = from + delta;
        if (from < 0 || to < 0 || to >= items.size()) return;
        Collections.swap(items, from, to);
        persist(); render();
    }

    private void confirmDelete(PopupItemConfig item) {
        new AlertDialog.Builder(this).setTitle("Удалить плитку?").setMessage(item.name)
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> {
                    items.remove(item); persist(); render();
                }).show();
    }

    private void persist() {
        try {
            store.save(items);
            apply();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось сохранить: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void apply() {
        if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
    }

    private String uniqueId(String prefix) {
        int suffix = 1;
        while (true) {
            String value = prefix + "_" + suffix++;
            boolean used = false;
            for (PopupItemConfig item : items) if (item.id.equals(value)) { used = true; break; }
            if (!used) return value;
        }
    }

    private static String sourceLabel(PopupItemConfig item) {
        if (PopupItemConfig.TYPE_BUILTIN.equals(item.type)) return "Штатный элемент · " + item.builtinId;
        SourceBinding value = item.sourceBinding;
        if (value == null || !value.isBound()) return "Без источника";
        String connector = value.connectorType == ConnectorType.HOME_ASSISTANT ? "Home Assistant"
                : value.connectorType == ConnectorType.SPRUTHUB ? "Sprut.hub" : "MQTT";
        return connector + " · " + value.resourceId
                + (value.valuePath.isEmpty() ? "" : " · " + value.valuePath);
    }

    private static String friendlyBrick(BrickType type) {
        switch (type) {
            case TIME: return "Время";
            case DATE: return "Дата";
            case MEDIA: return "Мультимедиа";
            case WIFI: return "Wi‑Fi";
            case GPS: return "GPS";
            case BLUETOOTH: return "Bluetooth";
            case INDOOR_TEMP: return "Температура в салоне";
            case OUTDOOR_TEMP: return "Температура снаружи";
            case HOME_ASSISTANT: return "Устройства умного дома";
            default: return type.name();
        }
    }

    private EditText field(LinearLayout parent, String title, String value) {
        parent.addView(label(title), topMargin(8));
        EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(value);
        parent.addView(edit, matchWrap()); return edit;
    }
    private int surfaceColor() { return (getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFF202124 : 0xFFF5F5F5; }
    private TextView heading(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private TextView label(String text) { TextView v = new TextView(this); v.setText(text); v.setTextSize(14); return v; }
    private Button button(String text) { Button v = new Button(this); v.setText(text); v.setMinWidth(0); v.setMinimumWidth(0); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams topMargin(int value) { LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(value); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String text(EditText value) { return value.getText() == null ? "" : value.getText().toString().trim(); }
    private static String safeMessage(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}
