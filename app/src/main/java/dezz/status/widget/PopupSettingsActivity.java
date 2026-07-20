/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;

/** Full editor for fixed-pixel popup geometry, its grid and every independent tile. */
public final class PopupSettingsActivity extends AppCompatActivity {
    private Preferences prefs;
    private PopupItemConfigStore store;
    private LinearLayout itemsHost;
    private final List<ItemEditor> editors = new ArrayList<>();

    private CheckBox enabled;
    private EditText width, height, rows, columns, gap;
    private EditText padLeft, padTop, padRight, padBottom;
    private EditText bgColor, bgAlpha, cornerRadius;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        store = new PopupItemConfigStore(prefs);
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
        header.addView(heading(getString(R.string.popup_settings_title), 24), weighted());
        page.addView(header);

        TextView hint = label("Размер ячейки вычисляется автоматически: ширина и высота оверлея "
                + "минус отступы и промежутки, делённые на число столбцов и строк.");
        page.addView(hint, topMargin(10));
        enabled = check("Включить второй оверлей", prefs.popupEnabled.get());
        page.addView(enabled);
        width = field(page, "Ширина оверлея, px", prefs.popupWidth.get(), true);
        height = field(page, "Высота оверлея, px", prefs.popupHeight.get(), true);
        rows = field(page, "Строк сетки", prefs.popupRows.get(), true);
        columns = field(page, "Столбцов сетки", prefs.popupColumns.get(), true);
        gap = field(page, "Промежуток между ячейками, px", prefs.popupCellGap.get(), true);
        padLeft = field(page, "Отступ сетки слева, px", prefs.popupPaddingLeft.get(), true);
        padTop = field(page, "Отступ сетки сверху, px", prefs.popupPaddingTop.get(), true);
        padRight = field(page, "Отступ сетки справа, px", prefs.popupPaddingRight.get(), true);
        padBottom = field(page, "Отступ сетки снизу, px", prefs.popupPaddingBottom.get(), true);
        bgColor = field(page, "Цвет фона оверлея", prefs.popupBackgroundColor.get(), false);
        bgAlpha = field(page, "Прозрачность фона, 0–255", prefs.popupBackgroundAlpha.get(), true);
        cornerRadius = field(page, "Скругление оверлея, px", prefs.popupCornerRadius.get(), true);

        LinearLayout itemsHeader = row();
        itemsHeader.setGravity(Gravity.CENTER_VERTICAL);
        itemsHeader.addView(heading("Плитки и элементы", 20), weighted());
        Button add = new Button(this);
        add.setText(R.string.popup_add_item);
        add.setOnClickListener(v -> addNew());
        itemsHeader.addView(add);
        page.addView(itemsHeader, topMargin(24));

