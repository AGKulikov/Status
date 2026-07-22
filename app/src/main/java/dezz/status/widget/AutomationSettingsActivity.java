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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.ScenarioPresets;

/** Visual catalog of connector-neutral main-row elements. */
public final class AutomationSettingsActivity extends AppCompatActivity {
    private Preferences prefs;
    private HaBrickConfigStore store;
    private List<HaBrickConfig> items = new ArrayList<>();
    private LinearLayout host;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        store = new HaBrickConfigStore(prefs);
        setContentView(buildScreen());
    }

    @Override protected void onResume() {
        super.onResume();
        items = new ArrayList<>(store.loadMain());
        render();
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Элементы основной строки", 24), weighted());
        page.addView(header, matchWrap());

        page.addView(label("Добавьте устройство из нужной системы. После выбора сразу откроется "
                + "визуальный редактор — технические поля заполняются автоматически."), topMargin(7));

        LinearLayout sourceButtons = row();
        Button ha = button("+ HA");
        ha.setOnClickListener(v -> startActivity(new Intent(this, HomeAssistantSettingsActivity.class)));
        sourceButtons.addView(ha, weighted());
        Button mqtt = button("+ MQTT");
        mqtt.setOnClickListener(v -> addMqtt());
        sourceButtons.addView(mqtt, weighted());
        Button sprut = button("+ Sprut.hub");
        sprut.setOnClickListener(v -> startActivity(new Intent(this, SprutHubSettingsActivity.class)));
        sourceButtons.addView(sprut, weighted());
        page.addView(sourceButtons, topMargin(14));

        host = column();
        page.addView(host, topMargin(12));
        return scroll;
    }

    private void render() {
        if (host == null) return;
        host.removeAllViews();
        if (items.isEmpty()) {
            host.addView(label("Пока нет добавленных элементов. Выберите HA, MQTT или Sprut.hub."),
                    topMargin(12));
            return;
        }
        for (HaBrickConfig item : items) host.addView(card(item), topMargin(10));
    }

    private View card(HaBrickConfig item) {
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
        description.addView(label(sourceLabel(item.sourceBinding)));
        top.addView(description, weighted());
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Показывать");
        enabled.setChecked(item.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> { item.enabled = value; persist(); });
        top.addView(enabled);
        card.addView(top, matchWrap());

        TextView summary = label("Шрифт " + item.fontSize + " px · ширина до " + item.maxWidth
                + " px · " + (item.displayRules == null ? "без правил" : "цвета по состоянию"));
        card.addView(summary, topMargin(5));

        LinearLayout actions = row();
        Button up = button("↑");
        up.setContentDescription("Выше");
        up.setOnClickListener(v -> move(item, -1));
        actions.addView(up);
        Button down = button("↓");
        down.setContentDescription("Ниже");
        down.setOnClickListener(v -> move(item, 1));
        actions.addView(down);
        Button edit = button("Оформление");
        edit.setOnClickListener(v -> startActivity(VisualBrickEditorActivity.intent(this,
                VisualBrickEditorActivity.SURFACE_MAIN, item.id)));
        actions.addView(edit, weighted());
        Button delete = button("Удалить");
        delete.setOnClickListener(v -> confirmDelete(item));
        actions.addView(delete, weighted());
        card.addView(actions, topMargin(7));
        return card;
    }

    private void addMqtt() {
        LinearLayout form = column();
        form.setPadding(dp(18), dp(4), dp(18), 0);
        form.addView(label("Topic состояния можно указать целиком. Цвета и оформление задаются "
                + "на следующем экране."));
        EditText name = field(form, "Понятное название", "Новый MQTT-элемент");
        EditText topic = field(form, "Topic состояния", "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Добавить MQTT-элемент")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton("Далее", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String topicValue = text(topic);
                    if (topicValue.isEmpty()) {
                        Toast.makeText(this, "Укажите topic состояния", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        String id = uniqueId("mqtt");
                        HaBrickConfig item = HaBrickConfig.create(id, items.size());
                        item.name = text(name).isEmpty() ? "MQTT" : text(name);
                        item.sourceBinding = new SourceBinding(ConnectorType.MQTT,
                                SourceBinding.DEFAULT_CONNECTOR_ID, topicValue, "",
                                SourceBinding.PRESENTATION_RAW, "");
                        item.displayRules = ScenarioPresets.raw();
                        items.add(item);
                        persist();
                        dialog.dismiss();
                        startActivity(VisualBrickEditorActivity.intent(this,
                                VisualBrickEditorActivity.SURFACE_MAIN, item.id));
                    } catch (Exception error) {
                        Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void move(HaBrickConfig item, int delta) {
        int from = items.indexOf(item);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= items.size()) return;
        Collections.swap(items, from, to);
        persist();
        render();
    }

    private void confirmDelete(HaBrickConfig item) {
        new AlertDialog.Builder(this).setTitle("Удалить элемент?")
                .setMessage(item.name + "\nИсточник останется в вашей системе умного дома.")
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> {
                    items.remove(item);
                    persist();
                    render();
                }).show();
    }

    private void persist() {
        try {
            store.saveMain(items);
            if (!items.isEmpty()) {
                List<BrickType> order = BrickType.parseOrder(prefs.brickOrder.get());
                if (!order.contains(BrickType.HOME_ASSISTANT)) {
                    order.add(BrickType.HOME_ASSISTANT);
                    prefs.brickOrder.set(BrickType.serializeOrder(order));
                }
            }
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось сохранить: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private String uniqueId(String prefix) {
        int suffix = 1;
        while (true) {
            String candidate = prefix + "_" + suffix++;
            boolean used = false;
            for (HaBrickConfig item : items) if (item.id.equals(candidate)) { used = true; break; }
            if (!used) return candidate;
        }
    }

    private EditText field(LinearLayout parent, String title, String value) {
        parent.addView(label(title), topMargin(8));
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        parent.addView(edit, matchWrap());
        return edit;
    }

    private static String sourceLabel(SourceBinding value) {
        if (value == null || !value.isBound()) return "Источник не выбран";
        String connector;
        switch (value.connectorType) {
            case HOME_ASSISTANT: connector = "Home Assistant"; break;
            case SPRUTHUB: connector = "Sprut.hub"; break;
            default: connector = "MQTT"; break;
        }
        return connector + " · " + value.resourceId
                + (value.valuePath.isEmpty() ? "" : " · " + value.valuePath);
    }

    private int surfaceColor() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFF202124 : 0xFFF5F5F5;
    }
    private TextView heading(String text, int size) { TextView v = new TextView(this); v.setText(text);
        v.setTextSize(size); v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private TextView label(String text) { TextView v = new TextView(this); v.setText(text); v.setTextSize(14); return v; }
    private Button button(String text) { Button v = new Button(this); v.setText(text); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams topMargin(int value) { LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(value); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String text(EditText value) { return value.getText() == null ? "" : value.getText().toString().trim(); }
    private static String safeMessage(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}
