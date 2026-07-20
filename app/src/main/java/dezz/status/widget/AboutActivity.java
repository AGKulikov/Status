/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dezz.status.widget.databinding.ActivityAboutBinding;
import dezz.status.widget.car.CarDiagnosticValue;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarSprutBinding;
import dezz.status.widget.car.CarSprutBindingStore;
import dezz.status.widget.car.CarTelemetryValue;
import dezz.status.widget.car.SprutCharacteristicConverter;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubCatalogStore;
import dezz.status.widget.sprut.SprutHubController;
import dezz.status.widget.sprut.SprutProtocolAdapter;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.mqtt.MqttController;

public class AboutActivity extends AppCompatActivity {
    private ActivityAboutBinding binding;
    private Preferences prefs;
    private CarSprutBindingStore carBindingStore;
    private List<CarSprutBinding> carBindings = new ArrayList<>();
    private List<CarDiagnosticValue> diagnosticValues = new ArrayList<>();
    private SprutCatalog sprutCatalog = SprutCatalog.empty();
    private boolean catalogIsFresh;
    private int diagnosticsRequestGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = new Preferences(this);
        carBindingStore = new CarSprutBindingStore(prefs);
        carBindings = carBindingStore.load();
        reloadSprutCatalog();

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        binding.backButton.setOnClickListener(v -> finish());

        binding.appVersionText.setText("Status Widget · версия "
                + VersionGetter.getAppVersionName(this) + "\n" + getPackageName());
        binding.haSettingsButton.setOnClickListener(v -> startActivity(
                new Intent(this, HomeAssistantSettingsActivity.class)));
        binding.mqttSettingsButton.setOnClickListener(v -> startActivity(
                new Intent(this, MqttSettingsActivity.class)));
        binding.sprutSettingsButton.setOnClickListener(v -> startActivity(
                new Intent(this, SprutHubSettingsActivity.class)));
        binding.mainBricksButton.setOnClickListener(v -> startActivity(
                new Intent(this, AutomationSettingsActivity.class)));
        binding.popupSettingsButton.setOnClickListener(v -> startActivity(
                new Intent(this, PopupSettingsActivity.class)));
        binding.scenarioSettingsButton.setOnClickListener(v -> startActivity(
                new Intent(this, ScenarioSettingsActivity.class)));
        binding.intentSettingsButton.setOnClickListener(v -> startActivity(
                new Intent(this, IntentScenarioSettingsActivity.class)));

