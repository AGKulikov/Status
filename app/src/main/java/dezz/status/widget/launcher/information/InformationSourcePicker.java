/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import android.text.Editable;
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

import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarTelemetryDescriptor;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.ha.api.HaEntity;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.BoundedCatalogSearch;
import dezz.status.widget.mqtt.MqttShortcutCatalogStore;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutProtocolAdapter;

/** Searchable, catalog-first picker that creates read-only status bindings only. */
public final class InformationSourcePicker {
    public interface Callback {
        void onSelected(@NonNull InformationPanelConfig.Item item);
    }

    private static final int MAX_VISIBLE_RESULTS = 80;
    private final AppCompatActivity activity;
    private final CarIntegration carIntegration;
    private final Callback callback;

    public InformationSourcePicker(@NonNull AppCompatActivity activity,
                                   @NonNull CarIntegration carIntegration,
                                   @NonNull Callback callback) {
        this.activity = activity;
        this.carIntegration = carIntegration;
        this.callback = callback;
    }

    public void show() {
        String[] sources = {
                "Автомобиль и магнитола",
                "Home Assistant",
                "MQTT",
                "Sprut.hub",
                "Телефон"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Источник статуса")
                .setItems(sources, (dialog, which) -> {
                    if (which == 0) showInternal();
                    else if (which == 1) showHomeAssistant();
                    else if (which == 2) showMqtt();
                    else if (which == 3) showSprut();
                    else showPhone();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showInternal() {
        AlertDialog progress = progress("Внутренние данные", "Получаю список датчиков…");
        carIntegration.requestTelemetryCatalog(catalog -> activity.runOnUiThread(() -> {
            if (!canUse(progress)) return;
            progress.dismiss();
            List<Choice> choices = systemChoices();
            for (CarTelemetryDescriptor descriptor : catalog) {
                InformationPanelConfig.Item item = InformationPanelConfig.Item.vehicle(
                        descriptor.id, descriptor.label, descriptor.unit,
                        descriptor.id + " vehicle " + descriptor.unit);
                choices.add(new Choice(item,
                        descriptor.label + "\nАвтомобиль · " + descriptor.id,
                        descriptor.label + " " + descriptor.id + " " + descriptor.unit));
            }
            choices.sort(Comparator.comparing(value -> value.label,
                    String.CASE_INSENSITIVE_ORDER));
            showSearch("Автомобиль и магнитола", "Название или ID датчика", choices,
                    "Доступные внутренние датчики не найдены.");
        }));
    }

    @NonNull
    private static List<Choice> systemChoices() {
        List<Choice> result = new ArrayList<>();
        result.add(system("system.time", "Время", "", "clock time"));
        result.add(system("system.date", "Дата", "", "calendar date"));
        result.add(system("system.battery.level", "Заряд магнитолы", "%",
                "battery level"));
        result.add(system("system.battery.charging", "Питание магнитолы", "",
                "battery charging"));
        result.add(system("system.network", "Сетевое подключение", "",
                "network wifi"));
        result.add(system("system.storage.free", "Свободная память", "ГБ",
                "storage disk"));
        return result;
    }

    @NonNull
    private static Choice system(@NonNull String id, @NonNull String label,
                                 @NonNull String unit, @NonNull String hint) {
        return new Choice(InformationPanelConfig.Item.system(id, label, unit, hint),
                label + "\nМагнитола · " + id, label + " " + id + " " + hint);
    }

    private void showHomeAssistant() {
        HaApiController active = HaApiController.active();
        if (active == null) {
            toast("Home Assistant не подключён или каталог ещё не загружен");
            return;
        }
        if (active.catalog().isEmpty()) {
            AlertDialog progress = progress("Home Assistant",
                    "Загружаю полный список сущностей…");
            active.refreshCatalog().whenComplete((catalog, failure) ->
                    activity.runOnUiThread(() -> {
                        if (!canUse(progress)) return;
                        progress.dismiss();
                        if (failure != null || catalog == null || catalog.isEmpty()) {
                            toast("Каталог Home Assistant пуст");
                        } else {
                            showHomeAssistant();
                        }
                    }));
            return;
        }
        List<Choice> choices = new ArrayList<>();
        for (HaEntity entity : active.catalog().values()) {
            String label = first(string(entity.attribute("friendly_name")), entity.entityId());
            String unit = string(entity.attribute("unit_of_measurement"));
            String hint = entity.domain() + " " + string(entity.attribute("device_class"));
            SourceBinding binding = new SourceBinding(ConnectorType.HOME_ASSISTANT,
                    SourceBinding.DEFAULT_CONNECTOR_ID, entity.entityId(), "",
                    suggestedPresentation(entity.domain(), entity.state()), unit);
            InformationPanelConfig.Item item = InformationPanelConfig.Item.connector(
                    binding, label, unit, hint);
            choices.add(new Choice(item,
                    label + "\n" + entity.entityId() + " · сейчас: " + display(entity.state()),
                    label + " " + entity.entityId() + " " + hint + " "
                            + entity.state() + " " + entity.attributes()));
        }
        choices.sort(Comparator.comparing(value -> value.label,
                String.CASE_INSENSITIVE_ORDER));
        showSearch("Home Assistant", "Название, entity_id, тип или состояние", choices,
                "В полном каталоге нет сущностей.");
    }

    private void showMqtt() {
        AlertDialog progress = progress("MQTT", "Читаю полученный каталог…");
        CompletableFuture.supplyAsync(this::mqttChoices)
                .whenComplete((choices, failure) -> activity.runOnUiThread(() -> {
                    if (!canUse(progress)) return;
                    progress.dismiss();
                    if (failure != null || choices == null) {
                        toast("Не удалось прочитать каталог MQTT");
                    } else {
                        showSearch("MQTT", "Scope, ID, тип или значение", choices,
                                "MQTT ещё не передавал доступных ресурсов.");
                    }
                }));
    }

    @NonNull
    private List<Choice> mqttChoices() {
        Map<String, ConnectorValue> values = new LinkedHashMap<>();
        for (ConnectorValue value : new MqttShortcutCatalogStore(activity,
                new Preferences(activity)).snapshot()) {
            values.put(value.resourceId, value);
        }
        WidgetService service = WidgetService.getInstance();
        if (service != null) {
            for (ConnectorValue value : service.connectorValueSnapshot()) {
                if (value.connectorType == ConnectorType.MQTT) {
                    values.put(value.resourceId, value);
                }
            }
        }
        List<Choice> result = new ArrayList<>();
        for (ConnectorValue value : values.values()) {
            String label = first(string(value.attributes.get("friendly_name")),
                    string(value.attributes.get("name")), value.resourceId);
            String hint = string(value.attributes.get("device_class")) + " "
                    + value.valueType;
            SourceBinding binding = new SourceBinding(ConnectorType.MQTT,
                    value.connectorId, value.resourceId, "", SourceBinding.PRESENTATION_AUTO,
                    value.unit);
            InformationPanelConfig.Item item = InformationPanelConfig.Item.connector(
                    binding, label, value.unit, hint);
            result.add(new Choice(item,
                    label + "\n" + value.resourceId
                            + (value.fresh ? " · сейчас: " + display(value.rawValue)
                            : " · ожидает live-значение"),
                    label + " " + value.resourceId + " " + hint + " " + value.rawValue));
        }
        result.sort(Comparator.comparing(value -> value.label,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void showSprut() {
        SprutHubController active = SprutHubController.active();
        if (active != null && !active.catalog().accessories().isEmpty()) {
            showSprutCatalog(active.catalog());
            return;
        }
        AlertDialog progress = progress("Sprut.hub", "Читаю полный сохранённый каталог…");
        CompletableFuture.supplyAsync(this::loadSprutCatalog)
                .whenComplete((catalog, failure) -> activity.runOnUiThread(() -> {
                    if (!canUse(progress)) return;
                    progress.dismiss();
                    if (failure != null || catalog == null || catalog.accessories().isEmpty()) {
                        toast("Каталог Sprut.hub пуст");
                    } else {
                        showSprutCatalog(catalog);
                    }
                }));
    }

    @NonNull
    private SprutCatalog loadSprutCatalog() {
        JSONObject cached = new SprutHubCatalogStore(activity).load();
        JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
        JSONObject accessories = cached == null ? null : cached.optJSONObject("accessories");
        return rooms == null || accessories == null ? SprutCatalog.empty()
                : SprutProtocolAdapter.parseCatalog(rooms, accessories);
    }

    private void showSprutCatalog(@NonNull SprutCatalog catalog) {
        AlertDialog progress = progress("Sprut.hub",
                "Подготавливаю читаемые характеристики всех устройств…");
        CompletableFuture.supplyAsync(() -> sprutChoices(catalog))
                .whenComplete((choices, failure) -> activity.runOnUiThread(() -> {
                    if (!canUse(progress)) return;
                    progress.dismiss();
                    if (failure != null || choices == null) {
                        toast("Не удалось подготовить каталог Sprut.hub");
                    } else {
                        showSearch("Sprut.hub",
                                "Комната, устройство, сервис или значение", choices,
                                "В каталоге нет читаемых характеристик.");
                    }
                }));
    }

    @NonNull
    private static List<Choice> sprutChoices(@NonNull SprutCatalog catalog) {
        List<Choice> choices = new ArrayList<>();
        for (SprutCatalog.Accessory accessory : catalog.accessories()) {
            String accessoryName = first(accessory.name(), "Устройство " + accessory.id());
            String room = catalog.roomNameFor(accessory);
            for (SprutCatalog.Service service : accessory.services()) {
                String serviceName = first(service.name(), service.type(),
                        "Сервис " + service.id());
                for (SprutCatalog.Characteristic characteristic :
                        service.characteristics()) {
                    if (!characteristic.readable()) continue;
                    String characteristicName = first(characteristic.name(),
                            characteristic.type(), "Характеристика");
                    String label = accessoryName + " · " + characteristicName;
                    String details = (room.isEmpty() ? "" : room + " → ")
                            + accessoryName + " → " + serviceName + " → " + characteristicName;
                    String hint = service.type() + " " + characteristic.type() + " "
                            + characteristic.format();
                    SourceBinding binding = new SourceBinding(ConnectorType.SPRUTHUB,
                            SourceBinding.DEFAULT_CONNECTOR_ID,
                            characteristic.path().stableId(), "",
                            SourceBinding.PRESENTATION_AUTO, characteristic.unit());
                    InformationPanelConfig.Item item = InformationPanelConfig.Item.connector(
                            binding, label, characteristic.unit(), hint);
                    choices.add(new Choice(item,
                            details + "\n" + characteristic.path().stableId()
                                    + " · сейчас: " + display(characteristic.currentValue()),
                            details + " " + characteristic.path().stableId() + " " + hint
                                    + " " + characteristic.currentValue()));
                }
            }
        }
        choices.sort(Comparator.comparing(value -> value.label,
                String.CASE_INSENSITIVE_ORDER));
        return choices;
    }

    private void showPhone() {
        List<Choice> choices = phoneChoices();
        showSearch("Телефон", "Название, ID, тип или текущее значение", choices,
                "Выбранный iPhone ещё не передал доступных данных.");
    }

    @NonNull
    private List<Choice> phoneChoices() {
        WidgetService service = WidgetService.getInstance();
        if (service == null) return Collections.emptyList();
        List<Choice> choices = new ArrayList<>();
        for (ConnectorValue value : service.connectorValueSnapshot()) {
            if (value.connectorType != ConnectorType.PHONE || !value.readable) continue;
            // diagnostics.device intentionally contains the exact Bluetooth address for the
            // local transport. It is useful to the connector itself, but must never become a
            // selectable launcher value whose generic object renderer could expose the full MAC.
            if ("diagnostics.device".equals(value.resourceId)) continue;
            String label = first(string(value.attributes.get("friendly_name")),
                    string(value.attributes.get("name")), phoneLabel(value.resourceId));
            String hint = value.valueType + " " + value.unit + " iphone phone "
                    + value.resourceId;
            SourceBinding binding = new SourceBinding(ConnectorType.PHONE,
                    value.connectorId, value.resourceId, "",
                    SourceBinding.PRESENTATION_AUTO, value.unit);
            InformationPanelConfig.Item item = InformationPanelConfig.Item.connector(
                    binding, label, value.unit, hint);
            choices.add(new Choice(item,
                    label + "\nТелефон · " + value.resourceId
                            + (value.fresh ? " · сейчас: " + display(value.rawValue)
                            : " · ожидает актуальное значение"),
                    label + " " + value.resourceId + " " + hint + " "
                            + value.rawValue + " " + value.attributes));
        }
        choices.sort(Comparator.comparing(value -> value.label,
                String.CASE_INSENSITIVE_ORDER));
        return choices;
    }

    @NonNull
    private static String phoneLabel(@NonNull String resourceId) {
        switch (resourceId) {
            case "connected": return "iPhone подключён";
            case "battery.level": return "Заряд iPhone";
            case "battery.charging": return "Зарядка iPhone";
            case "network.available": return "Сеть iPhone";
            case "network.operator": return "Оператор iPhone";
            case "network.type": return "Тип сети iPhone";
            case "network.signal": return "Сигнал сети iPhone";
            case "network.roaming": return "Роуминг iPhone";
            case "notifications.count": return "Количество уведомлений";
            case "notifications.latest": return "Последнее уведомление";
            case "notifications.items": return "Уведомления iPhone";
            case "messages.unread": return "Непрочитанные сообщения";
            case "messages.latest": return "Последнее сообщение";
            case "diagnostics.ancs": return "Состояние Apple ANCS";
            case "diagnostics.sms": return "Состояние SMS/MAP";
            case "diagnostics.last_error": return "Ошибка подключения iPhone";
            default: return resourceId;
        }
    }

    private void showSearch(@NonNull String title, @NonNull String hint,
                            @NonNull List<Choice> choices, @NonNull String emptyMessage) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), 0);
        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint(hint);
        content.addView(search, new LinearLayout.LayoutParams(match(), wrap()));
        TextView status = new TextView(activity);
        content.addView(status, new LinearLayout.LayoutParams(match(), wrap()));
        ListView list = new ListView(activity);
        content.addView(list, new LinearLayout.LayoutParams(match(), dp(390)));

        List<Choice> visible = new ArrayList<>();
        List<BoundedCatalogSearch.Item<Choice>> searchable =
                new ArrayList<>(choices.size());
        for (Choice choice : choices) {
            searchable.add(new BoundedCatalogSearch.Item<>(choice, choice.label,
                    choice.searchText));
        }
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
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("Назад", (ignored, which) -> show())
                .create();
        Runnable filter = () -> {
            BoundedCatalogSearch.Result<Choice> result = BoundedCatalogSearch.filter(
                    searchable, search.getText().toString(), MAX_VISIBLE_RESULTS);
            visible.clear();
            adapter.setNotifyOnChange(false);
            adapter.clear();
            for (BoundedCatalogSearch.Item<Choice> choice : result.visible) {
                visible.add(choice.value);
                adapter.add(choice.label);
            }
            adapter.notifyDataSetChanged();
            if (choices.isEmpty()) status.setText(emptyMessage);
            else if (result.matches > result.visible.size()) {
                status.setText("Найдено " + result.matches + ", показаны первые "
                        + result.visible.size());
            } else {
                status.setText("Найдено: " + result.matches);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                                    int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before,
                                                int count) { filter.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            InformationPanelConfig.Item selected = visible.get(position).item.copy();
            dialog.dismiss();
            callback.onSelected(selected);
        });
        filter.run();
        dialog.show();
    }

    @NonNull
    private AlertDialog progress(@NonNull String title, @NonNull String message) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        return dialog;
    }

    private boolean canUse(@NonNull AlertDialog dialog) {
        return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void toast(@NonNull String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private static String suggestedPresentation(@NonNull String domain,
                                                @Nullable Object state) {
        if ("cover".equals(domain)) return SourceBinding.PRESENTATION_COVER;
        if ("switch".equals(domain) || "binary_sensor".equals(domain)
                || "input_boolean".equals(domain) || "light".equals(domain)
                || state instanceof Boolean) return SourceBinding.PRESENTATION_BOOLEAN;
        if ("sensor".equals(domain)) return SourceBinding.PRESENTATION_AUTO;
        return SourceBinding.PRESENTATION_RAW;
    }

    @NonNull
    private static String first(@Nullable String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
        }
        return "Статус";
    }

    @NonNull
    private static String string(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @NonNull
    private static String display(@Nullable Object value) {
        String text = value == null ? "—" : String.valueOf(value).trim();
        return text.length() <= 80 ? text : text.substring(0, 77) + "…";
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }

    private static final class Choice {
        @NonNull final InformationPanelConfig.Item item;
        @NonNull final String label;
        @NonNull final String searchText;

        Choice(@NonNull InformationPanelConfig.Item item, @NonNull String label,
               @NonNull String searchText) {
            this.item = item;
            this.label = label;
            this.searchText = searchText.toLowerCase(Locale.ROOT);
        }
    }
}
