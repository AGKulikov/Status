/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

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

import dezz.status.widget.mqtt.MqttController;

/** Human-facing MQTT connection editor. Brick appearance is deliberately kept elsewhere. */
public final class MqttSettingsActivity extends AppCompatActivity {
    private Preferences prefs;
    private CheckBox enabled;
    private CheckBox tls;
    private CheckBox keepAwake;
    private EditText host;
    private EditText port;
    private EditText username;
    private EditText password;
    private EditText clientId;
    private EditText deviceId;
    private EditText baseTopic;
    private EditText qos;
    private EditText keepAlive;
    private TextView status;
    private LinearLayout advanced;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        View screen = buildScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹");
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Подключение MQTT", 24), weighted());
        page.addView(header, matchWrap());

        status = label("");
        page.addView(status, topMargin(8));
        refreshStatus();

        enabled = check("Использовать MQTT", prefs.mqttEnabled.get());
        page.addView(enabled, topMargin(12));
        host = field(page, "Адрес брокера", prefs.mqttHost.get(), false);
        port = field(page, "Порт", String.valueOf(prefs.mqttPort.get()), true);
        tls = check("Защищённое соединение TLS", prefs.mqttTls.get());
        page.addView(tls, topMargin(6));
        username = field(page, "Имя пользователя", prefs.mqttUsername.get(), false);
        password = field(page, "Новый пароль (пусто — оставить текущий)", "", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Button advancedButton = new Button(this);
        advancedButton.setText("Дополнительные параметры");
        page.addView(advancedButton, topMargin(16));
        advanced = column();
        advanced.setVisibility(View.GONE);
        clientId = field(advanced, "Client ID (пусто — автоматически)",
                prefs.mqttClientId.get(), false);
        deviceId = field(advanced, "Имя этой магнитолы", prefs.mqttDeviceId.get(), false);
        baseTopic = field(advanced, "Базовый topic", prefs.mqttBaseTopic.get(), false);
        qos = field(advanced, "QoS (0 или 1)", String.valueOf(prefs.mqttQos.get()), true);
        keepAlive = field(advanced, "Проверка связи, секунд",
                String.valueOf(prefs.mqttKeepAliveSeconds.get()), true);
        keepAwake = check("Поддерживать связь при погасшем экране", prefs.mqttKeepAwake.get());
        advanced.addView(keepAwake, topMargin(6));
        page.addView(advanced, matchWrap());
        advancedButton.setOnClickListener(v -> advanced.setVisibility(
                advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        TextView hint = label("После сохранения виджет переподключится сам. Старое состояние "
                + "останется отмеченным как неактуальное до получения свежих retained-данных.");
        page.addView(hint, topMargin(14));

        Button save = new Button(this);
        save.setText("Сохранить и подключиться");
        save.setOnClickListener(v -> save());
        page.addView(save, topMargin(18));
        return scroll;
    }

    private void save() {
        try {
            String hostValue = text(host);
            if (enabled.isChecked() && hostValue.isEmpty()) {
                throw new IllegalArgumentException("Укажите адрес брокера");
            }
            prefs.mqttEnabled.set(enabled.isChecked());
            prefs.mqttHost.set(hostValue);
            prefs.mqttPort.set(clamp(number(port, 1883), 1, 65535));
            prefs.mqttTls.set(tls.isChecked());
            prefs.mqttUsername.set(text(username));
            if (!text(password).isEmpty()) prefs.mqttPassword.set(text(password));
            prefs.mqttClientId.set(text(clientId));
            prefs.mqttDeviceId.set(text(deviceId).isEmpty() ? "geely" : text(deviceId));
            prefs.mqttBaseTopic.set(text(baseTopic).isEmpty()
                    ? "statuswidget/v1" : text(baseTopic));
            prefs.mqttQos.set(clamp(number(qos, 1), 0, 1));
            prefs.mqttKeepAliveSeconds.set(clamp(number(keepAlive, 30), 10, 600));
            prefs.mqttKeepAwake.set(keepAwake.isChecked());
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            Toast.makeText(this, "Настройки MQTT сохранены", Toast.LENGTH_SHORT).show();
            refreshStatus();
        } catch (Exception error) {
            Toast.makeText(this, "Проверьте настройки: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setText("Состояние: " + (MqttController.isConnected()
                ? "подключено" : MqttController.connectionDetail()));
    }

    private EditText field(LinearLayout parent, String title, String value, boolean numeric) {
        parent.addView(label(title), topMargin(9));
        EditText edit = new EditText(this);
        edit.setText(value == null ? "" : value);
        edit.setSingleLine(true);
        if (numeric) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(edit, matchWrap());
        return edit;
    }

    private CheckBox check(String title, boolean checked) {
        CheckBox view = new CheckBox(this);
        view.setText(title);
        view.setChecked(checked);
        view.setMinHeight(dp(48));
        return view;
    }

    private TextView heading(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        return view;
    }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams topMargin(int value) { LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(value); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String text(EditText value) { return value.getText() == null ? ""
            : value.getText().toString().trim(); }
    private static int number(EditText value, int fallback) { try {
        return Integer.parseInt(text(value)); } catch (NumberFormatException ignored) { return fallback; } }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String safeMessage(Throwable error) { return error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage(); }
}
