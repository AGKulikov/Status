/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.ha.api.HaEntity;
import dezz.status.widget.ha.api.HaEntityCatalog;
import dezz.status.widget.ha.api.HaEntityMapper;
import dezz.status.widget.ha.api.HaWebSocketConnector;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;

/** Catalog-first settings and binding wizard for the direct Home Assistant connector. */
public final class HomeAssistantSettingsActivity extends AppCompatActivity {
    private static final int MAX_RENDERED_ENTITIES = 200;

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            main.postDelayed(this, 1_000L);
        }
    };

    private Preferences prefs;
    private HaBrickConfigStore mainStore;
    private PopupItemConfigStore popupStore;
    private CheckBox enabled;
    private CheckBox keepAwake;
    private EditText baseUrl;
    private EditText token;
    private EditText search;
    private TextView status;
    private LinearLayout catalogContainer;
    private HaEntityCatalog shownCatalog = HaEntityCatalog.empty();
    private String shownConnectionDetail = "";

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Preferences(this);
        mainStore = new HaBrickConfigStore(prefs);
        popupStore = new PopupItemConfigStore(prefs);
        setContentView(buildScreen());
        reloadCatalog();
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(statusTick);
        main.post(statusTick);
        reloadCatalog();
    }

    @Override protected void onPause() {
        main.removeCallbacks(statusTick);
        super.onPause();
    }

    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(48));
        scroll.addView(page, matchWrap());

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Home Assistant — прямое подключение", 24), weighted());
        page.addView(header, matchWrap());

        page.addView(label("Приложение подключается напрямую к Home Assistant WebSocket API, "
                + "получает полный актуальный снимок сущностей и затем слушает изменения. "
                + "Для внешнего адреса используйте HTTPS, VPN или доверенный reverse proxy."),
                topMargin(8));

        enabled = check("Включить прямой коннектор Home Assistant", prefs.haApiEnabled.get());
        keepAwake = check("Поддерживать соединение при погасшем экране",
                prefs.haKeepAwake.get());
        page.addView(enabled, topMargin(12));
        baseUrl = field(page, "Адрес HA, например http://homeassistant.local:8123",
                prefs.haBaseUrl.get());
        token = field(page, "Новый Long-Lived Access Token (пусто = сохранить текущий)", "");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(keepAwake);

        status = label("");
        page.addView(status, topMargin(8));
        LinearLayout connectionActions = row();
        Button save = new Button(this);
        save.setText("Сохранить и подключиться");
        save.setOnClickListener(view -> saveConnection());
        connectionActions.addView(save, weighted());
        Button refresh = new Button(this);
        refresh.setText("Обновить сущности");
        refresh.setOnClickListener(view -> refreshCatalog());
        connectionActions.addView(refresh, weighted());
        page.addView(connectionActions, topMargin(8));

        page.addView(heading("Сущности и значения", 22), topMargin(24));
        page.addView(label("Для каждого элемента выберите точный источник: основное состояние "
                + "state либо конкретный атрибут. Цвета и подписи задаются локальными правилами "
                + "виджета и не зависят от соединения."));
        search = field(page, "Поиск по entity_id, названию, состоянию или атрибуту", "");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count,
                                                    int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before,
                                                int count) {
                renderCatalog();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        catalogContainer = column();
        page.addView(catalogContainer, matchWrap());
        return scroll;
    }

    private void saveConnection() {
        try {
            String endpoint = text(baseUrl);
            String replacementToken = text(token);
            String preservedToken = prefs.haAccessToken.get();
            if (enabled.isChecked()) {
                HaWebSocketConnector.Config.deriveWebSocketUrl(endpoint);
                if (replacementToken.isEmpty() && preservedToken.isEmpty()) {
                    throw new IllegalArgumentException("Укажите Long-Lived Access Token");
                }
            }
            prefs.haApiEnabled.set(enabled.isChecked());
            prefs.haBaseUrl.set(endpoint);
            if (!replacementToken.isEmpty()) prefs.haAccessToken.set(replacementToken);
            prefs.haKeepAwake.set(keepAwake.isChecked());
            token.setText("");
            if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
            Toast.makeText(this, "Настройки Home Assistant сохранены", Toast.LENGTH_SHORT).show();
            main.postDelayed(this::reloadCatalog, 1_500L);
        } catch (Exception error) {
            Toast.makeText(this, "Home Assistant: " + safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        String detail = HaApiController.connectionDetail();
        status.setText("Состояние: " + detail);
        boolean newSnapshot = !detail.equals(shownConnectionDetail)
                && detail.contains(" entities synchronized");
        shownConnectionDetail = detail;
        HaApiController current = HaApiController.active();
        if (current != null) {
            // State events may arrive several times per second. Keep the latest immutable
            // snapshot available to search without hashing the whole catalog or rebuilding Views.
            shownCatalog = current.catalog();
            if (newSnapshot) renderCatalog();
        }
    }

    private void refreshCatalog() {
        HaApiController current = HaApiController.active();
        if (current == null) {
            Toast.makeText(this, "Коннектор ещё не запущен. Сохраните настройки и дождитесь "
                    + "подключения.", Toast.LENGTH_LONG).show();
            return;
        }
        current.refreshCatalog().whenComplete((catalog, failure) -> main.post(() -> {
            if (failure != null) {
                Toast.makeText(this, "Не удалось обновить: " + safeMessage(rootCause(failure)),
                        Toast.LENGTH_LONG).show();
            } else {
                shownCatalog = catalog;
                shownConnectionDetail = HaApiController.connectionDetail();
                renderCatalog();
                Toast.makeText(this, "Список сущностей обновлён", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void reloadCatalog() {
        HaApiController current = HaApiController.active();
        if (current != null) shownCatalog = current.catalog();
        shownConnectionDetail = HaApiController.connectionDetail();
        renderCatalog();
    }

    private void renderCatalog() {
        if (catalogContainer == null) return;
        catalogContainer.removeAllViews();
        String query = search == null ? "" : text(search).toLowerCase(Locale.ROOT);
        if (shownCatalog.isEmpty()) {
            catalogContainer.addView(label("Каталог пока пуст. Сохраните подключение, дождитесь "
                    + "ONLINE и нажмите «Обновить сущности»."), topMargin(12));
            return;
        }

        List<HaEntity> entities = new ArrayList<>(shownCatalog.values());
        entities.sort(Comparator.comparing(entity ->
                (friendlyName(entity) + " " + entity.entityId()).toLowerCase(Locale.ROOT)));
        int matches = 0;
        int rendered = 0;
        for (HaEntity entity : entities) {
            String searchable = (entity.entityId() + " " + friendlyName(entity) + " "
                    + entity.state() + " " + entity.attributes()).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !searchable.contains(query)) continue;
            matches++;
            if (rendered >= MAX_RENDERED_ENTITIES) continue;
            catalogContainer.addView(entityCard(entity, valueChoices(entity)), cardParams());
            rendered++;
        }
        if (matches == 0) {
            catalogContainer.addView(label("Ничего не найдено"), topMargin(12));
        } else if (matches > rendered) {
            catalogContainer.addView(label("Показаны первые " + rendered + " из " + matches
                    + " совпадений. Уточните запрос в поиске, чтобы увидеть нужную сущность."),
                    topMargin(12));
        }
    }

    private LinearLayout entityCard(HaEntity entity, List<ValueChoice> choices) {
        LinearLayout card = card();
        card.addView(heading(friendlyName(entity), 18));
        card.addView(label(entity.entityId() + " · state: " + entity.state()
                + (entity.lastUpdated().isEmpty() ? "" : "\nОбновлено: " + entity.lastUpdated())));

        for (ValueChoice choice : choices) {
            LinearLayout valueCard = column();
            valueCard.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(0x227F7F7F);
            background.setCornerRadius(dp(8));
            valueCard.setBackground(background);

            TextView value = new TextView(this);
            value.setText(choice.label + "\n" + choice.path + " = " + displayValue(choice.value));
            value.setTextSize(14);
            valueCard.addView(value, matchWrap());

            LinearLayout actions = row();
            Button addMain = smallButton("В основную строку");
            addMain.setOnClickListener(view -> addMain(entity, choice));
            actions.addView(addMain, weighted());
            Button addPopup = smallButton("Во всплывающий");
            addPopup.setOnClickListener(view -> addPopup(entity, choice));
            actions.addView(addPopup, weighted());
            valueCard.addView(actions, topMargin(4));
            card.addView(valueCard, topMargin(7));
        }
        return card;
    }

    private List<ValueChoice> valueChoices(HaEntity entity) {
        List<ValueChoice> result = new ArrayList<>();
        result.add(new ValueChoice("Состояние", "state", entity.state()));
        List<Map.Entry<String, Object>> attributes =
                new ArrayList<>(entity.attributes().entrySet());
        attributes.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, Object> attribute : attributes) {
            flattenAttribute(result, attribute.getKey(), attribute.getValue(), 0);
        }
        return result;
    }

    private void flattenAttribute(List<ValueChoice> out, String path, Object value, int depth) {
        if (depth < 6 && value instanceof Map<?, ?> && !((Map<?, ?>) value).isEmpty()) {
            List<Map.Entry<?, ?>> nested = new ArrayList<>(((Map<?, ?>) value).entrySet());
            nested.sort(Comparator.comparing(item -> String.valueOf(item.getKey()),
                    String.CASE_INSENSITIVE_ORDER));
            for (Map.Entry<?, ?> item : nested) {
                flattenAttribute(out, path + "." + item.getKey(), item.getValue(), depth + 1);
            }
            return;
        }
        out.add(new ValueChoice("Атрибут: " + path, "attributes." + path, value));
    }

    private void addMain(HaEntity entity, ValueChoice choice) {
        try {
            List<HaBrickConfig> items = new ArrayList<>(mainStore.loadMain());
            String id = uniqueMainId(bindingId(entity, choice), items);
            HaBrickConfig config = HaBrickConfig.create(id, items.size());
            config.name = friendlyName(entity) + " · " + choice.shortLabel();
            Preset preset = presetFor(entity, choice);
            config.sourceBinding = sourceBinding(entity, choice, preset.presentation);
            config.displayRules = preset.rules;
            config.defaultText = defaultText(entity, choice);
            items.add(config);
            mainStore.saveMain(items);
            ensureMainAutomationBlock();
            applyLiveSettings();
            Toast.makeText(this, "Добавлено в основную строку: " + config.name,
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось добавить: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addPopup(HaEntity entity, ValueChoice choice) {
        try {
            List<PopupItemConfig> items = new ArrayList<>(popupStore.load());
            String id = uniquePopupId(bindingId(entity, choice), items);
            PopupItemConfig config = PopupItemConfig.create(id, items.size());
            config.automationId = id;
            config.name = friendlyName(entity) + " · " + choice.shortLabel();
            config.title = config.name;
            Preset preset = presetFor(entity, choice);
            config.sourceBinding = sourceBinding(entity, choice, preset.presentation);
            config.displayRules = preset.rules;
            config.defaultText = defaultText(entity, choice);
            config.icon = iconFor(entity, preset.presentation);
            if ("state".equals(choice.path) && isActionable(entity.domain())) {
                config.actionBinding = new ActionBinding(ConnectorType.HOME_ASSISTANT,
                        SourceBinding.DEFAULT_CONNECTOR_ID, entity.entityId(),
                        ActionBinding.OPERATION_TOGGLE, "{}");
                config.actionId = id + "_action";
            }
            items.add(config);
            popupStore.save(items);
            applyLiveSettings();
            Toast.makeText(this, "Добавлена всплывающая плитка: " + config.name,
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось добавить: " + safeMessage(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private SourceBinding sourceBinding(HaEntity entity, ValueChoice choice,
                                        String presentation) {
        return new SourceBinding(ConnectorType.HOME_ASSISTANT,
                SourceBinding.DEFAULT_CONNECTOR_ID, entity.entityId(), choice.path,
                presentation, "");
    }

    private Preset presetFor(HaEntity entity, ValueChoice choice) {
        String domain = entity.domain().toLowerCase(Locale.ROOT);
        String deviceClass = String.valueOf(entity.attribute("device_class"))
                .toLowerCase(Locale.ROOT);
        String path = choice.path.toLowerCase(Locale.ROOT);
        String state = String.valueOf(choice.value).toLowerCase(Locale.ROOT);
        boolean statePath = "state".equals(path);
        if ("cover".equals(domain) && statePath) {
            return new Preset(SourceBinding.PRESENTATION_COVER, ScenarioPresets.cover());
        }
        if ((statePath && ("temperature".equals(deviceClass)
                || unit(entity).contains("°c") || unit(entity).contains("°f")))
                || path.contains("temperature")) {
            return new Preset(SourceBinding.PRESENTATION_TEMPERATURE,
                    ScenarioPresets.temperature());
        }
        if (choice.value instanceof Boolean || (statePath
                && ("binary_sensor".equals(domain) || "input_boolean".equals(domain)
                || "switch".equals(domain) || "light".equals(domain)
                || "fan".equals(domain) || "on".equals(state) || "off".equals(state)
                || "true".equals(state) || "false".equals(state)))) {
            return new Preset(SourceBinding.PRESENTATION_BOOLEAN,
                    ScenarioPresets.booleanState());
        }
        return new Preset(SourceBinding.PRESENTATION_RAW, ScenarioPresets.raw());
    }

    private static String defaultText(HaEntity entity, ValueChoice choice) {
        if ("state".equals(choice.path)) {
            return HaEntityMapper.suggestedPresentation(entity).text();
        }
        return displayValue(choice.value);
    }

    private String iconFor(HaEntity entity, String presentation) {
        if (SourceBinding.PRESENTATION_COVER.equals(presentation)) return "gate";
        if (SourceBinding.PRESENTATION_TEMPERATURE.equals(presentation)) return "temperature";
        String domain = entity.domain().toLowerCase(Locale.ROOT);
        String deviceClass = String.valueOf(entity.attribute("device_class"))
                .toLowerCase(Locale.ROOT);
        if ("light".equals(domain)) return "light";
        if ("lock".equals(domain)) return "lock";
        if (deviceClass.contains("door") || deviceClass.contains("window")
                || deviceClass.contains("opening")) return "door";
        if (deviceClass.contains("water") || deviceClass.contains("moisture")
                || deviceClass.contains("humidity")) return "water";
        return "power";
    }

    private static boolean isActionable(String domain) {
        switch (domain) {
            case "button":
            case "cover":
            case "fan":
            case "input_boolean":
            case "input_button":
            case "light":
            case "lock":
            case "scene":
            case "script":
            case "switch":
                return true;
            default: return false;
        }
    }

    private void ensureMainAutomationBlock() {
        List<BrickType> order = BrickType.parseOrder(prefs.brickOrder.get());
        if (!order.contains(BrickType.HOME_ASSISTANT)) {
            order.add(BrickType.HOME_ASSISTANT);
            prefs.brickOrder.set(BrickType.serializeOrder(order));
        }
    }

    private void applyLiveSettings() {
        if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
    }

    private static String friendlyName(HaEntity entity) {
        Object value = entity.attribute("friendly_name");
        String name = value == null ? "" : String.valueOf(value).trim();
        return name.isEmpty() ? entity.entityId() : name;
    }

    private static String unit(HaEntity entity) {
        Object value = entity.attribute("unit_of_measurement");
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static String bindingId(HaEntity entity, ValueChoice choice) {
        String raw = "ha_" + entity.entityId() + "_" + choice.path;
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_")
                .replace('.', '_').replaceAll("_+", "_");
        if (safe.length() > 96) safe = safe.substring(0, 96);
        return safe;
    }

    private static String uniqueMainId(String base, List<HaBrickConfig> items) {
        String candidate = base;
        int suffix = 2;
        while (containsMainId(items, candidate)) candidate = withSuffix(base, suffix++);
        return candidate;
    }

    private static boolean containsMainId(List<HaBrickConfig> items, String id) {
        for (HaBrickConfig item : items) if (id.equals(item.id)) return true;
        return false;
    }

    private static String uniquePopupId(String base, List<PopupItemConfig> items) {
        String candidate = base;
        int suffix = 2;
        while (containsPopupId(items, candidate)) candidate = withSuffix(base, suffix++);
        return candidate;
    }

    private static boolean containsPopupId(List<PopupItemConfig> items, String id) {
        for (PopupItemConfig item : items) if (id.equals(item.id)) return true;
        return false;
    }

    private static String withSuffix(String base, int suffix) {
        String value = "_" + suffix;
        int length = Math.min(base.length(), 127 - value.length());
        return base.substring(0, length) + value;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor());
        background.setStroke(dp(1), 0x557F7F7F);
        background.setCornerRadius(dp(16));
        layout.setBackground(background);
        return layout;
    }

    private TextInputEditText field(LinearLayout parent, String hint, String value) {
        TextInputLayout box = new TextInputLayout(this);
        box.setHint(hint);
        TextInputEditText input = new TextInputEditText(this);
        input.setText(value);
        input.setSingleLine(true);
        box.addView(input, matchWrap());
        parent.addView(box, topMargin(8));
        return input;
    }

    private CheckBox check(String value, boolean checked) {
        CheckBox view = new CheckBox(this);
        view.setText(value);
        view.setChecked(checked);
        view.setMinHeight(dp(48));
        return view;
    }

    private Button smallButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(11);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private TextView heading(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView label(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(13);
        view.setAlpha(.75f);
        return view;
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

    private LinearLayout.LayoutParams cardParams() { return topMargin(12); }

    private int surfaceColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface,
                value, true) ? value.data : Color.TRANSPARENT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String displayValue(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "неизвестная ошибка" : error.getClass().getSimpleName())
                : message;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) result = result.getCause();
        return result;
    }

    private static final class ValueChoice {
        final String label;
        final String path;
        final Object value;

        ValueChoice(String label, String path, Object value) {
            this.label = label;
            this.path = path;
            this.value = value;
        }

        String shortLabel() {
            return "state".equals(path) ? "Состояние"
                    : path.substring("attributes.".length());
        }
    }

    private static final class Preset {
        final String presentation;
        final RuleSet rules;

        Preset(String presentation, RuleSet rules) {
            this.presentation = presentation;
            this.rules = rules;
        }
    }
}
