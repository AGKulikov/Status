/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.popup.PopupIconCatalog;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.scenario.ScenarioPresets;

/** Visual, slider-based appearance editor shared by the main row and popup tiles. */
public final class VisualBrickEditorActivity extends AppCompatActivity {
    public static final String SURFACE_MAIN = "main";
    public static final String SURFACE_POPUP = "popup";
    private static final String EXTRA_SURFACE = "surface";
    private static final String EXTRA_ID = "id";

    private Preferences prefs;
    private String surface;
    private String id;
    @Nullable private HaBrickConfig main;
    @Nullable private PopupItemConfig popup;
    private LinearLayout preview;
    private TextView previewTitle;
    private TextView previewValue;
    private ImageView previewIcon;

    public static Intent intent(Context context, String surface, String id) {
        return new Intent(context, VisualBrickEditorActivity.class)
                .putExtra(EXTRA_SURFACE, surface).putExtra(EXTRA_ID, id);
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        surface = getIntent().getStringExtra(EXTRA_SURFACE);
        id = getIntent().getStringExtra(EXTRA_ID);
        if (!load()) {
            Toast.makeText(this, "Элемент не найден", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(buildScreen());
    }

    private boolean load() {
        if (SURFACE_POPUP.equals(surface)) {
            for (PopupItemConfig item : new PopupItemConfigStore(prefs).load()) {
                if (item.id.equals(id)) { popup = item; return true; }
            }
            return false;
        }
        surface = SURFACE_MAIN;
        for (HaBrickConfig item : new HaBrickConfigStore(prefs).loadMain()) {
            if (item.id.equals(id)) { main = item; return true; }
        }
        return false;
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(48));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading(popup == null ? "Оформление кирпичика" : "Оформление плитки", 24),
                weighted());
        page.addView(header, matchWrap());

        String source = sourceLabel(popup == null ? main.sourceBinding : popup.sourceBinding);
        page.addView(label("Источник: " + source + "\nТехнический ID: " + id), topMargin(5));

        preview = column();
        preview.setGravity(Gravity.CENTER);
        preview.setMinimumHeight(dp(130));
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        previewIcon = new ImageView(this);
        previewTitle = new TextView(this);
        previewValue = new TextView(this);
        preview.addView(previewIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        preview.addView(previewTitle, matchWrap());
        preview.addView(previewValue, matchWrap());
        page.addView(preview, topMargin(14));

        if (main != null) buildMainEditor(page); else buildPopupEditor(page);
        updatePreview();

        Button save = button("Сохранить оформление");
        save.setOnClickListener(v -> save());
        page.addView(save, topMargin(24));
        return scroll;
    }

    private void buildMainEditor(LinearLayout page) {
        HaBrickConfig c = main;
        page.addView(section("Текст"), topMargin(22));
        EditText name = field(page, "Название в настройках", c.name);
        watch(name, value -> { c.name = value; updatePreview(); });
        EditText defaultText = field(page, "Текст, если источник ничего не прислал", c.defaultText);
        watch(defaultText, value -> { c.defaultText = value; updatePreview(); });
        CheckBox enabled = check("Показывать этот кирпичик", c.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> c.enabled = value);
        page.addView(enabled);
        CheckBox bold = check("Жирный шрифт", c.bold);
        bold.setOnCheckedChangeListener((v, value) -> { c.bold = value; updatePreview(); });
        page.addView(bold);
        CheckBox italic = check("Курсив", c.italic);
        italic.setOnCheckedChangeListener((v, value) -> { c.italic = value; updatePreview(); });
        page.addView(italic);
        addSlider(page, "Размер шрифта", 10, 160, c.fontSize, value -> { c.fontSize = value; updatePreview(); }, " px");
        addSlider(page, "Прозрачность текста", 0, 255, c.contentAlpha, value -> { c.contentAlpha = value; updatePreview(); }, " / 255");
        addColor(page, "Обычный цвет", c.defaultColor, value -> { c.defaultColor = value; updatePreview(); });

        page.addView(section("Цвета по состоянию"), topMargin(20));
        Button preset = button("Выбрать готовые правила отображения");
        preset.setOnClickListener(v -> chooseRulePreset());
        page.addView(preset, topMargin(6));
        addStateRow(page, "До первого актуального значения", c.pendingText, c.pendingColor,
                (text, color) -> { c.pendingText = text; c.pendingColor = color; });
        addStateRow(page, "Если значение устарело", c.staleText, c.staleColor,
                (text, color) -> { c.staleText = text; c.staleColor = color; });
        addStateRow(page, "Если значение пустое", c.emptyText, c.emptyColor,
                (text, color) -> { c.emptyText = text; c.emptyColor = color; });
        addSlider(page, "Считать устаревшим через", 0, 3600, (int) c.staleAfterSeconds,
                value -> c.staleAfterSeconds = value, " сек (0 = никогда)");
        CheckBox collapse = check("Скрывать, если текст пустой", c.collapseWhenEmpty);
        collapse.setOnCheckedChangeListener((v, value) -> c.collapseWhenEmpty = value);
        page.addView(collapse);

        page.addView(section("Размер и положение"), topMargin(20));
        addSlider(page, "Максимальная ширина", 0, 1600, c.maxWidth,
                value -> c.maxWidth = value, " px");
        addSlider(page, "Отступ слева", 0, 300, c.marginStart,
                value -> c.marginStart = value, " px");
        addSlider(page, "Отступ справа", 0, 300, c.marginEnd,
                value -> c.marginEnd = value, " px");
        addSlider(page, "Внутренний отступ слева", 0, 200, c.paddingLeft,
                value -> c.paddingLeft = value, " px");
        addSlider(page, "Внутренний отступ сверху", 0, 200, c.paddingTop,
                value -> c.paddingTop = value, " px");
        addSlider(page, "Внутренний отступ справа", 0, 200, c.paddingRight,
                value -> c.paddingRight = value, " px");
        addSlider(page, "Внутренний отступ снизу", 0, 200, c.paddingBottom,
                value -> c.paddingBottom = value, " px");
        addSlider(page, "Вертикальное смещение", -250, 250, c.adjustY,
                value -> { c.adjustY = value; updatePreview(); }, " px");
        CheckBox marquee = check("Прокручивать слишком длинный текст", c.marquee);
        marquee.setOnCheckedChangeListener((v, value) -> c.marquee = value);
        page.addView(marquee);

        page.addView(section("Обводка"), topMargin(20));
        addColor(page, "Цвет обводки", c.outlineColor,
                value -> { c.outlineColor = value; updatePreview(); });
        addSlider(page, "Толщина", 0, 20, c.outlineWidth,
                value -> { c.outlineWidth = value; updatePreview(); }, " px");
        addSlider(page, "Прозрачность обводки", 0, 255, c.outlineAlpha,
                value -> { c.outlineAlpha = value; updatePreview(); }, " / 255");
    }

    private void buildPopupEditor(LinearLayout page) {
        PopupItemConfig c = popup;
        page.addView(section("Положение в сетке"), topMargin(22));
        TextView cellInfo = label(cellInfo());
        page.addView(cellInfo, topMargin(5));
        Button position = button(positionText());
        position.setOnClickListener(v -> chooseGridPosition(position));
        page.addView(position, topMargin(7));
        addSlider(page, "Ширина плитки", 1, Math.max(1, prefs.popupColumns.get()), c.columnSpan,
                value -> { c.columnSpan = value; cellInfo.setText(cellInfo()); updatePreview(); }, " яч.");
        addSlider(page, "Высота плитки", 1, Math.max(1, prefs.popupRows.get()), c.rowSpan,
                value -> { c.rowSpan = value; cellInfo.setText(cellInfo()); updatePreview(); }, " яч.");

        page.addView(section("Иконка"), topMargin(20));
        Button icon = button("Выбрать иконку: " + c.icon);
        icon.setOnClickListener(v -> chooseIcon(icon));
        page.addView(icon, topMargin(5));
        addSlider(page, "Размер иконки", 0, 200, c.iconSize,
                value -> { c.iconSize = value; updatePreview(); }, " px");
        addSlider(page, "Прозрачность иконки", 0, 255, c.iconAlpha,
                value -> { c.iconAlpha = value; updatePreview(); }, " / 255");
        addColor(page, "Цвет иконки", c.iconColor,
                value -> { c.iconColor = value; updatePreview(); });
        addSlider(page, "Сдвиг иконки по горизонтали", -200, 200, c.iconAdjustX,
                value -> { c.iconAdjustX = value; updatePreview(); }, " px");
        addSlider(page, "Сдвиг иконки по вертикали", -200, 200, c.iconAdjustY,
                value -> { c.iconAdjustY = value; updatePreview(); }, " px");
        addSlider(page, "Поворот иконки", -180, 180, c.iconRotation,
                value -> { c.iconRotation = value; updatePreview(); }, "°");

        page.addView(section("Название и статус"), topMargin(20));
        CheckBox enabled = check("Показывать плитку", c.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> c.enabled = value);
        page.addView(enabled);
        CheckBox showTitle = check("Показывать название", c.showTitle);
        showTitle.setOnCheckedChangeListener((v, value) -> { c.showTitle = value; updatePreview(); });
        page.addView(showTitle);
        EditText title = field(page, "Название на плитке", c.title);
        watch(title, value -> { c.title = value; c.name = value; updatePreview(); });
        addSlider(page, "Размер названия", 8, 100, c.titleSize,
                value -> { c.titleSize = value; updatePreview(); }, " px");
        addColor(page, "Цвет названия", c.titleColor,
                value -> { c.titleColor = value; updatePreview(); });
        CheckBox showStatus = check("Показывать статус", c.showStatus);
        showStatus.setOnCheckedChangeListener((v, value) -> { c.showStatus = value; updatePreview(); });
        page.addView(showStatus);
        EditText defaultText = field(page, "Текст без полученного статуса", c.defaultText);
        watch(defaultText, value -> { c.defaultText = value; updatePreview(); });
        addSlider(page, "Размер статуса", 8, 140, c.textSize,
                value -> { c.textSize = value; updatePreview(); }, " px");
        addColor(page, "Обычный цвет статуса", c.defaultTextColor,
                value -> { c.defaultTextColor = value; updatePreview(); });
        Button preset = button("Выбрать готовые правила отображения");
        preset.setOnClickListener(v -> chooseRulePreset());
        page.addView(preset, topMargin(7));
        addStateRow(page, "До первого актуального значения", c.pendingText, c.pendingColor,
                (text, color) -> { c.pendingText = text; c.pendingColor = color; });
        addStateRow(page, "Если значение устарело", c.staleText, c.staleColor,
                (text, color) -> { c.staleText = text; c.staleColor = color; });
        addSlider(page, "Считать устаревшим через", 0, 3600, (int) c.staleAfterSeconds,
                value -> c.staleAfterSeconds = value, " сек (0 = никогда)");

        page.addView(section("Фон и форма"), topMargin(20));
        addColor(page, "Цвет фона", c.backgroundColor,
                value -> { c.backgroundColor = value; updatePreview(); });
        addSlider(page, "Прозрачность фона", 0, 255, c.backgroundAlpha,
                value -> { c.backgroundAlpha = value; updatePreview(); }, " / 255");
        addColor(page, "Цвет рамки", c.borderColor,
                value -> { c.borderColor = value; updatePreview(); });
        addSlider(page, "Толщина рамки", 0, 30, c.borderWidth,
                value -> { c.borderWidth = value; updatePreview(); }, " px");
        addSlider(page, "Скругление", 0, 120, c.cornerRadius,
                value -> { c.cornerRadius = value; updatePreview(); }, " px");
        addSlider(page, "Внутренний отступ", 0, 100, c.padding,
                value -> { c.padding = value; updatePreview(); }, " px");
        addSlider(page, "Сдвиг плитки по горизонтали", -200, 200, c.adjustX,
                value -> c.adjustX = value, " px");
        addSlider(page, "Сдвиг плитки по вертикали", -200, 200, c.adjustY,
                value -> c.adjustY = value, " px");

        page.addView(section("Нажатие"), topMargin(20));
        TextView actionSummary = label(actionSummary());
        page.addView(actionSummary, topMargin(5));
        Button action = button("Настроить действие по нажатию");
        action.setOnClickListener(v -> choosePopupAction(actionSummary));
        page.addView(action, topMargin(6));
        CheckBox confirm = check("Спрашивать подтверждение", c.confirmationRequired);
        confirm.setOnCheckedChangeListener((v, value) -> c.confirmationRequired = value);
        page.addView(confirm);
        CheckBox hide = check("Скрыть плитку после команды", c.autoHideAfterAction);
        hide.setOnCheckedChangeListener((v, value) -> c.autoHideAfterAction = value);
        page.addView(hide);
    }

    private void addStateRow(LinearLayout page, String title, String initialText,
                             String initialColor, StateConsumer consumer) {
        page.addView(label(title), topMargin(10));
        LinearLayout row = row();
        EditText text = new EditText(this);
        text.setSingleLine(true);
        text.setText(initialText);
        row.addView(text, weighted());
        final String[] color = {initialColor};
        Button colorButton = button(colorName(initialColor));
        colorButton.setOnClickListener(v -> chooseColor(initialColor, selected -> {
            color[0] = selected;
            colorButton.setText(colorName(selected));
            consumer.accept(text(text), selected);
            updatePreview();
        }));
        row.addView(colorButton);
        watch(text, value -> consumer.accept(value, color[0]));
        page.addView(row, matchWrap());
    }

    private void addColor(LinearLayout page, String title, String initial, ColorConsumer consumer) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title), weighted());
        Button button = button(colorName(initial));
        final String[] current = {initial};
        button.setOnClickListener(v -> chooseColor(current[0], value -> {
            current[0] = value;
            button.setText(colorName(value));
            consumer.accept(value);
        }));
        row.addView(button);
        page.addView(row, topMargin(7));
    }

    private void chooseColor(String current, ColorConsumer consumer) {
        String[] labels = {"Белый", "Зелёный", "Оранжевый", "Красный", "Голубой",
                "Серый", "Чёрный", "Прозрачный", "Свой цвет…"};
        String[] colors = {"#FFFFFFFF", "#FF4CAF50", "#FFFF9800", "#FFF44336",
                "#FF03A9F4", "#FF9E9E9E", "#FF000000", "transparent"};
        new AlertDialog.Builder(this).setTitle("Выберите цвет").setItems(labels, (d, which) -> {
            if (which < colors.length) consumer.accept(colors[which]);
            else customColor(current, consumer);
        }).setNegativeButton("Отмена", null).show();
    }

    private void customColor(String current, ColorConsumer consumer) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        new AlertDialog.Builder(this).setTitle("Цвет #AARRGGBB")
                .setView(input).setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (d, w) -> {
                    String value = text(input);
                    try { parseColor(value); consumer.accept(value); }
                    catch (Exception e) { Toast.makeText(this, "Неверный цвет", Toast.LENGTH_LONG).show(); }
                }).show();
    }

    private void chooseRulePreset() {
        String[] labels = {"Автоматически по типу устройства", "Ворота / дверь",
                "Включено / выключено", "Температура", "Показывать как есть"};
        new AlertDialog.Builder(this).setTitle("Правила текста и цвета")
                .setItems(labels, (d, which) -> {
                    String presentation = sourcePresentation();
                    if (which == 1) presentation = SourceBinding.PRESENTATION_COVER;
                    else if (which == 2) presentation = SourceBinding.PRESENTATION_BOOLEAN;
                    else if (which == 3) presentation = SourceBinding.PRESENTATION_TEMPERATURE;
                    else if (which == 4) presentation = SourceBinding.PRESENTATION_RAW;
                    if (which == 0 && presentation == null) presentation = SourceBinding.PRESENTATION_RAW;
                    if (main != null) main.displayRules = rulesFor(presentation);
                    else popup.displayRules = rulesFor(presentation);
                    Toast.makeText(this, "Готовые правила применены", Toast.LENGTH_SHORT).show();
                    updatePreview();
                }).setNegativeButton("Отмена", null).show();
    }

    private dezz.status.widget.scenario.RuleSet rulesFor(String presentation) {
        if (SourceBinding.PRESENTATION_COVER.equals(presentation)) return ScenarioPresets.cover();
        if (SourceBinding.PRESENTATION_BOOLEAN.equals(presentation)) return ScenarioPresets.booleanState();
        if (SourceBinding.PRESENTATION_TEMPERATURE.equals(presentation)) return ScenarioPresets.temperature();
        return ScenarioPresets.raw();
    }

    @Nullable private String sourcePresentation() {
        SourceBinding binding = main != null ? main.sourceBinding : popup.sourceBinding;
        return binding == null ? null : binding.presentation;
    }

    private void chooseIcon(Button button) {
        String[] labels = {"Ворота", "Гараж", "Свет", "Замок", "Питание", "Температура",
                "Вода", "Дверь", "Wi‑Fi", "GPS", "Bluetooth"};
        new AlertDialog.Builder(this).setTitle("Иконка").setItems(labels, (d, which) -> {
            popup.icon = PopupIconCatalog.IDS.get(which);
            button.setText("Выбрать иконку: " + labels[which]);
            updatePreview();
        }).setNegativeButton("Отмена", null).show();
    }

    private String actionSummary() {
        if (popup.actionBinding == null || !popup.actionBinding.isBound()) return "Нажатие ничего не делает";
        return "Команда: " + popup.actionBinding.connectorType.jsonName() + " · "
                + popup.actionBinding.operation + " · " + popup.actionBinding.resourceId;
    }

    private void choosePopupAction(TextView summary) {
        SourceBinding source = popup.sourceBinding;
        if (source == null || !source.isBound()) {
            Toast.makeText(this, "Сначала выберите устройство-источник", Toast.LENGTH_LONG).show();
            return;
        }
        if (source.connectorType == ConnectorType.MQTT) {
            configureMqttAction(summary);
            return;
        }
        String[] labels = {"Ничего не делать", "Переключить состояние",
                "Включить / открыть", "Выключить / закрыть"};
        new AlertDialog.Builder(this).setTitle("Действие по нажатию")
                .setItems(labels, (d, which) -> {
                    if (which == 0) popup.actionBinding = ActionBinding.unbound();
                    else if (source.connectorType == ConnectorType.SPRUTHUB) {
                        popup.actionBinding = new ActionBinding(ConnectorType.SPRUTHUB,
                                SourceBinding.DEFAULT_CONNECTOR_ID, source.resourceId,
                                which == 1 ? ActionBinding.OPERATION_TOGGLE : ActionBinding.OPERATION_SET,
                                which == 2 ? "true" : which == 3 ? "false" : "{}");
                    } else {
                        String domain = source.resourceId.contains(".")
                                ? source.resourceId.substring(0, source.resourceId.indexOf('.')) : "homeassistant";
                        String service = "";
                        if (which == 2) service = "cover".equals(domain)
                                ? "cover.open_cover" : domain + ".turn_on";
                        if (which == 3) service = "cover".equals(domain)
                                ? "cover.close_cover" : domain + ".turn_off";
                        popup.actionBinding = new ActionBinding(ConnectorType.HOME_ASSISTANT,
                                SourceBinding.DEFAULT_CONNECTOR_ID, source.resourceId,
                                which == 1 ? ActionBinding.OPERATION_TOGGLE : ActionBinding.OPERATION_SET,
                                which == 1 ? "{}" : "{\"service\":\"" + service + "\"}");
                    }
                    popup.actionId = popup.actionBinding.isBound() ? popup.id + "_action" : "";
                    summary.setText(actionSummary());
                }).setNegativeButton("Отмена", null).show();
    }

    private void configureMqttAction(TextView summary) {
        LinearLayout form = column();
        form.setPadding(dp(18), dp(4), dp(18), 0);
        String currentTopic = popup.actionBinding != null
                && popup.actionBinding.connectorType == ConnectorType.MQTT
                ? popup.actionBinding.resourceId : "";
        String currentPayload = popup.actionBinding != null
                && popup.actionBinding.connectorType == ConnectorType.MQTT
                ? popup.actionBinding.payload : "ON";
        EditText topic = field(form, "Topic команды", currentTopic);
        EditText payload = field(form, "Что отправить", currentPayload);
        new AlertDialog.Builder(this).setTitle("MQTT-команда")
                .setView(form).setNeutralButton("Отключить", (d, w) -> {
                    popup.actionBinding = ActionBinding.unbound();
                    popup.actionId = "";
                    summary.setText(actionSummary());
                }).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (d, w) -> {
                    if (text(topic).isEmpty()) {
                        Toast.makeText(this, "Укажите topic команды", Toast.LENGTH_LONG).show();
                        return;
                    }
                    popup.actionBinding = new ActionBinding(ConnectorType.MQTT,
                            SourceBinding.DEFAULT_CONNECTOR_ID, text(topic),
                            ActionBinding.OPERATION_PUBLISH, text(payload));
                    popup.actionId = popup.id + "_action";
                    summary.setText(actionSummary());
                }).show();
    }

    private void chooseGridPosition(Button button) {
        int rows = Math.max(1, prefs.popupRows.get());
        int columns = Math.max(1, prefs.popupColumns.get());
        List<String> labels = new ArrayList<>();
        labels.add("Автоматически — первое свободное место");
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            labels.add("Строка " + (row + 1) + ", столбец " + (column + 1));
        }
        new AlertDialog.Builder(this).setTitle("Положение плитки")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) { popup.row = -1; popup.column = -1; }
                    else { int index = which - 1; popup.row = index / columns; popup.column = index % columns; }
                    button.setText(positionText());
                }).setNegativeButton("Отмена", null).show();
    }

    private String positionText() {
        return popup.row < 0 || popup.column < 0 ? "Положение: автоматически"
                : "Положение: строка " + (popup.row + 1) + ", столбец " + (popup.column + 1);
    }

    private String cellInfo() {
        int columns = Math.max(1, prefs.popupColumns.get());
        int rows = Math.max(1, prefs.popupRows.get());
        int width = Math.max(1, prefs.popupWidth.get() - prefs.popupPaddingLeft.get()
                - prefs.popupPaddingRight.get() - prefs.popupCellGap.get() * (columns - 1));
        int height = Math.max(1, prefs.popupHeight.get() - prefs.popupPaddingTop.get()
                - prefs.popupPaddingBottom.get() - prefs.popupCellGap.get() * (rows - 1));
        return "Оверлей " + prefs.popupWidth.get() + "×" + prefs.popupHeight.get()
                + " px · сетка " + columns + "×" + rows + " · одна ячейка примерно "
                + (width / columns) + "×" + (height / rows) + " px\nТекущая плитка: "
                + popup.columnSpan + "×" + popup.rowSpan + " ячеек";
    }

    private void updatePreview() {
        if (preview == null) return;
        if (main != null) {
            previewIcon.setVisibility(View.GONE);
            previewTitle.setVisibility(View.GONE);
            previewValue.setVisibility(View.VISIBLE);
            previewValue.setText(main.defaultText.isEmpty() ? main.name : main.defaultText);
            previewValue.setTextSize(Math.min(72, main.fontSize));
            previewValue.setTextColor(parseColor(main.defaultColor));
            previewValue.setAlpha(main.contentAlpha / 255f);
            previewValue.setTypeface(Typeface.DEFAULT, (main.bold ? Typeface.BOLD : 0)
                    | (main.italic ? Typeface.ITALIC : 0));
            previewValue.setTranslationY(main.adjustY);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x332196F3);
            bg.setCornerRadius(dp(18));
            preview.setBackground(bg);
            return;
        }
        previewIcon.setVisibility(popup.iconSize > 0 ? View.VISIBLE : View.GONE);
        previewTitle.setVisibility(popup.showTitle ? View.VISIBLE : View.GONE);
        previewValue.setVisibility(popup.showStatus ? View.VISIBLE : View.GONE);
        int iconDrawable = PopupIconCatalog.resolve(popup.icon);
        if (iconDrawable != 0) previewIcon.setImageResource(iconDrawable);
        previewIcon.setColorFilter(parseColor(popup.iconColor));
        previewIcon.setAlpha(popup.iconAlpha / 255f);
        ViewGroup.LayoutParams iconParams = previewIcon.getLayoutParams();
        iconParams.width = dp(Math.min(120, popup.iconSize));
        iconParams.height = dp(Math.min(120, popup.iconSize));
        previewIcon.setLayoutParams(iconParams);
        previewIcon.setTranslationX(popup.iconAdjustX);
        previewIcon.setTranslationY(popup.iconAdjustY);
        previewIcon.setRotation(popup.iconRotation);
        previewTitle.setText(popup.title);
        previewTitle.setTextSize(Math.min(64, popup.titleSize));
        previewTitle.setTextColor(parseColor(popup.titleColor));
        previewTitle.setAlpha(popup.titleAlpha / 255f);
        previewTitle.setGravity(Gravity.CENTER);
        previewValue.setText(popup.defaultText.isEmpty() ? "Актуальный статус" : popup.defaultText);
        previewValue.setTextSize(Math.min(72, popup.textSize));
        previewValue.setTextColor(parseColor(popup.defaultTextColor));
        previewValue.setAlpha(popup.textAlpha / 255f);
        previewValue.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        int fill = withAlpha(parseColor(popup.backgroundColor), popup.backgroundAlpha);
        bg.setColor(fill);
        bg.setCornerRadius(dp(Math.min(120, popup.cornerRadius)));
        bg.setStroke(dp(Math.min(20, popup.borderWidth)),
                withAlpha(parseColor(popup.borderColor), popup.borderAlpha));
        preview.setBackground(bg);
        preview.setPadding(dp(Math.min(80, popup.padding)), dp(Math.min(80, popup.padding)),
                dp(Math.min(80, popup.padding)), dp(Math.min(80, popup.padding)));
    }

    private void save() {
        try {
            if (main != null) {
                List<HaBrickConfig> items = new HaBrickConfigStore(prefs).loadMain();
                replaceMain(items, main);
                new HaBrickConfigStore(prefs).saveMain(items);
            } else {
                List<PopupItemConfig> items = new PopupItemConfigStore(prefs).load();
                replacePopup(items, popup);
                new PopupItemConfigStore(prefs).save(items);
            }
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            Toast.makeText(this, "Оформление сохранено", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось сохранить: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private static void replaceMain(List<HaBrickConfig> items, HaBrickConfig replacement) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(replacement.id)) {
            items.set(i, replacement); return;
        }
        items.add(replacement);
    }

    private static void replacePopup(List<PopupItemConfig> items, PopupItemConfig replacement) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(replacement.id)) {
            items.set(i, replacement); return;
        }
        items.add(replacement);
    }

    private void addSlider(LinearLayout parent, String title, int min, int max, int current,
                           IntConsumer consumer, String suffix) {
        LinearLayout labels = row();
        labels.addView(label(title), weighted());
        TextView value = label(clamp(current, min, max) + suffix);
        labels.addView(value);
        parent.addView(labels, topMargin(8));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(clamp(current, min, max) - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = min + progress;
                value.setText(selected + suffix);
                consumer.accept(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        parent.addView(seek, matchWrap());
    }

    private EditText field(LinearLayout parent, String title, String value) {
        parent.addView(label(title), topMargin(8));
        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        parent.addView(input, matchWrap());
        return input;
    }

    private static void watch(EditText input, StringConsumer consumer) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                consumer.accept(value == null ? "" : value.toString().trim());
            }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    private static String sourceLabel(SourceBinding binding) {
        if (binding == null || !binding.isBound()) return "не выбран";
        return binding.connectorType.jsonName() + " · " + binding.resourceId
                + (binding.valuePath == null ? "" : " · " + binding.valuePath);
    }

    private static int parseColor(String raw) {
        if (raw == null || raw.trim().isEmpty() || "transparent".equalsIgnoreCase(raw.trim())) return Color.TRANSPARENT;
        try { return Color.parseColor(raw.trim()); } catch (IllegalArgumentException ignored) { return Color.WHITE; }
    }

    private static int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static String colorName(String color) {
        if (color == null) return "Не задан";
        switch (color.toUpperCase()) {
            case "#FFFFFFFF": return "Белый";
            case "#FF4CAF50": return "Зелёный";
            case "#FFFF9800": return "Оранжевый";
            case "#FFF44336": return "Красный";
            case "#FF03A9F4": return "Голубой";
            case "#FF9E9E9E": return "Серый";
            case "TRANSPARENT": return "Прозрачный";
            default: return color;
        }
    }

    private TextView section(String text) { return heading(text, 19); }
    private TextView heading(String text, int size) { TextView v = new TextView(this); v.setText(text);
        v.setTextSize(size); v.setTypeface(v.getTypeface(), Typeface.BOLD); return v; }
    private TextView label(String text) { TextView v = new TextView(this); v.setText(text); v.setTextSize(14); return v; }
    private Button button(String text) { Button v = new Button(this); v.setText(text); return v; }
    private CheckBox check(String text, boolean checked) { CheckBox v = new CheckBox(this);
        v.setText(text); v.setChecked(checked); v.setMinHeight(dp(48)); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams topMargin(int value) { LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(value); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String text(EditText value) { return value.getText() == null ? "" : value.getText().toString().trim(); }
    private static String safeMessage(Throwable value) { return value.getMessage() == null
            ? value.getClass().getSimpleName() : value.getMessage(); }

    private interface ColorConsumer { void accept(String value); }
    private interface StateConsumer { void accept(String text, String color); }
    private interface StringConsumer { void accept(String value); }
}
