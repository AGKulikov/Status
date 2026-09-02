/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.hud.HudCanvasView;
import dezz.status.widget.hud.HudDisplaySelector;
import dezz.status.widget.hud.HudElementConfig;
import dezz.status.widget.hud.HudElementType;
import dezz.status.widget.hud.HudHorizontalGroup;
import dezz.status.widget.hud.HudPanelConfig;
import dezz.status.widget.hud.HudPanelStore;
import dezz.status.widget.hud.HudPresentationService;
import dezz.status.widget.hud.HudRuntimeData;
import dezz.status.widget.hud.HudViewportPolicy;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.navigation.NavigationIntegrationConfig;
import dezz.status.widget.navigation.NavigationHudEndpointService;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/**
 * Main-display, live HUD editor. Dragging/resizing is projected onto the selected external display
 * after a short write debounce; the HUD itself never hosts editor controls or receives touch.
 */
public final class HudPanelSettingsActivity extends AppCompatActivity {
    private static final int PICK_FONT = 0x4846;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    private Preferences preferences;
    private HudPanelStore store;
    private HudPanelConfig config;
    private HudRuntimeData runtime;
    private HudCanvasView canvas;
    private TextView status;
    private TextView selection;
    private Switch enabled;
    private final Runnable persistLive = () -> persist(false);
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            updateStatus();
            main.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new HudPanelStore(preferences);
        config = store.load();
        runtime = new HudRuntimeData(this, config, () -> {
            if (canvas != null) canvas.invalidate();
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D12);
        int driverInset = driverPanelInset();
        boolean driverOnRight = preferences.driverPanelSide.get() == 1;
        root.setPadding(dp(12) + (driverOnRight ? 0 : driverInset), dp(10),
                dp(12) + (driverOnRight ? driverInset : 0), dp(10));
        root.addView(toolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = text("HUD не запущен", 13, 0xFFB7C2D2);
        root.addView(status, marginTop(6));

        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.BLACK);
        canvas = new HudCanvasView(this, config, runtime, true,
                new HudCanvasView.EditorListener() {
                    @Override public void onSelectionChanged(
                            @Nullable HudElementConfig selected) {
                        updateSelection(selected);
                    }

                    @Override public void onGeometryChanged(
                            @NonNull HudElementConfig item, boolean committed) {
                        updateSelection(item);
                        main.removeCallbacks(persistLive);
                        main.postDelayed(persistLive, committed ? 0L : 55L);
                    }

                    @Override public void onConfigure(@NonNull HudElementConfig item) {
                        editElement(item);
                    }
                });
        preview.addView(canvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.topMargin = dp(8);
        root.addView(preview, previewParams);

        root.addView(selectionBar(), marginTop(8));
        TextView hint = text("Коснитесь элемента, перетащите его по сетке; синий маркер "
                + "в правом нижнем углу меняет размер. Изменения сразу видны на HUD.",
                12, 0xFF8F9AA9);
        root.addView(hint, marginTop(6));
        setContentView(root);
        SettingsBackNavigation.applySafeTopInset(this, root);
        enabled.setChecked(preferences.hudPanelEnabled.get());
    }

    @Override protected void onStart() {
        super.onStart();
        runtime.start();
        main.removeCallbacks(statusTick);
        main.post(statusTick);
    }

    @Override protected void onStop() {
        main.removeCallbacks(statusTick);
        main.removeCallbacks(persistLive);
        persist(false);
        runtime.stop();
        super.onStop();
    }

    private View toolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←");
        back.setOnClickListener(view -> finish());
        row.addView(back, fixed(54));

        TextView title = text("HUD-дисплей", 20, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(8);
        row.addView(title, titleParams);

        enabled = switchView("HUD", preferences != null && preferences.hudPanelEnabled.get());
        enabled.setOnCheckedChangeListener((button, checked) -> {
            preferences.hudPanelEnabled.set(checked);
            if (checked) WidgetServiceStarter.startIfNeeded(this);
            HudPresentationService.apply(this);
            WidgetService host = WidgetService.getInstance();
            if (host != null) host.applyPreferences();
            updateStatus();
        });
        row.addView(enabled);

        Button display = button("HUD · ID 2");
        display.setOnClickListener(view -> chooseDisplay());
        row.addView(display);
        Button add = button("+ Элемент");
        add.setOnClickListener(view -> addElement());
        row.addView(add);
        Button backdrop = button("+ Подложка");
        backdrop.setOnClickListener(view -> addBackdrop());
        row.addView(backdrop);
        Button navigator = button("Навигатор 30.3");
        navigator.setOnClickListener(view -> editNavigatorIntegration());
        row.addView(navigator);
        Button options = button("Параметры");
        options.setOnClickListener(view -> editGlobalOptions());
        row.addView(options);
        Button save = button("Сохранить");
        save.setOnClickListener(view -> persist(true));
        row.addView(save);
        return row;
    }

    private View selectionBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        selection = text("Элемент не выбран", 13, 0xFFD5DCE6);
        row.addView(selection, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button edit = button("Настроить");
        edit.setOnClickListener(view -> {
            HudElementConfig item = canvas.selected();
            if (item != null) editElement(item);
        });
        row.addView(edit);
        Button duplicate = button("Копия");
        duplicate.setOnClickListener(view -> duplicateSelected());
        row.addView(duplicate);
        Button down = button("↓ слой");
        down.setOnClickListener(view -> changeLayer(-1));
        row.addView(down);
        Button up = button("↑ слой");
        up.setOnClickListener(view -> changeLayer(1));
        row.addView(up);
        Button delete = button("Удалить");
        delete.setOnClickListener(view -> deleteSelected());
        row.addView(delete);
        return row;
    }

    private void chooseDisplay() {
        List<HudDisplaySelector.Candidate> available = HudDisplaySelector.available(this);
        ArrayList<HudDisplaySelector.Candidate> external = new ArrayList<>();
        for (HudDisplaySelector.Candidate item : available) {
            if (!item.defaultDisplay
                    && item.id == HudViewportPolicy.VERIFIED_DISPLAY_ID) {
                external.add(item);
            }
        }
        if (external.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("HUD · Display ID 2")
                    .setMessage("В дампе магнитолы HUD подтверждён как постоянный Display ID 2 "
                            + "с поверхностью 1920×1080. Сейчас Android его не сообщает; "
                            + "приложение безопасно ждёт именно ID 2 и не перейдёт на другой экран.")
                    .setPositiveButton("Понятно", null).show();
            return;
        }
        String[] labels = new String[external.size()];
        int selected = -1;
        for (int index = 0; index < external.size(); index++) {
            HudDisplaySelector.Candidate item = external.get(index);
            labels[index] = item.label();
            if (config.displayId == item.id) {
                selected = index;
            }
        }
        new AlertDialog.Builder(this).setTitle("Подтверждённый HUD · ID 2")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    HudDisplaySelector.remember(config, external.get(which));
                    persist(false);
                    dialog.dismiss();
                    updateStatus();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void addElement() {
        ArrayList<HudElementType> types = new ArrayList<>();
        for (HudElementType type : HudElementType.values()) {
            if (type != HudElementType.BACKDROP) types.add(type);
        }
        String[] labels = new String[types.size()];
        for (int index = 0; index < types.size(); index++) {
            labels[index] = types.get(index).category + " · " + types.get(index).label;
        }
        new AlertDialog.Builder(this).setTitle("Добавить на HUD")
                .setItems(labels, (dialog, which) -> {
                    HudElementType type = types.get(which);
                    if (type == HudElementType.NAV_MAP && findMapElement() != null) {
                        Toast.makeText(this,
                                "На HUD может быть только одна независимая карта",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int ordinal = 1;
                    HudElementConfig item;
                    do {
                        item = HudElementConfig.create(type, ordinal++,
                                config.gridColumns, config.gridRows);
                    } while (containsId(item.id));
                    item.zIndex = nextLayer();
                    config.elements.add(item);
                    canvas.select(item.id);
                    canvas.updateConfig(config);
                    persist(false);
                    editElement(item);
                }).setNegativeButton("Отмена", null).show();
    }

    private void addBackdrop() {
        int ordinal = 1;
        HudElementConfig item;
        do {
            item = HudElementConfig.create(HudElementType.BACKDROP, ordinal++,
                    config.gridColumns, config.gridRows);
        } while (containsId(item.id));
        item.zIndex = previousBackdropLayer();
        config.elements.add(item);
        canvas.updateConfig(config);
        canvas.select(item.id);
        persist(false);
        editElement(item);
    }

    private void editElement(@NonNull HudElementConfig item) {
        if (item.type == HudElementType.BACKDROP) {
            editBackdrop(item);
            return;
        }
        if (item.type == HudElementType.HORIZONTAL_GROUP) {
            editHorizontalGroup(item);
            return;
        }
        if (item.type == HudElementType.NAV_MAP) {
            editMapSurface(item);
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(form);

        EditText title = field(form, "Название", item.title, false);
        TextView immutableId = text("ID: " + item.id + "\nЦель сценариев: "
                + item.automationId, 12, 0xFF95A0AF);
        form.addView(immutableId, marginTop(4));
        EditText automation = field(form, "ID цели сценариев", item.automationId, false);
        EditText metric = field(form, "ID телеметрии автомобиля",
                item.telemetryMetricId, false);
        EditText format = field(form, "Формат (%s или %.1f)", item.textFormat, false);
        EditText unit = field(form, "Единица", item.unit, false);
        EditText textColor = field(form, "Цвет текста", item.textColor, false);
        EditText unitColor = field(form, "Цвет единицы", item.unitColor, false);
        SliderField fontSize = slider(form, "Размер текста",
                item.fontSizeSp, 8, 96, 1, " sp");
        SliderField fontWeight = slider(form, "Насыщенность шрифта",
                item.fontWeight, 100, 900, 100, "");

        form.addView(section("Положение в сетке HUD"), marginTop(12));
        SliderField x = slider(form, "Слева", item.x,
                0, Math.max(0, config.gridColumns - 1), 1, " яч.");
        SliderField y = slider(form, "Сверху", item.y,
                0, Math.max(0, config.gridRows - 1), 1, " яч.");
        SliderField width = slider(form, "Ширина", item.width,
                1, Math.max(1, config.gridColumns), 1, " яч.");
        SliderField height = slider(form, "Высота", item.height,
                1, Math.max(1, config.gridRows), 1, " яч.");

        Spinner alignment = spinner(new String[]{"LEFT", "CENTER", "RIGHT"}, item.alignment);
        form.addView(label("Выравнивание"), marginTop(10));
        form.addView(alignment);
        Switch itemEnabled = switchView("Показывать элемент", item.enabled);
        Switch wrap = switchView("Переносить длинный текст", item.wrapText);
        form.addView(itemEnabled, marginTop(8));
        form.addView(wrap, marginTop(4));
        form.addView(text("У виджета нет собственной подложки. Размер фрейма меняет место "
                + "для текста и масштаб графики; размер текста меняется только здесь.",
                12, 0xFF95A0AF), marginTop(6));

        form.addView(section("Умный дом / внешний источник"), marginTop(16));
        String connectorName = item.sourceBinding == null
                ? ConnectorType.HOME_ASSISTANT.name()
                : item.sourceBinding.connectorType.name();
        Spinner connector = spinner(new String[]{"HOME_ASSISTANT", "MQTT", "SPRUTHUB", "PHONE"},
                connectorName);
        form.addView(connector);
        EditText connectorId = field(form, "ID подключения",
                item.sourceBinding == null ? SourceBinding.DEFAULT_CONNECTOR_ID
                        : item.sourceBinding.connectorId, false);
        EditText resource = field(form, "Entity / topic / characteristic",
                item.sourceBinding == null ? "" : item.sourceBinding.resourceId, false);
        EditText valuePath = field(form, "Путь к значению",
                item.sourceBinding == null ? "" : item.sourceBinding.valuePath, false);

        form.addView(section("Дополнительные настройки элемента"), marginTop(16));
        Map<String, Object> visualOptions = new LinkedHashMap<>();
        addVisualElementOptions(form, item, visualOptions);
        EditText options = field(form,
                "Расширенные параметры JSON (визуальные поля выше имеют приоритет)",
                item.options.toString(), false);
        options.setMinLines(5);
        options.setSingleLine(false);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.type.label)
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        item.title = value(title);
                        item.automationId = value(automation);
                        item.telemetryMetricId = value(metric);
                        item.textFormat = value(format);
                        item.unit = value(unit);
                        item.textColor = value(textColor);
                        item.unitColor = value(unitColor);
                        item.backgroundColor = "#00000000";
                        item.fontSizeSp = fontSize.intValue();
                        item.fontWeight = fontWeight.intValue();
                        item.x = x.intValue();
                        item.y = y.intValue();
                        item.width = width.intValue();
                        item.height = height.intValue();
                        item.alignment = String.valueOf(alignment.getSelectedItem());
                        item.enabled = itemEnabled.isChecked();
                        item.wrapText = wrap.isChecked();
                        String resourceId = value(resource);
                        if (resourceId.isEmpty()) {
                            item.sourceBinding = null;
                        } else {
                            item.sourceBinding = new SourceBinding(
                                    ConnectorType.fromJsonName(
                                            String.valueOf(connector.getSelectedItem()),
                                            ConnectorType.HOME_ASSISTANT),
                                    value(connectorId), resourceId, value(valuePath),
                                    SourceBinding.PRESENTATION_AUTO, item.unit);
                        }
                        JSONObject parsedOptions = new JSONObject(value(options).isEmpty()
                                ? "{}" : value(options));
                        applyVisualElementOptions(parsedOptions, visualOptions);
                        item.options = parsedOptions;
                        item.normalize(config.gridColumns, config.gridRows);
                        canvas.updateConfig(config);
                        updateSelection(item);
                        persist(false);
                        dialog.dismiss();
                    } catch (Exception error) {
                        Toast.makeText(this, "Проверьте параметры: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        showSafeDialog(dialog);
    }

    /** Geometry belongs to the HUD element; rendering settings belong to the HUD MapProfile. */
    private void editMapSurface(@NonNull HudElementConfig item) {
        NavigationIntegrationConfig navigation = loadNavigationIntegrationConfig();
        NavigationIntegrationConfig.MapProfile profile = navigation.hudMap;

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(form);

        EditText title = field(form, "Название", item.title, false);
        form.addView(text("Положение в сетке HUD. Карту также можно перетащить и растянуть "
                + "прямо на предпросмотре.", 12, 0xFF95A0AF), marginTop(10));
        SliderField x = slider(form, "Слева", item.x,
                0, Math.max(0, config.gridColumns - 1), 1, " яч.");
        SliderField y = slider(form, "Сверху", item.y,
                0, Math.max(0, config.gridRows - 1), 1, " яч.");
        SliderField width = slider(form, "Ширина", item.width,
                1, Math.max(1, config.gridColumns), 1, " яч.");
        SliderField height = slider(form, "Высота", item.height,
                1, Math.max(1, config.gridRows), 1, " яч.");
        Switch elementEnabled = switchView("Показывать область карты", item.enabled);
        Switch rendererEnabled = switchView("Рендерить независимую карту HUD", profile.enabled);
        form.addView(elementEnabled, marginTop(8));
        form.addView(rendererEnabled, marginTop(4));
        SliderField radius = slider(form, "Скругление карты",
                item.options.optInt("cornerRadiusPx", 0), 0, 80, 1, " px");
        SliderField opacity = slider(form, "Непрозрачность карты",
                item.options.optInt("opacityPercent", 100), 20, 100, 1, " %");

        form.addView(section("Камера HUD"), marginTop(16));
        Spinner cameraMode = navigationCameraModeSpinner(profile.cameraMode);
        form.addView(label("Как карта следует за автомобилем"), marginTop(8));
        form.addView(cameraMode);
        SliderField zoom = slider(form,
                "Приближение: 0 — стандартное, + ближе, − дальше",
                profile.zoomDelta, -8, 8, 0.25, "");
        Switch fixedZoom = switchView(
                "Фиксировать масштаб — не менять его от скорости",
                profile.fixedZoomEnabled);
        form.addView(fixedZoom, marginTop(4));
        SliderField fixedZoomLevel = slider(form,
                "Фиксированное увеличение карты",
                profile.fixedZoomLevel, 2, 21, 0.25, "");
        Runnable updateFixedZoomControl = () -> {
            zoom.setEnabled(!fixedZoom.isChecked());
            fixedZoomLevel.setEnabled(fixedZoom.isChecked());
        };
        fixedZoom.setOnCheckedChangeListener(
                (button, checked) -> updateFixedZoomControl.run());
        updateFixedZoomControl.run();
        SliderField tilt = slider(form, "Наклон карты: 0° — сверху, 60° — перспектива",
                profile.tiltDegrees, 0, 80, 1, "°");
        SliderField focusX = slider(form, "Автомобиль по горизонтали",
                profile.focusXPercent, 0, 100, 1, " %");
        SliderField focusY = slider(form, "Автомобиль по вертикали",
                profile.focusYPercent, 0, 100, 1, " %");
        SliderField mapScale = slider(form,
                "Общий размер подписей и объектов основы карты",
                profile.mapScalePercent, 50, 300, 5, " %");
        addNavigationCameraPresets(form, cameraMode, zoom, tilt, focusX, focusY);
        SliderField maximumFps = slider(form,
                "Плавность камеры (рекомендуется 30)",
                profile.maximumFps, 5, 60, 1, " кадр/с");

        form.addView(section("Состав и цвет карты HUD"), marginTop(16));
        Spinner dayNight = dayNightSpinner(profile.automaticDayNight, profile.nightMode);
        form.addView(label("Оформление день / ночь"), marginTop(8));
        form.addView(dayNight);
        Switch showRoute = switchView("Маршрут", profile.showRoute);
        Switch destination = switchView(
                "Конечная точка маршрута", profile.showDestination);
        Switch showRouteTraffic = switchView(
                "Пробки на линии маршрута", profile.showRouteTraffic);
        Switch showTraffic = switchView("Пробки на остальных дорогах", profile.showTraffic);
        Switch showTrafficLights = switchView(
                "Светофоры с отсчётом — отдельный слой", profile.showTrafficLights);
        Switch showRouteTurns = switchView(
                "Стрелки поворотов прямо на линии маршрута", profile.showRouteTurns);
        Switch showLaneGuidance = switchView(
                "Подсказки по полосам — слой на маршруте", profile.showLaneGuidance);
        Switch showHudSpeedCameras = switchView(
                "Камеры из HUD Speed — отдельный знак", profile.showHudSpeedCameras);
        Switch showLabels = switchView(
                "Штатные названия улиц Яндекса", profile.showLabels);
        Switch showPois = switchView("Полезные места", profile.showPois);
        Switch showBuildings = switchView("Здания", profile.showBuildings);
        Switch showParks = switchView("Парки", profile.showParks);
        Switch showWater = switchView("Вода", profile.showWater);
        Switch showModels = switchView("3D-модели", profile.showModels);
        Switch showCursor = switchView("Курсор автомобиля", profile.showCursor);
        Switch roadsOnly = switchView(
                "Только дороги — прозрачный фон", profile.roadsOnly);
        for (Switch control : new Switch[]{showRoute, destination,
                showRouteTraffic, showTraffic, showTrafficLights,
                showRouteTurns, showLaneGuidance, showHudSpeedCameras,
                showLabels, showPois, showBuildings,
                showParks, showWater,
                showModels, showCursor, roadsOnly}) {
            form.addView(control, marginTop(4));
        }
        form.addView(text("Названия, шрифт, контур и изгиб текста рисует сам слой карты "
                + "Яндекса. Отдельных нарисованных плашек Natro больше нет.",
                12, 0xFF95A0AF), marginTop(4));
        Button roadEvents = button("Дорожные события — выбрать типы и режимы");
        roadEvents.setOnClickListener(view -> editHudRoadEvents(navigation, profile));
        form.addView(roadEvents, marginTop(10));
        form.addView(text("Для каждой отметки: скрыть, показывать всегда или только вдоль "
                + "активного маршрута. Скорость, тип и направление камер берутся только из "
                + "данных Яндекса или HUD Speed.",
                12, 0xFF95A0AF), marginTop(5));
        form.addView(section("Размер каждого слоя"), marginTop(16));
        SliderField cursorScale = slider(form, "Размер курсора",
                profile.cursorScalePercent, 25, 300, 5, " %");
        SliderField laneGuidanceScale = slider(form,
                "Размер знаков движения по полосам",
                profile.laneGuidanceScalePercent, 50, 250, 5, " %");
        SliderField cameraScale = slider(form,
                "Размер единых знаков камер",
                profile.cameraScalePercent, 50, 250, 5, " %");
        SliderField cameraDirectionScale = slider(form,
                "Размер полупрозрачного направления камер",
                profile.cameraDirectionScalePercent, 25, 300, 5, " %");
        SliderField cameraDirectionOpacity = slider(form,
                "Прозрачность направления камер",
                profile.cameraDirectionOpacityPercent, 0, 100, 5, " %");
        SliderField trafficLightScale = slider(form,
                "Размер светофоров и плашек секунд",
                profile.trafficLightScalePercent, 50, 250, 5, " %");
        SliderField routeTurnScale = slider(form,
                "Размер стрелок поворотов на маршруте",
                profile.routeTurnScalePercent, 50, 250, 5, " %");
        SliderField routeLabelScale = slider(form,
                "Размер штатных названий улиц",
                profile.routeLabelScalePercent, 50, 250, 5, " %");
        SliderField roadEventScale = slider(form,
                "Размер остальных дорожных событий",
                profile.roadEventScalePercent, 50, 250, 5, " %");
        SliderField destinationScale = slider(form,
                "Размер конечной точки маршрута",
                profile.destinationScalePercent, 50, 250, 5, " %");
        ColorField cursorColor = navigationColorField(form, "Цвет автомобиля",
                profile.cursorColor, navigation, value -> profile.cursorColor = value);
        ColorField cursorOutline = navigationColorField(form, "Контур автомобиля",
                profile.cursorOutlineColor, navigation,
                value -> profile.cursorOutlineColor = value);
        ColorField routeColor = navigationColorField(form, "Цвет маршрута",
                profile.routeColor, navigation, value -> profile.routeColor = value);
        ColorField routeOutline = navigationColorField(form, "Контур маршрута",
                profile.routeOutlineColor, navigation,
                value -> profile.routeOutlineColor = value);
        ColorField roadColor = navigationColorField(form,
                "Цвет дорог без маршрута и пробок", profile.roadColor,
                navigation, value -> profile.roadColor = value);
        SliderField routeWidthPercent = slider(form,
                "Толщина линии маршрута: 100% — как сейчас",
                profile.routeWidthPercent, 25, 300, 5, " %");
        SliderField roadWidthPercent = slider(form,
                "Толщина улиц без маршрута: 100% — как сейчас",
                profile.roadWidthPercent, 25, 300, 5, " %");
        SliderField routeOutlineWidth = slider(form, "Толщина контура маршрута",
                profile.routeOutlineWidth, 0, 20, 0.5, " px");
        form.addView(section("Порядок слоёв"), marginTop(16));
        Switch manualLayerPriorities = switchView(
                "Ручной порядок слоёв", profile.manualLayerPrioritiesEnabled);
        form.addView(manualLayerPriorities, marginTop(4));
        form.addView(text("Выключено: Яндекс автоматически разводит конфликтующие элементы. "
                + "Включено: большее значение ползунка располагает слой выше.",
                12, 0xFF95A0AF), marginTop(4));
        SliderField cameraDirectionLayerPriority = slider(form,
                "Знаки камер и их направления", profile.cameraDirectionLayerPriority,
                0, 100, 1, "");
        SliderField roadEventLayerPriority = slider(form,
                "Остальные дорожные события", profile.roadEventLayerPriority,
                0, 100, 1, "");
        SliderField routeLayerPriority = slider(form,
                "Маршрут", profile.routeLayerPriority, 0, 100, 1, "");
        SliderField destinationLayerPriority = slider(form,
                "Конечная точка маршрута", profile.destinationLayerPriority,
                0, 100, 1, "");
        SliderField trafficLightLayerPriority = slider(form,
                "Светофоры и секунды", profile.trafficLightLayerPriority,
                0, 100, 1, "");
        SliderField routeTurnLayerPriority = slider(form,
                "Стрелки поворотов на маршруте", profile.routeTurnLayerPriority,
                0, 100, 1, "");
        SliderField laneGuidanceLayerPriority = slider(form,
                "Знаки движения по полосам", profile.laneGuidanceLayerPriority,
                0, 100, 1, "");
        SliderField cursorLayerPriority = slider(form,
                "Курсор автомобиля", profile.cursorLayerPriority,
                0, 100, 1, "");
        SliderField[] layerPriorityControls = new SliderField[]{
                cameraDirectionLayerPriority, roadEventLayerPriority, routeLayerPriority,
                destinationLayerPriority, trafficLightLayerPriority,
                routeTurnLayerPriority,
                laneGuidanceLayerPriority, cursorLayerPriority};
        Runnable updateLayerPriorityControls = () -> {
            for (SliderField field : layerPriorityControls) {
                field.setEnabled(manualLayerPriorities.isChecked());
            }
        };
        manualLayerPriorities.setOnCheckedChangeListener(
                (button, checked) -> updateLayerPriorityControls.run());
        updateLayerPriorityControls.run();
        form.addView(section("Цвета загруженности дорог"), marginTop(16));
        ColorField trafficFreeColor = navigationColorField(form, "Дорога свободна",
                profile.trafficFreeColor, navigation,
                value -> profile.trafficFreeColor = value);
        ColorField trafficLightColor = navigationColorField(form, "Небольшое затруднение",
                profile.trafficLightColor, navigation,
                value -> profile.trafficLightColor = value);
        ColorField trafficHardColor = navigationColorField(form, "Плотное движение",
                profile.trafficHardColor, navigation,
                value -> profile.trafficHardColor = value);
        ColorField trafficVeryHardColor = navigationColorField(form, "Сильная пробка",
                profile.trafficVeryHardColor, navigation,
                value -> profile.trafficVeryHardColor = value);
        ColorField trafficBlockedColor = navigationColorField(form, "Дорога перекрыта",
                profile.trafficBlockedColor, navigation,
                value -> profile.trafficBlockedColor = value);
        ColorField trafficUnknownColor = navigationColorField(form, "Нет данных",
                profile.trafficUnknownColor, navigation,
                value -> profile.trafficUnknownColor = value);
        SliderField trafficGradient = slider(form, "Длина перехода цветов пробок",
                profile.trafficGradientLength, 0, 100, 1, " %");
        form.addView(text("Технические JSON-стили скрыты из обычных настроек. Цвета и состав "
                + "карты меняются элементами выше.", 12, 0xFF95A0AF), marginTop(12));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Независимая карта HUD")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        item.title = value(title);
                        item.x = x.intValue();
                        item.y = y.intValue();
                        item.width = width.intValue();
                        item.height = height.intValue();
                        item.enabled = elementEnabled.isChecked();
                        item.options.put("renderer", HudElementConfig.DIRECT_MAP_RENDERER);
                        item.options.put("cornerRadiusPx", radius.intValue());
                        item.options.put("opacityPercent", opacity.intValue());
                        item.options.put("transparentBackground", roadsOnly.isChecked());

                        profile.enabled = rendererEnabled.isChecked();
                        profile.cameraMode = navigationCameraModeValue(
                                cameraMode.getSelectedItemPosition());
                        profile.zoomDelta = zoom.value();
                        profile.fixedZoomEnabled = fixedZoom.isChecked();
                        profile.fixedZoomLevel = fixedZoomLevel.value();
                        profile.tiltDegrees = tilt.intValue();
                        profile.focusXPercent = focusX.intValue();
                        profile.focusYPercent = focusY.intValue();
                        profile.mapScalePercent = mapScale.intValue();
                        profile.maximumFps = maximumFps.intValue();
                        applyDayNight(dayNight, profile);
                        profile.showRoute = showRoute.isChecked();
                        profile.showDestination = destination.isChecked();
                        profile.showRouteTraffic = showRouteTraffic.isChecked();
                        profile.showTraffic = showTraffic.isChecked();
                        profile.showTrafficLights = showTrafficLights.isChecked();
                        profile.showRouteTurns = showRouteTurns.isChecked();
                        profile.showLaneGuidance = showLaneGuidance.isChecked();
                        profile.showHudSpeedCameras = showHudSpeedCameras.isChecked();
                        profile.showLabels = showLabels.isChecked();
                        profile.showPois = showPois.isChecked();
                        profile.showBuildings = showBuildings.isChecked();
                        profile.showParks = showParks.isChecked();
                        profile.showWater = showWater.isChecked();
                        profile.showModels = showModels.isChecked();
                        profile.showCursor = showCursor.isChecked();
                        profile.roadsOnly = roadsOnly.isChecked();
                        profile.cursorScalePercent = cursorScale.intValue();
                        profile.laneGuidanceScalePercent = laneGuidanceScale.intValue();
                        profile.cameraScalePercent = cameraScale.intValue();
                        profile.cameraDirectionScalePercent =
                                cameraDirectionScale.intValue();
                        profile.cameraDirectionOpacityPercent =
                                cameraDirectionOpacity.intValue();
                        profile.trafficLightScalePercent = trafficLightScale.intValue();
                        profile.routeTurnScalePercent = routeTurnScale.intValue();
                        profile.routeLabelScalePercent = routeLabelScale.intValue();
                        profile.roadEventScalePercent = roadEventScale.intValue();
                        profile.destinationScalePercent = destinationScale.intValue();
                        profile.manualLayerPrioritiesEnabled =
                                manualLayerPriorities.isChecked();
                        profile.cursorColor = cursorColor.value;
                        profile.cursorOutlineColor = cursorOutline.value;
                        profile.routeColor = routeColor.value;
                        profile.routeOutlineColor = routeOutline.value;
                        profile.roadColor = roadColor.value;
                        profile.routeWidthPercent = routeWidthPercent.intValue();
                        profile.roadWidthPercent = roadWidthPercent.intValue();
                        profile.routeOutlineWidth = routeOutlineWidth.value();
                        profile.cameraDirectionLayerPriority =
                                cameraDirectionLayerPriority.intValue();
                        profile.roadEventLayerPriority = roadEventLayerPriority.intValue();
                        profile.routeLayerPriority = routeLayerPriority.intValue();
                        profile.destinationLayerPriority =
                                destinationLayerPriority.intValue();
                        profile.trafficLightLayerPriority =
                                trafficLightLayerPriority.intValue();
                        profile.routeTurnLayerPriority =
                                routeTurnLayerPriority.intValue();
                        profile.laneGuidanceLayerPriority =
                                laneGuidanceLayerPriority.intValue();
                        profile.cursorLayerPriority = cursorLayerPriority.intValue();
                        profile.trafficFreeColor = trafficFreeColor.value;
                        profile.trafficLightColor = trafficLightColor.value;
                        profile.trafficHardColor = trafficHardColor.value;
                        profile.trafficVeryHardColor = trafficVeryHardColor.value;
                        profile.trafficBlockedColor = trafficBlockedColor.value;
                        profile.trafficUnknownColor = trafficUnknownColor.value;
                        profile.trafficGradientLength = trafficGradient.value();

                        item.normalize(config.gridColumns, config.gridRows);
                        navigation.normalize();
                        String encodedNavigation = navigation.toJson().toString();
                        if (!preferences.navigationIntegrationConfigJson.commit(
                                encodedNavigation)
                                || !encodedNavigation.equals(
                                preferences.navigationIntegrationConfigJson.get())) {
                            throw new IllegalStateException("не удалось записать настройки карты");
                        }
                        NavigationHudEndpointService.requestConfigurationRefresh(this);
                        config.normalize();
                        canvas.updateConfig(config);
                        updateSelection(item);
                        persist(false);
                        dialog.dismiss();
                    } catch (Exception error) {
                        Toast.makeText(this, "Проверьте параметры: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        showSafeDialog(dialog);
    }

    private void editHudRoadEvents(
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull NavigationIntegrationConfig.MapProfile profile) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(form);
        form.addView(text("Режим «Только с маршрутом» проверяет штатный признак MapKit "
                + "«на маршруте» для каждого события. Направление камеры отображается, "
                + "когда оно есть в данных события.",
                12, 0xFFB8C0CC));

        final String[] modes = {"Не показывать", "Всегда", "Только с маршрутом"};
        ArrayList<RoadEventModeControl> controls = new ArrayList<>();
        String lastGroup = "";
        for (NavigationIntegrationConfig.RoadEventSpec spec
                : NavigationIntegrationConfig.HUD_ROAD_EVENTS) {
            if (!spec.group.equals(lastGroup)) {
                form.addView(section(spec.group), marginTop(lastGroup.isEmpty() ? 12 : 18));
                lastGroup = spec.group;
            }
            form.addView(label(spec.title), marginTop(8));
            Spinner mode = spinner(modes, "");
            mode.setSelection(roadEventModePosition(profile.roadEventMode(spec.tag)));
            form.addView(mode);
            controls.add(new RoadEventModeControl(spec.tag, mode));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Дорожные события HUD")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    for (RoadEventModeControl control : controls) {
                        profile.setRoadEventMode(control.tag,
                                roadEventModeValue(control.spinner.getSelectedItemPosition()));
                    }
                    if (persistNavigationConfiguration(navigation)) dialog.dismiss();
                }));
        showSafeDialog(dialog);
    }

    private static int roadEventModePosition(
            @NonNull NavigationIntegrationConfig.RoadEventMode mode) {
        switch (mode) {
            case ALWAYS: return 1;
            case ROUTE_ONLY: return 2;
            case HIDDEN:
            default: return 0;
        }
    }

    @NonNull
    private static NavigationIntegrationConfig.RoadEventMode roadEventModeValue(int position) {
        if (position == 1) return NavigationIntegrationConfig.RoadEventMode.ALWAYS;
        if (position == 2) return NavigationIntegrationConfig.RoadEventMode.ROUTE_ONLY;
        return NavigationIntegrationConfig.RoadEventMode.HIDDEN;
    }

    private static final class RoadEventModeControl {
        @NonNull final String tag;
        @NonNull final Spinner spinner;

        RoadEventModeControl(@NonNull String tag, @NonNull Spinner spinner) {
            this.tag = tag;
            this.spinner = spinner;
        }
    }

    /** Main-map and floating-window profile; HUD map stays independent in its own element dialog. */
    private void editNavigatorIntegration() {
        NavigationIntegrationConfig navigation = loadNavigationIntegrationConfig();
        NavigationIntegrationConfig.MapProfile map = navigation.mainMap;
        NavigationIntegrationConfig.FloatingWindowProfile window =
                navigation.mainFloatingWindow;

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(form);

        form.addView(text("Основной экран использует штатные маршрут, стрелку и ведение камеры "
                + "Навигатора — это исключает вторую стрелку и прыжки. Здесь меняется только "
                + "безопасное оформление. Полностью отдельные маршрут, курсор и камера "
                + "настраиваются в элементе «Карта HUD».", 12, 0xFF95A0AF));
        Switch mapEnabled = switchView("Изменять оформление основной карты", map.enabled);
        form.addView(mapEnabled, marginTop(8));
        SliderField focusX = slider(form, "Точка фокуса по горизонтали",
                map.focusXPercent, 0, 100, 1, " %");
        SliderField focusY = slider(form, "Точка фокуса по вертикали",
                map.focusYPercent, 0, 100, 1, " %");
        SliderField mapScale = slider(form, "Размер подписей и объектов карты",
                map.mapScalePercent, 50, 300, 5, " %");
        SliderField maximumFps = slider(form, "Плавность основной карты",
                map.maximumFps, 5, 60, 1, " кадр/с");
        Spinner dayNight = dayNightSpinner(map.automaticDayNight, map.nightMode);
        form.addView(label("Оформление день / ночь"), marginTop(8));
        form.addView(dayNight);
        Switch showLabels = switchView("Подписи", map.showLabels);
        Switch showPois = switchView("Полезные места", map.showPois);
        Switch showBuildings = switchView("Здания", map.showBuildings);
        Switch showParks = switchView("Парки", map.showParks);
        Switch showWater = switchView("Вода", map.showWater);
        Switch showModels = switchView("3D-модели", map.showModels);
        for (Switch control : new Switch[]{showLabels,
                showPois, showBuildings, showParks, showWater, showModels}) {
            form.addView(control, marginTop(4));
        }
        form.addView(text("Маршрут, пробки и стрелка основной карты остаются штатными. Их "
                + "отдельное оформление доступно только для карты HUD.",
                12, 0xFF95A0AF), marginTop(12));

        form.addView(section("Плавающее окно Навигатора"), marginTop(18));
        Switch windowEnabled = switchView("Разрешить оконный режим", window.enabled);
        Switch windowLocked = switchView("Зафиксировать окно и скрыть обе ручки",
                window.movementLocked && window.resizeLocked);
        Switch aspectLocked = switchView("Зафиксировать пропорции",
                window.aspectRatioLocked);
        Switch rememberGeometry = switchView("Запоминать позицию и размер",
                window.rememberGeometry);
        for (Switch control : new Switch[]{windowEnabled, windowLocked,
                aspectLocked, rememberGeometry}) {
            form.addView(control, marginTop(4));
        }
        SliderField left = slider(form, "Позиция слева",
                window.leftPercent, 0, 100, 1, " %");
        SliderField top = slider(form, "Позиция сверху",
                window.topPercent, 0, 100, 1, " %");
        SliderField width = slider(form, "Ширина окна",
                window.widthPercent, 20, 100, 1, " %");
        SliderField height = slider(form, "Высота окна",
                window.heightPercent, 20, 100, 1, " %");
        SliderField corner = slider(form, "Скругление окна",
                window.cornerRadiusDp, 0, 160, 1, " dp");
        SliderField opacity = slider(form, "Непрозрачность окна",
                window.opacityPercent, 20, 100, 1, " %");
        form.addView(text("Фон снаружи карты всегда прозрачный — элементы главного экрана и "
                + "системные панели остаются видимыми вокруг окна.",
                12, 0xFF95A0AF), marginTop(8));
        SliderField borderWidth = slider(form, "Толщина рамки",
                window.borderWidthDp, 0, 24, 1, " dp");
        ColorField borderColor = colorField(form, "Цвет рамки", window.borderColor);
        SliderField shadowRadius = slider(form, "Радиус тени",
                window.shadowRadiusDp, 0, 96, 1, " dp");
        ColorField shadowColor = colorField(form, "Цвет тени", window.shadowColor);

        form.addView(section("Кнопки окна"), marginTop(16));
        Switch modeButtonVisible = switchView("Кнопка окно / полный экран",
                window.modeButtonVisible);
        Switch dragHandleVisible = switchView("Ручка перемещения",
                window.dragHandleVisible);
        Switch resizeHandleVisible = switchView("Ручка размера",
                window.resizeHandleVisible);
        Switch closeButtonVisible = switchView("Кнопка закрытия",
                window.closeButtonVisible);
        for (Switch control : new Switch[]{modeButtonVisible, dragHandleVisible,
                resizeHandleVisible, closeButtonVisible}) {
            form.addView(control, marginTop(4));
        }
        form.addView(text("Кнопка режима находится слева под штатными кнопками дорожного "
                + "события и голосового помощника. Она появляется по касанию карты и "
                + "скрывается вместе с ними в оконном и полноэкранном режимах.",
                12, 0xFFB8C0CC), marginTop(8));
        SliderField buttonSize = slider(form, "Размер кнопки",
                window.modeButtonSizeDp, 28, 96, 1, " dp");
        SliderField buttonOpacity = slider(form, "Непрозрачность кнопки",
                window.modeButtonOpacityPercent, 20, 100, 1, " %");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Основная карта и окно Навигатора")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        map.enabled = mapEnabled.isChecked();
                        map.focusXPercent = focusX.intValue();
                        map.focusYPercent = focusY.intValue();
                        map.mapScalePercent = mapScale.intValue();
                        map.maximumFps = maximumFps.intValue();
                        applyDayNight(dayNight, map);
                        map.showLabels = showLabels.isChecked();
                        map.showPois = showPois.isChecked();
                        map.showBuildings = showBuildings.isChecked();
                        map.showParks = showParks.isChecked();
                        map.showWater = showWater.isChecked();
                        map.showModels = showModels.isChecked();
                        window.enabled = windowEnabled.isChecked();
                        window.movementLocked = windowLocked.isChecked();
                        window.resizeLocked = windowLocked.isChecked();
                        window.aspectRatioLocked = aspectLocked.isChecked();
                        window.rememberGeometry = rememberGeometry.isChecked();
                        window.leftPercent = left.intValue();
                        window.topPercent = top.intValue();
                        window.widthPercent = width.intValue();
                        window.heightPercent = height.intValue();
                        window.cornerRadiusDp = corner.intValue();
                        window.opacityPercent = opacity.intValue();
                        window.borderWidthDp = borderWidth.intValue();
                        window.borderColor = borderColor.value;
                        window.shadowRadiusDp = shadowRadius.intValue();
                        window.shadowColor = shadowColor.value;
                        window.modeButtonVisible = modeButtonVisible.isChecked();
                        window.dragHandleVisible = dragHandleVisible.isChecked();
                        window.resizeHandleVisible = resizeHandleVisible.isChecked();
                        window.closeButtonVisible = closeButtonVisible.isChecked();
                        window.modeButtonSizeDp = buttonSize.intValue();
                        window.modeButtonOpacityPercent = buttonOpacity.intValue();

                        navigation.normalize();
                        String encodedNavigation = navigation.toJson().toString();
                        if (!preferences.navigationIntegrationConfigJson.commit(
                                encodedNavigation)
                                || !encodedNavigation.equals(
                                preferences.navigationIntegrationConfigJson.get())) {
                            throw new IllegalStateException(
                                    "не удалось проверить сохранённые настройки Навигатора");
                        }
                        NavigationHudEndpointService.requestConfigurationRefresh(this);
                        dialog.dismiss();
                        Toast.makeText(this, "Настройки Навигатора сохранены",
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Проверьте параметры: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        showSafeDialog(dialog);
    }

    @NonNull
    private NavigationIntegrationConfig loadNavigationIntegrationConfig() {
        String raw = preferences.navigationIntegrationConfigJson.get();
        if (raw == null || raw.trim().isEmpty()) return new NavigationIntegrationConfig();
        try {
            return NavigationIntegrationConfig.fromJson(raw);
        } catch (IllegalArgumentException invalid) {
            return new NavigationIntegrationConfig();
        }
    }

    @Nullable
    private HudElementConfig findMapElement() {
        for (HudElementConfig value : config.elements) {
            if (value.type == HudElementType.NAV_MAP) return value;
        }
        return null;
    }

    private void addVisualElementOptions(@NonNull LinearLayout form,
            @NonNull HudElementConfig item, @NonNull Map<String, Object> controls) {
        if (item.type.name().startsWith("NAV_")) {
            visualSwitch(form, controls, "bool:hideWhenInactive",
                    "Скрывать без активного маршрута",
                    item.options.optBoolean("hideWhenInactive", false));
            if (item.type != HudElementType.NAV_MANEUVER_ARROW
                    && item.type != HudElementType.NAV_LANES
                    && item.type != HudElementType.NAV_TRAFFIC_LIGHTS
                    && item.type != HudElementType.NAV_ROUTE_GRAPHIC) {
                visualSwitch(form, controls, "bool:hideWhenEmpty",
                        "Скрывать при отсутствии значения",
                        item.options.optBoolean("hideWhenEmpty", true));
            }
        }
        switch (item.type) {
            case CLOCK:
                visualSpinner(form, controls, "string:clockMode", "Формат часов",
                        new String[]{"SYSTEM", "24H", "12H"},
                        item.options.optString("clockMode", "SYSTEM"));
                break;
            case NAV_MANEUVER_ARROW:
                visualSwitch(form, controls, "bool:arrowAnimation",
                        "Анимация стрелки",
                        item.options.optBoolean("arrowAnimation", true));
                visualSwitch(form, controls, "bool:preferSourceImage",
                        "Использовать штатную графику манёвра",
                        item.options.optBoolean("preferSourceImage", true));
                visualSpinner(form, controls, "string:arrowLayout",
                        "Положение стрелки", new String[]{"LEFT", "RIGHT", "TOP", "BOTTOM"},
                        item.options.optString("arrowLayout", "LEFT"));
                break;
            case NAV_COMBINED:
                form.addView(text("Знак передаётся из Яндекс Навигатора как есть. "
                        + "Собственная стрелка для этой карточки не рисуется.",
                        12, 0xFF95A0AF), marginTop(5));
                visualSpinner(form, controls, "string:arrowLayout",
                        "Положение стрелки", new String[]{"LEFT", "RIGHT", "TOP", "BOTTOM"},
                        item.options.optString("arrowLayout", "LEFT"));
                visualSwitch(form, controls, "bool:showCardBackground",
                        "Синяя карточка",
                        item.options.optBoolean("showCardBackground", true));
                visualSwitch(form, controls, "bool:showRoadBadge",
                        "Номер дороги отдельной плашкой",
                        item.options.optBoolean("showRoadBadge", true));
                visualSwitch(form, controls, "bool:showDirection",
                        "Название дороги / направление",
                        item.options.optBoolean("showDirection", true));
                visualColor(form, controls, item, "cardColor",
                        "Цвет карточки ARGB", "#FF0758E8");
                visualColor(form, controls, item, "roadBadgeColor",
                        "Цвет номера дороги ARGB", "#FF16A34A");
                controls.put("int:cardOpacityPercent", slider(form,
                        "Непрозрачность карточки",
                        item.options.optInt("cardOpacityPercent", 94),
                        0, 100, 1, " %"));
                controls.put("int:cardCornerRadiusPx", slider(form,
                        "Скругление карточки",
                        item.options.optInt("cardCornerRadiusPx", 18),
                        0, 80, 1, " px"));
                visualColor(form, controls, item, "cardBorderColor",
                        "Цвет рамки карточки ARGB", "#00000000");
                controls.put("int:cardBorderWidthPx", slider(form,
                        "Толщина рамки карточки",
                        item.options.optInt("cardBorderWidthPx", 0),
                        0, 24, 1, " px"));

                form.addView(section("Компоновка карточки"), marginTop(14));
                controls.put("int:arrowAreaPercent", slider(form,
                        "Доля места для исходного знака",
                        item.options.optInt("arrowAreaPercent", 38),
                        10, 75, 1, " %"));
                controls.put("int:sourceIconScalePercent", slider(form,
                        "Размер исходного знака",
                        item.options.optInt("sourceIconScalePercent", 100),
                        25, 250, 5, " %"));
                controls.put("int:arrowTextGapPx", slider(form,
                        "Расстояние между знаком и текстом",
                        item.options.optInt("arrowTextGapPx", 6),
                        0, 80, 1, " px"));
                controls.put("int:paddingLeftPx", slider(form,
                        "Внутренний отступ карточки слева",
                        item.options.optInt("paddingLeftPx", 10),
                        0, 160, 1, " px"));
                controls.put("int:paddingTopPx", slider(form,
                        "Внутренний отступ карточки сверху",
                        item.options.optInt("paddingTopPx", 8),
                        0, 160, 1, " px"));
                controls.put("int:paddingRightPx", slider(form,
                        "Внутренний отступ карточки справа",
                        item.options.optInt("paddingRightPx", 10),
                        0, 160, 1, " px"));
                controls.put("int:paddingBottomPx", slider(form,
                        "Внутренний отступ карточки снизу",
                        item.options.optInt("paddingBottomPx", 8),
                        0, 160, 1, " px"));

                form.addView(section("Отступы исходного знака"), marginTop(14));
                addCombinedPaddingControls(form, controls, item, "arrowPadding",
                        3, 120);
                form.addView(section("Отступы текстового блока"), marginTop(14));
                addCombinedPaddingControls(form, controls, item, "textPadding",
                        0, 120);

                form.addView(section("Шрифты и строки"), marginTop(14));
                controls.put("int:distanceFontSizeSp", slider(form,
                        "Размер шрифта расстояния",
                        item.options.optInt("distanceFontSizeSp", item.fontSizeSp),
                        8, 160, 1, " sp"));
                controls.put("int:roadBadgeFontSizeSp", slider(form,
                        "Размер шрифта номера дороги",
                        item.options.optInt("roadBadgeFontSizeSp", 17),
                        8, 120, 1, " sp"));
                controls.put("int:directionFontSizeSp", slider(form,
                        "Размер шрифта улицы / направления",
                        item.options.optInt("directionFontSizeSp", 18),
                        8, 120, 1, " sp"));
                controls.put("int:distanceAreaPercent", slider(form,
                        "Высота строки расстояния",
                        item.options.optInt("distanceAreaPercent", 56),
                        20, 80, 1, " %"));
                controls.put("int:textRowGapPx", slider(form,
                        "Интервал между строками",
                        item.options.optInt("textRowGapPx", 2),
                        0, 60, 1, " px"));
                controls.put("int:roadBadgePaddingHorizontalPx", slider(form,
                        "Отступ номера дороги по горизонтали",
                        item.options.optInt("roadBadgePaddingHorizontalPx", 5),
                        0, 60, 1, " px"));
                controls.put("int:roadBadgePaddingVerticalPx", slider(form,
                        "Отступ номера дороги по вертикали",
                        item.options.optInt("roadBadgePaddingVerticalPx", 2),
                        0, 40, 1, " px"));
                break;
            case NAV_LANES:
                visualSwitch(form, controls, "bool:preferSourceImage",
                        "Использовать штатную графику полос",
                        item.options.optBoolean("preferSourceImage", true));
                visualInt(form, controls, "int:laneThresholdMeters",
                        "Показывать полосы ближе, м",
                        item.options.optInt("laneThresholdMeters", 700));
                visualSpinner(form, controls, "string:laneDistancePosition",
                        "Расстояние до полос", new String[]{"BOTTOM", "TOP", "OFF"},
                        item.options.optString("laneDistancePosition", "BOTTOM"));
                visualColor(form, controls, item, "highlightColor",
                        "Цвет рекомендуемой полосы", "#FF34C759");
                break;
            case NAV_SPEED_LIMIT:
                visualSwitch(form, controls, "bool:whiteSign",
                        "Белый фон знака", item.options.optBoolean("whiteSign", true));
                visualSwitch(form, controls, "bool:routeOnly",
                        "Только при активном маршруте",
                        item.options.optBoolean("routeOnly", false));
                visualSwitch(form, controls, "bool:onlyWhenExceeded",
                        "Показывать только при превышении",
                        item.options.optBoolean("onlyWhenExceeded", false));
                visualInt(form, controls, "int:overspeedDelta",
                        "Допуск превышения, км/ч",
                        item.options.optInt("overspeedDelta", 10));
                visualSwitch(form, controls, "bool:overspeedBlink",
                        "Мигать при превышении",
                        item.options.optBoolean("overspeedBlink", true));
                break;
            case NAV_TRAFFIC_LIGHTS:
                visualSpinner(form, controls, "string:style", "Стиль светофора",
                        new String[]{"CAPSULE", "CLASSIC"},
                        item.options.optString("style", "CAPSULE"));
                visualSpinner(form, controls, "string:orientation", "Ориентация",
                        new String[]{"VERTICAL", "HORIZONTAL"},
                        item.options.optString("orientation", "VERTICAL"));
                visualSpinner(form, controls, "string:countdownSide",
                        "Положение обратного отсчёта",
                        new String[]{"BOTTOM", "TOP", "LEFT", "RIGHT"},
                        item.options.optString("countdownSide", "BOTTOM"));
                visualSwitch(form, controls, "bool:showFrame",
                        "Рамка светофора", item.options.optBoolean("showFrame", true));
                visualSwitch(form, controls, "bool:arrowAnimation",
                        "Анимация стрелки",
                        item.options.optBoolean("arrowAnimation", true));
                visualColor(form, controls, item, "redColor",
                        "Красный сигнал ARGB", "#FFFF3B30");
                visualColor(form, controls, item, "yellowColor",
                        "Жёлтый сигнал ARGB", "#FFFFCC00");
                visualColor(form, controls, item, "greenColor",
                        "Зелёный сигнал ARGB", "#FF34C759");
                visualColor(form, controls, item, "unknownColor",
                        "Нет данных ARGB", "#FF6B7280");
                break;
            case NAV_TRIP_PROGRESS:
                visualSpinner(form, controls, "string:progressMode", "Данные прогресса",
                        new String[]{"COMBINED", "DISTANCE", "TIME", "ARRIVAL"},
                        item.options.optString("progressMode", "COMBINED"));
                visualSpinner(form, controls, "string:orientation", "Ориентация",
                        new String[]{"HORIZONTAL", "VERTICAL"},
                        item.options.optString("orientation", "HORIZONTAL"));
                break;
            case NAV_JAM_PROGRESS:
                visualSpinner(form, controls, "string:orientation", "Ориентация",
                        new String[]{"HORIZONTAL", "VERTICAL"},
                        item.options.optString("orientation", "HORIZONTAL"));
                addTrafficPaletteOptions(form, controls, item);
                break;
            case NAV_ROUTE_GRAPHIC:
                addTrafficPaletteOptions(form, controls, item);
                break;
            default:
                break;
        }
    }

    private void visualSwitch(LinearLayout form, Map<String, Object> controls, String key,
            String title, boolean checked) {
        Switch control = switchView(title, checked);
        form.addView(control, marginTop(4));
        controls.put(key, control);
    }

    private void addCombinedPaddingControls(@NonNull LinearLayout form,
            @NonNull Map<String, Object> controls, @NonNull HudElementConfig item,
            @NonNull String prefix, int fallback, int maximum) {
        String[] keys = {"LeftPx", "TopPx", "RightPx", "BottomPx"};
        String[] labels = {"Слева", "Сверху", "Справа", "Снизу"};
        for (int index = 0; index < keys.length; index++) {
            String key = prefix + keys[index];
            controls.put("int:" + key, slider(form, labels[index],
                    item.options.optInt(key, fallback), 0, maximum, 1, " px"));
        }
    }

    private void visualInt(LinearLayout form, Map<String, Object> controls, String key,
            String title, int value) {
        boolean distance = key.endsWith("laneThresholdMeters");
        controls.put(key, slider(form, title, value,
                0, distance ? 2_000 : 50, distance ? 50 : 1,
                distance ? " м" : " км/ч"));
    }

    private void visualColor(LinearLayout form, Map<String, Object> controls,
            HudElementConfig item, String key, String title, String fallback) {
        controls.put("color:" + key, field(form, title,
                optionColorText(item, key, fallback), false));
    }

    private void addTrafficPaletteOptions(LinearLayout form, Map<String, Object> controls,
            HudElementConfig item) {
        visualColor(form, controls, item, "freeColor", "Свободно ARGB", "#FF34C759");
        visualColor(form, controls, item, "lightColor", "Небольшая пробка ARGB", "#FFFFCC00");
        visualColor(form, controls, item, "hardColor", "Затруднение ARGB", "#FFFF3B30");
        visualColor(form, controls, item, "veryHardColor", "Тяжёлая пробка ARGB", "#FFB00020");
        visualColor(form, controls, item, "blockedColor", "Перекрыто ARGB", "#FF7A1FA2");
        visualColor(form, controls, item, "unknownColor", "Нет данных ARGB", "#FF8E8E93");
    }

    @NonNull
    private static String optionColorText(HudElementConfig item, String key, String fallback) {
        Object value = item.options.opt(key);
        if (value instanceof Number) {
            return String.format(Locale.ROOT, "#%08X", ((Number) value).intValue());
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private void visualSpinner(LinearLayout form, Map<String, Object> controls, String key,
            String title, String[] choices, String selected) {
        form.addView(label(title), marginTop(8));
        Spinner control = spinner(choices, selected);
        form.addView(control);
        controls.put(key, control);
    }

    private static void applyVisualElementOptions(JSONObject output,
            Map<String, Object> controls) throws JSONException {
        for (Map.Entry<String, Object> entry : controls.entrySet()) {
            String encoded = entry.getKey();
            int separator = encoded.indexOf(':');
            if (separator <= 0 || separator >= encoded.length() - 1) continue;
            String kind = encoded.substring(0, separator);
            String key = encoded.substring(separator + 1);
            Object control = entry.getValue();
            if ("bool".equals(kind) && control instanceof Switch) {
                output.put(key, ((Switch) control).isChecked());
            } else if ("int".equals(kind) && control instanceof SliderField) {
                output.put(key, ((SliderField) control).intValue());
            } else if ("color".equals(kind) && control instanceof EditText) {
                String color = value((EditText) control);
                Color.parseColor(color);
                output.put(key, color);
            } else if ("string".equals(kind) && control instanceof Spinner) {
                output.put(key, String.valueOf(((Spinner) control).getSelectedItem()));
            }
        }
    }

    /** Configures a real geometry container; it never paints its own surface or shadow. */
    private void editHorizontalGroup(@NonNull HudElementConfig group) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(form);

        EditText title = field(form, "Название", group.title, false);
        form.addView(section("Положение и размер фрейма"), marginTop(12));
        SliderField x = slider(form, "Слева", group.x,
                0, Math.max(0, config.gridColumns - 1), 1, " яч.");
        SliderField y = slider(form, "Сверху", group.y,
                0, Math.max(0, config.gridRows - 1), 1, " яч.");
        SliderField width = slider(form, "Ширина", group.width,
                1, Math.max(1, config.gridColumns), 1, " яч.");
        SliderField height = slider(form, "Высота", group.height,
                1, Math.max(1, config.gridRows), 1, " яч.");

        form.addView(section("Отступы"), marginTop(14));
        SliderField gap = slider(form, "Между элементами",
                HudHorizontalGroup.gapPx(group), 0, 200, 1, " px");
        SliderField paddingLeft = slider(form, "Внутри слева",
                HudHorizontalGroup.paddingLeftPx(group), 0, 200, 1, " px");
        SliderField paddingTop = slider(form, "Внутри сверху",
                HudHorizontalGroup.paddingTopPx(group), 0, 200, 1, " px");
        SliderField paddingRight = slider(form, "Внутри справа",
                HudHorizontalGroup.paddingRightPx(group), 0, 200, 1, " px");
        SliderField paddingBottom = slider(form, "Внутри снизу",
                HudHorizontalGroup.paddingBottomPx(group), 0, 200, 1, " px");
        SliderField marginLeft = slider(form, "Снаружи слева",
                HudHorizontalGroup.marginLeftPx(group), 0, 200, 1, " px");
        SliderField marginTop = slider(form, "Снаружи сверху",
                HudHorizontalGroup.marginTopPx(group), 0, 200, 1, " px");
        SliderField marginRight = slider(form, "Снаружи справа",
                HudHorizontalGroup.marginRightPx(group), 0, 200, 1, " px");
        SliderField marginBottom = slider(form, "Снаружи снизу",
                HudHorizontalGroup.marginBottomPx(group), 0, 200, 1, " px");

        Spinner distribution = spinner(new String[]{"Компактно", "Равные ячейки"},
                HudHorizontalGroup.distribution(group) == 1
                        ? "Равные ячейки" : "Компактно");
        form.addView(label("Распределение"), marginTop(10));
        form.addView(distribution);
        Spinner horizontal = spinner(new String[]{"Слева", "По центру", "Справа"},
                horizontalGroupAlignmentLabel(
                        HudHorizontalGroup.horizontalAlignment(group)));
        form.addView(label("Положение содержимого по горизонтали"), marginTop(10));
        form.addView(horizontal);
        Spinner vertical = spinner(new String[]{"Сверху", "По центру", "Снизу"},
                verticalGroupAlignmentLabel(HudHorizontalGroup.verticalAlignment(group)));
        form.addView(label("Выравнивание элементов по вертикали"), marginTop(10));
        form.addView(vertical);
        Switch itemEnabled = switchView("Показывать группу", group.enabled);
        form.addView(itemEnabled, marginTop(8));

        form.addView(section("Элементы слева направо"), marginTop(16));
        form.addView(text("Отметьте элементы. Стрелками меняется порядок; кнопка ⚙ открывает "
                + "индивидуальные настройки. Размеры текста при растяжении ряда не меняются.",
                12, 0xFF95A0AF), marginTop(4));
        List<HudElementConfig> candidates = hudHorizontalGroupCandidates(group);
        Map<String, Boolean> selected = new LinkedHashMap<>();
        List<String> existing = HudHorizontalGroup.memberIds(group);
        for (HudElementConfig candidate : candidates) {
            selected.put(candidate.id, existing.contains(candidate.id));
        }
        LinearLayout memberRows = column();
        form.addView(memberRows, marginTop(6));
        rebuildHudHorizontalGroupMembers(memberRows, candidates, selected);
        form.addView(text("Группа прозрачна. Для цвета, рамки и скругления добавьте отдельную "
                + "подложку HUD. Тень на HUD не используется.",
                12, 0xFF95A0AF), marginTop(10));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Горизонтальный ряд HUD")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ArrayList<String> memberIds = new ArrayList<>();
                        for (HudElementConfig candidate : candidates) {
                            if (Boolean.TRUE.equals(selected.get(candidate.id))) {
                                memberIds.add(candidate.id);
                            }
                        }
                        if (memberIds.size() < 2) {
                            Toast.makeText(this, "Выберите минимум два элемента",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        group.title = value(title);
                        group.x = x.intValue();
                        group.y = y.intValue();
                        group.width = width.intValue();
                        group.height = height.intValue();
                        group.enabled = itemEnabled.isChecked();
                        HudHorizontalGroup.setMemberIds(group, memberIds);
                        putHudGroupOption(group, "gapPx", gap.intValue());
                        putHudGroupOption(group, "paddingLeftPx", paddingLeft.intValue());
                        putHudGroupOption(group, "paddingTopPx", paddingTop.intValue());
                        putHudGroupOption(group, "paddingRightPx", paddingRight.intValue());
                        putHudGroupOption(group, "paddingBottomPx", paddingBottom.intValue());
                        putHudGroupOption(group, "marginLeftPx", marginLeft.intValue());
                        putHudGroupOption(group, "marginTopPx", marginTop.intValue());
                        putHudGroupOption(group, "marginRightPx", marginRight.intValue());
                        putHudGroupOption(group, "marginBottomPx", marginBottom.intValue());
                        putHudGroupOption(group, "distribution",
                                distribution.getSelectedItemPosition());
                        putHudGroupOption(group, "horizontalAlignment",
                                horizontal.getSelectedItemPosition());
                        putHudGroupOption(group, "verticalAlignment",
                                vertical.getSelectedItemPosition());
                        group.normalize(config.gridColumns, config.gridRows);
                        config.normalize();
                        canvas.updateConfig(config);
                        updateSelection(group);
                        persist(false);
                        dialog.dismiss();
                    } catch (RuntimeException error) {
                        Toast.makeText(this, "Проверьте параметры: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));
        showSafeDialog(dialog);
    }

    @NonNull
    private List<HudElementConfig> hudHorizontalGroupCandidates(
            @NonNull HudElementConfig editedGroup) {
        ArrayList<HudElementConfig> result = new ArrayList<>();
        List<String> current = HudHorizontalGroup.memberIds(editedGroup);
        for (String id : current) {
            HudElementConfig value = findElement(id);
            if (value != null && value.type != HudElementType.BACKDROP
                    && value.type != HudElementType.HORIZONTAL_GROUP
                    && value.type != HudElementType.NAV_MAP) {
                result.add(value);
            }
        }
        for (HudElementConfig value : config.elements) {
            if (value.id.equals(editedGroup.id) || result.contains(value)
                    || value.type == HudElementType.BACKDROP
                    || value.type == HudElementType.HORIZONTAL_GROUP
                    || value.type == HudElementType.NAV_MAP
                    || belongsToOtherHudHorizontalGroup(value.id, editedGroup.id)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    private boolean belongsToOtherHudHorizontalGroup(@NonNull String memberId,
                                                     @NonNull String editedGroupId) {
        for (HudElementConfig value : config.elements) {
            if (value.type == HudElementType.HORIZONTAL_GROUP
                    && !value.id.equals(editedGroupId)
                    && HudHorizontalGroup.memberIds(value).contains(memberId)) {
                return true;
            }
        }
        return false;
    }

    private void rebuildHudHorizontalGroupMembers(
            @NonNull LinearLayout container,
            @NonNull List<HudElementConfig> candidates,
            @NonNull Map<String, Boolean> selected) {
        container.removeAllViews();
        for (int index = 0; index < candidates.size(); index++) {
            HudElementConfig candidate = candidates.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Switch included = switchView(candidate.title,
                    Boolean.TRUE.equals(selected.get(candidate.id)));
            included.setOnCheckedChangeListener((button, checked) ->
                    selected.put(candidate.id, checked));
            row.addView(included, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button up = button("↑");
            up.setEnabled(index > 0);
            int from = index;
            up.setOnClickListener(view -> {
                Collections.swap(candidates, from, from - 1);
                rebuildHudHorizontalGroupMembers(container, candidates, selected);
            });
            row.addView(up, fixed(48));
            Button down = button("↓");
            down.setEnabled(index + 1 < candidates.size());
            down.setOnClickListener(view -> {
                Collections.swap(candidates, from, from + 1);
                rebuildHudHorizontalGroupMembers(container, candidates, selected);
            });
            row.addView(down, fixed(48));
            Button configure = button("⚙");
            configure.setOnClickListener(view -> editElement(candidate));
            row.addView(configure, fixed(54));
            container.addView(row);
        }
    }

    @Nullable
    private HudElementConfig findElement(@NonNull String id) {
        for (HudElementConfig value : config.elements) {
            if (id.equals(value.id)) return value;
        }
        return null;
    }

    private static void putHudGroupOption(@NonNull HudElementConfig group,
                                          @NonNull String key, int value) {
        try {
            group.options.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @NonNull
    private static String horizontalGroupAlignmentLabel(int value) {
        return value == 1 ? "По центру" : value == 2 ? "Справа" : "Слева";
    }

    @NonNull
    private static String verticalGroupAlignmentLabel(int value) {
        return value == 1 ? "По центру" : value == 2 ? "Снизу" : "Сверху";
    }

    private void editBackdrop(@NonNull HudElementConfig item) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(form);

        EditText title = field(form, "Название", item.title, false);
        form.addView(section("Положение и размер"), marginTop(12));
        SliderField x = slider(form, "Слева", item.x,
                0, Math.max(0, config.gridColumns - 1), 1, " яч.");
        SliderField y = slider(form, "Сверху", item.y,
                0, Math.max(0, config.gridRows - 1), 1, " яч.");
        SliderField width = slider(form, "Ширина", item.width,
                1, Math.max(1, config.gridColumns), 1, " яч.");
        SliderField height = slider(form, "Высота", item.height,
                1, Math.max(1, config.gridRows), 1, " яч.");

        EditText color = field(form, "Цвет подложки", item.backgroundColor, false);
        SliderField opacity = slider(form, "Непрозрачность заливки",
                item.backgroundOpacityPercent, 0, 100, 1, " %");
        SliderField corner = slider(form, "Скругление",
                item.cornerRadiusPx, 0, 80, 1, " px");
        EditText borderColor = field(form, "Цвет рамки", item.borderColor, false);
        SliderField borderOpacity = slider(form, "Непрозрачность рамки",
                item.borderOpacityPercent, 0, 100, 1, " %");
        SliderField borderWidth = slider(form, "Толщина рамки",
                item.borderWidthPx, 0, 20, 1, " px");
        Switch itemEnabled = switchView("Показывать подложку", item.enabled);
        form.addView(itemEnabled, marginTop(8));
        form.addView(text("Подложка всегда рисуется ниже всех HUD-виджетов. "
                + "Тень на HUD отключена и в настройках отсутствует.",
                12, 0xFF95A0AF), marginTop(6));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Подложка HUD")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    item.title = value(title);
                    item.x = x.intValue();
                    item.y = y.intValue();
                    item.width = width.intValue();
                    item.height = height.intValue();
                    item.backgroundColor = value(color);
                    item.backgroundOpacityPercent = opacity.intValue();
                    item.cornerRadiusPx = corner.intValue();
                    item.borderColor = value(borderColor);
                    item.borderOpacityPercent = borderOpacity.intValue();
                    item.borderWidthPx = borderWidth.intValue();
                    item.enabled = itemEnabled.isChecked();
                    item.normalize(config.gridColumns, config.gridRows);
                    canvas.updateConfig(config);
                    updateSelection(item);
                    persist(false);
                    dialog.dismiss();
                }));
        showSafeDialog(dialog);
    }

    private void editGlobalOptions() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(form);
        SliderField columns = slider(form, "Колонки сетки",
                config.gridColumns, 4, 200, 1, "");
        SliderField rows = slider(form, "Строки сетки",
                config.gridRows, 2, 100, 1, "");
        TextView hardwareBounds = text("Аппаратная область (зафиксирована): "
                + HudViewportPolicy.SAFE_WIDTH + "×" + HudViewportPolicy.SAFE_HEIGHT
                + " px, X=" + HudViewportPolicy.SAFE_LEFT
                + ", Y=" + HudViewportPolicy.SAFE_TOP + ".\n"
                + "Полная поверхность выбранного Display ID проверяется во время работы. "
                + "Панель и каждый виджет жёстко обрезаются по этой области.",
                13, 0xFFFFCC66);
        form.addView(hardwareBounds, marginTop(10));
        form.addView(text("Фон панели всегда полностью прозрачный. Непрозрачными могут быть "
                + "только явно добавленные подложки и сама карта.",
                12, 0xFFB8C0CC), marginTop(10));
        SliderField brightness = slider(form, "Общая яркость",
                config.globalBrightness, 0, 100, 1, " %");
        EditText globalColor = field(form, "Общий цвет текста", config.globalTextColor, false);
        EditText globalUnit = field(form, "Общий цвет единиц", config.globalUnitColor, false);
        SliderField fontWeight = slider(form, "Общая насыщенность шрифта",
                config.globalFontWeight, 100, 900, 100, "");
        EditText fontUri = field(form, "URI пользовательского шрифта",
                config.customFontUri, false);
        SliderField navThreshold = slider(form, "Показывать навигацию до расстояния",
                config.navigationDisplayThresholdMeters, 0, 5_000, 100, " м");
        SliderField navDelay = slider(form, "Задержка скрытия навигации",
                config.navigationHideDelaySeconds, 0, 60, 1, " с");
        Switch showGrid = switchView("Показывать сетку в редакторе", config.showGrid);
        Switch free = switchView("Свободное перемещение между линиями", config.freeMovement);
        Switch snow = switchView("Снежный режим", config.snowMode);
        Switch sync = switchView("Один цвет для всех элементов", config.syncElementColors);
        Switch autostart = switchView("Запускать HUD после перезагрузки",
                preferences.hudPanelAutostart.get());
        form.addView(showGrid, marginTop(8));
        form.addView(free);
        Button stockHud = button("Штатные режимы и разделы HUD");
        stockHud.setOnClickListener(view -> editStockHudControls());
        form.addView(stockHud, marginTop(10));
        form.addView(snow);
        form.addView(sync);
        form.addView(autostart);
        Button chooseFont = button("Выбрать файл шрифта");
        chooseFont.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(intent, PICK_FONT);
        });
        form.addView(chooseFont, marginTop(10));
        Button reset = button("Сбросить HUD к исходной раскладке");
        reset.setOnClickListener(view -> confirmReset());
        form.addView(reset, marginTop(12));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Параметры HUD")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    config.gridColumns = columns.intValue();
                    config.gridRows = rows.intValue();
                    config.backgroundMode = "TRANSPARENT";
                    config.globalBrightness = brightness.intValue();
                    config.globalTextColor = value(globalColor);
                    config.globalUnitColor = value(globalUnit);
                    config.globalFontWeight = fontWeight.intValue();
                    config.customFontUri = value(fontUri);
                    config.navigationDisplayThresholdMeters = navThreshold.intValue();
                    config.navigationHideDelaySeconds = navDelay.intValue();
                    config.showGrid = showGrid.isChecked();
                    config.freeMovement = free.isChecked();
                    config.snowMode = snow.isChecked();
                    config.syncElementColors = sync.isChecked();
                    preferences.hudPanelAutostart.set(autostart.isChecked());
                    config.normalize();
                    canvas.updateConfig(config);
                    persist(false);
                    dialog.dismiss();
                }));
        showSafeDialog(dialog);
    }

    private void editStockHudControls() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(22));
        scroll.addView(form);

        form.addView(section("ProfileTransfer · CB33278"));
        form.addView(text(
                "Четыре штатных режима, найденные в ECARX: 0 Guide, 1 Drive, 2 AR, "
                        + "3 Simple. Команда меняет только выбранный режим и не передаёт "
                        + "нулевую HUD-маску.",
                12, 0xFFB8C0CC), marginTop(5));
        String[] modeChoices = {
                "Не менять",
                "0 · Guide",
                "1 · Drive",
                "2 · AR",
                "3 · Simple"
        };
        Spinner mode = spinner(modeChoices, "Не менять");
        int savedMode = preferences.hudStockProfileMode.get();
        mode.setSelection(savedMode >= 0 && savedMode <= 3 ? savedMode + 1 : 0);
        form.addView(mode, marginTop(7));
        Switch autoRepeat = switchView(
                "Резервный автоповтор выбранного режима",
                preferences.hudStockProfileModeAutoRepeat.get());
        form.addView(autoRepeat, marginTop(6));
        form.addView(text(
                "Автоповтор срабатывает после загрузки, смены профиля, переходов HUD/ADAS "
                        + "и пересечения 20 км/ч. Не чаще 5 записей в минуту, с circuit breaker. "
                        + "Передаётся только CB33278 со значением 0…3.",
                12, 0xFFFFCC66), marginTop(4));

        form.addView(section("Разделы штатного HUD"), marginTop(14));
        form.addView(text(
                "Отдельный штатный путь ICarFunction.setFunctionValue. На прошивках, где "
                        + "раздел недоступен, приложение покажет фактический отказ ECARX.",
                12, 0xFFB8C0CC), marginTop(4));
        boolean originalDrive = preferences.hudStockDriveEnvironment.get();
        boolean originalSafety = preferences.hudStockSafety.get();
        boolean originalMedia = preferences.hudStockMedia.get();
        boolean originalNavigation = preferences.hudStockNavigation.get();
        boolean originalPhone = preferences.hudStockPhone.get();
        Switch drive = switchView("Drive Environment · машинка и окружение", originalDrive);
        Switch safety = switchView("Safety · скорость и безопасность", originalSafety);
        Switch media = switchView("Media · музыка", originalMedia);
        Switch navigation = switchView("Navigation · навигация", originalNavigation);
        Switch phone = switchView("Phone · телефон", originalPhone);
        final boolean[] forceAllCategories = {false};
        form.addView(drive, marginTop(5));
        form.addView(safety);
        form.addView(media);
        form.addView(navigation);
        form.addView(phone);
        Button restore = button("Включить все пять разделов");
        restore.setOnClickListener(view -> {
            forceAllCategories[0] = true;
            drive.setChecked(true);
            safety.setChecked(true);
            media.setChecked(true);
            navigation.setChecked(true);
            phone.setChecked(true);
        });
        form.addView(restore, marginTop(8));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Штатный HUD ECARX")
                .setView(scroll)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int selectedMode = mode.getSelectedItemPosition() - 1;
                    boolean repeat = selectedMode >= 0 && autoRepeat.isChecked();
                    preferences.hudStockProfileMode.set(selectedMode);
                    preferences.hudStockProfileModeAutoRepeat.set(repeat);
                    CarIntegration integration = CarIntegrations.get(this);
                    if (selectedMode >= 0) {
                        integration.setStockHudProfileMode(selectedMode, repeat,
                                (success, message) -> showStockHudResult(
                                        "Режим " + selectedMode, success, message));
                    } else {
                        integration.stopStockHudProfileModeAutoRepeat(
                                (success, message) -> {
                                    if (!success) showStockHudResult(
                                            "Автоповтор", false, message);
                                });
                    }

                    applyStockHudCategoryIfChanged(integration,
                            CarIntegration.StockHudDisplayCategory.DRIVE_ENVIRONMENT,
                            originalDrive, drive.isChecked(), forceAllCategories[0],
                            preferences.hudStockDriveEnvironment);
                    applyStockHudCategoryIfChanged(integration,
                            CarIntegration.StockHudDisplayCategory.SAFETY,
                            originalSafety, safety.isChecked(), forceAllCategories[0],
                            preferences.hudStockSafety);
                    applyStockHudCategoryIfChanged(integration,
                            CarIntegration.StockHudDisplayCategory.MEDIA,
                            originalMedia, media.isChecked(), forceAllCategories[0],
                            preferences.hudStockMedia);
                    applyStockHudCategoryIfChanged(integration,
                            CarIntegration.StockHudDisplayCategory.NAVIGATION,
                            originalNavigation, navigation.isChecked(), forceAllCategories[0],
                            preferences.hudStockNavigation);
                    applyStockHudCategoryIfChanged(integration,
                            CarIntegration.StockHudDisplayCategory.PHONE,
                            originalPhone, phone.isChecked(), forceAllCategories[0],
                            preferences.hudStockPhone);
                    dialog.dismiss();
                }));
        showSafeDialog(dialog);
    }

    private void applyStockHudCategoryIfChanged(
            @NonNull CarIntegration integration,
            @NonNull CarIntegration.StockHudDisplayCategory category,
            boolean original, boolean desired, boolean force,
            @NonNull Preferences.Bool preference) {
        preference.set(desired);
        if (!force && original == desired) return;
        integration.setStockHudDisplayCategory(category, desired, (success, message) -> {
            if (!success) showStockHudResult(category.name(), false, message);
        });
    }

    private void showStockHudResult(String operation, boolean success,
                                    @Nullable String message) {
        String detail = message == null || message.trim().isEmpty()
                ? (success ? "команда принята" : "ECARX не подтвердил команду")
                : message.trim();
        Toast.makeText(getApplicationContext(),
                operation + ": " + detail, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("Сбросить HUD?")
                .setMessage("Будут заменены сетка и все элементы HUD. Остальные панели "
                        + "приложения не изменятся.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    String displayUniqueId = config.displayUniqueId;
                    int displayId = config.displayId;
                    String displayName = config.displayName;
                    int displayWidth = config.displayWidth;
                    int displayHeight = config.displayHeight;
                    config = HudPanelConfig.defaults();
                    config.displayUniqueId = displayUniqueId;
                    config.displayId = displayId;
                    config.displayName = displayName;
                    config.displayWidth = displayWidth;
                    config.displayHeight = displayHeight;
                    canvas.updateConfig(config);
                    canvas.select(null);
                    persist(false);
                }).setNegativeButton("Отмена", null).show();
    }

    private void duplicateSelected() {
        HudElementConfig source = canvas.selected();
        if (source == null) return;
        if (source.type == HudElementType.NAV_MAP) {
            Toast.makeText(this, "Для одного HUD доступна только одна карта",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            HudElementConfig copy = HudElementConfig.fromJson(source.toJson(),
                    config.gridColumns, config.gridRows);
            int suffix = 2;
            String base = source.id;
            while (containsId(base + "_" + suffix)) suffix++;
            copy.id = base + "_" + suffix;
            copy.automationId = copy.id;
            copy.x = Math.min(config.gridColumns - copy.width, source.x + 1);
            copy.y = Math.min(config.gridRows - copy.height, source.y + 1);
            copy.zIndex = nextLayer();
            copy.normalize(config.gridColumns, config.gridRows);
            config.elements.add(copy);
            canvas.updateConfig(config);
            canvas.select(copy.id);
            persist(false);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void deleteSelected() {
        HudElementConfig item = canvas.selected();
        if (item == null) return;
        new AlertDialog.Builder(this).setTitle("Удалить «" + item.title + "»?")
                .setMessage("Цель сценариев " + item.automationId
                        + " останется в сохранённых сценариях, но перестанет отображаться.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    config.elements.remove(item);
                    canvas.select(null);
                    canvas.updateConfig(config);
                    persist(false);
                }).setNegativeButton("Отмена", null).show();
    }

    private void changeLayer(int delta) {
        HudElementConfig item = canvas.selected();
        if (item == null) return;
        item.zIndex += delta;
        canvas.invalidate();
        persist(false);
    }

    private void persist(boolean toast) {
        if (store == null || config == null) return;
        main.removeCallbacks(persistLive);
        config.normalize();
        store.save(config);
        if (runtime != null) runtime.updateConfig(config);
        if (canvas != null) canvas.updateConfig(config);
        HudPresentationService.notifyConfigChanged(this);
        if (toast) Toast.makeText(this, "HUD сохранён", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        if (status == null) return;
        String selectedDisplay = "Display ID " + HudViewportPolicy.VERIFIED_DISPLAY_ID
                + (config.displayName.isEmpty() ? "" : " · " + config.displayName)
                + (config.displayUniqueId.isEmpty() ? "" : " · " + config.displayUniqueId)
                + (config.displayWidth <= 0 || config.displayHeight <= 0 ? ""
                : " · " + config.displayWidth + "×" + config.displayHeight);
        status.setText((preferences.hudPanelEnabled.get() ? "Включён" : "Выключен")
                + " · " + selectedDisplay + "\n"
                + HudPresentationService.runtimeDetail(this));
    }

    private void updateSelection(@Nullable HudElementConfig item) {
        if (selection == null) return;
        selection.setText(item == null ? "Элемент не выбран"
                : item.title + " · " + item.type.label + " · "
                + item.x + ":" + item.y + " · " + item.width + "×" + item.height
                + " · сценарии: " + item.automationId);
    }

    private boolean containsId(String id) {
        for (HudElementConfig item : config.elements) if (id.equals(item.id)) return true;
        return false;
    }

    private int nextLayer() {
        int maximum = 0;
        for (HudElementConfig item : config.elements) maximum = Math.max(maximum, item.zIndex);
        return maximum + 1;
    }

    private int previousBackdropLayer() {
        int minimum = 0;
        for (HudElementConfig item : config.elements) {
            if (item.type == HudElementType.BACKDROP) {
                minimum = Math.min(minimum, item.zIndex);
            }
        }
        return minimum - 1;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FONT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (RuntimeException ignored) {}
        config.customFontUri = uri.toString();
        persist(false);
        Toast.makeText(this, "Шрифт HUD выбран", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value) { return text(value, 12, 0xFF98A4B3); }

    private TextView section(String value) {
        TextView view = text(value, 16, Color.WHITE);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinWidth(0);
        return button;
    }

    private Switch switchView(String label, boolean checked) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setShowText(true);
        view.setTextOn("Вкл");
        view.setTextOff("Выкл");
        view.setChecked(checked);
        view.setPadding(dp(6), dp(5), dp(6), dp(5));
        return view;
    }

    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        parent.addView(label(label), marginTop(8));
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFF667080);
        field.setSingleLine(true);
        if (number) field.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        parent.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    /** A bounded control for common HUD visual values; no keyboard or malformed numbers. */
    private SliderField slider(@NonNull LinearLayout parent, @NonNull String title,
                               double initial, double minimum, double maximum,
                               double step, @NonNull String suffix) {
        TextView valueLabel = label("");
        parent.addView(valueLabel, marginTop(8));
        SeekBar control = new SeekBar(this);
        int steps = Math.max(1, (int) Math.round((maximum - minimum) / step));
        control.setMax(steps);
        int initialProgress = (int) Math.round((initial - minimum) / step);
        control.setProgress(Math.max(0, Math.min(steps, initialProgress)));
        SliderField result = new SliderField(
                title, suffix, minimum, step, control, valueLabel);
        result.updateLabel();
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                result.updateLabel();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        parent.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return result;
    }

    private static final class SliderField {
        @NonNull private final String title;
        @NonNull private final String suffix;
        private final double minimum;
        private final double step;
        @NonNull private final SeekBar control;
        @NonNull private final TextView valueLabel;

        SliderField(@NonNull String title, @NonNull String suffix,
                    double minimum, double step, @NonNull SeekBar control,
                    @NonNull TextView valueLabel) {
            this.title = title;
            this.suffix = suffix;
            this.minimum = minimum;
            this.step = step;
            this.control = control;
            this.valueLabel = valueLabel;
        }

        double value() {
            return minimum + control.getProgress() * step;
        }

        int intValue() {
            return (int) Math.round(value());
        }

        void setValue(double value) {
            int progress = (int) Math.round((value - minimum) / step);
            control.setProgress(Math.max(0, Math.min(control.getMax(), progress)));
            updateLabel();
        }

        void setEnabled(boolean enabled) {
            control.setEnabled(enabled);
            control.setAlpha(enabled ? 1f : 0.35f);
            valueLabel.setAlpha(enabled ? 1f : 0.45f);
        }

        void updateLabel() {
            double current = value();
            String rendered;
            if (Math.abs(current - Math.rint(current)) < 0.0001) {
                rendered = Integer.toString((int) Math.rint(current));
            } else if (Math.abs(current * 2 - Math.rint(current * 2)) < 0.0001) {
                rendered = String.format(Locale.ROOT, "%.1f", current);
            } else {
                rendered = String.format(Locale.ROOT, "%.2f", current);
            }
            valueLabel.setText(title + ": " + rendered + suffix);
        }
    }

    private Spinner spinner(String[] choices, String selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, choices);
        spinner.setAdapter(adapter);
        for (int index = 0; index < choices.length; index++) {
            if (choices[index].equalsIgnoreCase(selected)) {
                spinner.setSelection(index);
                break;
            }
        }
        return spinner;
    }

    private Spinner navigationCameraModeSpinner(@NonNull String mode) {
        Spinner result = spinner(new String[]{
                "По маршруту", "Север всегда сверху", "По направлению движения",
                "Свободное положение"
        }, "");
        int selected;
        switch (mode) {
            case "NORTH_UP": selected = 1; break;
            case "HEADING_UP": selected = 2; break;
            case "FREE": selected = 3; break;
            case "FOLLOW_ROUTE":
            default: selected = 0; break;
        }
        result.setSelection(selected);
        return result;
    }

    /** Three visual starting points; every value remains independently adjustable afterwards. */
    private void addNavigationCameraPresets(@NonNull LinearLayout parent,
                                            @NonNull Spinner cameraMode,
                                            @NonNull SliderField zoom,
                                            @NonNull SliderField tilt,
                                            @NonNull SliderField focusX,
                                            @NonNull SliderField focusY) {
        parent.addView(label("Быстрые варианты камеры"), marginTop(8));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button top = button("2D сверху");
        top.setOnClickListener(view -> {
            cameraMode.setSelection(1);
            zoom.setValue(0);
            tilt.setValue(0);
            focusX.setValue(50);
            focusY.setValue(55);
        });
        Button city = button("3D город");
        city.setOnClickListener(view -> {
            cameraMode.setSelection(0);
            zoom.setValue(0);
            tilt.setValue(55);
            focusX.setValue(50);
            focusY.setValue(70);
        });
        Button highway = button("3D трасса");
        highway.setOnClickListener(view -> {
            cameraMode.setSelection(0);
            zoom.setValue(-1);
            tilt.setValue(68);
            focusX.setValue(50);
            focusY.setValue(76);
        });
        for (Button control : new Button[]{top, city, highway}) {
            row.addView(control, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        parent.addView(row);
    }

    private Spinner dayNightSpinner(boolean automatic, boolean night) {
        Spinner result = spinner(new String[]{"Автоматически", "Всегда день", "Всегда ночь"},
                "");
        result.setSelection(automatic ? 0 : night ? 2 : 1);
        return result;
    }

    private static void applyDayNight(@NonNull Spinner control,
                                      @NonNull NavigationIntegrationConfig.MapProfile map) {
        int selection = control.getSelectedItemPosition();
        map.automaticDayNight = selection == 0;
        map.nightMode = selection == 2;
    }

    @NonNull
    private static String navigationCameraModeValue(int position) {
        switch (position) {
            case 1: return "NORTH_UP";
            case 2: return "HEADING_UP";
            case 3: return "FREE";
            case 0:
            default: return "FOLLOW_ROUTE";
        }
    }

    private ColorField navigationColorField(
            @NonNull LinearLayout parent,
            @NonNull String title,
            @NonNull String initial,
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull ColorSelectionListener setter) {
        return colorField(parent, title, initial, selected -> {
            setter.onSelected(selected);
            persistNavigationConfiguration(navigation);
        });
    }

    private boolean persistNavigationConfiguration(
            @NonNull NavigationIntegrationConfig navigation) {
        try {
            navigation.normalize();
            String encoded = navigation.toJson().toString();
            boolean stored = preferences.navigationIntegrationConfigJson.commit(encoded);
            String verified = preferences.navigationIntegrationConfigJson.get();
            if (!stored || !encoded.equals(verified)) {
                throw new IllegalStateException(
                        "записанное значение не прошло контрольное чтение");
            }
            NavigationHudEndpointService.requestConfigurationRefresh(this);
            return true;
        } catch (Exception failure) {
            Toast.makeText(this, "Не удалось сохранить настройки карты: "
                    + failure.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private ColorField colorField(@NonNull LinearLayout parent, @NonNull String title,
                                  @NonNull String initial) {
        return colorField(parent, title, initial, null);
    }

    private ColorField colorField(@NonNull LinearLayout parent, @NonNull String title,
                                  @NonNull String initial,
                                  @Nullable ColorSelectionListener selectionListener) {
        ColorField field = new ColorField(initial);
        MaterialButton button = new MaterialButton(this);
        button.setText(title);
        button.setAllCaps(false);
        field.button = button;
        AppleColorPickerDialog.decorateButton(button, title, initial);
        button.setOnClickListener(view -> AppleColorPickerDialog.show(
                this, title, field.value, AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    private boolean apply(@Nullable String selected) {
                        if (selected == null || selected.trim().isEmpty()) return false;
                        field.value = selected;
                        AppleColorPickerDialog.decorateButton(button, title, field.value);
                        return true;
                    }

                    @Override public void onPreview(@Nullable String selected) {
                        apply(selected);
                    }

                    @Override public void onSelected(@Nullable String selected) {
                        if (apply(selected) && selectionListener != null) {
                            selectionListener.onSelected(field.value);
                        }
                    }
                }));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(6);
        parent.addView(button, params);
        return field;
    }

    private static final class ColorField {
        @NonNull String value;
        @Nullable MaterialButton button;

        ColorField(@NonNull String value) {
            this.value = value;
        }
    }

    private interface ColorSelectionListener {
        void onSelected(@NonNull String selected);
    }

    private LinearLayout.LayoutParams fixed(int widthDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(dp);
        return params;
    }

    private static String value(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    /**
     * Keeps every editor dialog below the live driver/status panel on the 1920x720 head unit.
     * The content remains scrollable, so enlarged OEM font scaling cannot push Apply off-screen.
     */
    private void showSafeDialog(@NonNull AlertDialog dialog) {
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        WidgetService host = WidgetService.getInstance();
        int overlayTop = host == null ? 0 : host.getStatusBarOverlayHeight();
        int reservedTop = Math.max(dp(72), overlayTop + dp(8));
        int width = Math.max(dp(480), metrics.widthPixels - dp(32));
        int height = Math.max(dp(300), metrics.heightPixels - reservedTop - dp(12));
        width = Math.min(width, metrics.widthPixels);
        height = Math.min(height, metrics.heightPixels);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.setLayout(width, height);
        int driverInset = driverPanelInset();
        boolean driverOnRight = preferences.driverPanelSide.get() == 1;
        View decor = window.getDecorView();
        decor.setPadding(driverOnRight ? 0 : driverInset, decor.getPaddingTop(),
                driverOnRight ? driverInset : 0, decor.getPaddingBottom());
    }

    private int driverPanelInset() {
        if (preferences == null || !preferences.driverPanelEnabled.get()) return 0;
        return Math.max(dp(12), preferences.driverPanelWidthPx.get() + dp(12));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
