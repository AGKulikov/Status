/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.popup.PopupIconCatalog;
import dezz.status.widget.scenario.Condition;
import dezz.status.widget.scenario.ConditionMode;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.LocalAction;
import dezz.status.widget.scenario.LocalField;
import dezz.status.widget.scenario.Operator;
import dezz.status.widget.scenario.Scenario;
import dezz.status.widget.scenario.TargetScope;
import dezz.status.widget.scenario.ValueReference;

/**
 * Small, deliberately constrained editor for connector-neutral local scenarios.
 *
 * <p>The first UI version edits {@code ALL} scenarios containing exactly one condition and one
 * local action. Other JSON objects are kept in the ordered array byte-for-byte semantically and
 * shown as read-only cards, so opening this screen cannot discard a newer or more advanced valid
 * scenario written by another version of the application.</p>
 */
public final class ScenarioSettingsActivity extends AppCompatActivity {
    private static final String DEFAULT_CONNECTOR_ID = "default";
    private static final String[] CONNECTORS = {
            "HOME_ASSISTANT", "MQTT", "SPRUTHUB"
    };
    private static final String[] OPERATOR_VALUES = {
            "EQUALS", "NOT_EQUALS", "TRUE", "FALSE", "GREATER", "GREATER_OR_EQUAL",
            "LESS", "LESS_OR_EQUAL", "BETWEEN", "CONTAINS", "EMPTY", "NOT_EMPTY",
            "AVAILABLE", "UNAVAILABLE", "FRESH", "STALE", "ALWAYS"
    };
    private static final String[] OPERATOR_LABELS = {
            "равно", "не равно", "включено / да", "выключено / нет", "больше",
            "больше или равно", "меньше", "меньше или равно", "в диапазоне",
            "содержит текст", "пустое", "не пустое", "доступно", "недоступно",
            "актуально", "устарело", "всегда"
    };
    private static final String[] TARGET_VALUES = {"MAIN", "POPUP", "BUILTIN", "OVERLAY"};
    private static final String[] TARGET_LABELS = {
            "Элемент основной строки", "Плитка плавающего оверлея",
            "Штатный элемент", "Весь плавающий оверлей"
    };
    private static final String[] FIELD_VALUES = {
            "VISIBLE", "TEXT_COLOR", "BACKGROUND_COLOR", "ICON", "ACTION_ENABLED"
    };
    private static final String[] FIELD_LABELS = {
            "Показать или скрыть", "Изменить цвет текста", "Изменить цвет фона",
            "Изменить иконку", "Разрешить или запретить нажатие"
    };

    private Preferences prefs;
    private LinearLayout scenarioHost;
    private Button addButton;
    private final List<Entry> entries = new ArrayList<>();
    private String loadError;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        loadEntries();
        setContentView(buildScreen());
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
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Локальные сценарии", 24), weighted());
        page.addView(header, matchWrap());

        TextView explanation = label("Соберите правило из трёх шагов: выберите устройство, "
                + "задайте понятное условие и выберите, что изменить. Источник и цель могут быть "
                + "из разных систем — например, датчик Home Assistant может показать плитку "
                + "Sprut.hub.");
        page.addView(explanation, topMargin(10));

        Button intentCommands = new Button(this);
        intentCommands.setText("Намерения руля: Android Intent → команды");
        intentCommands.setOnClickListener(view -> startActivity(
                new Intent(this, IntentScenarioSettingsActivity.class)));
        page.addView(intentCommands, topMargin(14));

        LinearLayout listHeader = row();
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.addView(heading("Список", 20), weighted());
        addButton = new Button(this);
        addButton.setText("Добавить");
        addButton.setEnabled(loadError == null);
        addButton.setOnClickListener(view -> showEditor(-1));
        listHeader.addView(addButton);
        page.addView(listHeader, topMargin(20));

        if (loadError != null) {
            TextView error = label("Конфигурация не открыта: " + loadError
                    + ". Исходный JSON оставлен без изменений.");
            error.setTextColor(0xFFFF6B6B);
            page.addView(error, topMargin(8));
        }

