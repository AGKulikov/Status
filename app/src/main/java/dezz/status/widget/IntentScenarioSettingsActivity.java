/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.ClipData;
import android.content.ClipboardManager;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.sprut.SprutActionValue;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutPath;
import dezz.status.widget.sprut.SprutProtocolAdapter;

/** Editor for one-shot, exact Android Intent action to Sprut.hub command mappings. */
public final class IntentScenarioSettingsActivity extends AppCompatActivity {
    private Preferences prefs;
    private IntentActionRuleStore store;
    private SprutCatalog catalog = SprutCatalog.empty();
    private boolean catalogFresh;
    private final List<IntentActionRule> rules = new ArrayList<>();
    private LinearLayout ruleHost;
    private TextView catalogStatus;
    private String loadError;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        store = new IntentActionRuleStore(prefs);
        loadCatalog();
        loadRules();
        View screen = buildScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ruleHost == null) return;
        loadCatalog();
        renderCatalogStatus();
    }

    @NonNull
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
        header.addView(heading("Кнопки руля → Sprut.hub", 23), weighted());
        page.addView(header, matchWrap());

        page.addView(label("Каждое правило сопоставляет Android Intent action с одной командой. "
                + "Введите понятный префикс, например sh.car.parkovka: приложение добавит к нему "
                + "секретный суффикс. Затем скопируйте из карточки полное action и укажите его "
                + "на руле вместе с целевым приложением ru.natro.statuswidget. Extras не нужны "
                + "и не могут изменить сохранённую команду. Полное action работает как секрет: "
                + "не публикуйте его и используйте только доверенные приложения магнитолы. "
                + "Основной виджет должен быть включён, а Sprut.hub — подключён и синхронизирован."),
                topMargin(10));
        TextView coldStart = label("Резервный запуск после выгрузки процесса: action "
                + "ru.natro.statuswidget.SCENARIO_TRIGGER и два строковых extra из карточки: "
                + "trigger_id и trigger_token. Команда всё равно берётся только из этого экрана.");
        coldStart.setTextColor(0xFFFFB74D);
        page.addView(coldStart, topMargin(8));

        LinearLayout catalogRow = row();
        catalogRow.setGravity(Gravity.CENTER_VERTICAL);
        catalogStatus = label("");
        catalogRow.addView(catalogStatus, weighted());
        Button reload = new Button(this);
        reload.setText("Обновить список");
        reload.setOnClickListener(view -> {
            loadCatalog();
            renderCatalogStatus();
            Toast.makeText(this, catalog.accessories().isEmpty()
                    ? "Каталог Sprut.hub пока пуст"
                    : "Список целей обновлён", Toast.LENGTH_SHORT).show();
        });
        catalogRow.addView(reload);
        page.addView(catalogRow, topMargin(12));
        renderCatalogStatus();

        LinearLayout listHeader = row();
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.addView(heading("Правила", 20), weighted());
        Button add = new Button(this);
        add.setText("Добавить");
        add.setEnabled(loadError == null);
        add.setOnClickListener(view -> showEditor(-1));
        listHeader.addView(add);
        page.addView(listHeader, topMargin(18));

        if (loadError != null) {
            TextView error = label("Конфигурация не открыта: " + loadError
                    + ". Исходные настройки не изменены.");
            error.setTextColor(0xFFFF6B6B);
            page.addView(error, topMargin(8));
        }

        ruleHost = column();
        page.addView(ruleHost, matchWrap());
        renderRules();
        return scroll;
    }

    private void loadRules() {
        rules.clear();
        loadError = null;
        try {
            rules.addAll(store.loadStrict());
        } catch (RuntimeException error) {
            loadError = rootMessage(error);
        }
    }

    private void loadCatalog() {
        catalogFresh = false;
        SprutHubController active = SprutHubController.active();
        if (active != null && !active.catalog().accessories().isEmpty()) {
            catalog = active.catalog();
            catalogFresh = SprutHubController.isSynced();
            return;
        }
        catalog = SprutCatalog.empty();
        try {
            JSONObject cached = new SprutHubCatalogStore(this).load();
            JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
            JSONObject accessories = cached == null ? null : cached.optJSONObject("accessories");
            if (rooms != null && accessories != null) {
                catalog = SprutProtocolAdapter.parseCatalog(rooms, accessories);
            }
        } catch (RuntimeException ignored) {
            catalog = SprutCatalog.empty();
        }
    }

    private void renderCatalogStatus() {
        if (catalogStatus == null) return;
        if (catalog.accessories().isEmpty()) {
            catalogStatus.setText("Цели Sprut.hub не загружены.");
            catalogStatus.setTextColor(0xFFFFB74D);
        } else if (catalogFresh) {
            catalogStatus.setText("Актуальный каталог: " + catalog.accessories().size()
                    + " устройств");
            catalogStatus.setTextColor(0xFF66BB6A);
        } else {
            catalogStatus.setText("Показан последний сохранённый каталог: "
                    + catalog.accessories().size() + " устройств");
            catalogStatus.setTextColor(0xFFFFB74D);
        }
    }

    private void renderRules() {
        if (ruleHost == null) return;
        ruleHost.removeAllViews();
        if (rules.isEmpty() && loadError == null) {
            ruleHost.addView(label("Правил пока нет."), topMargin(12));
            return;
        }
        for (int index = 0; index < rules.size(); index++) {
            ruleHost.addView(buildCard(rules.get(index), index), cardParams());
        }
    }

    @NonNull
    private View buildCard(IntentActionRule rule, int index) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor());
        background.setStroke(dp(1), 0x557F7F7F);
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        LinearLayout title = row();
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(heading(rule.intentAction, 18), weighted());
        Switch enabled = new Switch(this);
        enabled.setText("Вкл.");
        enabled.setChecked(rule.enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (checked == rule.enabled) return;
            IntentActionRule replacement = copyWithEnabled(rule, checked);
            List<IntentActionRule> candidate = new ArrayList<>(rules);
            candidate.set(index, replacement);
            if (!persist(candidate, false)) enabled.setChecked(rule.enabled);
        });
        title.addView(enabled);
        card.addView(title, matchWrap());

        String target = firstNonBlank(rule.accessoryLabel, "Устройство") + " → "
                + firstNonBlank(rule.serviceLabel, "Сервис") + " → "
                + firstNonBlank(rule.characteristicLabel, rule.command.resourceId);
        String command = ActionBinding.OPERATION_TOGGLE.equals(rule.command.operation)
                ? "TOGGLE" : "SET = " + payloadDisplay(rule.command.payload);
        card.addView(label("Цель: " + target + "\nКоманда: " + command
                + "\npath: " + rule.command.resourceId + "  •  ID: " + rule.id
                + "\nРезервный trigger_token: " + rule.triggerToken), topMargin(6));

        Button copyAction = new Button(this);
        copyAction.setText("Копировать полное action");
        copyAction.setOnClickListener(view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                Toast.makeText(this, "Буфер обмена недоступен", Toast.LENGTH_SHORT).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Android Intent action",
                    rule.intentAction));
            Toast.makeText(this, "Полное action скопировано", Toast.LENGTH_SHORT).show();
        });
        card.addView(copyAction, topMargin(8));

        LinearLayout actions = row();
        Button edit = new Button(this);
        edit.setText("Изменить");
        edit.setOnClickListener(view -> showEditor(rules.indexOf(rule)));
        actions.addView(edit, weighted());
        Button delete = new Button(this);
        delete.setText("Удалить");
        delete.setOnClickListener(view -> confirmDelete(rule));
        actions.addView(delete, weighted());
        card.addView(actions, topMargin(8));
        return card;
    }

    private void confirmDelete(IntentActionRule rule) {
        List<LauncherShortcutStore.Shortcut> references = referencingHomeShortcuts(rule.id);
        if (!references.isEmpty()) {
            showReferencedRuleDialog(rule, references);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Удалить правило?")
                .setMessage(rule.intentAction)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    List<LauncherShortcutStore.Shortcut> currentReferences =
                            referencingHomeShortcuts(rule.id);
                    if (!currentReferences.isEmpty()) {
                        showReferencedRuleDialog(rule, currentReferences);
                        return;
                    }
                    List<IntentActionRule> candidate = new ArrayList<>(rules);
                    candidate.remove(rule);
                    if (persist(candidate, true)) renderRules();
                })
                .show();
    }

    private void showEditor(int index) {
        if (loadError != null) {
            Toast.makeText(this, "Сначала исправьте конфигурацию", Toast.LENGTH_LONG).show();
            return;
        }
        IntentActionRule source = index >= 0 && index < rules.size() ? rules.get(index) : null;
        if (source != null && source.command.connectorType != ConnectorType.SPRUTHUB) {
            showUnsupportedConnectorDialog(source);
            return;
        }
        EditorViews views = new EditorViews(source);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(views.root, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(source == null ? "Новая команда" : "Изменить команду")
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        IntentActionRule replacement = views.read();
                        List<IntentActionRule> candidate = new ArrayList<>(rules);
                        if (index < 0) {
                            if (candidate.size() >= IntentActionRuleStore.MAX_RULES) {
                                throw new IllegalArgumentException("Допускается не более "
                                        + IntentActionRuleStore.MAX_RULES + " правил");
                            }
                            candidate.add(replacement);
                        } else {
                            candidate.set(index, replacement);
                        }
                        if (!persist(candidate, true)) return;
                        renderRules();
                        dialog.dismiss();
                    } catch (Exception error) {
                        showValidationError(error);
                    }
                }));
        dialog.show();
    }

    private void showUnsupportedConnectorDialog(@NonNull IntentActionRule rule) {
        new AlertDialog.Builder(this)
                .setTitle("Действие создано в кнопках HOME")
                .setMessage("Это правило использует " + connectorLabel(rule.command.connectorType)
                        + ". Текущий редактор поддерживает только Sprut.hub, поэтому правило "
                        + "оставлено без изменений.")
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Открыть кнопки HOME", (dialog, which) ->
                        openHomeShortcutSettings())
                .show();
    }

    private void showReferencedRuleDialog(@NonNull IntentActionRule rule,
                                          @NonNull List<LauncherShortcutStore.Shortcut> references) {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(3, references.size());
        for (int index = 0; index < shown; index++) {
            if (names.length() > 0) names.append(", ");
            names.append('«').append(references.get(index).title).append('»');
        }
        if (references.size() > shown) names.append(" и ещё ").append(references.size() - shown);
        new AlertDialog.Builder(this)
                .setTitle("Правило используется на HOME")
                .setMessage("Сначала измените или удалите "
                        + (references.size() == 1 ? "кнопку " : "кнопки ")
                        + names + ". Правило «" + rule.id
                        + "» не удалено, чтобы кнопки HOME не перестали работать.")
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Открыть кнопки HOME", (dialog, which) ->
                        openHomeShortcutSettings())
                .show();
    }

    @NonNull
    private List<LauncherShortcutStore.Shortcut> referencingHomeShortcuts(
            @NonNull String ruleId) {
        List<LauncherShortcutStore.Shortcut> references = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut shortcut : new LauncherShortcutStore(prefs).all()) {
            boolean primary = shortcut.kind == LauncherShortcutStore.Kind.RULE
                    && ruleId.equals(shortcut.target);
            boolean longPress = shortcut.hasLongAction
                    && shortcut.longKind == LauncherShortcutStore.Kind.RULE
                    && ruleId.equals(shortcut.longTarget);
            if (primary || longPress) references.add(shortcut);
        }
        return references;
    }

    private void openHomeShortcutSettings() {
        startActivity(new Intent(this, LauncherShortcutSettingsActivity.class));
    }

    @NonNull
    private static String connectorLabel(@NonNull ConnectorType connectorType) {
        switch (connectorType) {
            case HOME_ASSISTANT:
                return "Home Assistant";
            case MQTT:
                return "MQTT";
            case SPRUTHUB:
                return "Sprut.hub";
            case PHONE:
                return "Телефон";
            default:
                return connectorType.jsonName();
        }
    }

    private boolean persist(List<IntentActionRule> candidate, boolean successToast) {
        try {
            store.save(candidate);
            rules.clear();
            rules.addAll(candidate);
            notifyRuntimeConfigurationChanged();
            if (successToast) {
                Toast.makeText(this, "Команды сохранены", Toast.LENGTH_SHORT).show();
            }
            renderRules();
            return true;
        } catch (RuntimeException error) {
            showValidationError(error);
            return false;
        }
    }

    private void notifyRuntimeConfigurationChanged() {
        WidgetService running = WidgetService.getInstance();
        if (running != null) {
            running.applyPreferences();
        } else if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
            startForegroundService(new Intent(this, WidgetService.class));
        }
    }

    private final class EditorViews {
        final LinearLayout root = column();
        final Switch enabled;
        final EditText id;
        final EditText intentAction;
        final Button targetButton;
        final TextView targetDetails;
        final Spinner operation;
        final TextView valueTitle;
        final Spinner presetValue;
        final EditText manualValue;
        final TextView valueHelp;
        final IntentActionRule source;
        final String triggerToken;
        final String actionToken;
        final List<ValueOption> valueOptions = new ArrayList<>();
        @Nullable SprutCatalog.Accessory accessory;
        @Nullable SprutCatalog.Service service;
        @Nullable SprutCatalog.Characteristic characteristic;
        String desiredOperation;

        EditorViews(@Nullable IntentActionRule source) {
            this.source = source;
            triggerToken = source == null
                    ? IntentActionRule.newTriggerToken() : source.triggerToken;
            actionToken = source == null ? IntentActionRule.newTriggerToken()
                    : actionTokenFrom(source.intentAction);
            root.setPadding(dp(12), dp(6), dp(12), dp(20));
            enabled = switchView("Правило включено", source == null || source.enabled);
            root.addView(enabled, matchWrap());
            id = field(root, "Уникальный ID правила",
                    source == null ? nextRuleId() : source.id);
            if (source != null) {
                // HOME shortcuts address a rule by this stable id. Renaming would be equivalent
                // to deleting a referenced rule while leaving a dead button behind.
                id.setEnabled(false);
                id.setAlpha(.7f);
            }
            intentAction = field(root, "Понятный префикс Android Intent action",
                    source == null ? "sh.car.parkovka"
                            : IntentActionRule.intentActionPrefix(source.intentAction));
            root.addView(label("При сохранении приложение добавит обязательный случайный "
                    + "суффикс. На руле нужно указать полное action из карточки правила."),
                    topMargin(3));

            root.addView(section("Цель Sprut.hub"), topMargin(16));
            targetButton = new Button(IntentScenarioSettingsActivity.this);
            targetButton.setText("Выбрать устройство и сервис");
            targetButton.setOnClickListener(view -> chooseAccessory(this));
            root.addView(targetButton, topMargin(6));
            targetDetails = label("Цель не выбрана");
            root.addView(targetDetails, topMargin(6));

            root.addView(section("Действие"), topMargin(16));
            operation = spinner(root, "Команда", new String[]{"SET"}, "SET");
            valueTitle = label("Значение");
            root.addView(valueTitle, topMargin(8));
            presetValue = new Spinner(IntentScenarioSettingsActivity.this);
            root.addView(presetValue, matchWrap());
            manualValue = new EditText(IntentScenarioSettingsActivity.this);
            manualValue.setSingleLine(true);
            manualValue.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            root.addView(manualValue, matchWrap());
            valueHelp = label("");
            root.addView(valueHelp, topMargin(4));

            desiredOperation = source == null
                    ? ActionBinding.OPERATION_SET : source.command.operation;
            operation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long rowId) {
                    updateValueControls();
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {
                    updateValueControls();
                }
            });

            if (source != null) restoreTarget(source);
            updateOperationChoices();
        }

        IntentActionRule read() {
            SprutCatalog.Characteristic selectedCharacteristic = characteristic;
            if (accessory == null || service == null || selectedCharacteristic == null) {
                throw new IllegalArgumentException("Выберите записываемую характеристику Sprut.hub");
            }
            if (!selectedCharacteristic.writable()) {
                throw new IllegalArgumentException("Характеристика больше не доступна для записи");
            }
            String selectedOperation = selected(operation);
            String payload;
            if (ActionBinding.OPERATION_TOGGLE.equals(selectedOperation)) {
                if (!SprutActionValue.supportsToggle(selectedCharacteristic)) {
                    throw new IllegalArgumentException("Для этой цели TOGGLE неоднозначен");
                }
                payload = "{}";
            } else if (!valueOptions.isEmpty()) {
                int selectedIndex = presetValue.getSelectedItemPosition();
                if (selectedIndex < 0 || selectedIndex >= valueOptions.size()) {
                    throw new IllegalArgumentException("Выберите значение");
                }
                payload = valueOptions.get(selectedIndex).payload;
            } else {
                payload = manualPayload(rawText(manualValue), selectedCharacteristic);
            }
            ActionBinding binding = new ActionBinding(ConnectorType.SPRUTHUB,
                    ActionBinding.DEFAULT_CONNECTOR_ID,
                    selectedCharacteristic.path().stableId(), selectedOperation, payload);
            // The same typed conversion and min/max/step/validValues checks run again at dispatch.
            SprutActionValue.resolve(binding, selectedCharacteristic);
            String secureAction = IntentActionRule.secureIntentAction(
                    text(intentAction), actionToken);
            return new IntentActionRule(text(id), enabled.isChecked(), secureAction,
                    triggerToken, binding, accessoryDisplay(accessory), serviceDisplay(service),
                    characteristicDisplay(selectedCharacteristic));
        }

        private void restoreTarget(IntentActionRule source) {
            try {
                SprutPath path = SprutPath.parse(source.command.resourceId);
                SprutCatalog.Accessory restoredAccessory = catalog.findAccessory(path.accessoryId());
                SprutCatalog.Service restoredService = catalog.findService(path.accessoryId(),
                        path.serviceId());
                SprutCatalog.Characteristic restoredCharacteristic = catalog.find(path);
                if (restoredAccessory != null && restoredService != null
                        && restoredCharacteristic != null && restoredCharacteristic.writable()) {
                    setTarget(restoredAccessory, restoredService, restoredCharacteristic, true);
                    return;
                }
            } catch (RuntimeException ignored) {
                // Preserve the labels below, but require an authoritative selectable target to save.
            }
            targetDetails.setText("Сохранённая цель не найдена в каталоге:\n"
                    + firstNonBlank(source.accessoryLabel, "Устройство") + " → "
                    + firstNonBlank(source.serviceLabel, "Сервис") + " → "
                    + firstNonBlank(source.characteristicLabel, source.command.resourceId)
                    + "\nОбновите каталог и выберите цель заново.");
            targetDetails.setTextColor(0xFFFFB74D);
        }

        private void setTarget(SprutCatalog.Accessory accessory,
                               SprutCatalog.Service service,
                               SprutCatalog.Characteristic characteristic,
                               boolean preserveSourceValue) {
            this.accessory = accessory;
            this.service = service;
            this.characteristic = characteristic;
            targetDetails.setText(accessoryDisplay(accessory) + " → " + serviceDisplay(service)
                    + " → " + characteristicDisplay(characteristic)
                    + "\npath: " + characteristic.path().stableId()
                    + "\nformat=" + emptyDash(characteristic.format())
                    + ", unit=" + emptyDash(characteristic.unit())
                    + ", min=" + valueOrDash(characteristic.minValue())
                    + ", max=" + valueOrDash(characteristic.maxValue())
                    + ", step=" + valueOrDash(characteristic.minStep()));
            targetDetails.setTextColor(defaultTextColor());
            if (!preserveSourceValue) {
                desiredOperation = ActionBinding.OPERATION_SET;
                manualValue.setText("");
            }
            updateOperationChoices();
        }

        private void updateOperationChoices() {
            boolean toggle = characteristic != null && SprutActionValue.supportsToggle(characteristic);
            String[] options = toggle ? new String[]{ActionBinding.OPERATION_SET,
                    ActionBinding.OPERATION_TOGGLE} : new String[]{ActionBinding.OPERATION_SET};
            operation.setAdapter(simpleAdapter(options));
            int position = toggle && ActionBinding.OPERATION_TOGGLE.equals(desiredOperation) ? 1 : 0;
            operation.setSelection(position);
            updateValueControls();
        }

        private void updateValueControls() {
            valueOptions.clear();
            SprutCatalog.Characteristic selectedCharacteristic = characteristic;
            boolean toggle = ActionBinding.OPERATION_TOGGLE.equals(selected(operation));
            if (selectedCharacteristic == null || toggle) {
                valueTitle.setVisibility(View.GONE);
                presetValue.setVisibility(View.GONE);
                manualValue.setVisibility(View.GONE);
                valueHelp.setVisibility(View.VISIBLE);
                valueHelp.setText(selectedCharacteristic == null
                        ? "Сначала выберите цель."
                        : "TOGGLE вычисляет противоположное значение из актуального снимка.");
                return;
            }

            valueTitle.setVisibility(View.VISIBLE);
            List<SprutCatalog.ValidValue> valid = selectedCharacteristic.validValues();
            if (!valid.isEmpty()) {
                for (SprutCatalog.ValidValue item : valid) {
                    try {
                        String name = firstNonBlank(item.name(), item.key(),
                                SprutActionValue.displayValue(item.value()));
                        String display = SprutActionValue.displayValue(item.value());
                        if (!name.equals(display)) name += "  ·  " + display;
                        valueOptions.add(new ValueOption(name,
                                SprutActionValue.encodePrimitive(item.value()), item.value()));
                    } catch (IllegalArgumentException ignored) {
                        // A hub extension may expose a non-primitive label entry. It cannot be
                        // written by the protocol and must not crash the settings screen.
                    }
                }
            } else if (isBoolean(selectedCharacteristic)) {
                valueOptions.add(new ValueOption("1 — включить / true", "1", true));
                valueOptions.add(new ValueOption("0 — выключить / false", "0", false));
            }

            if (!valueOptions.isEmpty()) {
                String[] labels = new String[valueOptions.size()];
                for (int index = 0; index < valueOptions.size(); index++) {
                    labels[index] = valueOptions.get(index).label;
                }
                presetValue.setAdapter(simpleAdapter(labels));
                presetValue.setSelection(initialOptionIndex(selectedCharacteristic));
                presetValue.setVisibility(View.VISIBLE);
                manualValue.setVisibility(View.GONE);
                valueHelp.setVisibility(View.VISIBLE);
                valueHelp.setText("В Sprut.hub уйдёт типизированное значение, а не текст подписи.");
            } else {
                presetValue.setVisibility(View.GONE);
                manualValue.setVisibility(View.VISIBLE);
                if (source != null && source.command.resourceId.equals(
                        selectedCharacteristic.path().stableId())
                        && ActionBinding.OPERATION_SET.equals(source.command.operation)
                        && rawText(manualValue).isEmpty()) {
                    manualValue.setText(payloadDisplay(source.command.payload));
                }
                boolean string = isString(selectedCharacteristic);
                boolean numeric = isNumeric(selectedCharacteristic);
                manualValue.setHint(numeric ? "Например: 22.5" : "Текст или JSON-примитив");
                manualValue.setInputType(numeric
                        ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                valueHelp.setVisibility(View.VISIBLE);
                valueHelp.setText(string
                        ? "Строка будет безопасно JSON-экранирована."
                        : numeric
                        ? "Число проверяется по min, max и step характеристики."
                        : "Тип не описан Sprut.hub: число или true/false будет "
                        + "отправлено как примитив, остальное — как строка.");
            }
        }

        private int initialOptionIndex(SprutCatalog.Characteristic target) {
            if (source == null || !ActionBinding.OPERATION_SET.equals(source.command.operation)
                    || !source.command.resourceId.equals(target.path().stableId())) return 0;
            try {
                Object existing = SprutActionValue.resolve(source.command, target);
                for (int index = 0; index < valueOptions.size(); index++) {
                    ValueOption option = valueOptions.get(index);
                    try {
                        ActionBinding candidate = new ActionBinding(ConnectorType.SPRUTHUB,
                                ActionBinding.DEFAULT_CONNECTOR_ID, target.path().stableId(),
                                ActionBinding.OPERATION_SET, option.payload);
                        Object resolvedOption = SprutActionValue.resolve(candidate, target);
                        if (equivalent(existing, resolvedOption)) return index;
                    } catch (IllegalArgumentException ignored) {
                        // Keep looking: a malformed hub label must not hide another valid value.
                    }
                }
            } catch (RuntimeException ignored) {
                // The save button will surface an actionable validation error if still invalid.
            }
            return 0;
        }
    }

    private void chooseAccessory(EditorViews editor) {
        loadCatalog();
        renderCatalogStatus();
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (SprutCatalog.Accessory accessory : catalog.accessories()) {
            if (hasWritableCharacteristic(accessory)) accessories.add(accessory);
        }
        accessories.sort(Comparator
                .comparing((SprutCatalog.Accessory value) -> catalog.roomNameFor(value),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SprutCatalog.Accessory::name, String.CASE_INSENSITIVE_ORDER));
        if (accessories.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет доступных целей")
                    .setMessage("Подключите Sprut.hub и обновите каталог. "
                            + "В список попадают только записываемые характеристики.")
                    .setPositiveButton("Настроить Sprut.hub", (dialog, which) ->
                            startActivity(new Intent(this, SprutHubSettingsActivity.class)))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        String[] labels = new String[accessories.size()];
        for (int index = 0; index < accessories.size(); index++) {
            SprutCatalog.Accessory item = accessories.get(index);
            String room = catalog.roomNameFor(item);
            labels[index] = (room.isEmpty() ? "" : room + " → ") + accessoryDisplay(item)
                    + (item.online() ? "" : "  [offline]");
        }
        new AlertDialog.Builder(this)
                .setTitle("Устройство")
                .setItems(labels, (dialog, which) -> chooseService(editor, accessories.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void chooseService(EditorViews editor, SprutCatalog.Accessory accessory) {
        List<SprutCatalog.Service> services = new ArrayList<>();
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasWritableCharacteristic(service)) services.add(service);
        }
        services.sort(Comparator.comparing(SprutCatalog.Service::name,
                String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[services.size()];
        for (int index = 0; index < services.size(); index++) {
            SprutCatalog.Service service = services.get(index);
            labels[index] = serviceDisplay(service) + "\nтип: " + emptyDash(service.type())
                    + "  •  sId=" + service.id();
        }
        new AlertDialog.Builder(this)
                .setTitle(accessoryDisplay(accessory) + " — сервис")
                .setItems(labels, (dialog, which) -> chooseCharacteristic(editor, accessory,
                        services.get(which)))
                .setNegativeButton("Назад", (dialog, which) -> chooseAccessory(editor))
                .show();
    }

    private void chooseCharacteristic(EditorViews editor, SprutCatalog.Accessory accessory,
                                      SprutCatalog.Service service) {
        List<SprutCatalog.Characteristic> values = new ArrayList<>();
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (characteristic.writable()) values.add(characteristic);
        }
        String[] labels = new String[values.size()];
        for (int index = 0; index < values.size(); index++) {
            SprutCatalog.Characteristic item = values.get(index);
            labels[index] = characteristicDisplay(item)
                    + "\nformat: " + emptyDash(item.format())
                    + "  •  unit: " + emptyDash(item.unit())
                    + "  •  сейчас: " + valueOrDash(item.currentValue())
                    + "\npath: " + item.path().stableId();
        }
        new AlertDialog.Builder(this)
                .setTitle(serviceDisplay(service) + " — что изменять")
                .setItems(labels, (dialog, which) -> editor.setTarget(accessory, service,
                        values.get(which), false))
                .setNegativeButton("Назад", (dialog, which) ->
                        chooseService(editor, accessory))
                .show();
    }

    private String nextRuleId() {
        int suffix = 1;
        while (true) {
            String candidate = "intent_" + suffix++;
            boolean used = false;
            for (IntentActionRule rule : rules) {
                if (rule.id.equals(candidate)) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
        }
    }

    private static IntentActionRule copyWithEnabled(IntentActionRule rule, boolean enabled) {
        return new IntentActionRule(rule.id, enabled, rule.intentAction, rule.triggerToken,
                rule.command,
                rule.accessoryLabel, rule.serviceLabel, rule.characteristicLabel);
    }

    private static String manualPayload(String raw,
                                        SprutCatalog.Characteristic characteristic) {
        String input = raw == null ? "" : raw;
        String value = input.trim();
        if (input.length() > 8_000) {
            throw new IllegalArgumentException("Значение слишком длинное");
        }
        if (value.isEmpty() && !isString(characteristic)) {
            throw new IllegalArgumentException("Укажите значение");
        }
        if (isString(characteristic)) return SprutActionValue.encodePrimitive(raw == null ? "" : raw);
        String normalized = value.replace(',', '.');
        if (isNumeric(characteristic)) {
            try {
                return decimalPayload(normalized);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Значение должно быть числом", error);
            }
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            return decimalPayload(normalized);
        } catch (NumberFormatException ignored) {
            return SprutActionValue.encodePrimitive(raw == null ? "" : raw);
        }
    }

    private static String decimalPayload(String raw) {
        BigDecimal number = new BigDecimal(raw).stripTrailingZeros();
        if (Math.abs((long) number.scale()) > 8_000L || number.precision() > 8_000) {
            throw new IllegalArgumentException("Число слишком длинное");
        }
        String encoded = number.toPlainString();
        if (encoded.length() > 8_000) {
            throw new IllegalArgumentException("Число слишком длинное");
        }
        return encoded;
    }

    private static boolean hasWritableCharacteristic(SprutCatalog.Accessory accessory) {
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasWritableCharacteristic(service)) return true;
        }
        return false;
    }

    private static boolean hasWritableCharacteristic(SprutCatalog.Service service) {
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (characteristic.writable()) return true;
        }
        return false;
    }

    private static boolean isBoolean(SprutCatalog.Characteristic value) {
        return SprutActionValue.isBooleanLike(value);
    }

    private static boolean isString(SprutCatalog.Characteristic value) {
        if (value.valueType() == SprutCatalog.ValueType.STRING) return true;
        if (value.valueType() != SprutCatalog.ValueType.UNKNOWN) return false;
        if (value.format().toLowerCase(Locale.ROOT).contains("string")) return true;
        return value.currentValue() instanceof String;
    }

    private static boolean isNumeric(SprutCatalog.Characteristic value) {
        switch (value.valueType()) {
            case INTEGER:
            case LONG:
            case FLOAT:
            case DOUBLE:
                return true;
            default:
                if (value.valueType() != SprutCatalog.ValueType.UNKNOWN) return false;
                String format = value.format().toLowerCase(Locale.ROOT);
                if (format.contains("int") || format.contains("float")
                        || format.contains("double") || format.contains("number")) return true;
                return value.currentValue() instanceof Number;
        }
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Boolean || right instanceof Boolean) {
            if (left instanceof Boolean && right instanceof Boolean) return left.equals(right);
            if (left instanceof Boolean && right instanceof Number) {
                return (Boolean) left == (((Number) right).doubleValue() != 0d);
            }
            if (right instanceof Boolean && left instanceof Number) {
                return (Boolean) right == (((Number) left).doubleValue() != 0d);
            }
        }
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(left)).compareTo(
                        new BigDecimal(String.valueOf(right))) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static String payloadDisplay(String payload) {
        String raw = payload == null ? "" : payload.trim();
        try {
            Object decoded = new JSONArray("[" + raw + "]").get(0);
            if (decoded instanceof JSONObject && ((JSONObject) decoded).has("value")) {
                decoded = ((JSONObject) decoded).get("value");
            }
            if (decoded instanceof Boolean) return (Boolean) decoded ? "1" : "0";
            return String.valueOf(decoded);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String actionTokenFrom(String secureAction) {
        String prefix = IntentActionRule.intentActionPrefix(secureAction);
        String suffix = secureAction.substring(prefix.length() + 2);
        if (suffix.length() != 32) throw new IllegalArgumentException("Invalid action token");
        return suffix;
    }

    private static String accessoryDisplay(SprutCatalog.Accessory value) {
        return firstNonBlank(value.name(), value.model(), "Устройство " + value.id());
    }

    private static String serviceDisplay(SprutCatalog.Service value) {
        String name = firstNonBlank(value.name(), value.type(), "Service " + value.id());
        return value.type().isEmpty() || value.type().equals(name)
                ? name : name + " [" + value.type() + "]";
    }

    private static String characteristicDisplay(SprutCatalog.Characteristic value) {
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

    private static String emptyDash(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value.trim();
    }

    private static String valueOrDash(Object value) {
        if (value == null || value == JSONObject.NULL) return "—";
        if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
        return String.valueOf(value);
    }

    private void showValidationError(Throwable error) {
        Toast.makeText(this, "Проверьте настройки: " + rootMessage(error),
                Toast.LENGTH_LONG).show();
    }

    private static String rootMessage(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) result = result.getCause();
        String message = result.getMessage();
        return message == null || message.trim().isEmpty()
                ? result.getClass().getSimpleName() : message;
    }

    private Spinner spinner(LinearLayout parent, String title, String[] values, String selected) {
        parent.addView(label(title), topMargin(8));
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(simpleAdapter(values));
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

    private ArrayAdapter<String> simpleAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private EditText field(LinearLayout parent, String title, String value) {
        parent.addView(label(title), topMargin(8));
        EditText edit = new EditText(this);
        edit.setText(value == null ? "" : value);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(edit, matchWrap());
        return edit;
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

    private int defaultTextColor() {
        TextView probe = new TextView(this);
        return probe.getCurrentTextColor();
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

    private static final class ValueOption {
        final String label;
        final String payload;
        final Object value;

        ValueOption(String label, String payload, Object value) {
            this.label = label;
            this.payload = payload;
            this.value = value;
        }
    }
}
