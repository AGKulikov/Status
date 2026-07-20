/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.mqtt.MqttController;

/**
 * Source-native editor for the automation layer. Kept independent from the upstream brick card
 * so adding new HA fields never risks breaking the original settings adapter.
 */
public final class AutomationSettingsActivity extends AppCompatActivity {
    private final android.os.Handler statusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            refreshMqttStatus();
            statusHandler.postDelayed(this, 1000L);
        }
    };
    private Preferences prefs;
    private HaBrickConfigStore configs;
    private final List<BrickEditor> editors = new ArrayList<>();
    private LinearLayout editorContainer;

    private CheckBox mqttEnabled;
    private CheckBox mqttTls;
    private CheckBox mqttKeepAwake;
    private EditText mqttHost;
    private EditText mqttPort;
    private EditText mqttUsername;
    private EditText mqttPassword;
    private EditText mqttClientId;
    private EditText mqttDeviceId;
    private EditText mqttBaseTopic;
    private EditText mqttKeepAlive;
    private EditText mqttQos;
    private TextView mqttStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Preferences(this);
        configs = new HaBrickConfigStore(prefs);
        setContentView(buildScreen());
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        TextView title = heading(getString(R.string.automation_settings_title), 24);
        header.addView(title, weighted());
        page.addView(header, matchWrap());

        Button popupSettings = new Button(this);
        popupSettings.setText(R.string.popup_settings_button);
        popupSettings.setOnClickListener(v ->
                startActivity(new Intent(this, PopupSettingsActivity.class)));
        page.addView(popupSettings, topMargin(12));

        page.addView(heading(getString(R.string.mqtt_section_title), 20), topMargin(18));
        TextView topicHint = label("Retained topics: statuswidget/v1/{device}/state/{main|builtin|popup}/{id}");
        page.addView(topicHint, matchWrap());
        mqttStatus = label("");
        page.addView(mqttStatus, matchWrap());
        refreshMqttStatus();
        mqttEnabled = check("Включить постоянное MQTT-подключение", prefs.mqttEnabled.get());
        mqttTls = check("TLS", prefs.mqttTls.get());
        mqttKeepAwake = check("Не давать MQTT-потоку засыпать", prefs.mqttKeepAwake.get());
        page.addView(mqttEnabled);
        mqttHost = field(page, "Адрес брокера", prefs.mqttHost.get(), false);
        mqttPort = field(page, "Порт", String.valueOf(prefs.mqttPort.get()), true);
        mqttTls = addCheck(page, mqttTls);
        mqttUsername = field(page, "Пользователь MQTT", prefs.mqttUsername.get(), false);
        mqttPassword = field(page, "Пароль MQTT", prefs.mqttPassword.get(), false);
        mqttPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mqttClientId = field(page, "Client ID (пусто = автоматически)", prefs.mqttClientId.get(), false);
        mqttDeviceId = field(page, "ID магнитолы в topic", prefs.mqttDeviceId.get(), false);
        mqttBaseTopic = field(page, "Базовый topic", prefs.mqttBaseTopic.get(), false);
        mqttQos = field(page, "QoS состояний и команд (0 или 1)",
                String.valueOf(prefs.mqttQos.get()), true);
        mqttKeepAlive = field(page, "Keepalive, секунд", String.valueOf(prefs.mqttKeepAliveSeconds.get()), true);
        mqttKeepAwake = addCheck(page, mqttKeepAwake);

        LinearLayout haHeader = row();
        haHeader.setGravity(Gravity.CENTER_VERTICAL);
        haHeader.addView(heading(getString(R.string.ha_bricks_section_title), 20), weighted());
        Button add = new Button(this);
        add.setText(R.string.ha_add_brick);
        add.setOnClickListener(v -> addNewBrick());
        haHeader.addView(add);
        page.addView(haHeader, topMargin(24));

        editorContainer = column();
        page.addView(editorContainer, matchWrap());
        for (HaBrickConfig config : configs.loadMain()) addEditor(config);

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(v -> saveAll());
        LinearLayout.LayoutParams saveParams = matchWrap();
        saveParams.topMargin = dp(24);
        page.addView(save, saveParams);
        return scroll;
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusHandler.removeCallbacks(statusTick);
        statusHandler.post(statusTick);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(statusTick);
        super.onPause();
    }

    private void refreshMqttStatus() {
        if (mqttStatus == null) return;
        mqttStatus.setText("Состояние: " + (MqttController.isConnected()
                ? "подключено" : MqttController.connectionDetail()));
    }

    private void addNewBrick() {
        int suffix = 1;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (BrickEditor editor : editors) used.add(editor.id.getText().toString().trim());
        while (used.contains("brick_" + suffix)) suffix++;
        addEditor(HaBrickConfig.create("brick_" + suffix, editors.size()));
    }

    private void addEditor(HaBrickConfig config) {
        BrickEditor editor = new BrickEditor(config);
        editors.add(editor);
        editorContainer.addView(editor.root, cardParams());
    }

    private void move(BrickEditor editor, int delta) {
        int from = editors.indexOf(editor);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= editors.size()) return;
        Collections.swap(editors, from, to);
        editorContainer.removeAllViews();
        for (BrickEditor item : editors) editorContainer.addView(item.root, cardParams());
    }

    private void remove(BrickEditor editor) {
        editors.remove(editor);
        editorContainer.removeView(editor.root);
    }

    private void duplicate(BrickEditor source) {
        try {
            java.util.Set<String> used = new java.util.HashSet<>();
            for (BrickEditor editor : editors) used.add(text(editor.id));
            String base = text(source.id).isEmpty() ? "brick" : text(source.id);
            int suffix = 2;
            String next = base + "_copy";
            while (used.contains(next)) next = base + "_copy" + suffix++;
            JSONObject json = source.read(editors.indexOf(source)).toJson();
            json.put("id", next).put("name", text(source.name) + " (копия)")
                    .put("order", editors.indexOf(source) + 1);
            BrickEditor copy = new BrickEditor(HaBrickConfig.fromJson(json, editors.size()));
            editors.add(editors.indexOf(source) + 1, copy);
            editorContainer.removeAllViews();
            for (BrickEditor editor : editors) editorContainer.addView(editor.root, cardParams());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.invalid_settings, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveAll() {
        try {
            prefs.mqttEnabled.set(mqttEnabled.isChecked());
            prefs.mqttHost.set(text(mqttHost));
            prefs.mqttPort.set(clamp(number(mqttPort, 1883), 1, 65535));
            prefs.mqttTls.set(mqttTls.isChecked());
            prefs.mqttUsername.set(text(mqttUsername));
            prefs.mqttPassword.set(text(mqttPassword));
            prefs.mqttClientId.set(text(mqttClientId));
            prefs.mqttDeviceId.set(text(mqttDeviceId));
            prefs.mqttBaseTopic.set(text(mqttBaseTopic));
            prefs.mqttQos.set(clamp(number(mqttQos, 1), 0, 1));
            prefs.mqttKeepAliveSeconds.set(clamp(number(mqttKeepAlive, 30), 10, 600));
            prefs.mqttKeepAwake.set(mqttKeepAwake.isChecked());

            List<HaBrickConfig> result = new ArrayList<>();
            for (int i = 0; i < editors.size(); i++) result.add(editors.get(i).read(i));
            configs.saveMain(result);
            if (!result.isEmpty()) {
                List<BrickType> order = BrickType.parseOrder(prefs.brickOrder.get());
                if (!order.contains(BrickType.HOME_ASSISTANT)) {
                    order.add(BrickType.HOME_ASSISTANT);
                    prefs.brickOrder.set(BrickType.serializeOrder(order));
                }
            }
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.invalid_settings,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private final class BrickEditor {
        final LinearLayout root = column();
        final EditText id;
        final EditText name;
        final CheckBox enabled;
        final EditText defaultText;
        final EditText defaultColor;
        final EditText pendingText;
        final EditText pendingColor;
        final EditText staleText;
        final EditText staleColor;
        final EditText emptyText;
        final EditText emptyColor;
        final EditText fontSize;
        final EditText fontFamily;
        final CheckBox bold;
        final CheckBox italic;
        final EditText contentAlpha;
        final EditText outlineColor;
        final EditText outlineAlpha;
        final EditText outlineWidth;
        final EditText marginStart;
        final EditText marginEnd;
        final EditText paddingLeft;
        final EditText paddingTop;
        final EditText paddingRight;
        final EditText paddingBottom;
        final EditText adjustY;
        final EditText maxWidth;
        final CheckBox marquee;
        final EditText staleSeconds;
        final CheckBox collapseWhenEmpty;
        final EditText hideInPackages;
        final CheckBox inheritGroupHide;
        final CheckBox hideKeepsSpace;

        BrickEditor(HaBrickConfig c) {
            root.setPadding(dp(14), dp(14), dp(14), dp(14));
            GradientDrawable background = new GradientDrawable();
            background.setColor(resolveSurfaceColor());
            background.setStroke(dp(1), 0x557F7F7F);
            background.setCornerRadius(dp(16));
            root.setBackground(background);

            LinearLayout actions = row();
            TextView title = heading(c.name, 18);
            actions.addView(title, weighted());
            Button up = smallButton(R.string.move_up);
            Button down = smallButton(R.string.move_down);
            Button copy = smallButton("Копия");
            Button delete = smallButton(R.string.delete);
            up.setOnClickListener(v -> move(this, -1));
            down.setOnClickListener(v -> move(this, 1));
            copy.setOnClickListener(v -> duplicate(this));
            delete.setOnClickListener(v -> remove(this));
            actions.addView(up);
            actions.addView(down);
            actions.addView(copy);
            actions.addView(delete);
            root.addView(actions);

            enabled = check("Включён", c.enabled);
            root.addView(enabled);
            id = field(root, "Уникальный ID / brick_id", c.id, false);
            name = field(root, "Название в настройках", c.name, false);
            defaultText = field(root, "Собственный текст по умолчанию", c.defaultText, false);
            defaultColor = field(root, "Цвет обычного статуса (#AARRGGBB)", c.defaultColor, false);
            pendingText = field(root, "Текст до первого актуального статуса", c.pendingText, false);
            pendingColor = field(root, "Цвет до первого актуального статуса", c.pendingColor, false);
            staleText = field(root, "Текст устаревшего статуса", c.staleText, false);
            staleColor = field(root, "Цвет устаревшего статуса", c.staleColor, false);
            emptyText = field(root, "Текст при пустом статусе", c.emptyText, false);
            emptyColor = field(root, "Цвет при пустом статусе", c.emptyColor, false);
            fontSize = field(root, "Размер шрифта, px", String.valueOf(c.fontSize), true);
            fontFamily = field(root, "Шрифт", c.fontFamily, false);
            bold = check("Жирный", c.bold);
            italic = check("Курсив", c.italic);
            root.addView(bold);
            root.addView(italic);
            contentAlpha = field(root, "Прозрачность элемента, 0–255", String.valueOf(c.contentAlpha), true);
            outlineColor = field(root, "Цвет обводки", c.outlineColor, false);
            outlineAlpha = field(root, "Прозрачность обводки, 0–255", String.valueOf(c.outlineAlpha), true);
            outlineWidth = field(root, "Толщина обводки, px", String.valueOf(c.outlineWidth), true);
            marginStart = field(root, "Отступ слева, px", String.valueOf(c.marginStart), true);
            marginEnd = field(root, "Отступ справа, px", String.valueOf(c.marginEnd), true);
            paddingLeft = field(root, "Внутренний отступ слева, px", String.valueOf(c.paddingLeft), true);
            paddingTop = field(root, "Внутренний отступ сверху, px", String.valueOf(c.paddingTop), true);
            paddingRight = field(root, "Внутренний отступ справа, px", String.valueOf(c.paddingRight), true);
            paddingBottom = field(root, "Внутренний отступ снизу, px", String.valueOf(c.paddingBottom), true);
            adjustY = field(root, "Вертикальное смещение, px", String.valueOf(c.adjustY), true);
            maxWidth = field(root, "Максимальная ширина, px", String.valueOf(c.maxWidth), true);
            marquee = check("Прокручивать длинный текст", c.marquee);
            root.addView(marquee);
            staleSeconds = field(root, "Считать неактуальным через, секунд (0 = никогда)",
                    String.valueOf(c.staleAfterSeconds), true);
            collapseWhenEmpty = check("Полностью скрывать при пустом тексте", c.collapseWhenEmpty);
            root.addView(collapseWhenEmpty);
            hideInPackages = field(root, "Скрывать в пакетах приложений (через запятую)",
                    android.text.TextUtils.join(",", c.hideInPackages), false);
            inheritGroupHide = check("Дополнительно наследовать скрытие общего HA-блока",
                    c.inheritGroupHide);
            hideKeepsSpace = check("При скрытии в приложении сохранять место", c.hideKeepsSpace);
            root.addView(inheritGroupHide);
            root.addView(hideKeepsSpace);
        }

        HaBrickConfig read(int order) throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", text(id));
            o.put("name", text(name));
            o.put("enabled", enabled.isChecked());
            o.put("order", order);
            o.put("defaultText", text(defaultText));
            o.put("defaultColor", text(defaultColor));
            o.put("pendingText", text(pendingText));
            o.put("pendingColor", text(pendingColor));
            o.put("staleText", text(staleText));
            o.put("staleColor", text(staleColor));
            o.put("emptyText", text(emptyText));
            o.put("emptyColor", text(emptyColor));
            o.put("fontSize", number(fontSize, 40));
            o.put("fontFamily", text(fontFamily));
            o.put("bold", bold.isChecked());
            o.put("italic", italic.isChecked());
            o.put("contentAlpha", number(contentAlpha, 255));
            o.put("outlineColor", text(outlineColor));
            o.put("outlineAlpha", number(outlineAlpha, 170));
            o.put("outlineWidth", number(outlineWidth, 2));
            o.put("marginStart", number(marginStart, 0));
            o.put("marginEnd", number(marginEnd, 0));
            o.put("paddingLeft", number(paddingLeft, 0));
            o.put("paddingTop", number(paddingTop, 0));
            o.put("paddingRight", number(paddingRight, 0));
            o.put("paddingBottom", number(paddingBottom, 0));
            o.put("adjustY", number(adjustY, 0));
            o.put("maxWidth", number(maxWidth, 500));
            o.put("marquee", marquee.isChecked());
            o.put("staleAfterSeconds", longNumber(staleSeconds, 0));
            o.put("collapseWhenEmpty", collapseWhenEmpty.isChecked());
            o.put("inheritGroupHide", inheritGroupHide.isChecked());
            o.put("hideKeepsSpace", hideKeepsSpace.isChecked());
            org.json.JSONArray hidden = new org.json.JSONArray();
            for (String packageName : text(hideInPackages).split(",")) {
                packageName = packageName.trim();
                if (!packageName.isEmpty()) hidden.put(packageName);
            }
            o.put("hideInPackages", hidden);
            return HaBrickConfig.fromJson(o, order);
        }
    }

    private TextInputEditText field(LinearLayout parent, String hint, String value, boolean number) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        TextInputEditText input = new TextInputEditText(this);
        input.setText(value);
        input.setSingleLine(true);
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        layout.addView(input, matchWrap());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        parent.addView(layout, lp);
        return input;
    }

    private CheckBox addCheck(LinearLayout parent, CheckBox check) {
        parent.addView(check, matchWrap());
        return check;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(text);
        check.setChecked(checked);
        check.setMinHeight(dp(48));
        return check;
    }

    private Button smallButton(int textRes) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private TextView heading(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setAlpha(0.75f);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams topMargin(int valueDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(valueDp);
        return lp;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        return lp;
    }

    private int resolveSurfaceColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface,
                value, true)) return value.data;
        return Color.TRANSPARENT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String text(EditText input) { return input.getText().toString().trim(); }
    private static int number(EditText input, int fallback) {
        try { return Integer.parseInt(text(input)); } catch (NumberFormatException e) { return fallback; }
    }
    private static long longNumber(EditText input, long fallback) {
        try { return Long.parseLong(text(input)); } catch (NumberFormatException e) { return fallback; }
    }
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
