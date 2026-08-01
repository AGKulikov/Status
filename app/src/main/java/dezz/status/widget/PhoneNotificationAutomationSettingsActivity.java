/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.phone.PhoneNotificationAutomation;
import dezz.status.widget.phone.PhoneStatusBarPolicy;
import dezz.status.widget.scenario.TargetScope;
import dezz.status.widget.settings.AppleColorPickerDialog;

/** Presentation and conditions of live iPhone notifications, kept in the Automations section. */
public final class PhoneNotificationAutomationSettingsActivity extends AppCompatActivity {
    private Preferences prefs;
    private Switch statusRow;
    private Switch popup;
    private TextView fieldsSummary;
    private TextView durationSummary;
    private MaterialButton statusColor;
    private final Set<String> selectedFields = new LinkedHashSet<>();
    private int durationSeconds;
    private String tickerColor;
    private boolean binding;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        reload();
        View screen = buildScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null || statusRow == null) return;
        reload();
        binding = true;
        statusRow.setChecked(prefs.phoneStatusBarNotificationsEnabled.get());
        popup.setChecked(prefs.phonePopupNotificationsEnabled.get());
        binding = false;
        refreshSummaries();
    }

    private void reload() {
        selectedFields.clear();
        selectedFields.addAll(PhoneStatusBarPolicy.parseIds(
                prefs.phoneStatusBarNotificationFields.get(),
                PhoneStatusBarPolicy.notificationFieldIds()));
        durationSeconds = clamp(prefs.phoneStatusBarNotificationSeconds.get(), 1, 120);
        tickerColor = validColor(prefs.phoneStatusBarNotificationColor.get());
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
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Уведомления телефона", 24), weighted());
        page.addView(header, matchWrap());

        page.addView(label("Новейшее уведомление iPhone может независимо появляться в строке "
                + "состояния и в отдельном плавающем оверлее. Если во время показа приходит "
                + "следующее уведомление, оно сразу заменяет предыдущее."), topMargin(8));

        LinearLayout destinations = card();
        statusRow = switchView("Показывать в строке состояния",
                prefs.phoneStatusBarNotificationsEnabled.get());
        destinations.addView(statusRow, matchWrap());
        popup = switchView("Показывать во всплывающем оверлее",
                prefs.phonePopupNotificationsEnabled.get());
        destinations.addView(popup, topMargin(8));
        page.addView(destinations, topMargin(16));

        statusRow.setOnCheckedChangeListener((button, checked) -> persist());
        popup.setOnCheckedChangeListener((button, checked) -> persist());

        Button fields = button("Состав текста уведомления");
        fields.setOnClickListener(view -> chooseFields());
        page.addView(fields, topMargin(14));
        fieldsSummary = label("");
        page.addView(fieldsSummary, topMargin(4));

        Button duration = button("Длительность показа");
        duration.setOnClickListener(view -> chooseDuration());
        page.addView(duration, topMargin(10));
        durationSummary = label("");
        page.addView(durationSummary, topMargin(4));

        statusColor = new MaterialButton(this);
        statusColor.setOnClickListener(view -> chooseStatusColor());
        page.addView(statusColor, topMargin(10));

        page.addView(heading("Всплывающий оверлей", 20), topMargin(24));
        page.addView(label("Размер и положение окна, компоновка иконки и текста, шрифты, "
                + "цвета, рамка, фон, прозрачность и внутренние отступы настраиваются "
                + "в обычном визуальном редакторе."), topMargin(5));
        Button firstAppearance = button("Настроить первое уведомление · без иконки");
        firstAppearance.setOnClickListener(view ->
                openPopupEditor(PhoneNotificationAutomation.OVERLAY_ID));
        page.addView(firstAppearance, topMargin(10));
        Button cachedAppearance = button("Настроить повторные · с иконкой");
        cachedAppearance.setOnClickListener(view ->
                openPopupEditor(PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID));
        page.addView(cachedAppearance, topMargin(8));

        page.addView(heading("Условия отдельных полей", 20), topMargin(20));
        page.addView(label("Приложение, тема и текст проверяются независимо. Одно условие "
                + "действует одновременно в строке состояния и во всплывающем окне — например, "
                + "при пассажире можно оставить приложение, но скрыть тему и текст."),
                topMargin(5));
        Button applicationConditions = button("Условия · Приложение");
        applicationConditions.setOnClickListener(view ->
                openFieldConditions(PhoneStatusBarPolicy.FIELD_APPLICATION));
        page.addView(applicationConditions, topMargin(10));
        Button topicConditions = button("Условия · Тема");
        topicConditions.setOnClickListener(view ->
                openFieldConditions(PhoneStatusBarPolicy.FIELD_TOPIC));
        page.addView(topicConditions, topMargin(8));
        Button textConditions = button("Условия · Текст");
        textConditions.setOnClickListener(view ->
                openFieldConditions(PhoneStatusBarPolicy.FIELD_TEXT));
        page.addView(textConditions, topMargin(8));
        Button overlayConditions = button("Условия всего всплывающего окна");
        overlayConditions.setOnClickListener(view -> openOverlayConditions());
        page.addView(overlayConditions, topMargin(8));
        page.addView(label("Доступны время, присутствие пассажира, видимость элементов других "
                + "автоматизаций и состояние любого устройства Home Assistant, MQTT или "
                + "Sprut.hub."), topMargin(4));

        Button filters = button("Категории и приложения iPhone");
        filters.setOnClickListener(view -> startActivity(
                new Intent(this, PhoneConnectorSettingsActivity.class)));
        page.addView(filters, topMargin(16));
        page.addView(label("Фильтры определяют, какие уведомления телефон передаёт. "
                + "Оформление и условия задаются здесь."), topMargin(4));

        refreshSummaries();
        return scroll;
    }

    private void chooseFields() {
        List<PhoneStatusBarPolicy.NotificationField> fields =
                PhoneStatusBarPolicy.notificationFields();
        String[] labels = new String[fields.size()];
        boolean[] checked = new boolean[fields.size()];
        Set<String> working = new LinkedHashSet<>(selectedFields);
        for (int index = 0; index < fields.size(); index++) {
            labels[index] = fields.get(index).label;
            checked[index] = working.contains(fields.get(index).id);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Состав текста уведомления")
                .setMultiChoiceItems(labels, checked, (choice, which, selected) -> {
                    String id = fields.get(which).id;
                    if (selected) working.add(id); else working.remove(id);
                })
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (working.isEmpty()) {
                        Toast.makeText(this, "Выберите хотя бы одно поле",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    selectedFields.clear();
                    selectedFields.addAll(working);
                    persist();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void chooseDuration() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(durationSeconds));
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Длительность показа")
                .setMessage("От 1 до 120 секунд. Новое уведомление начинает этот интервал заново.")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 1 || value > 120) throw new NumberFormatException();
                        durationSeconds = value;
                        persist();
                        dialog.dismiss();
                    } catch (NumberFormatException invalid) {
                        input.setError("Введите число от 1 до 120");
                    }
                }));
        dialog.show();
    }

    private void chooseStatusColor() {
        AppleColorPickerDialog.show(this, "Цвет текста в строке состояния", tickerColor,
                AppleColorPickerDialog.Options.opaque(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String selected) {
                        if (selected != null) decorateColor(selected);
                    }

                    @Override public void onSelected(@Nullable String selected) {
                        if (selected == null) return;
                        tickerColor = validColor(selected);
                        persist();
                    }

                    @Override public void onCancelled(@Nullable String originalValue) {
                        decorateColor(tickerColor);
                    }
                });
    }

    private void openPopupEditor(@NonNull String overlayId) {
        try {
            PhoneNotificationAutomation.ensureConfigured(prefs);
            startActivity(PopupSettingsActivity.editIntent(
                    this, overlayId));
        } catch (Exception error) {
            showError(error);
        }
    }

    private void openFieldConditions(@NonNull String fieldId) {
        try {
            PhoneNotificationAutomation.ensureConfigured(prefs);
            startActivity(ScenarioSettingsActivity.intentForTarget(this,
                    TargetScope.POPUP,
                    PhoneNotificationAutomation.automationIdForField(fieldId)));
        } catch (Exception error) {
            showError(error);
        }
    }

    private void openOverlayConditions() {
        try {
            PhoneNotificationAutomation.ensureConfigured(prefs);
            startActivity(ScenarioSettingsActivity.intentForTarget(this,
                    TargetScope.OVERLAY, PhoneNotificationAutomation.OVERLAY_ID));
        } catch (Exception error) {
            showError(error);
        }
    }

    private void persist() {
        if (binding) return;
        try {
            boolean statusEnabled = statusRow.isChecked();
            boolean popupEnabled = popup.isChecked();
            prefs.phoneStatusBarNotificationsEnabled.set(statusEnabled);
            prefs.phonePopupNotificationsEnabled.set(popupEnabled);
            prefs.phoneStatusBarNotificationFields.set(PhoneStatusBarPolicy.serializeIds(
                    selectedFields, PhoneStatusBarPolicy.notificationFieldIds()));
            prefs.phoneStatusBarNotificationSeconds.set(clamp(durationSeconds, 1, 120));
            prefs.phoneStatusBarNotificationColor.set(validColor(tickerColor));
            if (statusEnabled || popupEnabled) {
                prefs.phoneConnectorEnabled.set(true);
                prefs.phoneNotificationsEnabled.set(true);
                if (selectedFields.contains(PhoneStatusBarPolicy.FIELD_TOPIC)
                        || selectedFields.contains(PhoneStatusBarPolicy.FIELD_TEXT)) {
                    prefs.phoneIncludeNotificationText.set(true);
                }
            }
            if (statusEnabled || popupEnabled) {
                PhoneNotificationAutomation.ensureConfigured(prefs);
            }
            if (statusEnabled) {
                List<BrickType> order = BrickType.parseOrder(prefs.brickOrder.get());
                if (!order.contains(BrickType.MEDIA)) {
                    order.add(BrickType.MEDIA);
                    prefs.brickOrder.set(BrickType.serializeOrder(order));
                }
            }
            WidgetService running = WidgetService.getInstance();
            if (running != null) running.applyPreferences();
            else WidgetServiceStarter.startIfNeeded(this);
            refreshSummaries();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void refreshSummaries() {
        if (fieldsSummary != null) {
            fieldsSummary.setText("Выбрано полей: " + selectedFields.size());
        }
        if (durationSummary != null) {
            durationSummary.setText("Показывать " + durationSeconds + " сек.");
        }
        decorateColor(tickerColor);
    }

    private void decorateColor(String color) {
        if (statusColor == null) return;
        AppleColorPickerDialog.decorateButton(statusColor,
                "Цвет текста в строке состояния", validColor(color));
    }

    private void showError(Throwable error) {
        String message = error.getMessage();
        Toast.makeText(this, "Не удалось сохранить: "
                + (message == null ? error.getClass().getSimpleName() : message),
                Toast.LENGTH_LONG).show();
    }

    private String validColor(String value) {
        String color = value == null ? "" : value.trim();
        try {
            android.graphics.Color.parseColor(color);
            return color;
        } catch (IllegalArgumentException invalid) {
            return "#FFFFFFFF";
        }
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor((getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? 0xFF202124 : 0xFFF5F5F5);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x557F7F7F);
        view.setBackground(background);
        return view;
    }

    private Switch switchView(String text, boolean checked) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setTextSize(17);
        view.setChecked(checked);
        return view;
    }

    private TextView heading(String text, int size) {
        TextView view = label(text);
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        return view;
    }

    private Button button(String text) {
        Button view = new Button(this);
        view.setText(text);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams topMargin(int value) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(value);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
