/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutPath;
import dezz.status.widget.sprut.SprutProtocolAdapter;
import dezz.status.widget.sprut.SprutValueMapper;
import dezz.status.widget.sprut.preset.SprutPopupPreset;
import dezz.status.widget.sprut.preset.SprutPopupPresetEngine;
import dezz.status.widget.sprut.preset.SprutRuleSetFactory;
import dezz.status.widget.scenario.RuleSet;
import dezz.status.widget.scenario.ScenarioPresets;

/** Connection setup and catalog-first brick wizard for the direct Sprut.hub connector. */
public final class SprutHubSettingsActivity extends AppCompatActivity {
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
    private final SprutPopupPresetEngine presetEngine = new SprutPopupPresetEngine();
    private CheckBox enabled;
    private CheckBox keepAwake;
    private EditText url;
    private EditText email;
    private EditText password;
    private EditText serial;
    private EditText search;
    private TextView status;
    private LinearLayout catalogContainer;
    private SprutCatalog shownCatalog = SprutCatalog.empty();
    private long shownCatalogFingerprint = Long.MIN_VALUE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading("Sprut.hub — прямое подключение", 24), weighted());
        page.addView(header, matchWrap());

        TextView explanation = label("Приложение подключается напрямую к /spruthub, получает "
                + "полный список комнат, устройств и характеристик и после каждого reconnect "
                + "заново загружает актуальный снимок. ws:// используйте только в доверенной "
                + "локальной сети; для внешнего доступа нужен VPN или wss://.");
        page.addView(explanation, topMargin(8));

        enabled = check("Включить коннектор Sprut.hub", prefs.sprutEnabled.get());
        keepAwake = check("Поддерживать соединение при погасшем экране",
                prefs.sprutKeepAwake.get());
        page.addView(enabled, topMargin(12));
        url = field(page, "WebSocket URL, например ws://192.168.1.2/spruthub",
                prefs.sprutWebSocketUrl.get(), false);
        email = field(page, "Email учётной записи Sprut.hub", prefs.sprutEmail.get(), false);
        password = field(page, "Новый пароль (пусто = сохранить текущий)", "", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        serial = field(page, "Серийный номер хаба (пусто = определить автоматически)",
                prefs.sprutHubSerial.get(), false);
        page.addView(keepAwake);

        status = label("");
        page.addView(status, topMargin(8));
        LinearLayout connectionActions = row();
        Button save = new Button(this);
        save.setText("Сохранить и подключиться");
        save.setOnClickListener(v -> saveConnection());
        connectionActions.addView(save, weighted());
        Button refresh = new Button(this);
        refresh.setText("Обновить устройства");
        refresh.setOnClickListener(v -> refreshCatalog());
        connectionActions.addView(refresh, weighted());
        page.addView(connectionActions, topMargin(8));

        page.addView(heading("Устройства и характеристики", 22), topMargin(24));
        page.addView(label("Выберите не только устройство, но и конкретное значение, которое "
                + "нужно показать. Для целого сервиса можно создать готовую типизированную плитку."));
        search = field(page, "Поиск по комнате, устройству или характеристике", "", false);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderCatalog();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        catalogContainer = column();
        page.addView(catalogContainer, matchWrap());
        return scroll;
    }

    private void saveConnection() {
        try {
            String endpoint = text(url);
            String account = text(email);
            String replacementPassword = text(password);
            String preservedPassword = prefs.sprutPassword.get();
            if (enabled.isChecked()) {
                validateWebSocketUrl(endpoint);
                if (account.isEmpty()) {
                    throw new IllegalArgumentException("Укажите email Sprut.hub");
                }
                if (replacementPassword.isEmpty() && preservedPassword.isEmpty()) {
                    throw new IllegalArgumentException("Укажите пароль Sprut.hub");
                }
            }
            // Encrypt a replacement secret before enabling the connector or changing its other
            // settings. A Keystore failure therefore cannot leave a half-enabled connection.
            if (!replacementPassword.isEmpty()) prefs.sprutPassword.set(replacementPassword);
            prefs.sprutEnabled.set(enabled.isChecked());
            prefs.sprutWebSocketUrl.set(endpoint);
            prefs.sprutEmail.set(account);
            prefs.sprutHubSerial.set(text(serial));
            prefs.sprutKeepAwake.set(keepAwake.isChecked());
            password.setText("");
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPreferences();
            } else if (prefs.widgetEnabled.get() && Permissions.allPermissionsGranted(this)) {
                startForegroundService(new Intent(this, WidgetService.class));
            }
            Toast.makeText(this, "Настройки Sprut.hub сохранены", Toast.LENGTH_SHORT).show();
            main.postDelayed(this::reloadCatalog, 1_500L);
        } catch (Exception e) {
            Toast.makeText(this, "Sprut.hub: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setText("Состояние: " + (SprutHubController.isSynced()
                ? "ONLINE — актуальный снимок получен" : SprutHubController.connectionDetail()));
        SprutHubController current = SprutHubController.active();
        if (current != null) {
            SprutCatalog latest = current.catalog();
            long latestFingerprint = catalogFingerprint(latest);
            if (latest != shownCatalog || latestFingerprint != shownCatalogFingerprint) {
                shownCatalog = latest;
                if (latestFingerprint != shownCatalogFingerprint) {
                    shownCatalogFingerprint = latestFingerprint;
                    renderCatalog();
                }
            }
        }
    }

    private void refreshCatalog() {
        SprutHubController current = SprutHubController.active();
        if (current == null) {
            saveConnection();
            Toast.makeText(this, "Коннектор запускается; повторите обновление после ONLINE",
                    Toast.LENGTH_LONG).show();
            return;
        }
        current.refreshCatalog().whenComplete((catalog, failure) -> main.post(() -> {
            if (failure != null) {
                Throwable cause = rootCause(failure);
                Toast.makeText(this, "Не удалось обновить: " + cause.getMessage(),
                        Toast.LENGTH_LONG).show();
            } else {
                shownCatalog = catalog;
                shownCatalogFingerprint = catalogFingerprint(catalog);
                renderCatalog();
                Toast.makeText(this, "Устройства обновлены", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void reloadCatalog() {
        SprutHubController current = SprutHubController.active();
        if (current != null && (SprutHubController.isSynced()
                || !current.catalog().accessories().isEmpty())) {
            shownCatalog = current.catalog();
        } else {
            JSONObject cached = new SprutHubCatalogStore(this).load();
            JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
            JSONObject accessories = cached == null ? null : cached.optJSONObject("accessories");
            if (rooms != null && accessories != null) {
                try { shownCatalog = SprutProtocolAdapter.parseCatalog(rooms, accessories); }
                catch (RuntimeException ignored) { shownCatalog = SprutCatalog.empty(); }
            }
        }
        shownCatalogFingerprint = catalogFingerprint(shownCatalog);
        renderCatalog();
    }

    private static void validateWebSocketUrl(String endpoint) {
        final URI parsed;
        try {
            parsed = new URI(endpoint);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Некорректный WebSocket URL", error);
        }
        String scheme = parsed.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("URL должен начинаться с ws:// или wss://");
        }
        if (parsed.getHost() == null || parsed.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("В WebSocket URL не указан адрес хаба");
        }
        if (parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("WebSocket URL не должен содержать логин или #fragment");
        }
    }

    /** Includes mutable characteristic values because Sprut events update the catalog in place. */
    private static long catalogFingerprint(SprutCatalog catalog) {
        long hash = 1_125_899_906_842_597L;
        for (SprutCatalog.Accessory accessory : catalog.accessories()) {
            hash = mix(hash, accessory.id());
            hash = mix(hash, accessory.name());
            hash = mix(hash, accessory.model());
            hash = mix(hash, accessory.online());
            for (SprutCatalog.Service service : accessory.services()) {
                hash = mix(hash, service.id());
                hash = mix(hash, service.name());
                hash = mix(hash, service.type());
                for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
                    hash = mix(hash, characteristic.path());
                    hash = mix(hash, characteristic.name());
                    hash = mix(hash, characteristic.type());
                    hash = mix(hash, characteristic.unit());
                    hash = mix(hash, characteristic.readable());
                    hash = mix(hash, characteristic.writable());
                    hash = mix(hash, characteristic.valueType());
                    hash = mix(hash, characteristic.currentValue());
                }
            }
        }
        return hash;
    }

    private static long mix(long hash, Object value) {
        return hash * 31L + (value == null ? 0 : value.hashCode());
    }

    private void renderCatalog() {
        if (catalogContainer == null) return;
        catalogContainer.removeAllViews();
        String query = search == null ? "" : text(search).toLowerCase(Locale.ROOT);
        if (shownCatalog.accessories().isEmpty()) {
            catalogContainer.addView(label("Каталог пока пуст. Сохраните подключение, дождитесь "
                    + "ONLINE и нажмите «Обновить устройства»."), topMargin(12));
            return;
        }
        List<SprutCatalog.Accessory> accessories = new ArrayList<>(shownCatalog.accessories());
        accessories.sort(Comparator.comparing(a -> (shownCatalog.roomNameFor(a) + " " + a.name())
                .toLowerCase(Locale.ROOT)));
        for (SprutCatalog.Accessory accessory : accessories) {
            String searchable = accessory.name() + " " + accessory.model() + " "
                    + shownCatalog.roomNameFor(accessory);
            boolean accessoryMatches = query.isEmpty()
                    || searchable.toLowerCase(Locale.ROOT).contains(query);
            LinearLayout accessoryCard = buildAccessoryCard(accessory, query, accessoryMatches);
            if (accessoryCard.getChildCount() > 1) {
                catalogContainer.addView(accessoryCard, cardParams());
            }
        }
        if (catalogContainer.getChildCount() == 0) {
            catalogContainer.addView(label("Ничего не найдено"), topMargin(12));
        }
    }

    private LinearLayout buildAccessoryCard(SprutCatalog.Accessory accessory, String query,
                                            boolean accessoryMatches) {
        LinearLayout card = card();
        String room = shownCatalog.roomNameFor(accessory);
        String title = (room.isEmpty() ? "" : room + " · ") + accessory.name();
        card.addView(heading(title, 19));
        card.addView(label("ID " + accessory.id() + " · "
                + (accessory.online() ? "online" : "offline")
                + (accessory.model().isEmpty() ? "" : " · " + accessory.model())));

        int serviceCount = 0;
        for (SprutCatalog.Service service : accessory.services()) {
            String serviceSearch = service.name() + " " + service.type();
            boolean serviceMatches = accessoryMatches || query.isEmpty()
                    || serviceSearch.toLowerCase(Locale.ROOT).contains(query);
            LinearLayout serviceView = buildService(accessory, service, query, serviceMatches);
            if (serviceView.getChildCount() > 0) {
                card.addView(serviceView, topMargin(12));
                serviceCount++;
            }
        }
        if (serviceCount == 0) {
            // Remove header-only cards from filtered results; caller checks child count.
            card.removeAllViews();
        }
        return card;
    }

    private LinearLayout buildService(SprutCatalog.Accessory accessory,
                                      SprutCatalog.Service service, String query,
                                      boolean serviceMatches) {
        LinearLayout group = column();
        List<SprutCatalog.Characteristic> visible = new ArrayList<>();
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            String value = characteristic.name() + " " + characteristic.type() + " "
                    + characteristic.path().characteristicId();
            if (serviceMatches || query.isEmpty()
                    || value.toLowerCase(Locale.ROOT).contains(query)) visible.add(characteristic);
        }
        if (visible.isEmpty()) return group;

        LinearLayout serviceHeader = row();
        serviceHeader.setGravity(Gravity.CENTER_VERTICAL);
        serviceHeader.addView(heading(firstNonBlank(service.name(), service.type(), "Сервис"), 16),
                weighted());
        Button preset = smallButton("Готовая плитка");
        preset.setOnClickListener(v -> addServicePreset(accessory, service));
        serviceHeader.addView(preset);
        group.addView(serviceHeader, matchWrap());
        group.addView(label(service.type() + " · aId/sId " + accessory.id() + "/" + service.id()));

        for (SprutCatalog.Characteristic characteristic : visible) {
            LinearLayout characteristicRow = column();
            SprutValueMapper.DisplayValue display =
                    SprutValueMapper.toDisplayPayload(characteristic);
            TextView value = new TextView(this);
            value.setText(firstNonBlank(characteristic.name(), characteristic.type(), "Характеристика")
                    + "  [cId " + characteristic.path().characteristicId() + "]\n"
                    + "Сейчас: " + display.text() + " · "
                    + (characteristic.readable() ? "read" : "")
                    + (characteristic.writable() ? "/write" : "")
                    + (characteristic.unit().isEmpty() ? "" : " · " + characteristic.unit()));
            value.setTextSize(14);
            characteristicRow.addView(value, matchWrap());
            if (characteristic.readable()) {
                LinearLayout actions = row();
                Button mainButton = smallButton("В основную строку");
                mainButton.setOnClickListener(v -> addMain(accessory, service, characteristic));
                actions.addView(mainButton, weighted());
                Button popupButton = smallButton("Во всплывающий");
                popupButton.setOnClickListener(v -> addPopup(accessory, service, characteristic));
                actions.addView(popupButton, weighted());
                characteristicRow.addView(actions, topMargin(4));
            }
            GradientDrawable divider = new GradientDrawable();
            divider.setColor(0x227F7F7F);
            characteristicRow.setBackground(divider);
            characteristicRow.setPadding(dp(8), dp(8), dp(8), dp(8));
            group.addView(characteristicRow, topMargin(6));
        }
        return group;
    }

    private void addMain(SprutCatalog.Accessory accessory, SprutCatalog.Service service,
                         SprutCatalog.Characteristic characteristic) {
        try {
            List<HaBrickConfig> items = new ArrayList<>(mainStore.loadMain());
            String id = uniqueMainId("sprut_" + characteristic.path().stableId().replace('/', '_'),
                    items);
            HaBrickConfig config = HaBrickConfig.create(id, items.size());
            config.name = accessory.name() + " · "
                    + firstNonBlank(characteristic.name(), service.name(), characteristic.type());
            config.sourceBinding = sourceBinding(characteristic);
            config.displayRules = rulesFor(accessory, service, characteristic,
                    config.sourceBinding);
            SprutValueMapper.DisplayValue display =
                    SprutValueMapper.toDisplayPayload(characteristic);
            config.defaultText = display.text();
            items.add(config);
            mainStore.saveMain(items);
            ensureMainAutomationBlock();
            applyLiveSettings();
            Toast.makeText(this, "Добавлено в основную строку: " + config.name,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось добавить: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addPopup(SprutCatalog.Accessory accessory, SprutCatalog.Service service,
                          SprutCatalog.Characteristic characteristic) {
        try {
            List<PopupItemConfig> items = new ArrayList<>(popupStore.load());
            String id = uniquePopupId("sprut_" + characteristic.path().stableId().replace('/', '_'),
                    items);
            PopupItemConfig config = PopupItemConfig.create(id, items.size());
            config.automationId = id;
            config.name = accessory.name() + " · "
                    + firstNonBlank(characteristic.name(), service.name(), characteristic.type());
            config.title = config.name;
            config.sourceBinding = sourceBinding(characteristic);
            config.displayRules = rulesFor(accessory, service, characteristic,
                    config.sourceBinding);
            config.icon = firstNonBlank(SprutValueMapper.iconFor(characteristic), "power");
            if (characteristic.writable()
                    && (characteristic.valueType() == SprutCatalog.ValueType.BOOLEAN
                    || characteristic.currentValue() instanceof Boolean)) {
                config.actionBinding = new ActionBinding(ConnectorType.SPRUTHUB, "default",
                        characteristic.path().stableId(), ActionBinding.OPERATION_TOGGLE, "{}");
                config.actionId = id + "_action";
            }
            items.add(config);
            popupStore.save(items);
            applyLiveSettings();
            Toast.makeText(this, "Добавлена всплывающая плитка: " + config.name,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось добавить: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addServicePreset(SprutCatalog.Accessory accessory,
                                  SprutCatalog.Service service) {
        try {
            SprutPopupPreset preset = presetEngine.recommend(accessory, service);
            if (!preset.primaryCharacteristicPath().isPresent()) {
                throw new IllegalArgumentException("В сервисе нет читаемой характеристики");
            }
            List<PopupItemConfig> items = new ArrayList<>(popupStore.load());
            SprutPath primaryPath = preset.primaryCharacteristicPath().get();
            SprutCatalog.Characteristic primary = shownCatalog.find(primaryPath);
            String id = uniquePopupId("sprut_" + primaryPath.stableId().replace('/', '_'), items);
            PopupItemConfig config = PopupItemConfig.create(id, items.size());
            config.automationId = id;
            config.name = preset.title();
            config.title = preset.title();
            config.icon = preset.iconId();
            config.columnSpan = preset.columnSpan();
            config.rowSpan = preset.rowSpan();
            config.sourceBinding = new SourceBinding(ConnectorType.SPRUTHUB, "default",
                    primaryPath.stableId(), "", preset.presentation().name(), "");
            config.displayRules = SprutRuleSetFactory.fromPreset(preset);
            if (primary != null) {
                SprutValueMapper.DisplayValue display = SprutValueMapper.toDisplayPayload(primary);
                config.defaultText = display.text();
            }
            if (preset.actionCharacteristicPath().isPresent()
                    && preset.actionOperation().isPresent()) {
                String operation = preset.actionOperation().get()
                        == SprutPopupPreset.ActionOperation.TOGGLE
                        ? ActionBinding.OPERATION_TOGGLE : ActionBinding.OPERATION_SET;
                String payload = preset.defaultActionPayload().isPresent()
                        ? primitiveJson(preset.defaultActionPayload().get()) : "{}";
                // SET without a chosen value would be unsafe; leave it display-only until the
                // user selects the desired value in the tile editor.
                if (ActionBinding.OPERATION_TOGGLE.equals(operation)
                        || preset.defaultActionPayload().isPresent()) {
                    config.actionBinding = new ActionBinding(ConnectorType.SPRUTHUB, "default",
                            preset.actionCharacteristicPath().get().stableId(), operation, payload);
                    config.actionId = id + "_action";
                }
            }
            items.add(config);
            popupStore.save(items);
            applyLiveSettings();
            Toast.makeText(this, "Создан пресет плитки: " + config.title,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось создать пресет: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private SourceBinding sourceBinding(SprutCatalog.Characteristic characteristic) {
        String presentation = SourceBinding.PRESENTATION_AUTO;
        String key = (characteristic.type() + " " + characteristic.serviceType())
                .toLowerCase(Locale.ROOT);
        if (key.contains("doorstate") || key.contains("garage") || key.contains("cover")) {
            presentation = SourceBinding.PRESENTATION_COVER;
        } else if (key.contains("temperature") || key.contains("thermostat")) {
            presentation = SourceBinding.PRESENTATION_TEMPERATURE;
        } else if (characteristic.valueType() == SprutCatalog.ValueType.BOOLEAN) {
            presentation = SourceBinding.PRESENTATION_BOOLEAN;
        }
        return new SourceBinding(ConnectorType.SPRUTHUB, "default",
                characteristic.path().stableId(), "", presentation, "");
    }

    private static RuleSet rulesFor(SourceBinding binding) {
        if (SourceBinding.PRESENTATION_COVER.equals(binding.presentation)) {
            return ScenarioPresets.cover();
        }
        if (SourceBinding.PRESENTATION_BOOLEAN.equals(binding.presentation)) {
            return ScenarioPresets.booleanState();
        }
        if (SourceBinding.PRESENTATION_TEMPERATURE.equals(binding.presentation)) {
            return ScenarioPresets.temperature();
        }
        return ScenarioPresets.raw();
    }

    private RuleSet rulesFor(SprutCatalog.Accessory accessory, SprutCatalog.Service service,
                             SprutCatalog.Characteristic characteristic,
                             SourceBinding binding) {
        try {
            SprutPopupPreset preset = presetEngine.recommend(accessory, service);
            if (preset.primaryCharacteristicPath().isPresent()
                    && preset.primaryCharacteristicPath().get().equals(characteristic.path())) {
                return SprutRuleSetFactory.fromPreset(preset);
            }
        } catch (RuntimeException ignored) {
            // Metadata on custom Sprut services can be incomplete; presentation still has a
            // safe generic fallback and remains editable in the brick settings.
        }
        return rulesFor(binding);
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

    private static String uniqueMainId(String base, List<HaBrickConfig> items) {
        String candidate = base;
        int suffix = 2;
        while (containsMainId(items, candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }

    private static boolean containsMainId(List<HaBrickConfig> items, String id) {
        for (HaBrickConfig item : items) if (id.equals(item.id)) return true;
        return false;
    }

    private static String uniquePopupId(String base, List<PopupItemConfig> items) {
        String candidate = base;
        int suffix = 2;
        while (containsPopupId(items, candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }

    private static boolean containsPopupId(List<PopupItemConfig> items, String id) {
        for (PopupItemConfig item : items) if (id.equals(item.id)) return true;
        return false;
    }

    private static String primitiveJson(Object value) {
        JSONArray array = new JSONArray();
        array.put(value);
        String encoded = array.toString();
        return encoded.substring(1, encoded.length() - 1);
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

    private TextInputEditText field(LinearLayout parent, String hint, String value,
                                     boolean numeric) {
        TextInputLayout box = new TextInputLayout(this);
        box.setHint(hint);
        TextInputEditText input = new TextInputEditText(this);
        input.setText(value);
        input.setSingleLine(true);
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) result = result.getCause();
        return result;
    }
}
