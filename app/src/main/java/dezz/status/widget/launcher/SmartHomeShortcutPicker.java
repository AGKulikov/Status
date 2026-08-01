/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Intent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dezz.status.widget.HomeAssistantSettingsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.SprutHubSettingsActivity;
import dezz.status.widget.WidgetService;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.ha.api.HaEntity;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.mqtt.MqttShortcutCatalogStore;
import dezz.status.widget.sprut.SprutActionValue;
import dezz.status.widget.sprut.SprutActionability;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutProtocolAdapter;

/** Catalog-first chooser for a HOME shortcut command. No previously created brick is required. */
public final class SmartHomeShortcutPicker {
    private static final int MAX_VISIBLE_RESULTS = 60;

    public interface Callback { void onSelected(@NonNull Selection selection); }

    public static final class Selection {
        @NonNull public final ActionBinding command;
        @NonNull public final String title;
        @NonNull public final String details;
        @NonNull public final String iconKey;
        @Nullable public final SourceBinding stateBinding;

        Selection(@NonNull ActionBinding command, @NonNull String title,
                  @NonNull String details, @NonNull String iconKey,
                  @Nullable SourceBinding stateBinding) {
            this.command = command;
            this.title = title;
            this.details = details;
            this.iconKey = iconKey;
            this.stateBinding = stateBinding;
        }
    }

    private final AppCompatActivity activity;
    private final Callback callback;

