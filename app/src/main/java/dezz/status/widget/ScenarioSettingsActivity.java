/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.ha.api.HaEntity;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.popup.PopupIconCatalog;
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.scenario.Condition;
import dezz.status.widget.scenario.ConditionMode;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.LocalAction;
import dezz.status.widget.scenario.LocalField;
import dezz.status.widget.scenario.Operator;
import dezz.status.widget.scenario.Scenario;
import dezz.status.widget.scenario.TargetScope;
import dezz.status.widget.scenario.ValueReference;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutProtocolAdapter;

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
    private static final String[] CONNECTOR_LABELS = {
            "Home Assistant", "MQTT", "Sprut.hub"
    };
    /** Views remain bounded even when a connector exposes hundreds of devices. */
    private static final int MAX_SOURCE_RESULTS = 100;
    private static final String[] OPERATOR_VALUES = {
            "EQUALS", "NOT_EQUALS", "EQUALS_IGNORE_CASE", "NOT_EQUALS_IGNORE_CASE",
            "TRUE", "FALSE", "GREATER", "GREATER_OR_EQUAL",
            "LESS", "LESS_OR_EQUAL", "BETWEEN", "BETWEEN_EXCLUSIVE", "CONTAINS",
            "CONTAINS_IGNORE_CASE", "STARTS_WITH", "ENDS_WITH", "EMPTY", "NOT_EMPTY",
            "AVAILABLE", "UNAVAILABLE", "FRESH", "STALE", "ALWAYS"
    };
    private static final String[] OPERATOR_LABELS = {
            "равно", "не равно", "равно без учёта регистра",
            "не равно без учёта регистра", "включено / да", "выключено / нет", "больше",
            "больше или равно", "меньше", "меньше или равно", "в диапазоне включительно",
            "в диапазоне без границ", "содержит текст", "содержит без учёта регистра",
            "начинается с", "заканчивается на", "пустое", "не пустое", "доступно", "недоступно",
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
    private static final String[] BOOLEAN_BRANCH_LABELS = {
            "Не переопределять (обычное состояние)",
            "Показать / разрешить", "Скрыть / запретить"
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
        showContent();
    }

    private void showContent() {
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
                        showContent();
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

        merged.put("actions", mergeActionArray(original.optJSONArray("actions"),
                canonical.getJSONArray("actions")));
        merged.put("elseActions", mergeActionArray(original.optJSONArray("elseActions"),
                canonical.getJSONArray("elseActions")));
        return merged;
    }

    private static JSONArray mergeActionArray(@Nullable JSONArray original,
                                               JSONArray canonical) throws JSONException {
        if (canonical.length() == 0) return new JSONArray();
        JSONObject source = firstObject(original);
        JSONObject target = canonical.getJSONObject(0);
        JSONObject action = source == null ? new JSONObject() : copyObject(source);
        action.put("targetScope", target.getString("targetScope"));
        action.put("targetId", target.getString("targetId"));
        action.put("field", target.getString("field"));
        action.put("value", target.get("value"));
        return new JSONArray().put(action);
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
        final Spinner trueBooleanChoice;
        final Spinner falseBooleanChoice;
        final Switch trueStyleEnabled;
        final Switch falseStyleEnabled;
        final EditText stringValue;
        final EditText falseStringValue;
        final TextView sourceSummary;
        final TextView targetSummary;
        final Button chooseStyleValue;
        final Button chooseFalseStyleValue;
        final String conditionId;

        EditorViews(@Nullable Scenario source) {
            root.setPadding(dp(10), dp(6), dp(10), dp(20));
            Condition condition = source == null ? null : source.conditions.get(0);
            LocalAction action = source == null || source.actions.isEmpty()
                    ? null : source.actions.get(0);
            LocalAction elseAction = source == null || source.elseActions.isEmpty()
                    ? null : source.elseActions.get(0);
            if (elseAction == null && source != null && source.legacyFailClosed) {
                elseAction = legacyImplicitElse(action);
            }
            LocalAction baseAction = action == null ? elseAction : action;
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
            chooseSource.setText("Выбрать коннектор и устройство");
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
                    baseAction == null ? TargetScope.OVERLAY.jsonName()
                            : baseAction.targetScope.jsonName());
            targetId = field(root, "ID кирпичика / элемента (например sprut_gate, builtin.time, popup)",
                    baseAction == null ? "" : baseAction.targetId);
            hideControlAndLabel(root, targetId);
            targetSummary = label(baseAction == null ? "Элемент ещё не выбран"
                    : targetLabel(baseAction.targetScope, baseAction.targetId));
            root.addView(targetSummary, topMargin(6));
            Button chooseTarget = new Button(ScenarioSettingsActivity.this);
            chooseTarget.setText("Выбрать элемент");
            chooseTarget.setOnClickListener(view -> showTargetPicker());
            root.addView(chooseTarget, topMargin(6));
            localField = mappedSpinner(root, "Действие", FIELD_LABELS, FIELD_VALUES,
                    baseAction == null ? LocalField.VISIBLE.jsonName()
                            : baseAction.field.jsonName());
            root.addView(label("Если условие выполняется"), topMargin(10));
            trueBooleanChoice = simpleSpinner(BOOLEAN_BRANCH_LABELS,
                    booleanBranchIndex(action));
            root.addView(trueBooleanChoice, matchWrap());
            root.addView(label("Если условие НЕ выполняется"), topMargin(10));
            falseBooleanChoice = simpleSpinner(BOOLEAN_BRANCH_LABELS,
                    booleanBranchIndex(elseAction));
            root.addView(falseBooleanChoice, matchWrap());
            trueStyleEnabled = switchView("Если условие выполняется — изменить оформление",
                    action != null);
            root.addView(trueStyleEnabled, topMargin(8));
            falseStyleEnabled = switchView("Если условие НЕ выполняется — изменить оформление",
                    elseAction != null);
            root.addView(falseStyleEnabled, topMargin(8));
            stringValue = field(root, "Строковое значение стиля",
                    action == null || action.stringValue() == null ? "" : action.stringValue());
            hideControlAndLabel(root, stringValue);
            falseStringValue = field(root, "Строковое значение для ложной ветки",
                    elseAction == null || elseAction.stringValue() == null
                            ? "" : elseAction.stringValue());
            hideControlAndLabel(root, falseStringValue);
            chooseStyleValue = new Button(ScenarioSettingsActivity.this);
            chooseStyleValue.setText(action == null || action.stringValue() == null
                    ? "Значение для выполненного условия" : "Выбрано: " + action.stringValue());
            chooseStyleValue.setOnClickListener(view -> chooseActionValue(false));
            root.addView(chooseStyleValue, topMargin(6));
            chooseFalseStyleValue = new Button(ScenarioSettingsActivity.this);
            chooseFalseStyleValue.setText(elseAction == null || elseAction.stringValue() == null
                    ? "Значение для невыполненного условия"
                    : "Выбрано: " + elseAction.stringValue());
            chooseFalseStyleValue.setOnClickListener(view -> chooseActionValue(true));
            root.addView(chooseFalseStyleValue, topMargin(6));
            localField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    updateValueControl();
                    keepTileVisibilityOutOfPopupScope();
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {
                    updateValueControl();
                }
            });
            target.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    keepTileVisibilityOutOfPopupScope();
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            updateValueControl();
            keepTileVisibilityOutOfPopupScope();
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
            TargetScope targetScope = TargetScope.fromJsonName(mappedValue(target, TARGET_VALUES));
            String selectedTargetId = text(targetId);
            validateTarget(targetScope, selectedTargetId);
            if (field == LocalField.VISIBLE && targetScope == TargetScope.POPUP) {
                throw new IllegalArgumentException("Видимость настраивается для всего "
                        + "плавающего оверлея. Выберите «Весь плавающий оверлей»");
            }
            List<LocalAction> trueActions = branchActions(false, targetScope,
                    selectedTargetId, field);
            List<LocalAction> falseActions = branchActions(true, targetScope,
                    selectedTargetId, field);
            if (trueActions.isEmpty() && falseActions.isEmpty()) {
                throw new IllegalArgumentException("Выберите действие хотя бы для одного случая");
            }
            return new Scenario(id, enabled.isChecked(), ConditionMode.ALL,
                    Collections.singletonList(condition), trueActions, falseActions);
        }

        private List<LocalAction> branchActions(boolean falseBranch, TargetScope targetScope,
                                                String selectedTargetId, LocalField field) {
            if (isBooleanField(field)) {
                int choice = (falseBranch ? falseBooleanChoice : trueBooleanChoice)
                        .getSelectedItemPosition();
                if (choice <= 0) return Collections.emptyList();
                return Collections.singletonList(new LocalAction(targetScope, selectedTargetId,
                        field, choice == 1));
            }
            Switch enabledControl = falseBranch ? falseStyleEnabled : trueStyleEnabled;
            if (!enabledControl.isChecked()) return Collections.emptyList();
            String value = text(falseBranch ? falseStringValue : stringValue);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(falseBranch
                        ? "Выберите оформление для невыполненного условия"
                        : "Выберите оформление для выполненного условия");
            }
            return Collections.singletonList(new LocalAction(targetScope, selectedTargetId,
                    field, value));
        }

        private void updateValueControl() {
            LocalField field;
            try {
                field = LocalField.fromJsonName(mappedValue(localField, FIELD_VALUES));
            } catch (RuntimeException ignored) {
                field = LocalField.VISIBLE;
            }
            boolean bool = isBooleanField(field);
            trueBooleanChoice.setVisibility(bool ? View.VISIBLE : View.GONE);
            falseBooleanChoice.setVisibility(bool ? View.VISIBLE : View.GONE);
            trueStyleEnabled.setVisibility(bool ? View.GONE : View.VISIBLE);
            falseStyleEnabled.setVisibility(bool ? View.GONE : View.VISIBLE);
            stringValue.setVisibility(View.GONE);
            falseStringValue.setVisibility(View.GONE);
            chooseStyleValue.setVisibility(bool ? View.GONE : View.VISIBLE);
            chooseFalseStyleValue.setVisibility(bool ? View.GONE : View.VISIBLE);
        }

        /** Visibility of popup UI belongs to the independent window, not to a child tile.
         * Legacy POPUP/VISIBLE JSON remains valid in the runtime controller, but the visual
         * editor always redirects that combination to an OVERLAY target. */
        private void keepTileVisibilityOutOfPopupScope() {
            LocalField field;
            try {
                field = LocalField.fromJsonName(mappedValue(localField, FIELD_VALUES));
            } catch (RuntimeException ignored) {
                return;
            }
            if (field != LocalField.VISIBLE
                    || !TargetScope.POPUP.jsonName().equals(mappedValue(target, TARGET_VALUES))) {
                return;
            }
            selectSpinnerValue(target, TARGET_VALUES, TargetScope.OVERLAY.jsonName());
            targetId.setText("");
            targetSummary.setText("Выберите плавающий оверлей целиком");
        }

        private void showSourcePicker() {
            int current = 0;
            String selectedConnector = selected(connector);
            for (int index = 0; index < CONNECTORS.length; index++) {
                if (CONNECTORS[index].equals(selectedConnector)) {
                    current = index;
                    break;
                }
            }
            new AlertDialog.Builder(ScenarioSettingsActivity.this)
                    .setTitle("1. Выберите коннектор")
                    .setSingleChoiceItems(CONNECTOR_LABELS, current, (dialog, which) -> {
                        dialog.dismiss();
                        switch (CONNECTORS[which]) {
                            case "HOME_ASSISTANT":
                                showHomeAssistantSourcePicker();
                                break;
                            case "SPRUTHUB":
                                showSprutSourcePicker();
                                break;
                            case "MQTT":
                            default:
                                showMqttSourcePicker();
                                break;
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        }

        private void showHomeAssistantSourcePicker() {
            HaApiController active = HaApiController.active();
            if (active == null || active.catalog().isEmpty()) {
                new AlertDialog.Builder(ScenarioSettingsActivity.this)
                        .setTitle("Каталог Home Assistant пуст")
                        .setMessage("Подключите Home Assistant и дождитесь загрузки полного "
                                + "списка сущностей.")
                        .setPositiveButton("Настроить", (dialog, which) -> startActivity(
                                new Intent(ScenarioSettingsActivity.this,
                                        HomeAssistantSettingsActivity.class)))
                        .setNegativeButton("Назад", (dialog, which) -> showSourcePicker())
                        .show();
                return;
            }
            List<HaEntity> entities = new ArrayList<>(active.catalog().values());
            entities.sort(Comparator.comparing(entity ->
                    (haFriendlyName(entity) + " " + entity.entityId())
                            .toLowerCase(Locale.ROOT)));
            List<SearchChoice<HaEntity>> choices = new ArrayList<>(entities.size());
            for (HaEntity entity : entities) {
                String name = haFriendlyName(entity);
                choices.add(new SearchChoice<>(entity,
                        name + "\n" + entity.entityId() + "  •  state: "
                                + displayCatalogValue(entity.state()),
                        name + " " + entity.entityId() + " " + entity.domain() + " "
                                + entity.state()));
            }
            showSearchPicker("2. Устройство Home Assistant",
                    "Поиск по названию, entity_id или состоянию", choices,
                    "В каталоге Home Assistant нет сущностей.", this::showSourcePicker,
                    null, null, choice -> showHomeAssistantValuePicker(choice.value));
        }

        private void showHomeAssistantValuePicker(HaEntity entity) {
            List<SearchChoice<SourceBinding>> choices = new ArrayList<>();
            SourceBinding state = sourceBinding(ConnectorType.HOME_ASSISTANT,
                    entity.entityId(), "state");
            choices.add(new SearchChoice<>(state,
                    "Состояние (state)\nсейчас: " + displayCatalogValue(entity.state()),
                    "state состояние " + entity.state()));
            List<Map.Entry<String, Object>> attributes =
                    new ArrayList<>(entity.attributes().entrySet());
            attributes.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
            for (Map.Entry<String, Object> attribute : attributes) {
                appendAttributeChoices(choices, ConnectorType.HOME_ASSISTANT,
                        entity.entityId(), attribute.getKey(), attribute.getValue(), 0);
            }
            String name = haFriendlyName(entity);
            showSearchPicker(name + " — значение", "Поиск по атрибутам", choices,
                    "У сущности нет доступных значений.", this::showHomeAssistantSourcePicker,
                    null, null, choice -> applySource(choice.value,
                            "Home Assistant · " + name + "\n" + entity.entityId() + " · "
                                    + choice.value.valuePath));
        }

        private void showSprutSourcePicker() {
            SprutHubController active = SprutHubController.active();
            if (active != null && !active.catalog().accessories().isEmpty()) {
                showSprutAccessoryPicker(active.catalog());
                return;
            }
            AlertDialog loading = new AlertDialog.Builder(ScenarioSettingsActivity.this)
                    .setTitle("Sprut.hub")
                    .setMessage("Загружаем сохранённый каталог…")
                    .setNegativeButton("Отмена", null)
                    .create();
            loading.show();
            CompletableFuture.supplyAsync(
                    ScenarioSettingsActivity.this::loadCachedSprutCatalog)
                    .whenComplete((catalog, failure) -> runOnUiThread(() -> {
                        if (!loading.isShowing() || isFinishing() || isDestroyed()) return;
                        loading.dismiss();
                        if (failure != null || catalog == null
                                || catalog.accessories().isEmpty()) {
                            showMissingSprutCatalog();
                        } else {
                            showSprutAccessoryPicker(catalog);
                        }
                    }));
        }

        private void showMissingSprutCatalog() {
            new AlertDialog.Builder(ScenarioSettingsActivity.this)
                    .setTitle("Каталог Sprut.hub пуст")
                    .setMessage("Подключите Sprut.hub и обновите каталог устройств.")
                    .setPositiveButton("Настроить", (dialog, which) -> startActivity(
                            new Intent(ScenarioSettingsActivity.this,
                                    SprutHubSettingsActivity.class)))
                    .setNegativeButton("Назад", (dialog, which) -> showSourcePicker())
                    .show();
        }

        private void showSprutAccessoryPicker(SprutCatalog catalog) {
            List<SprutCatalog.Accessory> accessories = new ArrayList<>();
            for (SprutCatalog.Accessory accessory : catalog.accessories()) {
                if (hasReadableCharacteristic(accessory)) accessories.add(accessory);
            }
            accessories.sort(Comparator
                    .comparing((SprutCatalog.Accessory value) -> catalog.roomNameFor(value),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ScenarioSettingsActivity::sprutAccessoryName,
                            String.CASE_INSENSITIVE_ORDER));
            List<SearchChoice<SprutCatalog.Accessory>> choices =
                    new ArrayList<>(accessories.size());
            for (SprutCatalog.Accessory accessory : accessories) {
                String room = catalog.roomNameFor(accessory);
                String name = sprutAccessoryName(accessory);
                String label = (room.isEmpty() ? "" : room + " → ") + name
                        + (accessory.online() ? "" : "  [offline]")
                        + (accessory.model().isEmpty() ? "" : "\n" + accessory.model());
                choices.add(new SearchChoice<>(accessory, label,
                        room + " " + name + " " + accessory.model() + " "
                                + accessory.manufacturer() + " " + accessory.serial()));
            }
            showSearchPicker("2. Устройство Sprut.hub",
                    "Поиск по комнате, названию, модели или серийному номеру", choices,
                    "В каталоге нет устройств с читаемыми характеристиками.",
                    this::showSourcePicker, null, null,
                    choice -> showSprutServicePicker(catalog, choice.value));
        }

        private void showSprutServicePicker(SprutCatalog catalog,
                                            SprutCatalog.Accessory accessory) {
            List<SprutCatalog.Service> services = new ArrayList<>();
            for (SprutCatalog.Service service : accessory.services()) {
                if (hasReadableCharacteristic(service)) services.add(service);
            }
            services.sort(Comparator.comparing(ScenarioSettingsActivity::sprutServiceName,
                    String.CASE_INSENSITIVE_ORDER));
            List<SearchChoice<SprutCatalog.Service>> choices = new ArrayList<>(services.size());
            for (SprutCatalog.Service service : services) {
                String name = sprutServiceName(service);
                choices.add(new SearchChoice<>(service,
                        name + "\nтип: " + dash(service.type()) + "  •  sId=" + service.id(),
                        name + " " + service.type() + " " + service.id()));
            }
            showSearchPicker(sprutAccessoryName(accessory) + " — сервис",
                    "Поиск по названию или типу сервиса", choices,
                    "У устройства нет читаемых сервисов.",
                    () -> showSprutAccessoryPicker(catalog), null, null,
                    choice -> showSprutCharacteristicPicker(catalog, accessory, choice.value));
        }

        private void showSprutCharacteristicPicker(SprutCatalog catalog,
                SprutCatalog.Accessory accessory, SprutCatalog.Service service) {
            List<SprutCatalog.Characteristic> characteristics = new ArrayList<>();
            for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
                if (characteristic.readable()) characteristics.add(characteristic);
            }
            characteristics.sort(Comparator.comparing(
                    ScenarioSettingsActivity::sprutCharacteristicName,
                    String.CASE_INSENSITIVE_ORDER));
            List<SearchChoice<SprutCatalog.Characteristic>> choices =
                    new ArrayList<>(characteristics.size());
            for (SprutCatalog.Characteristic characteristic : characteristics) {
                String name = sprutCharacteristicName(characteristic);
                String path = characteristic.path().stableId();
                choices.add(new SearchChoice<>(characteristic,
                        name + "\nсейчас: " + displayCatalogValue(characteristic.currentValue())
                                + "  •  " + path,
                        name + " " + characteristic.type() + " "
                                + characteristic.format() + " " + characteristic.unit()
                                + " " + characteristic.currentValue() + " " + path));
            }
            showSearchPicker(sprutServiceName(service) + " — значение",
                    "Поиск по названию, типу или path", choices,
                    "У сервиса нет читаемых характеристик.",
                    () -> showSprutServicePicker(catalog, accessory), null, null, choice -> {
                        SprutCatalog.Characteristic value = choice.value;
                        String room = catalog.roomNameFor(accessory);
                        String prefix = room.isEmpty() ? "" : room + " → ";
                        SourceBinding binding = sourceBinding(ConnectorType.SPRUTHUB,
                                value.path().stableId(), "");
                        applySource(binding, "Sprut.hub · " + prefix
                                + sprutAccessoryName(accessory) + " → "
                                + sprutServiceName(service) + " → "
                                + sprutCharacteristicName(value) + "\n"
                                + value.path().stableId());
                    });
        }

        private void showMqttSourcePicker() {
            WidgetService running = WidgetService.getInstance();
            List<ConnectorValue> snapshot = running == null
                    ? Collections.emptyList() : running.connectorValueSnapshot();
            LinkedHashMap<String, ConnectorValue> observed = new LinkedHashMap<>();
            for (ConnectorValue value : snapshot) {
                if (value.connectorType == ConnectorType.MQTT
                        && DEFAULT_CONNECTOR_ID.equals(value.connectorId)
                        && isCanonicalMqttResource(value.resourceId)) {
                    observed.put(value.resourceId, value);
                }
            }
            List<ConnectorValue> values = new ArrayList<>(observed.values());
            values.sort(Comparator.comparing(value -> value.resourceId,
                    String.CASE_INSENSITIVE_ORDER));
            List<SearchChoice<ConnectorValue>> choices = new ArrayList<>(values.size());
            for (ConnectorValue value : values) {
                choices.add(new SearchChoice<>(value,
                        value.resourceId + "\nсейчас: "
                                + displayCatalogValue(value.rawValue)
                                + (value.fresh ? "" : "  [stale]"),
                        value.resourceId + " " + value.rawValue + " " + value.valueType
                                + " " + value.unit + " " + value.attributes));
            }
            showSearchPicker("2. Наблюдаемые значения MQTT",
                    "Поиск по scope/id, значению или атрибутам", choices,
                    "Retained/live значения ещё не получены. Можно указать topic вручную.",
                    this::showSourcePicker, "Ввести topic", this::showManualMqttSourcePicker,
                    choice -> showMqttValuePicker(choice.value));
        }

        private void showMqttValuePicker(ConnectorValue value) {
            List<SearchChoice<SourceBinding>> choices = new ArrayList<>();
            SourceBinding primary = sourceBinding(ConnectorType.MQTT, value.resourceId, "");
            choices.add(new SearchChoice<>(primary,
                    "Основное значение\nсейчас: " + displayCatalogValue(value.rawValue),
                    "value state основное " + value.rawValue));
            List<Map.Entry<String, Object>> attributes =
                    new ArrayList<>(value.attributes.entrySet());
            attributes.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
            for (Map.Entry<String, Object> attribute : attributes) {
                appendAttributeChoices(choices, ConnectorType.MQTT, value.resourceId,
                        attribute.getKey(), attribute.getValue(), 0);
            }
            showSearchPicker(value.resourceId + " — значение", "Поиск по атрибутам", choices,
                    "Нет доступных значений.", this::showMqttSourcePicker,
                    "Ввести topic", this::showManualMqttSourcePicker,
                    choice -> applySource(choice.value, "MQTT · " + value.resourceId
                            + (choice.value.valuePath.isEmpty() ? ""
                            : " · " + choice.value.valuePath)));
        }

        private void showManualMqttSourcePicker() {
            LinearLayout form = column();
            form.setPadding(dp(16), dp(4), dp(16), dp(4));
            String currentResource = "MQTT".equals(selected(connector))
                    ? text(resourceId) : "";
            EditText topic = field(form, "Полный MQTT topic или scope/id", currentResource);
            EditText path = field(form, "Путь к атрибуту (необязательно)",
                    "MQTT".equals(selected(connector)) ? text(valuePath) : "");
            AlertDialog dialog = new AlertDialog.Builder(ScenarioSettingsActivity.this)
                    .setTitle("MQTT — ручной источник")
                    .setMessage("Для наблюдаемых сообщений предпочтителен scope/id. Полный topic "
                            + "также поддерживается.")
                    .setView(form)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Применить", null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        try {
                            String selectedTopic = text(topic);
                            if (selectedTopic.isEmpty()) {
                                throw new IllegalArgumentException("Укажите MQTT topic");
                            }
                            SourceBinding binding = sourceBinding(ConnectorType.MQTT,
                                    selectedTopic, text(path));
                            applySource(binding, "MQTT · " + binding.resourceId
                                    + (binding.valuePath.isEmpty() ? ""
                                    : " · " + binding.valuePath));
                            dialog.dismiss();
                        } catch (RuntimeException error) {
                            showValidationError(error);
                        }
                    }));
            dialog.show();
        }

        private void appendAttributeChoices(List<SearchChoice<SourceBinding>> choices,
                ConnectorType type, String resource, String path, Object value, int depth) {
            if (depth < 6 && value instanceof Map<?, ?> && !((Map<?, ?>) value).isEmpty()) {
                List<Map.Entry<?, ?>> nested = new ArrayList<>(((Map<?, ?>) value).entrySet());
                nested.sort(Comparator.comparing(item -> String.valueOf(item.getKey()),
                        String.CASE_INSENSITIVE_ORDER));
                for (Map.Entry<?, ?> item : nested) {
                    appendAttributeChoices(choices, type, resource,
                            path + "." + item.getKey(), item.getValue(), depth + 1);
                }
                return;
            }
            String valuePath = "attributes." + path;
            SourceBinding binding = sourceBinding(type, resource, valuePath);
            choices.add(new SearchChoice<>(binding,
                    "Атрибут: " + path + "\nсейчас: " + displayCatalogValue(value),
                    path + " " + valuePath + " " + value));
        }

        private SourceBinding sourceBinding(ConnectorType type, String resource, String path) {
            return new SourceBinding(type, SourceBinding.DEFAULT_CONNECTOR_ID,
                    resource, path, SourceBinding.PRESENTATION_AUTO, "");
        }

        private void applySource(SourceBinding value, String label) {
            selectSpinnerValue(connector, CONNECTORS, value.connectorType.jsonName());
            connectorId.setText(value.connectorId);
            resourceId.setText(value.resourceId);
            valuePath.setText(value.valuePath);
            sourceSummary.setText(label);
        }

        private void chooseActionValue(boolean falseBranch) {
            LocalField field = LocalField.fromJsonName(mappedValue(localField, FIELD_VALUES));
            EditText destination = falseBranch ? falseStringValue : stringValue;
            Button destinationButton = falseBranch ? chooseFalseStyleValue : chooseStyleValue;
            if (field == LocalField.ICON) {
                String[] labels = PopupIconCatalog.LABELS.toArray(new String[0]);
                new AlertDialog.Builder(ScenarioSettingsActivity.this).setTitle("Иконка")
                        .setItems(labels, (d, which) -> {
                            String value = PopupIconCatalog.IDS.get(which);
                            destination.setText(value);
                            destinationButton.setText("Выбрано: " + labels[which]);
                        }).setNegativeButton("Отмена", null).show();
                return;
            }
            String current = text(destination).isEmpty() ? "#FFFFFFFF" : text(destination);
            AppleColorPickerDialog.show(ScenarioSettingsActivity.this, "Цвет", current,
                    AppleColorPickerDialog.Options.standard(),
                    new AppleColorPickerDialog.Listener() {
                        private void apply(@Nullable String selected) {
                            String value = selected == null ? current : selected;
                            destination.setText(value);
                            destinationButton.setText("Выбрано: " + value);
                        }

                        @Override public void onPreview(@Nullable String selected) {
                            apply(selected);
                        }

                        @Override public void onSelected(@Nullable String selected) {
                            apply(selected);
                        }
                    });
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
                for (PopupOverlayConfig overlay : new PopupOverlayConfigStore(prefs).load()) {
                    result.add(new TargetOption(overlay.id,
                            overlay.name + " — весь оверлей [" + overlay.id + "]"));
                }
                break;
            default:
                break;
        }
        return result;
    }

    private SprutCatalog loadCachedSprutCatalog() {
        JSONObject cached = new SprutHubCatalogStore(this).load();
        JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
        JSONObject accessories = cached == null ? null : cached.optJSONObject("accessories");
        return rooms == null || accessories == null
                ? SprutCatalog.empty() : SprutProtocolAdapter.parseCatalog(rooms, accessories);
    }

    /** Search is performed against the complete lightweight choice list, while the ListView is
     * capped. This avoids creating hundreds of Android Views for large connector catalogs. */
    private <T> void showSearchPicker(String title, String hint,
                                      List<SearchChoice<T>> choices, String emptyMessage,
                                      @Nullable Runnable back,
                                      @Nullable String extraActionLabel,
                                      @Nullable Runnable extraAction,
                                      ChoiceHandler<T> onSelected) {
        LinearLayout content = column();
        content.setPadding(dp(16), dp(4), dp(16), 0);
        EditText search = new EditText(this);
        search.setHint(hint);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        content.addView(search, matchWrap());
        TextView status = label("");
        content.addView(status, topMargin(4));
        ListView results = new ListView(this);
        content.addView(results, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        List<SearchChoice<T>> visible = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int position, @Nullable View convertView,
                                          ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setSingleLine(false);
                view.setMaxLines(4);
                view.setPadding(dp(8), dp(7), dp(8), dp(7));
                return view;
            }
        };
        results.setAdapter(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content);
        if (back == null) {
            builder.setNegativeButton("Отмена", null);
        } else {
            builder.setNegativeButton("Назад", (dialog, which) -> back.run());
        }
        if (extraActionLabel != null && extraAction != null) {
            builder.setNeutralButton(extraActionLabel, (dialog, which) -> extraAction.run());
        }
        AlertDialog dialog = builder.create();
        results.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            SearchChoice<T> selectedChoice = visible.get(position);
            dialog.dismiss();
            onSelected.onSelected(selectedChoice);
        });

        Runnable filter = () -> {
            String query = rawText(search).trim().toLowerCase(Locale.ROOT);
            visible.clear();
            int matches = 0;
            for (SearchChoice<T> choice : choices) {
                if (!query.isEmpty() && !choice.searchText.contains(query)) continue;
                matches++;
                if (visible.size() < MAX_SOURCE_RESULTS) visible.add(choice);
            }
            adapter.setNotifyOnChange(false);
            adapter.clear();
            for (SearchChoice<T> choice : visible) adapter.add(choice.label);
            adapter.notifyDataSetChanged();
            if (choices.isEmpty()) {
                status.setText(emptyMessage);
            } else if (matches == 0) {
                status.setText("Ничего не найдено");
            } else if (matches > visible.size()) {
                status.setText("Показаны первые " + visible.size() + " из " + matches
                        + ". Уточните поиск.");
            } else {
                status.setText("Найдено: " + matches);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count,
                                                    int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before,
                                                int count) {
                filter.run();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        filter.run();
        dialog.show();
    }

    private static boolean hasReadableCharacteristic(SprutCatalog.Accessory accessory) {
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasReadableCharacteristic(service)) return true;
        }
        return false;
    }

    private static boolean hasReadableCharacteristic(SprutCatalog.Service service) {
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (characteristic.readable()) return true;
        }
        return false;
    }

    private static String haFriendlyName(HaEntity entity) {
        Object value = entity.attribute("friendly_name");
        String name = value == null ? "" : String.valueOf(value).trim();
        return name.isEmpty() ? entity.entityId() : name;
    }

    private static String sprutAccessoryName(SprutCatalog.Accessory value) {
        return firstNonBlank(value.name(), value.model(), "Устройство " + value.id());
    }

    private static String sprutServiceName(SprutCatalog.Service value) {
        String name = firstNonBlank(value.name(), value.type(), "Service " + value.id());
        return value.type().isEmpty() || value.type().equals(name)
                ? name : name + " [" + value.type() + "]";
    }

    private static String sprutCharacteristicName(SprutCatalog.Characteristic value) {
        String name = firstNonBlank(value.name(), value.type(), "Characteristic");
        return value.type().isEmpty() || value.type().equals(name)
                ? name : name + " [" + value.type() + "]";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String dash(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value.trim();
    }

    private static String displayCatalogValue(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL) return "—";
        if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
        String text = String.valueOf(value);
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    private static boolean isCanonicalMqttResource(String resource) {
        if (resource == null) return false;
        int slash = resource.indexOf('/');
        if (slash <= 0 || slash != resource.lastIndexOf('/')
                || slash == resource.length() - 1) return false;
        try {
            String scope = resource.substring(0, slash);
            String id = resource.substring(slash + 1);
            return scope.equals(AutomationContract.normalizeScope(scope))
                    && id.equals(AutomationContract.requireSafeId(id));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
            case EQUALS_IGNORE_CASE:
            case NOT_EQUALS_IGNORE_CASE:
            case CONTAINS:
            case CONTAINS_IGNORE_CASE:
            case STARTS_WITH:
            case ENDS_WITH:
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
            case BETWEEN_EXCLUSIVE:
                BigDecimal lower = requireNumber(first, "Первый operand должен быть числом");
                BigDecimal upper = requireNumber(second, "Second operand должен быть числом");
                int order = lower.compareTo(upper);
                if (order > 0 || (operator == Operator.BETWEEN_EXCLUSIVE && order == 0)) {
                    throw new IllegalArgumentException(operator == Operator.BETWEEN_EXCLUSIVE
                            ? "Для строгого диапазона нижняя граница должна быть меньше верхней"
                            : "Нижняя граница больше верхней");
                }
                break;
            default:
                // State/empty/boolean operators do not consume operands.
                break;
        }
    }

    private static BigDecimal requireNumber(String value, String error) {
        try {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty() || (normalized.indexOf(',') >= 0
                    && normalized.indexOf('.') >= 0)) throw new NumberFormatException();
            return new BigDecimal(normalized.replace(',', '.'));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(error);
        }
    }

    private static boolean isEditorSupported(@Nullable Scenario scenario) {
        if (scenario == null || scenario.mode != ConditionMode.ALL
                || scenario.conditions.size() != 1 || scenario.actions.size() > 1
                || scenario.elseActions.size() > 1) return false;
        Condition condition = scenario.conditions.get(0);
        if (!Input.FIELD_VALUE.equals(condition.field)) return false;
        if (!scenario.actions.isEmpty() && !scenario.elseActions.isEmpty()) {
            LocalAction whenTrue = scenario.actions.get(0);
            LocalAction whenFalse = scenario.elseActions.get(0);
            if (whenTrue.targetScope != whenFalse.targetScope
                    || !whenTrue.targetId.equals(whenFalse.targetId)
                    || whenTrue.field != whenFalse.field) return false;
        }
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
            text.append("\nЕсли да: ").append(friendlyField(action.field))
                    .append(" → ").append(action.targetId).append(" = ").append(action.value);
        } else {
            text.append("\nЕсли да: обычное состояние");
        }
        if (!scenario.elseActions.isEmpty()) {
            LocalAction action = scenario.elseActions.get(0);
            text.append("\nЕсли нет: ").append(friendlyField(action.field))
                    .append(" → ").append(action.targetId).append(" = ").append(action.value);
        } else if (scenario.legacyFailClosed) {
            LocalAction implicit = scenario.actions.isEmpty()
                    ? null : legacyImplicitElse(scenario.actions.get(0));
            if (implicit != null) {
                text.append("\nЕсли нет: ").append(friendlyField(implicit.field))
                        .append(" → ").append(implicit.targetId).append(" = false")
                        .append(" (старый режим)");
            } else {
                text.append("\nЕсли нет: обычное состояние (старый режим)");
            }
        } else {
            text.append("\nЕсли нет: обычное состояние");
        }
        return text.toString();
    }

    @Nullable
    private static LocalAction legacyImplicitElse(@Nullable LocalAction action) {
        if (action == null || (action.field != LocalField.VISIBLE
                && action.field != LocalField.ACTION_ENABLED)
                || !Boolean.TRUE.equals(action.booleanValue())) return null;
        return new LocalAction(action.targetScope, action.targetId, action.field, false);
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

    private Spinner simpleSpinner(String[] labels, int selection) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(labels.length - 1, selection)));
        return spinner;
    }

    private static int booleanBranchIndex(@Nullable LocalAction action) {
        if (action == null || action.booleanValue() == null) return 0;
        return action.booleanValue() ? 1 : 2;
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

    private interface ChoiceHandler<T> {
        void onSelected(SearchChoice<T> choice);
    }

    private static final class SearchChoice<T> {
        final T value;
        final String label;
        final String searchText;

        SearchChoice(T value, String label, String searchText) {
            this.value = value;
            this.label = label == null ? "" : label;
            this.searchText = (this.label + " " + (searchText == null ? "" : searchText))
                    .toLowerCase(Locale.ROOT);
        }
    }
}
