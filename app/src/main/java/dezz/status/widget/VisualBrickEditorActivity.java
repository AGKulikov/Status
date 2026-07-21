/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import java.math.BigDecimal;
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
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.Operator;
import dezz.status.widget.scenario.Output;
import dezz.status.widget.scenario.Result;
import dezz.status.widget.scenario.Rule;
import dezz.status.widget.scenario.RuleSet;
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
    private LinearLayout rulesContainer;
    private EditText ruleTestInput;
    private TextView ruleTestStatus;
    private TextView liveStatus;
    private Handler liveHandler;
    private boolean screenReady;
    private boolean liveApplyScheduled;

    /** Fast enough to look live while avoiding a full overlay rebuild for every touch sample. */
    private static final long LIVE_APPLY_INTERVAL_MS = 120L;
    private static final Operator[] VISUAL_OPERATORS = {
            Operator.EQUALS, Operator.EQUALS_IGNORE_CASE, Operator.NOT_EQUALS,
            Operator.NOT_EQUALS_IGNORE_CASE, Operator.CONTAINS,
            Operator.CONTAINS_IGNORE_CASE, Operator.STARTS_WITH, Operator.ENDS_WITH,
            Operator.GREATER, Operator.GREATER_OR_EQUAL, Operator.LESS,
            Operator.LESS_OR_EQUAL, Operator.BETWEEN_EXCLUSIVE, Operator.BETWEEN,
            Operator.TRUE, Operator.FALSE, Operator.EMPTY, Operator.NOT_EMPTY,
            Operator.FRESH, Operator.STALE, Operator.AVAILABLE, Operator.UNAVAILABLE,
            Operator.ALWAYS
    };
    private static final String[] VISUAL_OPERATOR_LABELS = {
            "равно", "равно (без учёта регистра)", "не равно",
            "не равно (без учёта регистра)", "содержит",
            "содержит (без учёта регистра)", "начинается с", "заканчивается на",
            "больше", "больше или равно", "меньше", "меньше или равно",
            "больше A и меньше B", "от A до B включительно",
            "включено / истина", "выключено / ложь", "пустое", "не пустое",
            "актуальное", "устаревшее", "доступно", "недоступно",
            "любое значение"
    };

    public static Intent intent(Context context, String surface, String id) {
        return new Intent(context, VisualBrickEditorActivity.class)
                .putExtra(EXTRA_SURFACE, surface).putExtra(EXTRA_ID, id);
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        liveHandler = new Handler(Looper.getMainLooper());
        prefs = new Preferences(this);
        surface = getIntent().getStringExtra(EXTRA_SURFACE);
        id = getIntent().getStringExtra(EXTRA_ID);
        if (!load()) {
            Toast.makeText(this, "Элемент не найден", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(buildScreen());
        screenReady = true;
    }

    @Override protected void onPause() {
        flushLiveApply();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (liveHandler != null) liveHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
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
        TextView autoSave = label("Изменения сохраняются автоматически и сразу применяются "
                + "к работающему виджету и оверлею.");
        autoSave.setTextColor(0xFF81C784);
        page.addView(autoSave, topMargin(8));
        liveStatus = label("Готово к настройке");
        page.addView(liveStatus, topMargin(3));

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

        Button done = button("Готово");
        done.setOnClickListener(v -> { flushLiveApply(); finish(); });
        page.addView(done, topMargin(24));
        return scroll;
    }

    private void buildMainEditor(LinearLayout page) {
        HaBrickConfig c = main;
        page.addView(section("Текст"), topMargin(22));
        EditText name = field(page, "Название в настройках", c.name);
        watch(name, value -> { c.name = value; onConfigChanged(); });
        EditText defaultText = field(page, "Текст, если источник ничего не прислал", c.defaultText);
        watch(defaultText, value -> { c.defaultText = value; onConfigChanged(); });
        CheckBox enabled = check("Показывать этот кирпичик", c.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> { c.enabled = value; onConfigChanged(); });
        page.addView(enabled);
        CheckBox bold = check("Жирный шрифт", c.bold);
        bold.setOnCheckedChangeListener((v, value) -> { c.bold = value; onConfigChanged(); });
        page.addView(bold);
        CheckBox italic = check("Курсив", c.italic);
        italic.setOnCheckedChangeListener((v, value) -> { c.italic = value; onConfigChanged(); });
        page.addView(italic);
        addSlider(page, "Размер шрифта", 10, 160, c.fontSize, value -> { c.fontSize = value; onConfigChanged(); }, " px");
        addSlider(page, "Прозрачность текста", 0, 255, c.contentAlpha, value -> { c.contentAlpha = value; onConfigChanged(); }, " / 255");
        addColor(page, "Обычный цвет", c.defaultColor, value -> { c.defaultColor = value; onConfigChanged(); });

        page.addView(section("Правила текста и цвета"), topMargin(20));
        Button preset = button("Выбрать готовые правила отображения");
        preset.setOnClickListener(v -> chooseRulePreset());
        page.addView(preset, topMargin(6));
        buildRuleEditor(page);
        addStateRow(page, "До первого актуального значения", c.pendingText, c.pendingColor,
                (text, color) -> { c.pendingText = text; c.pendingColor = color; onConfigChanged(); });
        addStateRow(page, "Если значение устарело", c.staleText, c.staleColor,
                (text, color) -> { c.staleText = text; c.staleColor = color; onConfigChanged(); });
        addStateRow(page, "Если значение пустое", c.emptyText, c.emptyColor,
                (text, color) -> { c.emptyText = text; c.emptyColor = color; onConfigChanged(); });
        addSlider(page, "Считать устаревшим через", 0, 3600, (int) c.staleAfterSeconds,
                value -> { c.staleAfterSeconds = value; onConfigChanged(); }, " сек (0 = никогда)");
        CheckBox collapse = check("Скрывать, если текст пустой", c.collapseWhenEmpty);
        collapse.setOnCheckedChangeListener((v, value) -> { c.collapseWhenEmpty = value; onConfigChanged(); });
        page.addView(collapse);

        page.addView(section("Размер и положение"), topMargin(20));
        addSlider(page, "Максимальная ширина", 0, 1600, c.maxWidth,
                value -> { c.maxWidth = value; onConfigChanged(); }, " px");
        addSlider(page, "Отступ слева", 0, 300, c.marginStart,
                value -> { c.marginStart = value; onConfigChanged(); }, " px");
        addSlider(page, "Отступ справа", 0, 300, c.marginEnd,
                value -> { c.marginEnd = value; onConfigChanged(); }, " px");
        addSlider(page, "Внутренний отступ слева", 0, 200, c.paddingLeft,
                value -> { c.paddingLeft = value; onConfigChanged(); }, " px");
        addSlider(page, "Внутренний отступ сверху", 0, 200, c.paddingTop,
                value -> { c.paddingTop = value; onConfigChanged(); }, " px");
        addSlider(page, "Внутренний отступ справа", 0, 200, c.paddingRight,
                value -> { c.paddingRight = value; onConfigChanged(); }, " px");
        addSlider(page, "Внутренний отступ снизу", 0, 200, c.paddingBottom,
                value -> { c.paddingBottom = value; onConfigChanged(); }, " px");
        addSlider(page, "Вертикальное смещение", -250, 250, c.adjustY,
                value -> { c.adjustY = value; onConfigChanged(); }, " px");
        CheckBox marquee = check("Прокручивать слишком длинный текст", c.marquee);
        marquee.setOnCheckedChangeListener((v, value) -> { c.marquee = value; onConfigChanged(); });
        page.addView(marquee);

        page.addView(section("Обводка"), topMargin(20));
        addColor(page, "Цвет обводки", c.outlineColor,
                value -> { c.outlineColor = value; onConfigChanged(); });
        addSlider(page, "Толщина", 0, 20, c.outlineWidth,
                value -> { c.outlineWidth = value; onConfigChanged(); }, " px");
        addSlider(page, "Прозрачность обводки", 0, 255, c.outlineAlpha,
                value -> { c.outlineAlpha = value; onConfigChanged(); }, " / 255");
    }

    private void buildPopupEditor(LinearLayout page) {
        PopupItemConfig c = popup;
        PopupOverlayConfig overlay = popupOverlayConfig();
        page.addView(section("Положение в сетке"), topMargin(22));
        TextView cellInfo = label(cellInfo());
        page.addView(cellInfo, topMargin(5));
        Button position = button(positionText());
        position.setOnClickListener(v -> chooseGridPosition(position));
        page.addView(position, topMargin(7));
        addSlider(page, "Ширина плитки", 1, Math.max(1, overlay.columns), c.columnSpan,
                value -> { c.columnSpan = value; cellInfo.setText(cellInfo()); onConfigChanged(); }, " яч.");
        addSlider(page, "Высота плитки", 1, Math.max(1, overlay.rows), c.rowSpan,
                value -> { c.rowSpan = value; cellInfo.setText(cellInfo()); onConfigChanged(); }, " яч.");

        page.addView(section("Иконка"), topMargin(20));
        Button icon = button("Выбрать иконку: " + c.icon);
        icon.setOnClickListener(v -> chooseIcon(icon));
        page.addView(icon, topMargin(5));
        addSlider(page, "Размер иконки", 0, 200, c.iconSize,
                value -> { c.iconSize = value; onConfigChanged(); }, " px");
        addSlider(page, "Прозрачность иконки", 0, 255, c.iconAlpha,
                value -> { c.iconAlpha = value; onConfigChanged(); }, " / 255");
        addColor(page, "Цвет иконки", c.iconColor,
                value -> { c.iconColor = value; onConfigChanged(); });
        addSlider(page, "Сдвиг иконки по горизонтали", -200, 200, c.iconAdjustX,
                value -> { c.iconAdjustX = value; onConfigChanged(); }, " px");
        addSlider(page, "Сдвиг иконки по вертикали", -200, 200, c.iconAdjustY,
                value -> { c.iconAdjustY = value; onConfigChanged(); }, " px");
        addSlider(page, "Поворот иконки", -180, 180, c.iconRotation,
                value -> { c.iconRotation = value; onConfigChanged(); }, "°");

        page.addView(section("Название и статус"), topMargin(20));
        CheckBox enabled = check("Показывать плитку", c.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> { c.enabled = value; onConfigChanged(); });
        page.addView(enabled);
        CheckBox showTitle = check("Показывать название", c.showTitle);
        showTitle.setOnCheckedChangeListener((v, value) -> { c.showTitle = value; onConfigChanged(); });
        page.addView(showTitle);
        EditText title = field(page, "Название на плитке", c.title);
        watch(title, value -> { c.title = value; c.name = value; onConfigChanged(); });
        addSlider(page, "Размер названия", 8, 100, c.titleSize,
                value -> { c.titleSize = value; onConfigChanged(); }, " px");
        addColor(page, "Цвет названия", c.titleColor,
                value -> { c.titleColor = value; onConfigChanged(); });
        CheckBox showStatus = check("Показывать статус", c.showStatus);
        showStatus.setOnCheckedChangeListener((v, value) -> { c.showStatus = value; onConfigChanged(); });
        page.addView(showStatus);
        EditText defaultText = field(page, "Текст без полученного статуса", c.defaultText);
        watch(defaultText, value -> { c.defaultText = value; onConfigChanged(); });
        addSlider(page, "Размер статуса", 8, 140, c.textSize,
                value -> { c.textSize = value; onConfigChanged(); }, " px");
        addColor(page, "Обычный цвет статуса", c.defaultTextColor,
                value -> { c.defaultTextColor = value; onConfigChanged(); });
        page.addView(section("Правила текста и цвета"), topMargin(16));
        Button preset = button("Выбрать готовые правила отображения");
        preset.setOnClickListener(v -> chooseRulePreset());
        page.addView(preset, topMargin(7));
        buildRuleEditor(page);
        addStateRow(page, "До первого актуального значения", c.pendingText, c.pendingColor,
                (text, color) -> { c.pendingText = text; c.pendingColor = color; onConfigChanged(); });
        addStateRow(page, "Если значение устарело", c.staleText, c.staleColor,
                (text, color) -> { c.staleText = text; c.staleColor = color; onConfigChanged(); });
        addSlider(page, "Считать устаревшим через", 0, 3600, (int) c.staleAfterSeconds,
                value -> { c.staleAfterSeconds = value; onConfigChanged(); }, " сек (0 = никогда)");

        page.addView(section("Фон и форма"), topMargin(20));
        addColor(page, "Цвет фона", c.backgroundColor,
                value -> { c.backgroundColor = value; onConfigChanged(); });
        addSlider(page, "Прозрачность фона", 0, 255, c.backgroundAlpha,
                value -> { c.backgroundAlpha = value; onConfigChanged(); }, " / 255");
        addColor(page, "Цвет рамки", c.borderColor,
                value -> { c.borderColor = value; onConfigChanged(); });
        addSlider(page, "Толщина рамки", 0, 30, c.borderWidth,
                value -> { c.borderWidth = value; onConfigChanged(); }, " px");
        addSlider(page, "Скругление", 0, 120, c.cornerRadius,
                value -> { c.cornerRadius = value; onConfigChanged(); }, " px");
        addSlider(page, "Внутренний отступ", 0, 100, c.padding,
                value -> { c.padding = value; onConfigChanged(); }, " px");
        addSlider(page, "Сдвиг плитки по горизонтали", -200, 200, c.adjustX,
                value -> { c.adjustX = value; onConfigChanged(); }, " px");
        addSlider(page, "Сдвиг плитки по вертикали", -200, 200, c.adjustY,
                value -> { c.adjustY = value; onConfigChanged(); }, " px");

        page.addView(section("Нажатие"), topMargin(20));
        TextView actionSummary = label(actionSummary());
        page.addView(actionSummary, topMargin(5));
        Button action = button("Настроить действие по нажатию");
        action.setOnClickListener(v -> choosePopupAction(actionSummary));
        page.addView(action, topMargin(6));
        CheckBox confirm = check("Спрашивать подтверждение", c.confirmationRequired);
        confirm.setOnCheckedChangeListener((v, value) -> { c.confirmationRequired = value; onConfigChanged(); });
        page.addView(confirm);
        CheckBox hide = check("Скрыть весь этот оверлей после команды", c.autoHideAfterAction);
        hide.setOnCheckedChangeListener((v, value) -> { c.autoHideAfterAction = value; onConfigChanged(); });
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
        colorButton.setOnClickListener(v -> chooseColor(color[0], selected -> {
            color[0] = selected;
            colorButton.setText(colorName(selected));
            consumer.accept(text(text), selected);
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

    /** Builds a connector-neutral, ordered display-rule editor. The owning source binding stays
     * unchanged: only presentation of its current raw value is configured here. */
    private void buildRuleEditor(LinearLayout page) {
        TextView hint = label("Правила проверяются сверху вниз. Применяется только первое "
                + "совпавшее правило. Если замена текста или цвета выключена, исходное "
                + "значение остаётся без изменений. Узкий диапазон размещайте выше "
                + "широкого условия.");
        hint.setTextColor(0xFFB0BEC5);
        page.addView(hint, topMargin(8));

        LinearLayout testRow = row();
        testRow.setGravity(Gravity.CENTER_VERTICAL);
        ruleTestInput = new EditText(this);
        ruleTestInput.setSingleLine(true);
        ruleTestInput.setHint("Проверить, например: open или 41");
        testRow.addView(ruleTestInput, weighted());
        Button clear = button("×");
        clear.setContentDescription("Очистить проверочное значение");
        clear.setOnClickListener(v -> ruleTestInput.setText(""));
        testRow.addView(clear, new LinearLayout.LayoutParams(dp(52), dp(52)));
        page.addView(testRow, topMargin(7));
        ruleTestStatus = label("Введите пример — результат появится в макете выше");
        ruleTestStatus.setTextColor(0xFFB0BEC5);
        page.addView(ruleTestStatus, topMargin(2));
        watchRaw(ruleTestInput, value -> updatePreview());

        rulesContainer = column();
        page.addView(rulesContainer, topMargin(7));
        renderRules();

        Button add = button("＋ Добавить своё правило");
        add.setOnClickListener(v -> editRule(-1));
        page.addView(add, topMargin(7));
    }

    private void renderRules() {
        if (rulesContainer == null) return;
        rulesContainer.removeAllViews();
        RuleSet set = currentRules();
        if (set == null || set.rules.isEmpty()) {
            TextView empty = label("Правил нет — отображается исходное значение и обычный цвет.");
            empty.setTextColor(0xFFB0BEC5);
            rulesContainer.addView(empty, topMargin(6));
            return;
        }
        for (int index = 0; index < set.rules.size(); index++) {
            Rule rule = set.rules.get(index);
            LinearLayout card = column();
            card.setPadding(dp(12), dp(9), dp(12), dp(9));
            GradientDrawable background = new GradientDrawable();
            background.setColor(0x182196F3);
            background.setStroke(dp(1), 0x553F51B5);
            background.setCornerRadius(dp(12));
            card.setBackground(background);

            TextView title = heading((index + 1) + ". Если значение "
                    + conditionSummary(rule), 15);
            card.addView(title, matchWrap());
            TextView output = label(outputSummary(rule.output));
            output.setTextColor(rule.output.textColor == null
                    || "transparent".equalsIgnoreCase(rule.output.textColor)
                    ? 0xFFB0BEC5 : parseColor(rule.output.textColor));
            card.addView(output, topMargin(3));

            LinearLayout actions = row();
            Button up = button("↑");
            up.setContentDescription("Поднять правило выше");
            up.setEnabled(index > 0);
            final int current = index;
            up.setOnClickListener(v -> moveRule(current, current - 1));
            actions.addView(up, new LinearLayout.LayoutParams(dp(54), dp(48)));
            Button down = button("↓");
            down.setContentDescription("Опустить правило ниже");
            down.setEnabled(index + 1 < set.rules.size());
            down.setOnClickListener(v -> moveRule(current, current + 1));
            actions.addView(down, new LinearLayout.LayoutParams(dp(54), dp(48)));
            Button edit = button("Изменить");
            edit.setOnClickListener(v -> editRule(current));
            actions.addView(edit, weighted());
            Button delete = button("Удалить");
            delete.setOnClickListener(v -> deleteRule(current));
            actions.addView(delete, weighted());
            card.addView(actions, topMargin(4));
            rulesContainer.addView(card, topMargin(7));
        }
    }

    private void editRule(int index) {
        RuleSet set = currentRules();
        Rule existing = set != null && index >= 0 && index < set.rules.size()
                ? set.rules.get(index) : null;
        if (existing == null && set != null && set.rules.size() >= RuleSet.MAX_RULES) {
            Toast.makeText(this, "Достигнут защитный предел правил", Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout form = column();
        form.setPadding(dp(18), dp(4), dp(18), 0);
        form.addView(label("Условие"), topMargin(4));
        Spinner operator = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, VISUAL_OPERATOR_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        operator.setAdapter(adapter);
        operator.setSelection(operatorIndex(existing == null ? Operator.EQUALS : existing.operator));
        form.addView(operator, matchWrap());

        LinearLayout operandGroup = ruleFieldGroup("Значение / граница A",
                existing == null ? "" : existing.operand);
        EditText operand = (EditText) operandGroup.getChildAt(1);
        form.addView(operandGroup, topMargin(5));
        LinearLayout secondGroup = ruleFieldGroup("Верхняя граница B",
                existing == null ? "" : existing.secondOperand);
        EditText secondOperand = (EditText) secondGroup.getChildAt(1);
        form.addView(secondGroup, topMargin(5));

        form.addView(section("Что показать"), topMargin(14));
        CheckBox replaceText = check("Заменить текст", existing != null
                && existing.output.textTemplate != null);
        form.addView(replaceText);
        EditText replacement = new EditText(this);
        replacement.setSingleLine(true);
        replacement.setHint("Например: О, > или Температура {value}");
        replacement.setText(existing == null || existing.output.textTemplate == null
                ? "" : existing.output.textTemplate);
        form.addView(replacement, matchWrap());
        TextView replacementHint = label("{value} подставляет исходное значение. "
                + "Если флажок выключен или поле пустое, текст не меняется.");
        replacementHint.setTextColor(0xFFB0BEC5);
        form.addView(replacementHint, topMargin(2));

        final String[] selectedColor = {existing == null ? null : existing.output.textColor};
        Button color = button("Цвет: " + colorName(selectedColor[0]));
        color.setOnClickListener(v -> chooseRuleColor(selectedColor[0], value -> {
            selectedColor[0] = value;
            color.setText("Цвет: " + colorName(value));
        }));
        form.addView(color, topMargin(8));

        Runnable refreshInputs = () -> {
            Operator selected = VISUAL_OPERATORS[operator.getSelectedItemPosition()];
            int operands = operandCount(selected);
            operandGroup.setVisibility(operands >= 1 ? View.VISIBLE : View.GONE);
            secondGroup.setVisibility(operands >= 2 ? View.VISIBLE : View.GONE);
            int inputType = isNumeric(selected)
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED
                    : InputType.TYPE_CLASS_TEXT;
            operand.setInputType(inputType);
            secondOperand.setInputType(inputType);
        };
        operator.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long itemId) {
                refreshInputs.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        replaceText.setOnCheckedChangeListener((v, checked) -> {
            replacement.setEnabled(checked);
            replacement.setAlpha(checked ? 1f : 0.45f);
        });
        replacement.setEnabled(replaceText.isChecked());
        replacement.setAlpha(replaceText.isChecked() ? 1f : 0.45f);
        refreshInputs.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новое правило" : "Изменить правило")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton(existing == null ? "Добавить" : "Применить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        Operator selected = VISUAL_OPERATORS[operator.getSelectedItemPosition()];
                        String first = text(operand);
                        String second = text(secondOperand);
                        if (isNumeric(selected)) {
                            first = normalizedNumber(first);
                            if (operandCount(selected) == 2) second = normalizedNumber(second);
                        }
                        if (operandCount(selected) >= 1 && first.isEmpty()) {
                            throw new IllegalArgumentException("Укажите значение условия");
                        }
                        if (operandCount(selected) == 2 && second.isEmpty()) {
                            throw new IllegalArgumentException("Укажите обе границы диапазона");
                        }
                        if (operandCount(selected) == 2) {
                            int order = new BigDecimal(first).compareTo(new BigDecimal(second));
                            if (order > 0 || (selected == Operator.BETWEEN_EXCLUSIVE
                                    && order == 0)) {
                                throw new IllegalArgumentException(selected
                                        == Operator.BETWEEN_EXCLUSIVE
                                        ? "Для строгого диапазона граница A должна быть меньше B"
                                        : "Граница A не должна быть больше B");
                            }
                        }
                        if (operandCount(selected) == 0) {
                            first = "";
                            second = "";
                        } else if (operandCount(selected) == 1) {
                            second = "";
                        }
                        Output old = existing == null ? Output.none() : existing.output;
                        String replacementText = rawText(replacement);
                        String textOverride = replaceText.isChecked()
                                && !replacementText.trim().isEmpty() ? replacementText : null;
                        Output output = new Output(textOverride, selectedColor[0], old.icon,
                                old.backgroundColor, old.visible, old.actionEnabled);
                        Rule replacementRule = new Rule(existing == null
                                ? nextRuleId(set) : existing.id, Input.FIELD_VALUE, selected,
                                first, second, output);
                        replaceRule(index, replacementRule);
                        dialog.dismiss();
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void replaceRule(int index, Rule replacement) {
        RuleSet current = currentRules();
        List<Rule> rules = current == null ? new ArrayList<>()
                : new ArrayList<>(current.rules);
        if (index >= 0 && index < rules.size()) rules.set(index, replacement);
        else {
            // Keep leading connection guards first, then give a human override priority over
            // preset value mappings and their ALWAYS fallback. Otherwise adding "open → O"
            // to the cover preset would never run because preset.open already matched earlier.
            int insertion = 0;
            while (insertion < rules.size()
                    && isConnectionGuard(rules.get(insertion).operator)) insertion++;
            rules.add(insertion, replacement);
        }
        setRules(new RuleSet(current == null ? "display.custom" : current.id,
                current == null ? null : current.sourceReference, rules));
        renderRules();
        onConfigChanged();
    }

    private void moveRule(int from, int to) {
        RuleSet current = currentRules();
        if (current == null || from < 0 || from >= current.rules.size()
                || to < 0 || to >= current.rules.size()) return;
        List<Rule> rules = new ArrayList<>(current.rules);
        Rule moving = rules.remove(from);
        rules.add(to, moving);
        setRules(new RuleSet(current.id, current.sourceReference, rules));
        renderRules();
        onConfigChanged();
    }

    private void deleteRule(int index) {
        RuleSet current = currentRules();
        if (current == null || index < 0 || index >= current.rules.size()) return;
        new AlertDialog.Builder(this).setTitle("Удалить правило?")
                .setMessage(conditionSummary(current.rules.get(index)))
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> {
                    List<Rule> rules = new ArrayList<>(current.rules);
                    rules.remove(index);
                    setRules(new RuleSet(current.id, current.sourceReference, rules));
                    renderRules();
                    onConfigChanged();
                }).show();
    }

    @Nullable private RuleSet currentRules() {
        return main != null ? main.displayRules : popup.displayRules;
    }

    private void setRules(RuleSet rules) {
        if (main != null) main.displayRules = rules;
        else popup.displayRules = rules;
    }

    private static String nextRuleId(@Nullable RuleSet set) {
        int number = set == null ? 1 : set.rules.size() + 1;
        while (true) {
            String candidate = "custom_" + number++;
            boolean found = false;
            if (set != null) for (Rule rule : set.rules) if (candidate.equals(rule.id)) {
                found = true;
                break;
            }
            if (!found) return candidate;
        }
    }

    private LinearLayout ruleFieldGroup(String title, String value) {
        LinearLayout group = column();
        group.addView(label(title), matchWrap());
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value == null ? "" : value);
        group.addView(input, matchWrap());
        return group;
    }

    private static int operatorIndex(Operator value) {
        for (int index = 0; index < VISUAL_OPERATORS.length; index++) {
            if (VISUAL_OPERATORS[index] == value) return index;
        }
        return VISUAL_OPERATORS.length - 1;
    }

    private static int operandCount(Operator operator) {
        switch (operator) {
            case ALWAYS:
            case TRUE:
            case FALSE:
            case EMPTY:
            case NOT_EMPTY:
            case FRESH:
            case STALE:
            case AVAILABLE:
            case UNAVAILABLE:
                return 0;
            case BETWEEN:
            case BETWEEN_EXCLUSIVE:
                return 2;
            default:
                return 1;
        }
    }

    private static boolean isNumeric(Operator operator) {
        switch (operator) {
            case GREATER:
            case GREATER_OR_EQUAL:
            case LESS:
            case LESS_OR_EQUAL:
            case BETWEEN:
            case BETWEEN_EXCLUSIVE:
                return true;
            default:
                return false;
        }
    }

    private static boolean isConnectionGuard(Operator operator) {
        // Only failure-state guards must stay ahead of a newly added value mapping. AVAILABLE and
        // FRESH match virtually every normal update and would make the new human rule unreachable.
        return operator == Operator.UNAVAILABLE || operator == Operator.STALE;
    }

    private static String normalizedNumber(String value) {
        String normalized = value == null ? "" : value.trim().replace(',', '.');
        if (normalized.isEmpty()) return "";
        try {
            return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Введите число, например 40 или 40,5");
        }
    }

    private static String conditionSummary(Rule rule) {
        int index = operatorIndex(rule.operator);
        String label = VISUAL_OPERATOR_LABELS[index];
        if (operandCount(rule.operator) == 0) return label;
        if (operandCount(rule.operator) == 2) return label.replace("A", rule.operand)
                .replace("B", rule.secondOperand);
        return label + " «" + rule.operand + "»";
    }

    private static String outputSummary(Output output) {
        String text = output.textTemplate == null ? "текст оставить как есть"
                : output.textTemplate.isEmpty() ? "показать пустой текст"
                : "текст → «" + output.textTemplate + "»";
        String color = output.textColor == null ? "цвет оставить как есть"
                : "цвет → " + colorName(output.textColor);
        return text + " · " + color;
    }

    private void chooseRuleColor(@Nullable String current, NullableColorConsumer consumer) {
        String[] labels = {"Не менять исходный цвет", "Белый", "Зелёный", "Жёлтый",
                "Оранжевый", "Красный", "Фиолетовый", "Голубой", "Серый",
                "Чёрный", "Прозрачный", "Свой цвет…"};
        String[] colors = {null, "#FFFFFFFF", "#FF4CAF50", "#FFFFC107", "#FFFF9800",
                "#FFF44336", "#FF9C27B0", "#FF03A9F4", "#FF9E9E9E", "#FF000000",
                "transparent"};
        new AlertDialog.Builder(this).setTitle("Цвет при совпадении")
                .setItems(labels, (d, which) -> {
                    if (which < colors.length) consumer.accept(colors[which]);
                    else customColor(current == null ? "#FFFFFFFF" : current, consumer::accept);
                }).setNegativeButton("Отмена", null).show();
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
                    try {
                        if (!"transparent".equalsIgnoreCase(value)) Color.parseColor(value);
                        consumer.accept(value);
                    }
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
                    renderRules();
                    onConfigChanged();
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
            onConfigChanged();
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
                    onConfigChanged();
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
                    onConfigChanged();
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
                    onConfigChanged();
                }).show();
    }

    private void chooseGridPosition(Button button) {
        PopupOverlayConfig overlay = popupOverlayConfig();
        int rows = Math.max(1, overlay.rows);
        int columns = Math.max(1, overlay.columns);
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
                    onConfigChanged();
                }).setNegativeButton("Отмена", null).show();
    }

    private String positionText() {
        return popup.row < 0 || popup.column < 0 ? "Положение: автоматически"
                : "Положение: строка " + (popup.row + 1) + ", столбец " + (popup.column + 1);
    }

    private String cellInfo() {
        PopupOverlayConfig overlay = popupOverlayConfig();
        int columns = Math.max(1, overlay.columns);
        int rows = Math.max(1, overlay.rows);
        int width = Math.max(1, overlay.width - overlay.paddingLeft
                - overlay.paddingRight - overlay.cellGap * (columns - 1));
        int height = Math.max(1, overlay.height - overlay.paddingTop
                - overlay.paddingBottom - overlay.cellGap * (rows - 1));
        return "Оверлей «" + overlay.name + "» · " + overlay.width + "×" + overlay.height
                + " px · сетка " + columns + "×" + rows + " · одна ячейка примерно "
                + (width / columns) + "×" + (height / rows) + " px\nТекущая плитка: "
                + popup.columnSpan + "×" + popup.rowSpan + " ячеек";
    }

    /** The visual editor must use the grid of the tile's owner, not the legacy global popup. */
    private PopupOverlayConfig popupOverlayConfig() {
        PopupOverlayConfig config = new PopupOverlayConfigStore(prefs).find(popup.overlayId);
        return config == null
                ? PopupOverlayConfig.create(popup.overlayId, popup.overlayId, 0) : config;
    }

    private void updatePreview() {
        if (preview == null) return;
        PreviewPresentation tested = testedPresentation();
        if (ruleTestStatus != null) {
            ruleTestStatus.setText(tested == null
                    ? "Введите пример — результат появится в макете выше"
                    : tested.ruleIndex < 0 ? "Ни одно правило не совпало"
                    : "Сработало правило №" + (tested.ruleIndex + 1));
        }
        if (main != null) {
            previewIcon.setVisibility(View.GONE);
            previewTitle.setVisibility(View.GONE);
            previewValue.setVisibility(View.VISIBLE);
            String normalText = main.defaultText.isEmpty() ? main.name : main.defaultText;
            previewValue.setText(tested == null ? normalText : tested.text);
            previewValue.setTextSize(Math.min(72, main.fontSize));
            previewValue.setTextColor(parseColor(tested == null ? main.defaultColor : tested.color));
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
        String normalText = popup.defaultText.isEmpty() ? "Актуальный статус" : popup.defaultText;
        previewValue.setText(tested == null ? normalText : tested.text);
        previewValue.setTextSize(Math.min(72, popup.textSize));
        previewValue.setTextColor(parseColor(tested == null
                ? popup.defaultTextColor : tested.color));
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

    @Nullable private PreviewPresentation testedPresentation() {
        if (ruleTestInput == null) return null;
        String raw = rawText(ruleTestInput);
        if (raw.isEmpty()) return null;
        RuleSet set = currentRules();
        if (set == null) return null;
        Result result = set.evaluate(Input.value(raw, true, true));
        String text = result.renderedText == null ? raw : result.renderedText;
        String fallback = main != null ? main.defaultColor : popup.defaultTextColor;
        String color = result.output.textColor == null ? fallback : result.output.textColor;
        return new PreviewPresentation(text, color, result.matchedRuleIndex);
    }

    private void onConfigChanged() {
        updatePreview();
        if (!screenReady || liveHandler == null) return;
        if (liveStatus != null) liveStatus.setText("Применяю изменения…");
        if (liveApplyScheduled) return;
        liveApplyScheduled = true;
        liveHandler.postDelayed(this::persistLive, LIVE_APPLY_INTERVAL_MS);
    }

    private void flushLiveApply() {
        if (!liveApplyScheduled || liveHandler == null) return;
        liveHandler.removeCallbacksAndMessages(null);
        persistLive();
    }

    private void persistLive() {
        liveApplyScheduled = false;
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
            if (WidgetService.isRunning()) {
                // Popup appearance and rules do not change connector subscriptions. Rebuild
                // only the floating windows so live sliders stay responsive even when the
                // Sprut catalog contains hundreds of accessories. Main-row edits still need the
                // full pass because they can affect layout and built-in sensor subscriptions.
                if (popup != null) WidgetService.getInstance().applyPopupItemPreferences();
                else WidgetService.getInstance().applyMainItemPreferences();
                if (liveStatus != null) liveStatus.setText("Сохранено и применено к виджету");
            } else if (liveStatus != null) {
                liveStatus.setText("Сохранено — применится при запуске виджета");
            }
        } catch (Exception error) {
            if (liveStatus != null) liveStatus.setText("Не удалось применить изменения");
            Toast.makeText(this, "Не удалось применить: " + safeMessage(error), Toast.LENGTH_LONG).show();
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

    private static void watchRaw(EditText input, StringConsumer consumer) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count,
                                                    int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before,
                                                int count) {
                consumer.accept(value == null ? "" : value.toString());
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
        if (color == null) return "Не менять";
        switch (color.toUpperCase()) {
            case "#FFFFFFFF": return "Белый";
            case "#FF4CAF50": return "Зелёный";
            case "#FFFFC107": return "Жёлтый";
            case "#FFFF9800": return "Оранжевый";
            case "#FFF44336": return "Красный";
            case "#FF9C27B0": return "Фиолетовый";
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
    private static String rawText(EditText value) { return value.getText() == null
            ? "" : value.getText().toString(); }
    private static String safeMessage(Throwable value) { return value.getMessage() == null
            ? value.getClass().getSimpleName() : value.getMessage(); }

    private interface ColorConsumer { void accept(String value); }
    private interface NullableColorConsumer { void accept(@Nullable String value); }
    private interface StateConsumer { void accept(String text, String color); }
    private interface StringConsumer { void accept(String value); }

    private static final class PreviewPresentation {
        final String text;
        final String color;
        final int ruleIndex;
        PreviewPresentation(String text, String color, int ruleIndex) {
            this.text = text;
            this.color = color;
            this.ruleIndex = ruleIndex;
        }
    }
}