    public SmartHomeShortcutPicker(@NonNull AppCompatActivity activity,
                                   @NonNull Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void showConnectorPicker() {
        String[] values = {"Home Assistant", "MQTT", "Sprut.hub"};
        new AlertDialog.Builder(activity).setTitle("Выберите коннектор")
                .setItems(values, (dialog, which) -> {
                    if (which == 0) showHomeAssistant();
                    else if (which == 1) showMqtt();
                    else showSprut();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void showHomeAssistant() {
        HaApiController active = HaApiController.active();
        if (active == null) {
            missingCatalog("Каталог Home Assistant пуст",
                    "Подключите Home Assistant и дождитесь полного списка сущностей.",
                    HomeAssistantSettingsActivity.class);
            return;
        }
        if (active.catalog().isEmpty()) {
            AlertDialog loading = new AlertDialog.Builder(activity)
                    .setTitle("Home Assistant")
                    .setMessage("Загружаю полный список сущностей…")
                    .setNegativeButton("Отмена", null)
                    .create();
            loading.show();
            try {
                active.refreshCatalog().whenComplete((catalog, failure) ->
                        activity.runOnUiThread(() -> {
                            if (!loading.isShowing() || activity.isFinishing()
                                    || activity.isDestroyed()) return;
                            loading.dismiss();
                            if (failure == null && catalog != null && !catalog.isEmpty()) {
                                showHomeAssistant();
                            } else {
                                missingCatalog("Каталог Home Assistant пуст",
                                        "Подключите Home Assistant и дождитесь полного списка "
                                                + "сущностей.",
                                        HomeAssistantSettingsActivity.class);
                            }
                        }));
            } catch (RuntimeException failure) {
                loading.dismiss();
                missingCatalog("Каталог Home Assistant пуст",
                        "Не удалось обновить полный список сущностей.",
                        HomeAssistantSettingsActivity.class);
            }
            return;
        }
        List<HaEntity> entities = new ArrayList<>(active.catalog().values());
        entities.sort(Comparator.comparing(entity ->
                (haName(entity) + " " + entity.entityId()).toLowerCase(Locale.ROOT)));
        List<BoundedCatalogSearch.Item<HaEntity>> choices = new ArrayList<>(entities.size());
        for (HaEntity entity : entities) {
            String name = haName(entity);
            choices.add(new BoundedCatalogSearch.Item<>(entity,
                    name + "\n" + entity.entityId() + "  •  " + display(entity.state()),
                    name + " " + entity.entityId() + " " + entity.domain() + " "
                            + entity.state() + " " + entity.attributes()));
        }
        showSearch("Home Assistant — устройство",
                "Название, entity_id, тип или состояние", choices,
                "В полном каталоге нет сущностей.", this::showConnectorPicker,
                choice -> chooseHaOperation(choice.value));
    }

    private void chooseHaOperation(HaEntity entity) {
        String domain = entity.domain();
        List<String> labels = new ArrayList<>();
        List<ActionBinding> bindings = new ArrayList<>();
        if ("cover".equals(domain)) {
            add(labels, bindings, "Переключать открыть / закрыть",
                    ha(entity, ActionBinding.OPERATION_TOGGLE, "{}"));
            add(labels, bindings, "Открыть",
                    ha(entity, ActionBinding.OPERATION_SET, "{\"value\":\"open\"}"));
            add(labels, bindings, "Закрыть",
                    ha(entity, ActionBinding.OPERATION_SET, "{\"value\":\"closed\"}"));
        } else if ("lock".equals(domain)) {
            add(labels, bindings, "Переключать замок",
                    ha(entity, ActionBinding.OPERATION_TOGGLE, "{}"));
            add(labels, bindings, "Закрыть замок",
                    ha(entity, ActionBinding.OPERATION_SET, "{\"service\":\"lock\"}"));
            add(labels, bindings, "Открыть замок",
                    ha(entity, ActionBinding.OPERATION_SET, "{\"service\":\"unlock\"}"));
        } else if ("button".equals(domain) || "input_button".equals(domain)
                || "scene".equals(domain) || "script".equals(domain)
                || "automation".equals(domain)) {
            add(labels, bindings, "Выполнить",
                    ha(entity, ActionBinding.OPERATION_TOGGLE, "{}"));
        } else if (supportsStandardHaAction(domain)) {
            add(labels, bindings, "Переключать Вкл / Выкл",
                    ha(entity, ActionBinding.OPERATION_TOGGLE, "{}"));
            add(labels, bindings, "Включить",
                    ha(entity, ActionBinding.OPERATION_SET, "true"));
            add(labels, bindings, "Выключить",
                    ha(entity, ActionBinding.OPERATION_SET, "false"));
        }
        labels.add("Указать сервис и данные вручную…");
        new AlertDialog.Builder(activity).setTitle(haName(entity) + " — действие")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == bindings.size()) {
                        showCustomHaService(entity);
                    } else {
                        finish(bindings.get(which), haName(entity),
                                "Home Assistant · " + entity.entityId(),
                                SmartHomeIconResolver.suggest(domain,
                                        string(entity.attribute("device_class")), "", haName(entity)));
                    }
                }).setNegativeButton("Назад", (dialog, which) -> showHomeAssistant()).show();
    }

    private void showCustomHaService(HaEntity entity) {
        LinearLayout form = form();
        EditText service = field(form, "Сервис, например climate.set_temperature",
                entity.domain() + ".toggle");
        EditText data = field(form, "JSON data без entity_id, например {\"temperature\":22}",
                "{}");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(haName(entity) + " — сервис")
                .setView(form).setPositiveButton("Выбрать", null)
                .setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String selectedService = text(service);
                        if (selectedService.isEmpty() || selectedService.indexOf('.') <= 0) {
                            throw new IllegalArgumentException("Укажите сервис domain.service");
                        }
                        JSONObject details = new JSONObject(text(data).isEmpty() ? "{}" : text(data));
                        JSONObject payload = new JSONObject().put("service", selectedService)
                                .put("data", details);
                        finish(ha(entity, ActionBinding.OPERATION_SET, payload.toString()),
                                haName(entity), "Home Assistant · " + entity.entityId()
                                        + " · " + selectedService,
                                SmartHomeIconResolver.suggest(entity.domain(),
                                        string(entity.attribute("device_class")), "", haName(entity)));
                        dialog.dismiss();
                    } catch (Exception error) {
                        toast(message(error));
                    }
                }));
        dialog.show();
    }

    private void showMqtt() {
        AlertDialog loading = new AlertDialog.Builder(activity).setTitle("MQTT")
                .setMessage("Загружаю каталог реально полученных ресурсов…")
                .setNegativeButton("Отмена", null).create();
        loading.show();
        CompletableFuture.supplyAsync(this::loadMqttValues)
                .whenComplete((values, failure) -> activity.runOnUiThread(() -> {
                    if (!loading.isShowing() || activity.isFinishing()
                            || activity.isDestroyed()) return;
                    loading.dismiss();
                    if (failure != null || values == null) {
                        toast("Не удалось прочитать каталог MQTT");
                        showConnectorPicker();
                    } else {
                        showMqttValues(values);
                    }
                }));
    }

    private List<ConnectorValue> loadMqttValues() {
        Map<String, ConnectorValue> logical = new LinkedHashMap<>();
        for (ConnectorValue value :
                new MqttShortcutCatalogStore(activity, new Preferences(activity)).snapshot()) {
            logical.put(value.resourceId, value);
        }
        WidgetService running = WidgetService.getInstance();
        List<ConnectorValue> snapshot = running == null
                ? Collections.emptyList() : running.connectorValueSnapshot();
        for (ConnectorValue value : snapshot) {
            if (value.connectorType == ConnectorType.MQTT
                    && SourceBinding.DEFAULT_CONNECTOR_ID.equals(value.connectorId)
                    && isLogicalMqttResource(value.resourceId)) {
                logical.put(value.resourceId, value);
            }
        }
        List<ConnectorValue> values = new ArrayList<>(logical.values());
        values.sort(Comparator.comparing(value -> value.resourceId,
                String.CASE_INSENSITIVE_ORDER));
        return values;
    }

    private void showMqttValues(List<ConnectorValue> values) {
        List<BoundedCatalogSearch.Item<ConnectorValue>> choices = new ArrayList<>(values.size());
        for (ConnectorValue value : values) {
            String state = value.readable
                    ? "сейчас: " + display(value.rawValue)
                    + (value.fresh ? "" : "  [неактуально]")
                    : "сохранённый ресурс  [неактуально]";
            choices.add(new BoundedCatalogSearch.Item<>(value,
                    value.resourceId + "\n" + state,
                    value.resourceId + " " + value.rawValue + " " + value.valueType + " "
                            + value.unit + " " + value.attributes));
        }
        showSearch("MQTT — полученный каталог",
                "Scope, ID, значение или атрибут", choices,
                "Retained/live устройства ещё не получены. Можно указать команду вручную.",
                this::showConnectorPicker, choice -> configureMqtt(choice.value),
                "Ввести вручную", () -> configureMqtt(null));
    }

    private void configureMqtt(@Nullable ConnectorValue value) {
        LinearLayout form = form();
        String resource = value == null ? "" : value.resourceId;
        int slash = resource.lastIndexOf('/');
        String suggestedCommand = slash >= 0 ? resource.substring(slash + 1) : resource;
        EditText title = field(form, "Название", value == null ? "MQTT" : resource);
        EditText topic = field(form, "Command topic или ID команды", suggestedCommand);
        EditText payload = field(form, "Payload", "TOGGLE");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("MQTT — действие")
                .setMessage("Список сформирован из всех retained/live значений брокера, а не "
                        + "только из ранее добавленных плиток. Сохранённый scope/ID подтверждает "
                        + "только реально полученное состояние: проверьте предложенный ID команды "
                        + "или укажите точный command topic — приложение его не придумывает.")
                .setView(form).setPositiveButton("Выбрать", null)
                .setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String target = text(topic);
                        if (target.isEmpty()) throw new IllegalArgumentException("Укажите topic");
                        ActionBinding command = new ActionBinding(ConnectorType.MQTT,
                                ActionBinding.DEFAULT_CONNECTOR_ID, target,
                                ActionBinding.OPERATION_PUBLISH, text(payload));
                        String name = text(title).isEmpty() ? target : text(title);
                        String deviceClass = value == null ? "" : string(
                                value.attributes.get("device_class"));
                        String type = value == null ? "" : value.valueType + " " + value.unit;
                        finish(command, name, "MQTT · " + target,
                                SmartHomeIconResolver.suggest("", deviceClass, type, name),
                                value == null ? null : new SourceBinding(ConnectorType.MQTT,
                                        SourceBinding.DEFAULT_CONNECTOR_ID, value.resourceId,
                                        "", SourceBinding.PRESENTATION_AUTO, ""));
                        dialog.dismiss();
                    } catch (RuntimeException error) {
                        toast(message(error));
                    }
                }));
        dialog.show();
    }

    private void showSprut() {
        SprutHubController active = SprutHubController.active();
        if (active != null && !active.catalog().accessories().isEmpty()) {
            showSprutAccessories(active.catalog());
            return;
        }
        AlertDialog loading = new AlertDialog.Builder(activity).setTitle("Sprut.hub")
                .setMessage("Загружаю сохранённый полный каталог…")
                .setNegativeButton("Отмена", null).create();
        loading.show();
        CompletableFuture.supplyAsync(this::loadCachedSprut)
                .whenComplete((catalog, failure) -> activity.runOnUiThread(() -> {
                    if (!loading.isShowing() || activity.isFinishing()
                            || activity.isDestroyed()) return;
                    loading.dismiss();
                    if (failure != null || catalog == null || catalog.accessories().isEmpty()) {
                        missingCatalog("Каталог Sprut.hub пуст",
                                "Подключите Sprut.hub и обновите каталог устройств.",
                                SprutHubSettingsActivity.class);
                    } else showSprutAccessories(catalog);
                }));
    }

    private SprutCatalog loadCachedSprut() {
        JSONObject cached = new SprutHubCatalogStore(activity).load();
        JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
        JSONObject accessories = cached == null ? null : cached.optJSONObject("accessories");
        return rooms == null || accessories == null ? SprutCatalog.empty()
                : SprutProtocolAdapter.parseCatalog(rooms, accessories);
    }

    private void showSprutAccessories(SprutCatalog catalog) {
        List<SprutCatalog.Accessory> accessories = new ArrayList<>(catalog.accessories());
        accessories.sort(Comparator
                .comparing((SprutCatalog.Accessory value) -> catalog.roomNameFor(value),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SmartHomeShortcutPicker::accessoryName,
                        String.CASE_INSENSITIVE_ORDER));
        List<BoundedCatalogSearch.Item<SprutCatalog.Accessory>> choices =
                new ArrayList<>(accessories.size());
        for (SprutCatalog.Accessory accessory : accessories) {
            String room = catalog.roomNameFor(accessory);
            String name = accessoryName(accessory);
            choices.add(new BoundedCatalogSearch.Item<>(accessory,
                    (room.isEmpty() ? "" : room + " → ") + name
                            + (accessory.online() ? "" : "  [offline]")
                            + (SprutActionability.canControl(accessory)
                            ? "" : "  [только чтение]")
                            + (accessory.model().isEmpty() ? "" : "\n" + accessory.model()),
                    room + " " + name + " " + accessory.model() + " "
                            + accessory.manufacturer() + " " + accessory.serial()));
        }
        showSearch("Sprut.hub — устройство",
                "Комната, название, модель или серийный номер", choices,
                "В полном каталоге нет устройств.", this::showConnectorPicker, choice -> {
                    if (SprutActionability.canControl(choice.value)) {
                        showSprutServices(catalog, choice.value);
                    } else {
                        showReadOnlySprut("Устройство «" + accessoryName(choice.value) + "»",
                                () -> showSprutAccessories(catalog));
                    }
                });
    }

    private void showSprutServices(SprutCatalog catalog, SprutCatalog.Accessory accessory) {
        List<SprutCatalog.Service> services = new ArrayList<>(accessory.services());
        services.sort(Comparator.comparing(SmartHomeShortcutPicker::serviceName,
                String.CASE_INSENSITIVE_ORDER));
        List<BoundedCatalogSearch.Item<SprutCatalog.Service>> choices = new ArrayList<>();
        for (SprutCatalog.Service service : services) {
            choices.add(new BoundedCatalogSearch.Item<>(service,
                    serviceName(service)
                            + (SprutActionability.canControl(service)
                            ? "" : "  [только чтение]")
                            + "\nтип: " + dash(service.type()),
                    serviceName(service) + " " + service.type() + " " + service.id()));
        }
        showSearch(accessoryName(accessory) + " — сервис", "Название или тип", choices,
                "У устройства нет сервисов.", () -> showSprutAccessories(catalog), choice -> {
                    if (SprutActionability.canControl(choice.value)) {
                        showSprutCharacteristics(catalog, accessory, choice.value);
                    } else {
                        showReadOnlySprut("Сервис «" + serviceName(choice.value) + "»",
                                () -> showSprutServices(catalog, accessory));
                    }
                });
    }

    private void showSprutCharacteristics(SprutCatalog catalog,
                                          SprutCatalog.Accessory accessory,
                                          SprutCatalog.Service service) {
        List<SprutCatalog.Characteristic> values =
                new ArrayList<>(service.characteristics());
        List<BoundedCatalogSearch.Item<SprutCatalog.Characteristic>> choices = new ArrayList<>();
        for (SprutCatalog.Characteristic value : values) {
            String name = characteristicName(value);
            choices.add(new BoundedCatalogSearch.Item<>(value,
                    name + (SprutActionability.canControl(value)
                            ? "" : "  [только чтение]")
                            + "\nсейчас: " + display(value.currentValue()) + "  •  "
                            + value.path().stableId(),
                    name + " " + value.type() + " " + value.format() + " " + value.unit()
                            + " " + value.currentValue() + " " + value.path().stableId()));
        }
        showSearch(serviceName(service) + " — что менять", "Название, тип или path", choices,
                "У сервиса нет характеристик.",
                () -> showSprutServices(catalog, accessory), choice -> {
                    if (SprutActionability.canControl(choice.value)) {
                        chooseSprutValue(catalog, accessory, service, choice.value);
                    } else {
                        showReadOnlySprut("Характеристика «"
                                        + characteristicName(choice.value) + "»",
                                () -> showSprutCharacteristics(
                                        catalog, accessory, service));
                    }
                });
    }

    private void chooseSprutValue(SprutCatalog catalog, SprutCatalog.Accessory accessory,
                                  SprutCatalog.Service service,
                                  SprutCatalog.Characteristic characteristic) {
        List<String> labels = new ArrayList<>();
        List<ActionBinding> commands = new ArrayList<>();
        if (SprutActionValue.supportsToggle(characteristic)) {
            labels.add("Переключать текущее значение");
            commands.add(sprut(characteristic, ActionBinding.OPERATION_TOGGLE, "{}"));
        }
        for (SprutCatalog.ValidValue value : characteristic.validValues()) {
            try {
                String label = first(value.name(), value.key(),
                        SprutActionValue.displayValue(value.value()));
                labels.add("Установить: " + label);
                commands.add(sprut(characteristic, ActionBinding.OPERATION_SET,
                        SprutActionValue.encodePrimitive(value.value())));
            } catch (IllegalArgumentException ignored) {
                // A non-primitive vendor extension cannot be written by the protocol.
            }
        }
        if (characteristic.validValues().isEmpty()
                && SprutActionValue.isBooleanLike(characteristic)) {
            labels.add("Включить");
            commands.add(sprut(characteristic, ActionBinding.OPERATION_SET, "1"));
            labels.add("Выключить");
            commands.add(sprut(characteristic, ActionBinding.OPERATION_SET, "0"));
        }
        labels.add("Указать значение вручную…");
        new AlertDialog.Builder(activity).setTitle(characteristicName(characteristic))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == commands.size()) {
                        showManualSprutValue(catalog, accessory, service, characteristic);
                    } else finishSprut(catalog, accessory, service, characteristic,
                            commands.get(which));
                }).setNegativeButton("Назад", (dialog, which) ->
                        showSprutCharacteristics(catalog, accessory, service)).show();
    }

    private void showManualSprutValue(SprutCatalog catalog, SprutCatalog.Accessory accessory,
                                      SprutCatalog.Service service,
                                      SprutCatalog.Characteristic characteristic) {
        LinearLayout form = form();
        EditText value = field(form, "JSON-примитив: число, true/false или \"текст\"", "");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(characteristicName(characteristic) + " — значение")
                .setMessage("Значение будет проверено по типу, min/max, шагу и списку "
                        + "допустимых значений Sprut.hub.")
                .setView(form).setPositiveButton("Выбрать", null)
                .setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String payload = text(value);
                        ActionBinding command = sprut(characteristic,
                                ActionBinding.OPERATION_SET, payload);
                        SprutActionValue.resolve(command, characteristic);
                        finishSprut(catalog, accessory, service, characteristic, command);
                        dialog.dismiss();
                    } catch (RuntimeException error) {
                        toast(message(error));
                    }
                }));
        dialog.show();
    }

    private void finishSprut(SprutCatalog catalog, SprutCatalog.Accessory accessory,
                             SprutCatalog.Service service,
                             SprutCatalog.Characteristic characteristic,
                             ActionBinding command) {
        String room = catalog.roomNameFor(accessory);
        String name = accessoryName(accessory);
        String details = "Sprut.hub · " + (room.isEmpty() ? "" : room + " → ") + name
                + " → " + serviceName(service) + " → " + characteristicName(characteristic);
        SourceBinding commandState = new SourceBinding(ConnectorType.SPRUTHUB,
                command.connectorId, command.resourceId, "",
                SourceBinding.PRESENTATION_AUTO, "");
        SourceBinding liveState = SmartHomeShortcutStateBindingPolicy.preferSprutPrimary(
                command, commandState, catalog);
        finish(command, name, details, SmartHomeIconResolver.suggest("", "",
                service.type() + " " + characteristic.type() + " " + characteristic.format(),
                name + " " + service.name() + " " + characteristic.name()), liveState);
    }

    private <T> void showSearch(String title, String hint,
                                List<BoundedCatalogSearch.Item<T>> choices,
                                String emptyMessage, @Nullable Runnable back,
                                SearchSelection<T> selected) {
        showSearch(title, hint, choices, emptyMessage, back, selected, null, null);
    }

    private <T> void showSearch(String title, String hint,
                                List<BoundedCatalogSearch.Item<T>> choices,
                                String emptyMessage, @Nullable Runnable back,
                                SearchSelection<T> selected,
                                @Nullable String extraLabel, @Nullable Runnable extra) {
        LinearLayout content = form();
        EditText search = field(content, hint, "");
        TextView status = new TextView(activity);
        content.addView(status, matchWrap());
        ListView list = new ListView(activity);
        content.addView(list, new LinearLayout.LayoutParams(match(), dp(390)));
        List<BoundedCatalogSearch.Item<T>> visible = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int position, @Nullable View convertView,
                                          @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setSingleLine(false);
                view.setMaxLines(4);
                view.setPadding(dp(8), dp(7), dp(8), dp(7));
                return view;
            }
        };
        list.setAdapter(adapter);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(title)
                .setView(content);
        if (back == null) builder.setNegativeButton("Отмена", null);
        else builder.setNegativeButton("Назад", (dialog, which) -> back.run());
        if (extraLabel != null && extra != null) {
            builder.setNeutralButton(extraLabel, (dialog, which) -> extra.run());
        }
        AlertDialog dialog = builder.create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            BoundedCatalogSearch.Item<T> choice = visible.get(position);
            dialog.dismiss();
            selected.onSelected(choice);
        });
        Runnable filter = () -> {
            BoundedCatalogSearch.Result<T> result = BoundedCatalogSearch.filter(
                    choices, text(search), MAX_VISIBLE_RESULTS);
            visible.clear();
            visible.addAll(result.visible);
            adapter.setNotifyOnChange(false);
            adapter.clear();
            for (BoundedCatalogSearch.Item<T> choice : visible) adapter.add(choice.label);
            adapter.notifyDataSetChanged();
            if (choices.isEmpty()) status.setText(emptyMessage);
            else if (result.matches == 0) status.setText("Ничего не найдено");
            else if (result.matches > visible.size()) status.setText("Показаны первые "
                    + visible.size() + " из " + result.matches + ". Уточните поиск.");
            else status.setText("Найдено: " + result.matches);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                                    int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before,
                                                int count) { filter.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        filter.run();
        dialog.show();
    }

    private void missingCatalog(String title, String message, Class<?> settings) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton("Настроить", (dialog, which) ->
                        activity.startActivity(new Intent(activity, settings)))
                .setNegativeButton("Назад", (dialog, which) -> showConnectorPicker()).show();
    }

    private void showReadOnlySprut(String title, Runnable back) {
        new AlertDialog.Builder(activity).setTitle(title)
                .setMessage("Элемент показан, потому что это полный каталог Sprut.hub, "
                        + "но у него нет характеристик с правом записи. Его можно просматривать, "
                        + "но нельзя выбрать как действие HOME.")
                .setPositiveButton("Понятно", (dialog, which) -> back.run()).show();
    }

    private void finish(ActionBinding command, String title, String details, String icon) {
        callback.onSelected(new Selection(command, title, details, icon,
                new SourceBinding(command.connectorType, command.connectorId,
                        command.resourceId, "", SourceBinding.PRESENTATION_AUTO, "")));
    }

    private void finish(ActionBinding command, String title, String details, String icon,
                        @Nullable SourceBinding stateBinding) {
        callback.onSelected(new Selection(command, title, details, icon, stateBinding));
    }

    private static ActionBinding ha(HaEntity entity, String operation, String payload) {
        return new ActionBinding(ConnectorType.HOME_ASSISTANT,
                ActionBinding.DEFAULT_CONNECTOR_ID, entity.entityId(), operation, payload);
    }

    private static ActionBinding sprut(SprutCatalog.Characteristic value, String operation,
                                       String payload) {
        return new ActionBinding(ConnectorType.SPRUTHUB,
                ActionBinding.DEFAULT_CONNECTOR_ID, value.path().stableId(), operation, payload);
    }

    private static void add(List<String> labels, List<ActionBinding> values, String label,
                            ActionBinding value) {
        labels.add(label);
        values.add(value);
    }

    private static boolean supportsStandardHaAction(String domain) {
        switch (domain) {
            case "alarm_control_panel":
            case "climate":
            case "fan":
            case "humidifier":
            case "input_boolean":
            case "light":
            case "media_player":
            case "remote":
            case "siren":
            case "switch":
            case "vacuum":
            case "water_heater":
                return true;
            default:
                return false;
        }
    }

    private static boolean isLogicalMqttResource(String resource) {
        if (resource == null) return false;
        int slash = resource.indexOf('/');
        if (slash <= 0 || slash != resource.lastIndexOf('/')
                || slash >= resource.length() - 1) return false;
        try {
            String scope = resource.substring(0, slash);
            String id = resource.substring(slash + 1);
            return scope.equals(AutomationContract.normalizeScope(scope))
                    && id.equals(AutomationContract.requireSafeId(id));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String haName(HaEntity entity) {
        return first(string(entity.attribute("friendly_name")), entity.entityId());
    }

    private static String accessoryName(SprutCatalog.Accessory value) {
        return first(value.name(), value.model(), "Устройство " + value.id());
    }

    private static String serviceName(SprutCatalog.Service value) {
        return first(value.name(), value.type(), "Service " + value.id());
    }

    private static String characteristicName(SprutCatalog.Characteristic value) {
        return first(value.name(), value.type(), "Characteristic");
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return "";
    }

    private static String string(@Nullable Object value) {
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static String display(@Nullable Object value) {
        String text = string(value);
        if (text.isEmpty()) return "—";
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    private static String dash(String value) { return first(value, "—"); }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(5), dp(18), dp(8));
        return form;
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parent.addView(input, matchWrap());
        return input;
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private interface SearchSelection<T> {
        void onSelected(BoundedCatalogSearch.Item<T> choice);
    }
}