        itemsHost = column();
        page.addView(itemsHost, matchWrap());
        for (PopupItemConfig config : store.load()) addEditor(config);

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(v -> save());
        page.addView(save, topMargin(24));
        return scroll;
    }

    private void addNew() {
        Set<String> ids = new HashSet<>();
        for (ItemEditor editor : editors) ids.add(text(editor.id));
        int n = 1;
        while (ids.contains("tile_" + n)) n++;
        addEditor(PopupItemConfig.create("tile_" + n, editors.size()));
    }

    private void addEditor(PopupItemConfig config) {
        ItemEditor editor = new ItemEditor(config);
        editors.add(editor);
        itemsHost.addView(editor.root, cardParams());
    }

    private void move(ItemEditor editor, int direction) {
        int from = editors.indexOf(editor);
        int to = from + direction;
        if (from < 0 || to < 0 || to >= editors.size()) return;
        Collections.swap(editors, from, to);
        rebuildItems();
    }

    private void remove(ItemEditor editor) {
        editors.remove(editor);
        rebuildItems();
    }

    private void duplicate(ItemEditor source) {
        try {
            Set<String> used = new HashSet<>();
            for (ItemEditor editor : editors) used.add(text(editor.id));
            String base = text(source.id).isEmpty() ? "tile" : text(source.id);
            int suffix = 2;
            String next = base + "_copy";
            while (used.contains(next)) next = base + "_copy" + suffix++;
            JSONObject json = source.read(editors.indexOf(source)).toJson();
            json.put("id", next).put("automationId", next)
                    .put("name", text(source.name) + " (копия)");
            ItemEditor copy = new ItemEditor(PopupItemConfig.fromJson(json, editors.size()));
            editors.add(editors.indexOf(source) + 1, copy);
            rebuildItems();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.invalid_settings, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void rebuildItems() {
        itemsHost.removeAllViews();
        for (ItemEditor editor : editors) itemsHost.addView(editor.root, cardParams());
    }

    private void save() {
        try {
            prefs.popupEnabled.set(enabled.isChecked());
            prefs.popupWidth.set(clamp(number(width, 500), 100, 4000));
            prefs.popupHeight.set(clamp(number(height, 500), 100, 4000));
            prefs.popupRows.set(clamp(number(rows, 2), 1, 50));
            prefs.popupColumns.set(clamp(number(columns, 2), 1, 50));
            prefs.popupCellGap.set(clamp(number(gap, 8), 0, 500));
            prefs.popupPaddingLeft.set(clamp(number(padLeft, 12), 0, 1000));
            prefs.popupPaddingTop.set(clamp(number(padTop, 12), 0, 1000));
            prefs.popupPaddingRight.set(clamp(number(padRight, 12), 0, 1000));
            prefs.popupPaddingBottom.set(clamp(number(padBottom, 12), 0, 1000));
            prefs.popupBackgroundColor.set(text(bgColor));
            prefs.popupBackgroundAlpha.set(clamp(number(bgAlpha, 204), 0, 255));
            prefs.popupCornerRadius.set(clamp(number(cornerRadius, 28), 0, 1000));

            List<PopupItemConfig> result = new ArrayList<>();
            for (int i = 0; i < editors.size(); i++) result.add(editors.get(i).read(i));
            store.save(result);
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.invalid_settings,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private final class ItemEditor {
        final LinearLayout root = column();
        final CheckBox enabled;
        final EditText id, automationId, type, builtinId, name, row, column, rowSpan, columnSpan;
        final EditText icon, iconSize, iconColor, iconAlpha, iconBackgroundColor;
        final EditText iconBackgroundAlpha, iconPadding, iconCornerRadius, iconAlignment;
        final EditText iconAdjustX, iconAdjustY, iconRotation, orientation;
        final EditText title, titleSize, titleColor, titleAlpha;
        final EditText defaultText, textSize, defaultTextColor, textAlpha;
        final EditText pendingText, pendingColor, staleText, staleColor, staleSeconds;
        final EditText backgroundColor, backgroundAlpha, borderColor, borderAlpha, borderWidth;
        final EditText cornerRadius, padding, adjustX, adjustY;
        final CheckBox showTitle, showStatus, titleBold, textBold;
        final EditText actionId, actionPayload, confirmationText;
        final CheckBox confirmationRequired;
        final CheckBox autoHideAfterAction;

        ItemEditor(PopupItemConfig c) {
            root.setPadding(dp(14), dp(14), dp(14), dp(14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(surfaceColor());
            bg.setStroke(dp(1), 0x557F7F7F);
            bg.setCornerRadius(dp(16));
            root.setBackground(bg);

            LinearLayout actions = row();
            actions.addView(heading(c.name, 18), weighted());
            Button up = smallButton(R.string.move_up);
            Button down = smallButton(R.string.move_down);
            Button copy = smallButton("Копия");
            Button delete = smallButton(R.string.delete);
            up.setOnClickListener(v -> move(this, -1));
            down.setOnClickListener(v -> move(this, 1));
            copy.setOnClickListener(v -> duplicate(this));
            delete.setOnClickListener(v -> remove(this));
            actions.addView(up); actions.addView(down); actions.addView(copy); actions.addView(delete);
            root.addView(actions);

            enabled = check("Включена", c.enabled); root.addView(enabled);
            id = field(root, "Локальный ID конфигурации", c.id, false);
            automationId = field(root, "Automation ID (MQTT/Broadcast)", c.automationId, false);
            type = field(root, "Тип: HA_DEVICE / HA_TEXT / HA_BUTTON / STATIC_TEXT / BUILTIN",
                    c.type, false);
            builtinId = field(root, "ID штатного блока для BUILTIN (например builtin.time)",
                    c.builtinId, false);
            name = field(root, "Название в настройках", c.name, false);
            row = field(root, "Строка (-1 = автоматически)", c.row, true);
            column = field(root, "Столбец (-1 = автоматически)", c.column, true);
            rowSpan = field(root, "Высота в ячейках: 1, 2…", c.rowSpan, true);
            columnSpan = field(root, "Ширина в ячейках: 1, 2…", c.columnSpan, true);
            icon = field(root, "Локальная иконка: gate, garage, light, lock, power, "
                    + "temperature, water, door, wifi, gps, bluetooth", c.icon, false);
            iconSize = field(root, "Размер иконки, px", c.iconSize, true);
            iconColor = field(root, "Цвет иконки", c.iconColor, false);
            iconAlpha = field(root, "Прозрачность иконки, 0–255", c.iconAlpha, true);
            iconBackgroundColor = field(root, "Цвет фона иконки", c.iconBackgroundColor, false);
            iconBackgroundAlpha = field(root, "Прозрачность фона иконки, 0–255",
                    c.iconBackgroundAlpha, true);
            iconPadding = field(root, "Внутренний отступ иконки, px", c.iconPadding, true);
            iconCornerRadius = field(root, "Скругление фона иконки, px", c.iconCornerRadius, true);
            iconAlignment = field(root, "Выравнивание иконки: 0 слева, 1 центр, 2 справа",
                    c.iconAlignment, true);
            iconAdjustX = field(root, "Сдвиг иконки X, px", c.iconAdjustX, true);
            iconAdjustY = field(root, "Сдвиг иконки Y, px", c.iconAdjustY, true);
            iconRotation = field(root, "Поворот иконки, градусов", c.iconRotation, true);
            orientation = field(root, "Компоновка: 0 вертикальная, 1 горизонтальная",
                    c.orientation, true);
            showTitle = check("Показывать название", c.showTitle); root.addView(showTitle);
            showStatus = check("Показывать строку статуса", c.showStatus); root.addView(showStatus);
            title = field(root, "Заголовок плитки", c.title, false);
            titleSize = field(root, "Размер заголовка, px", c.titleSize, true);
            titleColor = field(root, "Цвет заголовка", c.titleColor, false);
            titleAlpha = field(root, "Прозрачность заголовка, 0–255", c.titleAlpha, true);
            titleBold = check("Жирный заголовок", c.titleBold); root.addView(titleBold);
            defaultText = field(root, "Текст/статус по умолчанию", c.defaultText, false);
            textSize = field(root, "Размер статуса, px", c.textSize, true);
            defaultTextColor = field(root, "Цвет статуса по умолчанию", c.defaultTextColor, false);
            textAlpha = field(root, "Прозрачность статуса, 0–255", c.textAlpha, true);
            textBold = check("Жирный статус", c.textBold); root.addView(textBold);
            pendingText = field(root, "Текст до первого состояния", c.pendingText, false);
            pendingColor = field(root, "Цвет до первого состояния", c.pendingColor, false);
            staleText = field(root, "Текст неактуального состояния", c.staleText, false);
            staleColor = field(root, "Цвет неактуального состояния", c.staleColor, false);
            staleSeconds = field(root, "Неактуален через, секунд (0 = никогда)",
                    String.valueOf(c.staleAfterSeconds), true);
            backgroundColor = field(root, "Цвет фона плитки", c.backgroundColor, false);
            backgroundAlpha = field(root, "Прозрачность фона плитки, 0–255", c.backgroundAlpha, true);
            borderColor = field(root, "Цвет обводки плитки", c.borderColor, false);
            borderAlpha = field(root, "Прозрачность обводки, 0–255", c.borderAlpha, true);
            borderWidth = field(root, "Толщина обводки, px", c.borderWidth, true);
            cornerRadius = field(root, "Скругление плитки, px", c.cornerRadius, true);
            padding = field(root, "Внутренний отступ плитки, px", c.padding, true);
            adjustX = field(root, "Горизонтальное смещение плитки, px", c.adjustX, true);
            adjustY = field(root, "Вертикальное смещение, px", c.adjustY, true);
            actionId = field(root, "ID MQTT-действия (пусто = без управления)", c.actionId, false);
            actionPayload = field(root, "Дополнительный JSON команды", c.actionPayload, false);
            confirmationRequired = check("Требовать диалог подтверждения", c.confirmationRequired);
            root.addView(confirmationRequired);
            confirmationText = field(root, "Текст подтверждения", c.confirmationText, false);
            autoHideAfterAction = check("Скрыть плитку после отправки команды", c.autoHideAfterAction);
            root.addView(autoHideAfterAction);
        }

        PopupItemConfig read(int orderValue) throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", text(id)).put("automationId", text(automationId))
                    .put("type", text(type)).put("builtinId", text(builtinId))
                    .put("name", text(name)).put("enabled", enabled.isChecked())
                    .put("order", orderValue);
            o.put("row", number(row, -1)).put("column", number(column, -1))
                    .put("rowSpan", number(rowSpan, 1)).put("columnSpan", number(columnSpan, 1));
            o.put("icon", text(icon)).put("iconSize", number(iconSize, 42))
                    .put("iconColor", text(iconColor)).put("iconAlpha", number(iconAlpha, 255))
                    .put("iconBackgroundColor", text(iconBackgroundColor))
                    .put("iconBackgroundAlpha", number(iconBackgroundAlpha, 0))
                    .put("iconPadding", number(iconPadding, 0))
                    .put("iconCornerRadius", number(iconCornerRadius, 16))
                    .put("iconAlignment", number(iconAlignment, 1))
                    .put("iconAdjustX", number(iconAdjustX, 0))
                    .put("iconAdjustY", number(iconAdjustY, 0))
                    .put("iconRotation", number(iconRotation, 0))
                    .put("orientation", number(orientation, 0))
                    .put("showTitle", showTitle.isChecked()).put("showStatus", showStatus.isChecked());
            o.put("title", text(title)).put("titleSize", number(titleSize, 18))
                    .put("titleColor", text(titleColor)).put("titleAlpha", number(titleAlpha, 255))
                    .put("titleBold", titleBold.isChecked());
            o.put("defaultText", text(defaultText)).put("textSize", number(textSize, 24))
                    .put("defaultTextColor", text(defaultTextColor))
                    .put("textAlpha", number(textAlpha, 255)).put("textBold", textBold.isChecked());
            o.put("pendingText", text(pendingText)).put("pendingColor", text(pendingColor))
                    .put("staleText", text(staleText)).put("staleColor", text(staleColor))
                    .put("staleAfterSeconds", longNumber(staleSeconds, 0));
            o.put("backgroundColor", text(backgroundColor))
                    .put("backgroundAlpha", number(backgroundAlpha, 235))
                    .put("borderColor", text(borderColor)).put("borderAlpha", number(borderAlpha, 0))
                    .put("borderWidth", number(borderWidth, 0))
                    .put("cornerRadius", number(cornerRadius, 28))
                    .put("padding", number(padding, 12)).put("adjustX", number(adjustX, 0))
                    .put("adjustY", number(adjustY, 0));
            o.put("actionId", text(actionId)).put("actionPayload", text(actionPayload))
                    .put("confirmationRequired", confirmationRequired.isChecked())
                    .put("confirmationText", text(confirmationText))
                    .put("autoHideAfterAction", autoHideAfterAction.isChecked());
            // Validate command JSON now so a typo cannot leave a button that silently drops data.
            if (!text(actionPayload).isEmpty()) new JSONObject(text(actionPayload));
            return PopupItemConfig.fromJson(o, orderValue);
        }
    }

    private TextInputEditText field(LinearLayout parent, String hint, int value, boolean numeric) {
        return field(parent, hint, String.valueOf(value), numeric);
    }

    private TextInputEditText field(LinearLayout parent, String hint, String value, boolean numeric) {
        TextInputLayout box = new TextInputLayout(this);
        box.setHint(hint);
        TextInputEditText input = new TextInputEditText(this);
        input.setText(value);
        input.setSingleLine(true);
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        box.addView(input, matchWrap());
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dp(8);
        parent.addView(box, lp);
        return input;
    }

    private CheckBox check(String value, boolean checked) {
        CheckBox view = new CheckBox(this); view.setText(value); view.setChecked(checked);
        view.setMinHeight(dp(48)); return view;
    }

    private Button smallButton(int text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setMinWidth(0);
        b.setMinimumWidth(0); return b;
    }
    private Button smallButton(String text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setMinWidth(0);
        b.setMinimumWidth(0); return b;
    }

    private TextView heading(String value, float size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); return t;
    }

    private TextView label(String value) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(13); t.setAlpha(.75f); return t;
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private LinearLayout.LayoutParams topMargin(int value) { LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(value); return p; }
    private LinearLayout.LayoutParams cardParams() { return topMargin(12); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int surfaceColor() { android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, value, true)
                ? value.data : Color.TRANSPARENT; }
    private static String text(EditText e) { return e.getText().toString().trim(); }
    private static int number(EditText e, int fallback) { try { return Integer.parseInt(text(e)); }
        catch (NumberFormatException ignored) { return fallback; } }
    private static long longNumber(EditText e, long fallback) { try { return Long.parseLong(text(e)); }
        catch (NumberFormatException ignored) { return fallback; } }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