        scenarioHost = column();
        page.addView(scenarioHost, matchWrap());
        renderEntries();
        return scroll;
    }

    /** Advanced escape hatch for ALL/ANY and multiple conditions/actions supported by the engine. */
    private void showRawEditor() {
        EditText editor = new EditText(this);
        editor.setText(prefs.localScenariosJson.get());
        editor.setMinLines(12);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(false);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(editor, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Расширенный JSON сценариев")
                .setMessage("Движок поддерживает ALL/ANY, до 128 условий и действий. "
                        + "Обычные сравнения не срабатывают на stale/unavailable.")
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Проверить и сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        JSONArray array = new JSONArray(rawText(editor));
                        if (array.length() > 128) {
                            throw new IllegalArgumentException("Допускается не более 128 сценариев");
                        }
                        for (int index = 0; index < array.length(); index++) {
                            JSONObject item = array.optJSONObject(index);
                            if (item == null) {
                                throw new IllegalArgumentException("Запись " + (index + 1)
                                        + " должна быть объектом");
                            }
                            Scenario.fromJson(item);
                        }
                        prefs.localScenariosJson.set(array.toString());
                        if (WidgetService.isRunning()) {
                            WidgetService.getInstance().applyPreferences();
                        }
                        dialog.dismiss();
                        loadEntries();
                        // Rebuild the whole screen so a repaired JSON document also removes the
                        // old error banner and re-enables the Add button immediately.
                        setContentView(buildScreen());
                        Toast.makeText(this, "JSON сценариев сохранён", Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        showValidationError(error);
                    }
                }));
        dialog.show();
    }

    private void loadEntries() {
        entries.clear();
        loadError = null;
        String raw = prefs.localScenariosJson.get();
        String json = raw == null ? "[]" : raw.trim();
        if (json.isEmpty()) json = "[]";
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                Object rawItem = array.opt(index);
                Scenario scenario = parseScenario(rawItem);
                entries.add(new Entry(rawItem, scenario, isEditorSupported(scenario)));
            }
        } catch (JSONException error) {
            loadError = "ожидался JSON-массив";
        }
    }

    private void renderEntries() {
        if (scenarioHost == null) return;
        scenarioHost.removeAllViews();
        if (entries.isEmpty() && loadError == null) {
            scenarioHost.addView(label("Сценариев пока нет."), topMargin(12));
            return;
        }
        for (int index = 0; index < entries.size(); index++) {
            scenarioHost.addView(buildCard(entries.get(index), index), cardParams());
        }
    }

    private View buildCard(Entry entry, int index) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor());
        background.setStroke(dp(1), 0x557F7F7F);
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(heading(entryTitle(entry, index), 18), weighted());
        if (entry.scenario != null) {
            Switch enabled = new Switch(this);
            enabled.setText("Вкл.");
            enabled.setChecked(entry.scenario.enabled);
            enabled.setOnCheckedChangeListener((button, checked) ->
                    setEntryEnabled(entry, checked, enabled));
            titleRow.addView(enabled);
        }
        card.addView(titleRow, matchWrap());

        TextView summary = label(entrySummary(entry));
        card.addView(summary, topMargin(6));
        if (!entry.editorSupported) {
            TextView notice = label(entry.scenario == null
                    ? "Неизвестный формат: запись сохранится без изменений."
                    : "Сложный сценарий: доступно включение и удаление; содержимое сохранится "
                    + "без изменений.");
            notice.setTextColor(0xFFFFB74D);
            card.addView(notice, topMargin(6));
        }

        LinearLayout actions = row();
        Button up = new Button(this);
        up.setText("↑");
        up.setContentDescription("Выше");
        up.setEnabled(index > 0);
        up.setOnClickListener(view -> moveEntry(entry, -1));
        actions.addView(up);
        Button down = new Button(this);
        down.setText("↓");
        down.setContentDescription("Ниже");
        down.setEnabled(index >= 0 && index < entries.size() - 1);
        down.setOnClickListener(view -> moveEntry(entry, 1));
        actions.addView(down);
        Button edit = new Button(this);
        edit.setText("Изменить");
        edit.setEnabled(entry.editorSupported);
        edit.setOnClickListener(view -> showEditor(entries.indexOf(entry)));
        actions.addView(edit, weighted());
        Button delete = new Button(this);
        delete.setText("Удалить");
        delete.setOnClickListener(view -> confirmDelete(entry));
        actions.addView(delete, weighted());
        card.addView(actions, topMargin(8));
        return card;
    }

    private void moveEntry(Entry entry, int direction) {
        int from = entries.indexOf(entry);
        int to = from + direction;
        if (from < 0 || to < 0 || to >= entries.size()) return;
        Collections.swap(entries, from, to);
        if (!persist(false)) Collections.swap(entries, to, from);
        renderEntries();
    }

    private void setEntryEnabled(Entry entry, boolean checked, Switch control) {
        if (entry.scenario == null || entry.scenario.enabled == checked) return;
        boolean oldValue = entry.scenario.enabled;
        try {
            JSONObject updated = copyObject((JSONObject) entry.raw);
            updated.put("enabled", checked);
            // Parse and serialize once through the public model API. The original object remains
            // the storage base so forward-compatible fields unknown to this editor are retained.
            Scenario parsed = Scenario.fromJson(updated);
            parsed.toJson();
            Object oldRaw = entry.raw;
            Scenario oldScenario = entry.scenario;
            entry.raw = updated;
            entry.scenario = parsed;
            if (!persist(false)) {
                entry.raw = oldRaw;
                entry.scenario = oldScenario;
                control.setChecked(oldValue);
                return;
            }
            Toast.makeText(this, checked ? "Сценарий включён" : "Сценарий выключен",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            control.setChecked(oldValue);
            showValidationError(error);
        }
    }

    private void confirmDelete(Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить сценарий?")
                .setMessage(entryTitle(entry, entries.indexOf(entry)))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    int index = entries.indexOf(entry);
                    if (index < 0) return;
                    entries.remove(index);
                    if (persist(false)) {
                        renderEntries();
                        Toast.makeText(this, "Сценарий удалён", Toast.LENGTH_SHORT).show();
                    } else {
                        entries.add(index, entry);
                    }
                })
                .show();
    }

    private void showEditor(int index) {
        if (loadError != null) {
            Toast.makeText(this, "Сначала исправьте JSON конфигурации", Toast.LENGTH_LONG).show();
            return;
        }
        Entry original = index >= 0 && index < entries.size() ? entries.get(index) : null;
        if (original != null && !original.editorSupported) {
            Toast.makeText(this, "Этот сложный сценарий доступен только для включения или "
                    + "удаления", Toast.LENGTH_LONG).show();
            return;
        }
        EditorViews views = new EditorViews(original == null ? null : original.scenario);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(views.root, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(original == null ? "Новый сценарий" : "Изменить сценарий")
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        Scenario scenario = views.read();
                        ensureUniqueId(scenario.id, index);
                        JSONObject canonical = scenario.toJson();
                        JSONObject stored = original == null
                                ? canonical : mergeKnownFields((JSONObject) original.raw, canonical);
                        Scenario verified = Scenario.fromJson(stored);
                        verified.toJson();
                        Entry replacement = new Entry(stored, verified, true);
                        if (index < 0) {
                            if (entries.size() >= 128) {
                                throw new IllegalArgumentException("Допускается не более 128 сценариев");
                            }
                            entries.add(replacement);
                            if (!persist(false)) {
                                entries.remove(entries.size() - 1);
                                return;
                            }
                        } else {
                            Entry previous = entries.set(index, replacement);
                            if (!persist(false)) {
                                entries.set(index, previous);
                                return;
                            }
                        }
                        renderEntries();
                        dialog.dismiss();
                        Toast.makeText(this, "Сценарий сохранён", Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        showValidationError(error);
                    }
                }));
        dialog.show();
    }

    /**
     * Overlays the fields understood by this screen onto the original JSON object. This keeps
     * harmless future fields at the scenario/reference/condition/action levels while all edited
     * values still originate from {@link Scenario#toJson()}.
     */
    private static JSONObject mergeKnownFields(JSONObject original, JSONObject canonical)
            throws JSONException {
        JSONObject merged = copyObject(original);
        merged.put("schemaVersion", canonical.getInt("schemaVersion"));
        merged.put("id", canonical.getString("id"));
        merged.put("enabled", canonical.getBoolean("enabled"));
        merged.put("mode", canonical.getString("mode"));

        JSONObject originalCondition = firstObject(original.optJSONArray("conditions"));
        JSONObject canonicalCondition = canonical.getJSONArray("conditions").getJSONObject(0);
        JSONObject condition = originalCondition == null
                ? new JSONObject() : copyObject(originalCondition);
        condition.put("id", canonicalCondition.getString("id"));
        condition.put("field", canonicalCondition.getString("field"));
        condition.put("operator", canonicalCondition.getString("operator"));
        condition.put("operand", canonicalCondition.getString("operand"));
        condition.put("secondOperand", canonicalCondition.getString("secondOperand"));

        JSONObject originalReference = originalCondition == null
                ? null : originalCondition.optJSONObject("reference");
        JSONObject canonicalReference = canonicalCondition.getJSONObject("reference");
        JSONObject reference = originalReference == null
                ? new JSONObject() : copyObject(originalReference);
        reference.put("connectorType", canonicalReference.getString("connectorType"));
        reference.put("connectorId", canonicalReference.getString("connectorId"));
        reference.put("resourceId", canonicalReference.getString("resourceId"));
        if (canonicalReference.has("valuePath")) {
            reference.put("valuePath", canonicalReference.getString("valuePath"));
        } else {
            reference.remove("valuePath");
        }
        condition.put("reference", reference);
        merged.put("conditions", new JSONArray().put(condition));

        JSONObject originalAction = firstObject(original.optJSONArray("actions"));
        JSONObject canonicalAction = canonical.getJSONArray("actions").getJSONObject(0);
        JSONObject action = originalAction == null ? new JSONObject() : copyObject(originalAction);
        action.put("targetScope", canonicalAction.getString("targetScope"));
        action.put("targetId", canonicalAction.getString("targetId"));
        action.put("field", canonicalAction.getString("field"));
        action.put("value", canonicalAction.get("value"));
        merged.put("actions", new JSONArray().put(action));
        return merged;
    }

    private boolean persist(boolean showSuccess) {
        if (loadError != null) return false;
        if (entries.size() > 128) {
            Toast.makeText(this, "Допускается не более 128 сценариев", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) array.put(entry.raw);
            prefs.localScenariosJson.set(array.toString());
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            if (showSuccess) {
                Toast.makeText(this, "Сценарии сохранены", Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось сохранить сценарии: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void ensureUniqueId(String id, int editedIndex) {
        for (int index = 0; index < entries.size(); index++) {
            if (index == editedIndex) continue;
            Scenario scenario = entries.get(index).scenario;
            if (scenario != null && scenario.id.equals(id)) {
                throw new IllegalArgumentException("ID сценария уже используется: " + id);
            }
            Object raw = entries.get(index).raw;
            if (scenario == null && raw instanceof JSONObject
                    && id.equals(((JSONObject) raw).optString("id", ""))) {
                throw new IllegalArgumentException("ID сценария уже используется: " + id);
            }
        }
    }

    private final class EditorViews {
        final LinearLayout root = column();
        final Switch enabled;
        final EditText scenarioId;
        final Spinner connector;
        final EditText connectorId;
        final EditText resourceId;
        final EditText valuePath;
        final Spinner operator;
        final EditText operand;
        final EditText secondOperand;
        final Spinner target;
        final EditText targetId;
        final Spinner localField;
        final Switch booleanValue;
        final EditText stringValue;
        final TextView sourceSummary;
        final TextView targetSummary;
        final Button chooseStyleValue;
        final String conditionId;

        EditorViews(@Nullable Scenario source) {
            root.setPadding(dp(10), dp(6), dp(10), dp(20));
            Condition condition = source == null ? null : source.conditions.get(0);
            LocalAction action = source == null ? null : source.actions.get(0);
            conditionId = condition == null ? "condition_0" : condition.id;

            enabled = switchView("Сценарий включён", source == null || source.enabled);
            root.addView(enabled, matchWrap());
            scenarioId = field(root, "Уникальный ID сценария",
                    source == null ? nextScenarioId() : source.id);
            hideControlAndLabel(root, scenarioId);

            root.addView(section("1. Когда изменится устройство"), topMargin(16));
            connector = spinner(root, "Коннектор",
                    CONNECTORS, condition == null ? CONNECTORS[0]
                            : condition.reference.connectorType);
            hideControlAndLabel(root, connector);
            connectorId = field(root, "ID подключения",
                    condition == null ? DEFAULT_CONNECTOR_ID : condition.reference.connectorId);
            connectorId.setText(DEFAULT_CONNECTOR_ID);
            connectorId.setEnabled(false);
            hideControlAndLabel(root, connectorId);
            resourceId = field(root, "ID ресурса / entity / topic",
                    condition == null ? "" : condition.reference.resourceId);
            hideControlAndLabel(root, resourceId);
            valuePath = field(root, "Путь к значению (необязательно)",
                    condition == null || condition.reference.valuePath == null
                            ? "" : condition.reference.valuePath);
            hideControlAndLabel(root, valuePath);
            sourceSummary = label(condition == null ? "Устройство ещё не выбрано"
                    : sourceLabel(condition.reference));
            root.addView(sourceSummary, topMargin(6));
            Button chooseSource = new Button(ScenarioSettingsActivity.this);
            chooseSource.setText("Выбрать устройство");
            chooseSource.setOnClickListener(view -> showSourcePicker());
            root.addView(chooseSource, topMargin(6));

            root.addView(section("2. При каком условии"), topMargin(16));
            operator = mappedSpinner(root, "Сравнение", OPERATOR_LABELS, OPERATOR_VALUES,
                    condition == null ? Operator.EQUALS.jsonName()
                            : condition.operator.jsonName());
            operand = field(root, "Значение для сравнения",
                    condition == null ? "" : condition.operand);
            secondOperand = field(root, "Верхняя граница диапазона",
                    condition == null ? "" : condition.secondOperand);

            root.addView(section("3. Что сделать"), topMargin(16));
            target = mappedSpinner(root, "Где находится изменяемый элемент",
                    TARGET_LABELS, TARGET_VALUES,
                    action == null ? TargetScope.MAIN.jsonName()
                            : action.targetScope.jsonName());
            targetId = field(root, "ID кирпичика / элемента (например sprut_gate, builtin.time, popup)",
                    action == null ? "" : action.targetId);
            hideControlAndLabel(root, targetId);
            targetSummary = label(action == null ? "Элемент ещё не выбран"
                    : targetLabel(action.targetScope, action.targetId));
            root.addView(targetSummary, topMargin(6));
            Button chooseTarget = new Button(ScenarioSettingsActivity.this);
            chooseTarget.setText("Выбрать элемент");
            chooseTarget.setOnClickListener(view -> showTargetPicker());
            root.addView(chooseTarget, topMargin(6));
            localField = mappedSpinner(root, "Действие", FIELD_LABELS, FIELD_VALUES,
                    action == null ? LocalField.VISIBLE.jsonName() : action.field.jsonName());
            boolean initialBoolean = action == null || action.booleanValue() == null
                    || action.booleanValue();
            booleanValue = switchView("Показывать / разрешить", initialBoolean);
            root.addView(booleanValue, topMargin(8));
            stringValue = field(root, "Строковое значение стиля",
                    action == null || action.stringValue() == null ? "" : action.stringValue());
            hideControlAndLabel(root, stringValue);
            chooseStyleValue = new Button(ScenarioSettingsActivity.this);
            chooseStyleValue.setText(action == null || action.stringValue() == null
                    ? "Выбрать значение" : "Выбрано: " + action.stringValue());
            chooseStyleValue.setOnClickListener(view -> chooseActionValue());
            root.addView(chooseStyleValue, topMargin(6));
            localField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    updateValueControl();
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {
                    updateValueControl();
                }
            });
            updateValueControl();
        }

        Scenario read() {
            String id = text(scenarioId);
            String profile = DEFAULT_CONNECTOR_ID;
            String resource = text(resourceId);
            if (resource.isEmpty()) {
                throw new IllegalArgumentException("Укажите ID ресурса");
            }
            Operator selectedOperator = Operator.fromJsonName(
                    mappedValue(operator, OPERATOR_VALUES));
            String first = rawText(operand);
            String second = rawText(secondOperand);
            validateOperands(selectedOperator, first, second);

            ValueReference reference = new ValueReference(selected(connector), profile,
                    resource, optional(text(valuePath)));
            Condition condition = new Condition(conditionId, reference, Input.FIELD_VALUE,
                    selectedOperator, first, second);
            LocalField field = LocalField.fromJsonName(mappedValue(localField, FIELD_VALUES));
            Object value = isBooleanField(field)
                    ? booleanValue.isChecked() : text(stringValue);
            if (!isBooleanField(field) && ((String) value).isEmpty()) {
                throw new IllegalArgumentException("Укажите строковое значение стиля");
            }
            TargetScope targetScope = TargetScope.fromJsonName(mappedValue(target, TARGET_VALUES));
            String selectedTargetId = text(targetId);
            validateTarget(targetScope, selectedTargetId);
            LocalAction action = new LocalAction(targetScope, selectedTargetId, field, value);
            return new Scenario(id, enabled.isChecked(), ConditionMode.ALL,
                    Collections.singletonList(condition), Collections.singletonList(action));
        }

        private void updateValueControl() {
            LocalField field;
            try {
                field = LocalField.fromJsonName(mappedValue(localField, FIELD_VALUES));
            } catch (RuntimeException ignored) {
                field = LocalField.VISIBLE;
            }
            boolean bool = isBooleanField(field);
            booleanValue.setVisibility(bool ? View.VISIBLE : View.GONE);
            stringValue.setVisibility(View.GONE);
            chooseStyleValue.setVisibility(bool ? View.GONE : View.VISIBLE);
        }

        private void showSourcePicker() {
            List<SourceOption> options = sourceOptions();
            if (options.isEmpty()) {
                new AlertDialog.Builder(ScenarioSettingsActivity.this)
                        .setTitle("Нет доступных устройств")
                        .setMessage("Сначала добавьте нужное устройство в основную строку или "
                                + "плавающий оверлей. После этого оно появится здесь как источник.")
                        .setPositiveButton("Основная строка", (d, w) -> startActivity(
                                new Intent(ScenarioSettingsActivity.this,
                                        AutomationSettingsActivity.class)))
                        .setNegativeButton("Закрыть", null).show();
                return;
            }
            String[] labels = new String[options.size()];
            for (int index = 0; index < options.size(); index++) labels[index] = options.get(index).label;
            new AlertDialog.Builder(ScenarioSettingsActivity.this)
                    .setTitle("Устройство-источник").setItems(labels, (dialog, which) -> {
                        SourceBinding value = options.get(which).binding;
                        selectSpinnerValue(connector, CONNECTORS, value.connectorType.jsonName());
                        resourceId.setText(value.resourceId);
                        valuePath.setText(value.valuePath);
                        sourceSummary.setText(options.get(which).label);
                    }).setNegativeButton("Отмена", null).show();
        }

        private void chooseActionValue() {
            LocalField field = LocalField.fromJsonName(mappedValue(localField, FIELD_VALUES));
            if (field == LocalField.ICON) {
                String[] labels = {"Ворота", "Гараж", "Свет", "Замок", "Питание",
                        "Температура", "Вода", "Дверь", "Wi-Fi", "GPS", "Bluetooth"};
                new AlertDialog.Builder(ScenarioSettingsActivity.this).setTitle("Иконка")
                        .setItems(labels, (d, which) -> {
                            String value = PopupIconCatalog.IDS.get(which);
                            stringValue.setText(value);
                            chooseStyleValue.setText("Выбрано: " + labels[which]);
                        }).setNegativeButton("Отмена", null).show();
                return;
            }
            String[] labels = {"Белый", "Зелёный", "Оранжевый", "Красный", "Голубой",
                    "Серый", "Чёрный", "Прозрачный"};
            String[] colors = {"#FFFFFFFF", "#FF4CAF50", "#FFFF9800", "#FFF44336",
                    "#FF03A9F4", "#FF9E9E9E", "#FF000000", "transparent"};
            new AlertDialog.Builder(ScenarioSettingsActivity.this).setTitle("Цвет")
                    .setItems(labels, (d, which) -> {
                        stringValue.setText(colors[which]);
                        chooseStyleValue.setText("Выбрано: " + labels[which]);
                    }).setNegativeButton("Отмена", null).show();
        }

        private void showTargetPicker() {
            final TargetScope scope;
            try {
                scope = TargetScope.fromJsonName(mappedValue(target, TARGET_VALUES));
            } catch (RuntimeException error) {
                showValidationError(error);
                return;
            }
            List<TargetOption> options = targetOptions(scope);
            if (options.isEmpty()) {
                Toast.makeText(ScenarioSettingsActivity.this,
                        "Для выбранной области пока нет целей", Toast.LENGTH_LONG).show();
                return;
            }
            String[] labels = new String[options.size()];
            for (int index = 0; index < options.size(); index++) {
                labels[index] = options.get(index).label;
            }
            new AlertDialog.Builder(ScenarioSettingsActivity.this)
                    .setTitle("Цель сценария")
                    .setItems(labels, (dialog, which) -> {
                        targetId.setText(options.get(which).id);
                        targetSummary.setText(options.get(which).label);
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        }
    }

    private List<TargetOption> targetOptions(TargetScope scope) {
        List<TargetOption> result = new ArrayList<>();
        switch (scope) {
            case MAIN:
                for (HaBrickConfig item : new HaBrickConfigStore(prefs).loadMain()) {
                    String connector = item.sourceBinding == null ? "без источника"
                            : item.sourceBinding.connectorType.jsonName();
                    result.add(new TargetOption(item.id,
                            item.id + " — " + item.name + " [" + connector + "]"));
                }
                break;
            case POPUP:
                for (PopupItemConfig item : new PopupItemConfigStore(prefs).load()) {
                    String connector = item.sourceBinding == null ? "без источника"
                            : item.sourceBinding.connectorType.jsonName();
                    result.add(new TargetOption(item.automationId,
                            item.automationId + " — " + item.name + " [" + connector + "]"));
                }
                break;
            case BUILTIN:
                for (BrickType type : BrickType.values()) {
                    result.add(new TargetOption(type.automationId(),
                            type.automationId() + " — " + type.name()));
                }
                break;
            case OVERLAY:
                result.add(new TargetOption("popup", "popup — весь всплывающий оверлей"));
                break;
            default:
                break;
        }
        return result;
    }

    private List<SourceOption> sourceOptions() {
        LinkedHashMap<String, SourceOption> values = new LinkedHashMap<>();
        for (HaBrickConfig item : new HaBrickConfigStore(prefs).loadMain()) {
            addSourceOption(values, item.sourceBinding, item.name + " · основная строка");
        }
        for (PopupItemConfig item : new PopupItemConfigStore(prefs).load()) {
            addSourceOption(values, item.sourceBinding, item.name + " · плавающий оверлей");
        }
        return new ArrayList<>(values.values());
    }

    private static void addSourceOption(LinkedHashMap<String, SourceOption> values,
                                        SourceBinding binding, String name) {
        if (binding == null || !binding.isBound()) return;
        String key = binding.connectorType.jsonName() + '|' + binding.connectorId + '|'
                + binding.resourceId + '|' + binding.valuePath;
        String connector = binding.connectorType == dezz.status.widget.integration.ConnectorType.HOME_ASSISTANT
                ? "Home Assistant" : binding.connectorType
                == dezz.status.widget.integration.ConnectorType.SPRUTHUB ? "Sprut.hub" : "MQTT";
        values.putIfAbsent(key, new SourceOption(binding, name + "\n" + connector + " · "
                + binding.resourceId + (binding.valuePath.isEmpty() ? "" : " · " + binding.valuePath)));
    }

    private static String sourceLabel(ValueReference reference) {
        String connector = "HOME_ASSISTANT".equals(reference.connectorType) ? "Home Assistant"
                : "SPRUTHUB".equals(reference.connectorType) ? "Sprut.hub" : "MQTT";
        return connector + " · " + reference.resourceId
                + (reference.valuePath == null ? "" : " · " + reference.valuePath);
    }

    private String targetLabel(TargetScope scope, String id) {
        for (TargetOption option : targetOptions(scope)) if (option.id.equals(id)) return option.label;
        return id;
    }

    private void validateTarget(TargetScope scope, String id) {
        for (TargetOption option : targetOptions(scope)) {
            if (option.id.equals(id)) return;
        }
        throw new IllegalArgumentException("Выберите существующую цель для " + scope.jsonName());
    }

    private String nextScenarioId() {
        int suffix = 1;
        while (true) {
            String candidate = "scenario_" + suffix++;
            boolean used = false;
            for (Entry entry : entries) {
                if (entry.scenario != null && entry.scenario.id.equals(candidate)) {
                    used = true;
                    break;
                }
                if (entry.scenario == null && entry.raw instanceof JSONObject
                        && candidate.equals(((JSONObject) entry.raw).optString("id", ""))) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
        }
    }

    private static void validateOperands(Operator operator, String first, String second) {
        switch (operator) {
            case EQUALS:
            case NOT_EQUALS:
            case CONTAINS:
                if (first.isEmpty()) {
                    throw new IllegalArgumentException("Для выбранного оператора нужен operand");
                }
                break;
            case GREATER:
            case GREATER_OR_EQUAL:
            case LESS:
            case LESS_OR_EQUAL:
                requireNumber(first, "Operand должен быть числом");
                break;
            case BETWEEN:
                BigDecimal lower = requireNumber(first, "Первый operand должен быть числом");
                BigDecimal upper = requireNumber(second, "Second operand должен быть числом");
                if (lower.compareTo(upper) > 0) {
                    throw new IllegalArgumentException("Нижняя граница BETWEEN больше верхней");
                }
                break;
            default:
                // State/empty/boolean operators do not consume operands.
                break;
        }
    }

    private static BigDecimal requireNumber(String value, String error) {
        try {
            if (value.trim().isEmpty()) throw new NumberFormatException();
            return new BigDecimal(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(error);
        }
    }

    private static boolean isEditorSupported(@Nullable Scenario scenario) {
        if (scenario == null || scenario.mode != ConditionMode.ALL
                || scenario.conditions.size() != 1 || scenario.actions.size() != 1) return false;
        Condition condition = scenario.conditions.get(0);
        if (!Input.FIELD_VALUE.equals(condition.field)) return false;
        boolean connectorSupported = false;
        for (String connector : CONNECTORS) {
            if (connector.equals(condition.reference.connectorType)) {
                connectorSupported = true;
                break;
            }
        }
        return connectorSupported
                && DEFAULT_CONNECTOR_ID.equals(condition.reference.connectorId);
    }

    @Nullable
    private static Scenario parseScenario(Object raw) {
        if (!(raw instanceof JSONObject)) return null;
        try {
            return Scenario.fromJson((JSONObject) raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isBooleanField(LocalField field) {
        return field == LocalField.VISIBLE || field == LocalField.ACTION_ENABLED;
    }

    private static String entryTitle(Entry entry, int index) {
        return "Сценарий " + (index + 1);
    }

    private static String entrySummary(Entry entry) {
        if (entry.scenario == null) return "Формат не поддерживается этой версией редактора";
        Scenario scenario = entry.scenario;
        StringBuilder text = new StringBuilder();
        text.append(scenario.enabled ? "Работает" : "Выключен");
        if (!scenario.conditions.isEmpty()) {
            Condition condition = scenario.conditions.get(0);
            text.append("\nКогда: ").append(sourceLabel(condition.reference));
            if (condition.reference.valuePath != null) {
                text.append(" · ").append(condition.reference.valuePath);
            }
            text.append("\nУсловие: ").append(friendlyOperator(condition.operator));
            if (!condition.operand.isEmpty()) text.append(' ').append(condition.operand);
            if (!condition.secondOperand.isEmpty()) {
                text.append(" … ").append(condition.secondOperand);
            }
        }
        if (!scenario.actions.isEmpty()) {
            LocalAction action = scenario.actions.get(0);
            text.append("\nРезультат: ").append(friendlyField(action.field))
                    .append(" → ").append(action.targetId).append(" = ").append(action.value);
        }
        return text.toString();
    }

    private static String friendlyOperator(Operator operator) {
        for (int i = 0; i < OPERATOR_VALUES.length; i++) {
            if (OPERATOR_VALUES[i].equals(operator.jsonName())) return OPERATOR_LABELS[i];
        }
        return operator.jsonName();
    }

    private static String friendlyField(LocalField field) {
        for (int i = 0; i < FIELD_VALUES.length; i++) {
            if (FIELD_VALUES[i].equals(field.jsonName())) return FIELD_LABELS[i];
        }
        return field.jsonName();
    }

    private void showValidationError(Exception error) {
        Toast.makeText(this, "Проверьте настройки: " + safeMessage(error),
                Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static String optional(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static JSONObject firstObject(@Nullable JSONArray array) {
        return array == null ? null : array.optJSONObject(0);
    }

    private static JSONObject copyObject(JSONObject object) throws JSONException {
        return new JSONObject(object.toString());
    }

    private static String[] enumNames(Object[] values) {
        String[] result = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = String.valueOf(values[index]);
        }
        return result;
    }

    private Spinner spinner(LinearLayout parent, String title, String[] values, String selected) {
        parent.addView(label(title), topMargin(8));
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selectedIndex = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(selected)) {
                selectedIndex = index;
                break;
            }
        }
        spinner.setSelection(selectedIndex);
        parent.addView(spinner, matchWrap());
        return spinner;
    }

    private Spinner mappedSpinner(LinearLayout parent, String title, String[] labels,
                                  String[] values, String selectedValue) {
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(selectedValue)) {
            index = i;
            break;
        }
        Spinner result = spinner(parent, title, labels, labels[index]);
        result.setSelection(index);
        return result;
    }

    private static String mappedValue(Spinner spinner, String[] values) {
        int index = spinner.getSelectedItemPosition();
        return index < 0 || index >= values.length ? values[0] : values[index];
    }

    private static void selectSpinnerValue(Spinner spinner, String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(target)) {
            spinner.setSelection(i);
            return;
        }
    }

    private EditText field(LinearLayout parent, String title, String value) {
        parent.addView(label(title), topMargin(8));
        EditText edit = new EditText(this);
        edit.setText(value == null ? "" : value);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(edit, matchWrap());
        return edit;
    }

    private static void hideControlAndLabel(LinearLayout parent, View control) {
        int index = parent.indexOfChild(control);
        control.setVisibility(View.GONE);
        if (index > 0) parent.getChildAt(index - 1).setVisibility(View.GONE);
    }

    private Switch switchView(String title, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setChecked(checked);
        return control;
    }

    private TextView section(String text) {
        TextView view = heading(text, 17);
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView heading(String text, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        return view;
    }

    private static String selected(Spinner spinner) {
        Object value = spinner.getSelectedItem();
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(EditText field) {
        return rawText(field).trim();
    }

    private static String rawText(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
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
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(valueDp);
        return params;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int surfaceColor() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? 0xFF202124 : 0xFFF5F5F5;
    }

    private static final class Entry {
        Object raw;
        Scenario scenario;
        final boolean editorSupported;

        Entry(Object raw, @Nullable Scenario scenario, boolean editorSupported) {
            this.raw = raw;
            this.scenario = scenario;
            this.editorSupported = editorSupported;
        }
    }

    private static final class TargetOption {
        final String id;
        final String label;

        TargetOption(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final class SourceOption {
        final SourceBinding binding;
        final String label;

        SourceOption(SourceBinding binding, String label) {
            this.binding = binding;
            this.label = label;
        }
    }
}