        requestCarDiagnostics();
        refreshConnectionStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        carBindings = carBindingStore.load();
        reloadSprutCatalog();
        refreshConnectionStatuses();
        if (binding != null) renderCarDiagnostics();
    }

    private void refreshConnectionStatuses() {
        if (binding == null) return;
        binding.haStatusText.setText(prefs.haApiEnabled.get()
                ? "Включено · " + HaApiController.connectionDetail()
                : "Выключено");
        binding.mqttStatusText.setText(prefs.mqttEnabled.get()
                ? (MqttController.isConnected() ? "Подключено" : "Включено · "
                + MqttController.connectionDetail()) : "Выключено");
        binding.sprutStatusText.setText(prefs.sprutEnabled.get()
                ? (SprutHubController.isSynced() ? "Подключено, каталог актуален" : "Включено · "
                + SprutHubController.connectionDetail()) : "Выключено");
    }

    private void requestCarDiagnostics() {
        final int generation = ++diagnosticsRequestGeneration;
        if (binding != null) binding.carDiagnosticsStatus.setText(
                "Данные автомобиля → Sprut.hub\nПолучение актуального снимка…");
        CarIntegrations.get(this).requestDiagnostics(values -> {
            if (generation != diagnosticsRequestGeneration || binding == null
                    || isFinishing() || isDestroyed()) return;
            diagnosticValues = new ArrayList<>(values);
            renderCarDiagnostics();
        });
    }

    private void renderCarDiagnostics() {
        if (binding == null) return;
        LinearLayout container = binding.carDiagnosticsContainer;
        // The status is now a sibling above this dynamic list, so every child here belongs to
        // the previous render. Clearing all of them prevents duplicate action rows on refresh.
        container.removeAllViews();

        String connectorState = catalogIsFresh
                ? "Sprut.hub ONLINE, используется актуальный каталог"
                : sprutCatalog.accessories().isEmpty()
                ? "Каталог Sprut.hub недоступен — сначала настройте подключение"
                : "Показан сохранённый каталог; запись начнётся только после ONLINE";
        String runtimeState;
        if (!prefs.widgetEnabled.get()) {
            runtimeState = "Фоновая передача остановлена: включите основной виджет";
        } else if (!Permissions.allPermissionsGranted(this)) {
            runtimeState = "Фоновая передача ожидает разрешения основного виджета";
        } else if (WidgetService.getInstance() != null) {
            runtimeState = "Фоновая передача запущена";
        } else {
            runtimeState = "Фоновая передача запустится вместе с основным виджетом";
        }
        binding.carDiagnosticsStatus.setText("Данные автомобиля → Sprut.hub\n" + connectorState
                + "\n" + runtimeState
                + "\nДля RAW-показателей единица SDK не предполагается автоматически.");

        LinearLayout actions = row();
        Button refresh = new Button(this);
        refresh.setText("Обновить данные");
        refresh.setOnClickListener(v -> requestCarDiagnostics());
        actions.addView(refresh, weighted());
        Button sprut = new Button(this);
        sprut.setText("Настроить Sprut.hub");
        sprut.setOnClickListener(v -> startActivity(
                new Intent(this, SprutHubSettingsActivity.class)));
        actions.addView(sprut, weighted());
        container.addView(actions, topMargin(8));

        if (diagnosticValues.isEmpty()) {
            TextView empty = label(getString(R.string.car_diagnostics_empty));
            container.addView(empty, topMargin(12));
            return;
        }

        for (CarDiagnosticValue value : diagnosticValues) {
            if ("ecarx.sensor_manager".equals(value.id)) {
                TextView unavailable = label(value.label + ": " + value.unitNote);
                container.addView(unavailable, topMargin(12));
                continue;
            }
            container.addView(buildMetricCard(value), topMargin(10));
        }
    }

    private View buildMetricCard(CarDiagnosticValue value) {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor());
        background.setStroke(dp(1), 0x557F7F7F);
        background.setCornerRadius(dp(14));
        card.setBackground(background);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(value.label);
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title, weighted());
        CarSprutBinding configured = bindingFor(value.id);
        Button choose = new Button(this);
        choose.setText(configured == null ? "Назначить" : "Изменить");
        choose.setMinWidth(0);
        choose.setMinimumWidth(0);
        choose.setOnClickListener(v -> chooseAccessory(value));
        header.addView(choose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header, matchWrap());

        String raw = "raw: " + value.rawValue + "  •  support: " + value.supportStatus
                + "\n" + value.unitNote + "\nID: " + value.id;
        card.addView(label(raw), topMargin(4));

        TextView target = label(configured == null
                ? "Передача: выключена"
                : "Передача: " + bindingTargetLabel(configured)
                + "\nПреобразование: raw × " + plain(configured.scale) + " + "
                + plain(configured.offset) + "; интервал ≥ "
                + plain(configured.minIntervalMs / 1000d) + " с");
        target.setAlpha(configured == null ? .65f : .9f);
        card.addView(target, topMargin(5));
        if (configured != null) {
            TextView compatibility = label(bindingCompatibility(value, configured));
            compatibility.setTextColor(bindingCompatibilityError(value, configured)
                    ? 0xFFFF6B6B : 0xFF66BB6A);
            card.addView(compatibility, topMargin(4));
        }
        return card;
    }

    private String bindingCompatibility(CarDiagnosticValue metric, CarSprutBinding configured) {
        SprutCatalog.Characteristic target = sprutCatalog.find(configured.targetPath);
        if (target == null) return "Проверка: цель отсутствует в текущем каталоге";
        try {
            SprutCharacteristicConverter.validateNumericTarget(target);
            if (metric.numericValue == null) {
                return "Проверка: ожидается числовое значение автомобиля";
            }
            Object converted = SprutCharacteristicConverter.convert(
                    new CarTelemetryValue(metric.id, metric.numericValue.floatValue(),
                            System.currentTimeMillis(), "raw"), configured, target);
            return "Проверка: готово к записи → " + plainObject(converted)
                    + " (" + converted.getClass().getSimpleName() + ")";
        } catch (IllegalArgumentException error) {
            return "Проверка: " + rootMessage(error);
        }
    }

    private boolean bindingCompatibilityError(CarDiagnosticValue metric,
                                              CarSprutBinding configured) {
        SprutCatalog.Characteristic target = sprutCatalog.find(configured.targetPath);
        if (target == null) return true;
        try {
            SprutCharacteristicConverter.validateNumericTarget(target);
            if (metric.numericValue == null) return false;
            SprutCharacteristicConverter.convert(
                    new CarTelemetryValue(metric.id, metric.numericValue.floatValue(),
                            System.currentTimeMillis(), "raw"), configured, target);
            return false;
        } catch (IllegalArgumentException error) {
            return true;
        }
    }

    private void reloadSprutCatalog() {
        catalogIsFresh = false;
        SprutHubController controller = SprutHubController.active();
        if (controller != null && !controller.catalog().accessories().isEmpty()) {
            sprutCatalog = controller.catalog();
            catalogIsFresh = SprutHubController.isSynced();
            return;
        }
        sprutCatalog = SprutCatalog.empty();
        try {
            JSONObject cached = new SprutHubCatalogStore(this).load();
            JSONObject rooms = cached == null ? null : cached.optJSONObject("rooms");
            JSONObject accessories = cached == null ? null
                    : cached.optJSONObject("accessories");
            if (rooms != null && accessories != null) {
                sprutCatalog = SprutProtocolAdapter.parseCatalog(rooms, accessories);
            }
        } catch (Exception ignored) {
            sprutCatalog = SprutCatalog.empty();
        }
    }

    private void chooseAccessory(CarDiagnosticValue metric) {
        reloadSprutCatalog();
        List<SprutCatalog.Accessory> accessories = new ArrayList<>();
        for (SprutCatalog.Accessory accessory : sprutCatalog.accessories()) {
            if (hasWritableCharacteristic(accessory)) accessories.add(accessory);
        }
        accessories.sort(Comparator
                .comparing((SprutCatalog.Accessory value) -> sprutCatalog.roomNameFor(value),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SprutCatalog.Accessory::name,
                        String.CASE_INSENSITIVE_ORDER));
        if (accessories.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет доступных целей")
                    .setMessage("Подключите Sprut.hub, обновите список устройств и создайте "
                            + "виртуальное устройство с записываемой характеристикой.")
                    .setPositiveButton("Настроить Sprut.hub", (d, w) -> startActivity(
                            new Intent(this, SprutHubSettingsActivity.class)))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        String[] labels = new String[accessories.size()];
        for (int i = 0; i < accessories.size(); i++) {
            SprutCatalog.Accessory item = accessories.get(i);
            String room = sprutCatalog.roomNameFor(item);
            labels[i] = (room.isEmpty() ? "" : room + " → ")
                    + firstNonBlank(item.name(), item.model(), "Устройство " + item.id())
                    + (item.virtual() ? "  [virtual]" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(metric.label + " — устройство")
                .setItems(labels, (dialog, which) -> chooseService(metric,
                        accessories.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void chooseService(CarDiagnosticValue metric, SprutCatalog.Accessory accessory) {
        List<SprutCatalog.Service> services = new ArrayList<>();
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasWritableCharacteristic(service)) services.add(service);
        }
        services.sort(Comparator.comparing(SprutCatalog.Service::name,
                String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[services.size()];
        for (int i = 0; i < services.size(); i++) {
            SprutCatalog.Service service = services.get(i);
            labels[i] = firstNonBlank(service.name(), service.type(), "Service " + service.id())
                    + "\nтип: " + emptyDash(service.type()) + "  •  sId=" + service.id();
        }
        new AlertDialog.Builder(this)
                .setTitle(metric.label + " — сервис")
                .setItems(labels, (dialog, which) -> chooseCharacteristic(metric, accessory,
                        services.get(which)))
                .setNegativeButton("Назад", (d, w) -> chooseAccessory(metric))
                .show();
    }

    private void chooseCharacteristic(CarDiagnosticValue metric,
                                      SprutCatalog.Accessory accessory,
                                      SprutCatalog.Service service) {
        List<SprutCatalog.Characteristic> values = new ArrayList<>();
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (isCompatibleTelemetryTarget(characteristic)) values.add(characteristic);
        }
        String[] labels = new String[values.size()];
        for (int i = 0; i < values.size(); i++) {
            SprutCatalog.Characteristic item = values.get(i);
            labels[i] = firstNonBlank(item.name(), item.type(), "Characteristic")
                    + "\nтип: " + emptyDash(item.type())
                    + "  •  format: " + emptyDash(item.format())
                    + "  •  unit: " + emptyDash(item.unit())
                    + "\npath: " + item.path().stableId();
        }
        new AlertDialog.Builder(this)
                .setTitle(metric.label + " — характеристика")
                .setItems(labels, (dialog, which) -> configureConversion(metric, accessory,
                        service, values.get(which)))
                .setNegativeButton("Назад", (d, w) -> chooseService(metric, accessory))
                .show();
    }

    private void configureConversion(CarDiagnosticValue metric,
                                     SprutCatalog.Accessory accessory,
                                     SprutCatalog.Service service,
                                     SprutCatalog.Characteristic characteristic) {
        CarSprutBinding old = bindingFor(metric.id);
        boolean sameTarget = old != null && old.targetPath.equals(characteristic.path());
        double initialScale = sameTarget ? old.scale : 1d;
        double initialOffset = sameTarget ? old.offset : 0d;
        long initialInterval = sameTarget ? old.minIntervalMs : 1_000L;
        CarSprutBinding.IntegerPolicy initialPolicy = sameTarget
                ? old.integerPolicy : CarSprutBinding.IntegerPolicy.EXACT;

        LinearLayout form = column();
        int padding = dp(18);
        form.setPadding(padding, dp(4), padding, 0);
        String metadata = "Цель: " + accessoryDisplay(accessory) + " → "
                + serviceDisplay(service) + " → " + characteristicDisplay(characteristic)
                + "\npath: " + characteristic.path().stableId()
                + "\nformat=" + emptyDash(characteristic.format())
                + ", unit=" + emptyDash(characteristic.unit())
                + ", min=" + String.valueOf(characteristic.minValue())
                + ", max=" + String.valueOf(characteristic.maxValue())
                + ", step=" + String.valueOf(characteristic.minStep());
        form.addView(label(metadata), matchWrap());

        EditText scale = numericField("Коэффициент (raw × …)", initialScale);
        EditText offset = numericField("Смещение (… + значение)", initialOffset);
        EditText interval = numericField("Минимальный интервал отправки, секунд",
                initialInterval / 1000d);
        form.addView(scale, topMargin(8));
        form.addView(offset, topMargin(8));
        form.addView(interval, topMargin(8));

        TextView roundingTitle = label("Для целочисленной характеристики:");
        form.addView(roundingTitle, topMargin(8));
        Spinner integerPolicy = new Spinner(this);
        String[] policies = {"Только точное целое", "Округлять до ближайшего"};
        integerPolicy.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, policies));
        integerPolicy.setSelection(initialPolicy == CarSprutBinding.IntegerPolicy.ROUND_HALF_UP
                ? 1 : 0);
        form.addView(integerPolicy, matchWrap());

        TextView preview = label(metric.numericValue == null
                ? "Предпросмотр недоступен: автомобиль пока не вернул число."
                : "Текущее raw: " + plain(metric.numericValue.doubleValue()));
        form.addView(preview, topMargin(8));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Передача «" + metric.label + "»")
                .setView(form)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .setNeutralButton(old == null ? null : "Удалить привязку", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    double scaleValue = parseDecimal(scale);
                    double offsetValue = parseDecimal(offset);
                    double intervalSeconds = parseDecimal(interval);
                    if (intervalSeconds < 0d || intervalSeconds > 3_600d) {
                        throw new IllegalArgumentException(
                                "Интервал должен быть от 0 до 3600 секунд");
                    }
                    long intervalMs = Math.round(intervalSeconds * 1_000d);
                    CarSprutBinding.IntegerPolicy policy = integerPolicy.getSelectedItemPosition()
                            == 1 ? CarSprutBinding.IntegerPolicy.ROUND_HALF_UP
                            : CarSprutBinding.IntegerPolicy.EXACT;
                    CarSprutBinding candidate = new CarSprutBinding(metric.id, true,
                            characteristic.path(),
                            SprutCharacteristicConverter.snapshotSignature(characteristic),
                            accessoryDisplay(accessory), serviceDisplay(service),
                            characteristicDisplay(characteristic), scaleValue, offsetValue,
                            policy, intervalMs);
                    ensureUniqueTarget(candidate);
                    SprutCharacteristicConverter.validateNumericTarget(characteristic);
                    if (metric.numericValue != null) {
                        Object converted = SprutCharacteristicConverter.convert(
                                new CarTelemetryValue(metric.id,
                                        metric.numericValue.floatValue(),
                                        System.currentTimeMillis(), "raw"),
                                candidate, characteristic);
                        preview.setText("Предпросмотр: " + plainObject(converted)
                                + " (" + converted.getClass().getSimpleName() + ")");
                    }
                    saveBinding(candidate);
                    dialog.dismiss();
                } catch (Exception error) {
                    preview.setText("Не сохранено: " + rootMessage(error));
                    preview.setTextColor(0xFFFF6B6B);
                }
            });
            if (old != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    removeBinding(metric.id);
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private void saveBinding(CarSprutBinding replacement) throws Exception {
        List<CarSprutBinding> updated = new ArrayList<>();
        for (CarSprutBinding item : carBindings) {
            if (!item.metricId.equals(replacement.metricId)) updated.add(item);
        }
        updated.add(replacement);
        carBindingStore.save(updated);
        carBindings = updated;
        notifyRuntimeConfigurationChanged();
        renderCarDiagnostics();
        Toast.makeText(this, "Передача показателя настроена", Toast.LENGTH_SHORT).show();
    }

    private void removeBinding(String metricId) {
        try {
            List<CarSprutBinding> updated = new ArrayList<>();
            for (CarSprutBinding item : carBindings) {
                if (!item.metricId.equals(metricId)) updated.add(item);
            }
            carBindingStore.save(updated);
            carBindings = updated;
            notifyRuntimeConfigurationChanged();
            renderCarDiagnostics();
        } catch (Exception error) {
            Toast.makeText(this, rootMessage(error), Toast.LENGTH_LONG).show();
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

    private void ensureUniqueTarget(CarSprutBinding candidate) {
        for (CarSprutBinding existing : carBindings) {
            if (existing.metricId.equals(candidate.metricId)) continue;
            if (existing.targetPath.equals(candidate.targetPath)) {
                throw new IllegalArgumentException("Эта характеристика уже используется для «"
                        + metricLabel(existing.metricId) + "». Сначала удалите старую привязку.");
            }
        }
    }

    @Nullable
    private CarSprutBinding bindingFor(String metricId) {
        for (CarSprutBinding value : carBindings) {
            if (value.metricId.equals(metricId)) return value;
        }
        return null;
    }

    private String metricLabel(String metricId) {
        for (CarDiagnosticValue value : diagnosticValues) {
            if (value.id.equals(metricId)) return value.label;
        }
        return metricId;
    }

    private static boolean hasWritableCharacteristic(SprutCatalog.Accessory accessory) {
        for (SprutCatalog.Service service : accessory.services()) {
            if (hasWritableCharacteristic(service)) return true;
        }
        return false;
    }

    private static boolean hasWritableCharacteristic(SprutCatalog.Service service) {
        for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
            if (isCompatibleTelemetryTarget(characteristic)) return true;
        }
        return false;
    }

    /** Keep read-only and unresolved-format characteristics out of the selection path. */
    private static boolean isCompatibleTelemetryTarget(
            SprutCatalog.Characteristic characteristic) {
        try {
            SprutCharacteristicConverter.validateNumericTarget(characteristic);
            return true;
        } catch (IllegalArgumentException incompatible) {
            return false;
        }
    }

    private EditText numericField(String hint, double value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(plain(value));
        return input;
    }

    private static double parseDecimal(EditText input) {
        String raw = input.getText() == null ? "" : input.getText().toString().trim()
                .replace(',', '.');
        if (raw.isEmpty()) throw new IllegalArgumentException("Заполните все числовые поля");
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Число должно быть конечным");
        return value;
    }

    private static String bindingTargetLabel(CarSprutBinding binding) {
        return firstNonBlank(binding.targetAccessoryName, "Устройство") + " → "
                + firstNonBlank(binding.targetServiceName, "Сервис") + " → "
                + firstNonBlank(binding.targetCharacteristicName,
                binding.targetPath.stableId());
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

    private static String emptyDash(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String plain(double value) {
        if (!Double.isFinite(value)) return String.valueOf(value);
        java.math.BigDecimal decimal = java.math.BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }

    private static String plainObject(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
        return value instanceof Number ? plain(((Number) value).doubleValue())
                : String.valueOf(value);
    }

    private static String rootMessage(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) result = result.getCause();
        String message = result.getMessage();
        return message == null || message.trim().isEmpty()
                ? result.getClass().getSimpleName() : message;
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

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setAlpha(.78f);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams topMargin(int valueDp) {
        LinearLayout.LayoutParams result = matchWrap();
        result.topMargin = dp(valueDp);
        return result;
    }

    private int surfaceColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface,
                value, true) ? value.data : Color.TRANSPARENT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
