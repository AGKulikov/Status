/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.instrument.InstrumentClusterView;
import dezz.status.widget.instrument.InstrumentDisplayLauncher;
import dezz.status.widget.instrument.InstrumentElementConfig;
import dezz.status.widget.instrument.InstrumentElementType;
import dezz.status.widget.instrument.InstrumentInfoMetric;
import dezz.status.widget.instrument.InstrumentPanelConfig;
import dezz.status.widget.instrument.InstrumentPanelPreset;
import dezz.status.widget.instrument.InstrumentPanelStore;
import dezz.status.widget.instrument.InstrumentPanelView;
import dezz.status.widget.instrument.InstrumentStyleFamily;
import dezz.status.widget.navigation.NavigationHudEndpointService;
import dezz.status.widget.navigation.NavigationIntegrationConfig;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Live editor for the native 1920x720 driver instrument panel. */
public final class InstrumentPanelSettingsActivity extends AppCompatActivity {
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    private InstrumentPanelStore store;
    private InstrumentPanelConfig config;
    private InstrumentPanelView preview;
    private TextView selection;
    private Switch enabled;
    private Switch autostart;
    private final Runnable persistLive = () -> persist(false);

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new InstrumentPanelStore(this);
        config = store.load();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D12);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(toolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        DesignPreview frame = new DesignPreview(this);
        frame.setBackgroundColor(Color.BLACK);
        preview = new InstrumentPanelView(this, config, true,
                new InstrumentClusterView.EditorListener() {
                    @Override public void onSelectionChanged(
                            @Nullable InstrumentElementConfig element) {
                        updateSelection(element);
                    }

                    @Override public void onGeometryChanged(
                            @NonNull InstrumentElementConfig element, boolean committed) {
                        preview.refreshMapGeometry();
                        updateSelection(element);
                        main.removeCallbacks(persistLive);
                        main.postDelayed(persistLive, committed ? 0L : 55L);
                    }
                });
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.topMargin = dp(8);
        root.addView(frame, previewParams);
        root.addView(selectionBar(), marginTop(8));
        root.addView(text("Коснитесь элемента: перетаскивание меняет положение, синий угол — "
                + "размер. Стили можно смешивать; изменения сразу передаются на приборную панель.",
                12, 0xFF9BA7B7), marginTop(6));
        setContentView(root);
        SettingsBackNavigation.applySafeTopInset(this, root);
    }

    @Override protected void onStop() {
        main.removeCallbacks(persistLive);
        persist(false);
        super.onStop();
    }

    private View toolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←");
        back.setOnClickListener(view -> finish());
        row.addView(back, fixed(54));

        TextView title = text("Панель приборов", 20, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(8);
        row.addView(title, titleParams);

        enabled = switchView("Панель", store.isEnabled());
        enabled.setOnCheckedChangeListener((button, checked) -> {
            store.setEnabled(checked);
            if (checked) InstrumentDisplayLauncher.launch(this);
            else InstrumentDisplayLauncher.close(this);
        });
        row.addView(enabled);

        autostart = switchView("Авто", store.isAutostart());
        autostart.setOnCheckedChangeListener((button, checked) -> {
            store.setAutostart(checked);
            if (checked && enabled.isChecked()) InstrumentDisplayLauncher.launch(this);
        });
        row.addView(autostart);

        Button start = button("Запустить");
        start.setOnClickListener(view -> {
            if (!enabled.isChecked()) enabled.setChecked(true);
            persist(false);
            InstrumentDisplayLauncher.launch(this);
        });
        row.addView(start);
        Button add = button("+ Элемент");
        add.setOnClickListener(view -> addElement());
        row.addView(add);
        Button variants = button("5 вариантов");
        variants.setOnClickListener(view -> choosePanelPreset());
        row.addView(variants);
        Button modules = button("Модули");
        modules.setOnClickListener(view -> editModules());
        row.addView(modules);
        Button background = button("Фон");
        background.setOnClickListener(view -> editBackground());
        row.addView(background);
        Button map = button("Карта");
        map.setOnClickListener(view -> editMapPerformance());
        row.addView(map);
        return row;
    }

    private View selectionBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        selection = text("Элемент не выбран", 13, 0xFFD5DCE6);
        row.addView(selection, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button edit = button("Настроить");
        edit.setOnClickListener(view -> editSelected());
        row.addView(edit);
        Button copy = button("Копия");
        copy.setOnClickListener(view -> duplicateSelected());
        row.addView(copy);
        Button down = button("↓ слой");
        down.setOnClickListener(view -> changeLayer(-1));
        row.addView(down);
        Button up = button("↑ слой");
        up.setOnClickListener(view -> changeLayer(1));
        row.addView(up);
        Button delete = button("Удалить");
        delete.setOnClickListener(view -> deleteSelected());
        row.addView(delete);
        Button save = button("Сохранить");
        save.setOnClickListener(view -> persist(true));
        row.addView(save);
        return row;
    }

    private void addElement() {
        InstrumentElementType[] types = InstrumentElementType.values();
        String[] labels = new String[types.length];
        for (int index = 0; index < types.length; index++) {
            labels[index] = types[index].category + " · " + types[index].label;
        }
        new AlertDialog.Builder(this)
                .setTitle("Добавить элемент")
                .setItems(labels, (dialog, which) -> addElement(types[which]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addElement(@NonNull InstrumentElementType type) {
        if (type == InstrumentElementType.NAV_MAP && containsMap()) {
            Toast.makeText(this, "На панели уже есть независимая карта", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        String id = type.name().toLowerCase(Locale.ROOT) + "_" + System.currentTimeMillis();
        InstrumentElementConfig element = new InstrumentElementConfig(
                id, type, config.defaultStyle);
        int ordinal = config.elements.size();
        element.x = Math.min(config.columns - element.width, 2 + ordinal % 8);
        element.y = Math.min(config.rows - element.height, 2 + ordinal % 5);
        element.zIndex = ordinal;
        element.normalize(config.columns, config.rows);
        config.elements.add(element);
        refresh(element.id, true);
    }

    private void choosePanelPreset() {
        InstrumentPanelPreset[] presets = InstrumentPanelPreset.values();
        String[] labels = new String[presets.length];
        int selected = 0;
        for (int index = 0; index < presets.length; index++) {
            labels[index] = presets[index].label;
            if (presets[index].id.equals(config.presetId)) selected = index;
        }
        new AlertDialog.Builder(this)
                .setTitle("Базовая компоновка")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    config = store.switchPreset(presets[which], config);
                    dialog.dismiss();
                    refresh(null, true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editModules() {
        String[] labels = new String[config.elements.size()];
        boolean[] checked = new boolean[config.elements.size()];
        for (int index = 0; index < config.elements.size(); index++) {
            InstrumentElementConfig element = config.elements.get(index);
            labels[index] = element.type.label + " · " + element.style.label;
            checked[index] = element.enabled;
        }
        new AlertDialog.Builder(this)
                .setTitle("Модули панели")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, value) -> checked[which] = value)
                .setPositiveButton("Применить", (dialog, which) -> {
                    for (int index = 0; index < config.elements.size(); index++) {
                        config.elements.get(index).enabled = checked[index];
                    }
                    refresh(null, true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editBackground() {
        LinearLayout content = dialogColumn();
        content.addView(text("Сверху всегда чистый чёрный. Ниже начинается плавный переход "
                + "к выбранному цвету, которым можно визуально растворить штатные нижние крылья.",
                12, 0xFFB8C0CC));
        ColorField bottom = colorField(content, "Нижний цвет градиента",
                config.backgroundBottomColor);
        TextView blackValue = label("Чисто чёрная зона: " + config.blackZonePercent + "%");
        SeekBar blackZone = new SeekBar(this);
        blackZone.setMax(95);
        blackZone.setProgress(config.blackZonePercent);
        blackZone.setOnSeekBarChangeListener(seekListener(value ->
                blackValue.setText("Чисто чёрная зона: " + value + "%")));
        content.addView(blackValue, marginTop(8));
        content.addView(blackZone);
        Switch transparent = switchView(
                "Прозрачный фон (градиент отключён)", config.transparentBackground);
        content.addView(transparent);
        new AlertDialog.Builder(this)
                .setTitle("Фон приборной панели")
                .setView(content)
                .setPositiveButton("Применить", (dialog, which) -> {
                    config.backgroundBottomColor = bottom.value;
                    config.blackZonePercent = blackZone.getProgress();
                    config.transparentBackground = transparent.isChecked();
                    refresh(null, true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editSelected() {
        InstrumentElementConfig element = preview.instruments().selected();
        if (element == null) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = dialogColumn();
        scroll.addView(content);
        content.addView(text(element.type.label, 17, Color.WHITE));

        InstrumentStyleFamily[] styles = InstrumentStyleFamily.values();
        Spinner style = new Spinner(this);
        style.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, styleLabels(styles)));
        style.setSelection(element.style.ordinal());
        content.addView(label("Визуальный стиль"));
        content.addView(style);

        Switch visible = switchView("Показывать", element.enabled);
        content.addView(visible);
        TextView responseValue = label("Отклик стрелки: " + element.responseMillis + " мс");
        SeekBar response = new SeekBar(this);
        response.setMax(500);
        response.setProgress(element.responseMillis);
        response.setEnabled(element.type.isAnalogGauge());
        response.setOnSeekBarChangeListener(seekListener(value ->
                responseValue.setText("Отклик стрелки: " + value + " мс")));
        content.addView(responseValue);
        content.addView(response);

        final Switch showFace;
        final Switch showScale;
        final Switch showScaleLabels;
        final Switch showNeedle;
        final Switch showValue;
        final Switch showUnit;
        final Switch showProgress;
        final Switch showStreet;
        final Switch showArrival;
        final Switch showDistance;
        final Switch showEta;
        final Switch showDuration;
        final NavigationInfoControls navigationInfoControls;
        final Spinner[] infoRows = new Spinner[3];
        if (element.type.isAnalogGauge()) {
            showFace = switchView("Фон шкалы",
                    element.options.optBoolean("showFace", true));
            showScale = switchView("Шкала",
                    element.options.optBoolean("showScale", true));
            showScaleLabels = switchView("Цифры шкалы",
                    element.options.optBoolean("showScaleLabels", true));
            showNeedle = switchView("Стрелка",
                    element.options.optBoolean("showNeedle", true));
            showValue = switchView("Цифровое значение внутри",
                    element.options.optBoolean("showValue", true));
            showUnit = switchView("Единица измерения",
                    element.options.optBoolean("showUnit", true));
            showProgress = showStreet = showArrival = null;
            showDistance = showEta = showDuration = null;
            navigationInfoControls = null;
            content.addView(section("Состав аналогового прибора"), marginTop(10));
            content.addView(showFace);
            content.addView(showScale);
            content.addView(showScaleLabels);
            content.addView(showNeedle);
            content.addView(showValue);
            content.addView(showUnit);
        } else if (element.type == InstrumentElementType.INFO_BLOCK) {
            showFace = switchView("Фон блока",
                    element.options.optBoolean("showFace", false));
            showScale = showScaleLabels = showNeedle = showValue = showUnit = null;
            showProgress = showStreet = showArrival = null;
            showDistance = showEta = showDuration = null;
            navigationInfoControls = null;
            content.addView(showFace, marginTop(10));
            InstrumentInfoMetric[] metrics = InstrumentInfoMetric.values();
            String[] labels = infoMetricLabels(metrics);
            for (int row = 0; row < infoRows.length; row++) {
                content.addView(label("Строка " + (row + 1)), marginTop(5));
                Spinner spinner = spinner(labels);
                InstrumentInfoMetric current = InstrumentInfoMetric.fromName(
                        element.options.optString("row" + (row + 1), null),
                        row == 0 ? InstrumentInfoMetric.RANGE
                                : row == 1 ? InstrumentInfoMetric.AVERAGE_CONSUMPTION
                                : InstrumentInfoMetric.AMBIENT_TEMPERATURE);
                spinner.setSelection(current.ordinal());
                infoRows[row] = spinner;
                content.addView(spinner);
            }
        } else if (element.type == InstrumentElementType.NAVIGATION_INFO) {
            showFace = switchView("Фон блока",
                    element.options.optBoolean("showFace", true));
            showDistance = switchView("Оставшееся расстояние",
                    element.options.optBoolean("showDistance", true));
            showEta = switchView("Время прибытия",
                    element.options.optBoolean("showEta", true));
            showDuration = switchView("Оставшееся время",
                    element.options.optBoolean("showDuration", true));
            showProgress = switchView("Прогресс маршрута с пробками",
                    element.options.optBoolean("showRouteProgress", true));
            showScale = showScaleLabels = showNeedle = showValue = showUnit = null;
            showStreet = showArrival = null;
            navigationInfoControls = new NavigationInfoControls(content, element);
            content.addView(showFace, marginTop(10));
            content.addView(showDistance);
            content.addView(showEta);
            content.addView(showDuration);
            content.addView(showProgress);
            navigationInfoControls.addViews();
        } else if (isDigitalValueElement(element.type)) {
            showFace = switchView("Фон элемента",
                    element.options.optBoolean("showFace", true));
            showUnit = switchView("Единица измерения",
                    element.options.optBoolean("showUnit", true));
            showProgress = switchView("Линия значения",
                    element.options.optBoolean("showProgress", true));
            showScale = showScaleLabels = showNeedle = showValue = null;
            showStreet = showArrival = null;
            showDistance = showEta = showDuration = null;
            navigationInfoControls = null;
            content.addView(showFace, marginTop(10));
            content.addView(showUnit);
            content.addView(showProgress);
        } else {
            showFace = showScale = showScaleLabels = showNeedle = showValue = showUnit = null;
            showProgress = showStreet = showArrival = null;
            showDistance = showEta = showDuration = null;
            navigationInfoControls = null;
        }

        TextView opacityValue = label("Непрозрачность: " + element.opacityPercent + "%");
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(90);
        opacity.setProgress(element.opacityPercent - 10);
        opacity.setOnSeekBarChangeListener(seekListener(value ->
                opacityValue.setText("Непрозрачность: " + (value + 10) + "%")));
        content.addView(opacityValue);
        content.addView(opacity);

        new AlertDialog.Builder(this)
                .setTitle("Настройка элемента")
                .setView(scroll)
                .setPositiveButton("Применить", (dialog, which) -> {
                    element.style = styles[style.getSelectedItemPosition()];
                    element.enabled = visible.isChecked();
                    element.responseMillis = response.getProgress();
                    element.opacityPercent = opacity.getProgress() + 10;
                    if (showFace != null) setOption(element, "showFace", showFace.isChecked());
                    if (showScale != null) setOption(element, "showScale", showScale.isChecked());
                    if (showScaleLabels != null) {
                        setOption(element, "showScaleLabels", showScaleLabels.isChecked());
                    }
                    if (showNeedle != null) {
                        setOption(element, "showNeedle", showNeedle.isChecked());
                    }
                    if (showValue != null) setOption(element, "showValue", showValue.isChecked());
                    if (showUnit != null) setOption(element, "showUnit", showUnit.isChecked());
                    if (showProgress != null) {
                        setOption(element,
                                element.type == InstrumentElementType.NAVIGATION_INFO
                                        ? "showRouteProgress" : "showProgress",
                                showProgress.isChecked());
                    }
                    if (showStreet != null) {
                        setOption(element, "showStreet", showStreet.isChecked());
                    }
                    if (showArrival != null) {
                        setOption(element, "showArrival", showArrival.isChecked());
                    }
                    if (showDistance != null) {
                        setOption(element, "showDistance", showDistance.isChecked());
                    }
                    if (showEta != null) {
                        setOption(element, "showEta", showEta.isChecked());
                    }
                    if (showDuration != null) {
                        setOption(element, "showDuration", showDuration.isChecked());
                    }
                    if (navigationInfoControls != null) {
                        navigationInfoControls.apply(element);
                    }
                    InstrumentInfoMetric[] metrics = InstrumentInfoMetric.values();
                    for (int row = 0; row < infoRows.length; row++) {
                        if (infoRows[row] != null) {
                            setOption(element, "row" + (row + 1),
                                    metrics[infoRows[row].getSelectedItemPosition()].name());
                        }
                    }
                    element.normalize(config.columns, config.rows);
                    refresh(element.id, true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void duplicateSelected() {
        InstrumentElementConfig selected = preview.instruments().selected();
        if (selected == null) return;
        if (selected.type == InstrumentElementType.NAV_MAP && containsMap()) {
            Toast.makeText(this, "Независимая карта может быть только одна", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        InstrumentElementConfig copy = selected.copy();
        copy.id = selected.type.name().toLowerCase(Locale.ROOT) + "_" + System.currentTimeMillis();
        copy.x += 1;
        copy.y += 1;
        copy.zIndex += 1;
        copy.normalize(config.columns, config.rows);
        config.elements.add(copy);
        refresh(copy.id, true);
    }

    private void deleteSelected() {
        InstrumentElementConfig selected = preview.instruments().selected();
        if (selected == null) return;
        config.elements.remove(selected);
        refresh(null, true);
    }

    private void changeLayer(int delta) {
        InstrumentElementConfig selected = preview.instruments().selected();
        if (selected == null) return;
        selected.zIndex += delta;
        config.normalize();
        refresh(selected.id, true);
    }

    private void editMapPerformance() {
        Preferences preferences = new Preferences(this);
        NavigationIntegrationConfig navigation;
        String raw = preferences.navigationIntegrationConfigJson.get();
        try {
            navigation = raw == null || raw.trim().isEmpty()
                    ? new NavigationIntegrationConfig()
                    : NavigationIntegrationConfig.fromJson(raw);
        } catch (RuntimeException invalid) {
            navigation = new NavigationIntegrationConfig();
        }
        NavigationIntegrationConfig finalNavigation = navigation;
        NavigationIntegrationConfig.MapProfile map = navigation.clusterMap;
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = dialogColumn();
        scroll.addView(content);

        content.addView(text("Карта приборной панели полностью независима от основного экрана "
                + "Навигатора. Все значения ниже применяются только к этой карте.",
                12, 0xFFB8C0CC));
        Switch mapEnabled = switchView("Показывать карту", map.enabled);
        content.addView(mapEnabled);

        content.addView(section("Камера"), marginTop(14));
        Spinner cameraMode = navigationCameraModeSpinner(map.cameraMode);
        content.addView(label("Как карта следует за автомобилем"), marginTop(6));
        content.addView(cameraMode);
        SliderField zoom = slider(content,
                "Приближение: 0 — стандартное, + ближе, − дальше",
                map.zoomDelta, -8, 8, 0.25, "");
        Switch fixedZoom = switchView(
                "Фиксировать масштаб — не менять его от скорости",
                map.fixedZoomEnabled);
        content.addView(fixedZoom);
        SliderField fixedZoomLevel = slider(content,
                "Фиксированное увеличение карты",
                map.fixedZoomLevel, 2, 21, 0.25, "");
        Runnable updateFixedZoomControl = () -> {
            zoom.setEnabled(!fixedZoom.isChecked());
            fixedZoomLevel.setEnabled(fixedZoom.isChecked());
        };
        fixedZoom.setOnCheckedChangeListener(
                (button, checked) -> updateFixedZoomControl.run());
        updateFixedZoomControl.run();
        SliderField tilt = slider(content,
                "Наклон: 0° — сверху, 60° — перспектива",
                map.tiltDegrees, 0, 80, 1, "°");
        SliderField focusX = slider(content, "Автомобиль по горизонтали",
                map.focusXPercent, 0, 100, 1, " %");
        SliderField focusY = slider(content, "Автомобиль по вертикали",
                map.focusYPercent, 0, 100, 1, " %");
        SliderField mapScale = slider(content,
                "Общий размер подписей и объектов основы карты",
                map.mapScalePercent, 50, 300, 5, " %");
        addCameraPresets(content, cameraMode, zoom, tilt, focusX, focusY);

        SliderField maximumFps = slider(content,
                "Плавность (на стоянке автоматически не выше 15)",
                map.maximumFps, 5, 60, 1, " кадр/с");

        content.addView(section("Состав карты"), marginTop(14));
        Spinner dayNight = dayNightSpinner(map.automaticDayNight, map.nightMode);
        content.addView(label("Оформление день / ночь"), marginTop(6));
        content.addView(dayNight);
        Switch route = switchView("Маршрут", map.showRoute);
        Switch destination = switchView(
                "Конечная точка маршрута", map.showDestination);
        Switch routeTraffic = switchView("Пробки на линии маршрута", map.showRouteTraffic);
        Switch traffic = switchView("Пробки на остальных дорогах", map.showTraffic);
        Switch trafficLights = switchView(
                "Светофоры с отсчётом — отдельный слой", map.showTrafficLights);
        Switch routeTurns = switchView(
                "Стрелки поворотов прямо на линии маршрута", map.showRouteTurns);
        Switch laneGuidance = switchView(
                "Подсказки по полосам — слой на маршруте", map.showLaneGuidance);
        Switch hudSpeedCameras = switchView(
                "Камеры из HUD Speed — отдельный знак", map.showHudSpeedCameras);
        Switch labels = switchView(
                "Штатные названия улиц Яндекса", map.showLabels);
        Switch pois = switchView("Полезные места", map.showPois);
        Switch buildings = switchView("Здания", map.showBuildings);
        Switch parks = switchView("Парки", map.showParks);
        Switch water = switchView("Вода", map.showWater);
        Switch models = switchView("3D-модели (повышенная нагрузка)", map.showModels);
        Switch cursor = switchView("Курсор автомобиля", map.showCursor);
        Switch roadsOnly = switchView("Только дороги — прозрачный фон", map.roadsOnly);
        content.addView(route);
        content.addView(destination);
        content.addView(traffic);
        content.addView(routeTraffic);
        content.addView(trafficLights);
        content.addView(routeTurns);
        content.addView(laneGuidance);
        content.addView(hudSpeedCameras);
        content.addView(labels);
        content.addView(text("Названия, шрифт, контур и изгиб текста рисует сам слой карты "
                + "Яндекса. Отдельных нарисованных плашек Natro больше нет.",
                12, 0xFFB8C0CC));
        content.addView(pois);
        content.addView(buildings);
        content.addView(parks);
        content.addView(water);
        content.addView(models);
        content.addView(cursor);
        content.addView(roadsOnly);
        Button roadEvents = button("Дорожные события — типы и режимы");
        roadEvents.setOnClickListener(view -> editClusterRoadEvents(
                finalNavigation, map, preferences));
        content.addView(roadEvents);

        content.addView(section("Размер каждого слоя"), marginTop(14));
        SliderField cursorScale = slider(content, "Размер курсора",
                map.cursorScalePercent, 25, 300, 5, " %");
        SliderField laneGuidanceScale = slider(content,
                "Размер знаков движения по полосам",
                map.laneGuidanceScalePercent, 50, 250, 5, " %");
        SliderField cameraScale = slider(content,
                "Размер единых знаков камер",
                map.cameraScalePercent, 50, 250, 5, " %");
        SliderField cameraDirectionLength = slider(content,
                "Длина треугольника направления камер",
                map.cameraDirectionLengthPercent, 10, 300, 5, " %");
        SliderField cameraDirectionWidth = slider(content,
                "Ширина основания треугольника",
                map.cameraDirectionWidthPercent, 10, 300, 5, " %");
        ColorField cameraDirectionColor = opaqueNavigationColorField(
                content, "Цвет треугольника направления",
                map.cameraDirectionColor, finalNavigation, preferences,
                value -> map.cameraDirectionColor = value);
        SliderField cameraDirectionOpacity = slider(content,
                "Прозрачность направления камер",
                map.cameraDirectionOpacityPercent, 0, 100, 5, " %");
        content.addView(text("Ширина меняет только дальнее широкое основание треугольника; "
                + "длина и положение вершины при этом не меняются.",
                12, 0xFFB8C0CC));
        SliderField trafficLightScale = slider(content,
                "Размер светофоров и плашек секунд",
                map.trafficLightScalePercent, 50, 250, 5, " %");
        SliderField routeTurnLength = slider(content,
                "Длина стрелок поворотов на маршруте",
                map.routeTurnLengthPercent, 10, 250, 5, " %");
        SliderField routeTurnHeadSize = slider(content,
                "Размер наконечника стрелок поворотов",
                map.routeTurnHeadSizePercent, 10, 250, 5, " %");
        InheritedColorField routeTurnFillColor = inheritableNavigationColorField(
                content, "Цвет стрелок поворотов", map.routeTurnFillColor,
                finalNavigation, preferences, value -> map.routeTurnFillColor = value);
        InheritedColorField routeTurnOutlineColor = inheritableNavigationColorField(
                content, "Цвет обводки стрелок", map.routeTurnOutlineColor,
                finalNavigation, preferences, value -> map.routeTurnOutlineColor = value);
        SliderField routeTurnOutlineWidth = slider(content,
                "Толщина обводки стрелок",
                map.routeTurnOutlineWidth, 0, 20, 0.5, " px");
        content.addView(text("Ширина стрелки всегда равна толщине линии маршрута. Длина меняет "
                + "тело стрелки, а размер треугольного наконечника настраивается отдельно.",
                12, 0xFFB8C0CC));
        SliderField routeLabelScale = slider(content,
                "Размер штатных названий улиц",
                map.routeLabelScalePercent, 50, 250, 5, " %");
        SliderField roadEventScale = slider(content,
                "Размер остальных дорожных событий",
                map.roadEventScalePercent, 50, 250, 5, " %");
        SliderField destinationScale = slider(content,
                "Размер конечной точки маршрута",
                map.destinationScalePercent, 50, 250, 5, " %");
        ColorField cursorColor = navigationColorField(content, "Цвет автомобиля",
                map.cursorColor, finalNavigation, preferences,
                value -> map.cursorColor = value);
        ColorField cursorOutline = navigationColorField(content, "Контур автомобиля",
                map.cursorOutlineColor, finalNavigation, preferences,
                value -> map.cursorOutlineColor = value);
        ColorField routeColor = navigationColorField(content, "Цвет маршрута",
                map.routeColor, finalNavigation, preferences,
                value -> map.routeColor = value);
        ColorField routeOutline = navigationColorField(content, "Контур маршрута",
                map.routeOutlineColor, finalNavigation, preferences,
                value -> map.routeOutlineColor = value);
        ColorField roadColor = navigationColorField(content,
                "Цвет дорог без маршрута и пробок", map.roadColor,
                finalNavigation, preferences, value -> map.roadColor = value);
        SliderField routeWidthPercent = slider(content,
                "Толщина линии маршрута: 100% — как сейчас",
                map.routeWidthPercent, 25, 300, 5, " %");
        SliderField roadWidthPercent = slider(content,
                "Толщина улиц без маршрута: 100% — как сейчас",
                map.roadWidthPercent, 25, 300, 5, " %");
        SliderField routeOutlineWidth = slider(content, "Толщина контура маршрута",
                map.routeOutlineWidth, 0, 20, 0.5, " px");

        content.addView(section("Порядок слоёв"), marginTop(14));
        Switch manualLayerPriorities = switchView(
                "Ручной порядок слоёв", map.manualLayerPrioritiesEnabled);
        content.addView(manualLayerPriorities);
        content.addView(text("Выключено: порядок как в Навигаторе 30.3.0 — маршрут ниже "
                + "подписей, курсор ниже подсказок, конечная точка выше. Включено: большее "
                + "значение располагает слой выше внутри совместимой группы. Стрелки "
                + "полилинии всегда следуют приоритету маршрута.",
                12, 0xFFB8C0CC));
        SliderField cameraDirectionLayerPriority = slider(content,
                "Знаки камер и секторы направления", map.cameraDirectionLayerPriority,
                0, 100, 1, "");
        SliderField roadEventLayerPriority = slider(content,
                "Остальные дорожные события", map.roadEventLayerPriority,
                0, 100, 1, "");
        SliderField routeLayerPriority = slider(content,
                "Маршрут", map.routeLayerPriority, 0, 100, 1, "");
        SliderField destinationLayerPriority = slider(content,
                "Конечная точка маршрута", map.destinationLayerPriority,
                0, 100, 1, "");
        SliderField trafficLightLayerPriority = slider(content,
                "Светофоры и секунды", map.trafficLightLayerPriority,
                0, 100, 1, "");
        SliderField laneGuidanceLayerPriority = slider(content,
                "Знаки движения по полосам", map.laneGuidanceLayerPriority,
                0, 100, 1, "");
        SliderField cursorLayerPriority = slider(content,
                "Курсор автомобиля", map.cursorLayerPriority,
                0, 100, 1, "");
        SliderField[] layerPriorityControls = new SliderField[]{
                cameraDirectionLayerPriority, roadEventLayerPriority, routeLayerPriority,
                destinationLayerPriority, trafficLightLayerPriority,
                laneGuidanceLayerPriority, cursorLayerPriority};
        Runnable updateLayerPriorityControls = () -> {
            for (SliderField field : layerPriorityControls) {
                field.setEnabled(manualLayerPriorities.isChecked());
            }
        };
        manualLayerPriorities.setOnCheckedChangeListener(
                (button, checked) -> updateLayerPriorityControls.run());
        updateLayerPriorityControls.run();

        content.addView(section("Цвета загруженности дорог"), marginTop(14));
        ColorField trafficFreeColor = navigationColorField(content, "Дорога свободна",
                map.trafficFreeColor, finalNavigation, preferences,
                value -> map.trafficFreeColor = value);
        ColorField trafficLightColor = navigationColorField(content,
                "Небольшое затруднение", map.trafficLightColor,
                finalNavigation, preferences, value -> map.trafficLightColor = value);
        ColorField trafficHardColor = navigationColorField(content, "Плотное движение",
                map.trafficHardColor, finalNavigation, preferences,
                value -> map.trafficHardColor = value);
        ColorField trafficVeryHardColor = navigationColorField(content, "Сильная пробка",
                map.trafficVeryHardColor, finalNavigation, preferences,
                value -> map.trafficVeryHardColor = value);
        ColorField trafficBlockedColor = navigationColorField(content, "Дорога перекрыта",
                map.trafficBlockedColor, finalNavigation, preferences,
                value -> map.trafficBlockedColor = value);
        ColorField trafficUnknownColor = navigationColorField(content, "Нет данных",
                map.trafficUnknownColor, finalNavigation, preferences,
                value -> map.trafficUnknownColor = value);
        SliderField trafficGradient = slider(content, "Длина перехода цветов пробок",
                map.trafficGradientLength, 0, 100, 1, " %");

        new AlertDialog.Builder(this)
                .setTitle("Независимая карта приборной панели")
                .setView(scroll)
                .setPositiveButton("Применить", (dialog, which) -> {
                    map.enabled = mapEnabled.isChecked();
                    map.cameraMode = navigationCameraModeValue(
                            cameraMode.getSelectedItemPosition());
                    map.zoomDelta = zoom.value();
                    map.fixedZoomEnabled = fixedZoom.isChecked();
                    map.fixedZoomLevel = fixedZoomLevel.value();
                    map.tiltDegrees = tilt.intValue();
                    map.focusXPercent = focusX.intValue();
                    map.focusYPercent = focusY.intValue();
                    map.mapScalePercent = mapScale.intValue();
                    map.maximumFps = maximumFps.intValue();
                    applyDayNight(dayNight, map);
                    map.showRoute = route.isChecked();
                    map.showDestination = destination.isChecked();
                    map.showTraffic = traffic.isChecked();
                    map.showRouteTraffic = routeTraffic.isChecked();
                    map.showTrafficLights = trafficLights.isChecked();
                    map.showRouteTurns = routeTurns.isChecked();
                    map.showLaneGuidance = laneGuidance.isChecked();
                    map.showHudSpeedCameras = hudSpeedCameras.isChecked();
                    map.showPois = pois.isChecked();
                    map.showBuildings = buildings.isChecked();
                    map.showLabels = labels.isChecked();
                    map.showParks = parks.isChecked();
                    map.showWater = water.isChecked();
                    map.showModels = models.isChecked();
                    map.showCursor = cursor.isChecked();
                    map.roadsOnly = roadsOnly.isChecked();
                    map.cursorScalePercent = cursorScale.intValue();
                    map.laneGuidanceScalePercent = laneGuidanceScale.intValue();
                    map.cameraScalePercent = cameraScale.intValue();
                    map.cameraDirectionLengthPercent = cameraDirectionLength.intValue();
                    map.cameraDirectionWidthPercent = cameraDirectionWidth.intValue();
                    map.cameraDirectionColor = cameraDirectionColor.value;
                    map.cameraDirectionOpacityPercent = cameraDirectionOpacity.intValue();
                    map.trafficLightScalePercent = trafficLightScale.intValue();
                    map.routeTurnLengthPercent = routeTurnLength.intValue();
                    map.routeTurnHeadSizePercent = routeTurnHeadSize.intValue();
                    map.routeTurnFillColor = routeTurnFillColor.value;
                    map.routeTurnOutlineColor = routeTurnOutlineColor.value;
                    map.routeTurnOutlineWidth = routeTurnOutlineWidth.value();
                    map.routeLabelScalePercent = routeLabelScale.intValue();
                    map.roadEventScalePercent = roadEventScale.intValue();
                    map.destinationScalePercent = destinationScale.intValue();
                    map.manualLayerPrioritiesEnabled = manualLayerPriorities.isChecked();
                    map.cursorColor = cursorColor.value;
                    map.cursorOutlineColor = cursorOutline.value;
                    map.routeColor = routeColor.value;
                    map.routeOutlineColor = routeOutline.value;
                    map.roadColor = roadColor.value;
                    map.routeWidthPercent = routeWidthPercent.intValue();
                    map.roadWidthPercent = roadWidthPercent.intValue();
                    map.routeOutlineWidth = routeOutlineWidth.value();
                    map.cameraDirectionLayerPriority =
                            cameraDirectionLayerPriority.intValue();
                    map.roadEventLayerPriority = roadEventLayerPriority.intValue();
                    map.routeLayerPriority = routeLayerPriority.intValue();
                    map.destinationLayerPriority = destinationLayerPriority.intValue();
                    map.trafficLightLayerPriority = trafficLightLayerPriority.intValue();
                    map.routeTurnLayerPriority = map.routeLayerPriority;
                    map.laneGuidanceLayerPriority = laneGuidanceLayerPriority.intValue();
                    map.cursorLayerPriority = cursorLayerPriority.intValue();
                    map.trafficFreeColor = trafficFreeColor.value;
                    map.trafficLightColor = trafficLightColor.value;
                    map.trafficHardColor = trafficHardColor.value;
                    map.trafficVeryHardColor = trafficVeryHardColor.value;
                    map.trafficBlockedColor = trafficBlockedColor.value;
                    map.trafficUnknownColor = trafficUnknownColor.value;
                    map.trafficGradientLength = trafficGradient.value();
                    if (persistNavigationConfiguration(finalNavigation, preferences)) {
                        // Make the projected panel re-read map enable/opacity immediately. When
                        // disabled this revokes its Surface instead of retaining an idle lease.
                        sendBroadcast(new android.content.Intent(
                                InstrumentPanelStore.ACTION_CONFIG_CHANGED)
                                .setPackage(getPackageName()));
                        preview.updateConfig(config);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editClusterRoadEvents(
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull NavigationIntegrationConfig.MapProfile profile,
            @NonNull Preferences preferences) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = dialogColumn();
        scroll.addView(content);
        content.addView(text("Для каждого события: не показывать, показывать всегда или только "
                + "когда MapKit пометил его как находящееся на активном маршруте. Направление "
                + "камер берётся из штатных данных Яндекса.",
                12, 0xFFB8C0CC));
        String[] modes = {"Не показывать", "Всегда", "Только с маршрутом"};
        List<RoadEventControl> controls = new ArrayList<>();
        String lastGroup = "";
        for (NavigationIntegrationConfig.RoadEventSpec spec
                : NavigationIntegrationConfig.HUD_ROAD_EVENTS) {
            if (!spec.group.equals(lastGroup)) {
                TextView group = text(spec.group, 15, Color.WHITE);
                group.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                content.addView(group, marginTop(lastGroup.isEmpty() ? 10 : 18));
                lastGroup = spec.group;
            }
            content.addView(label(spec.title), marginTop(6));
            Spinner mode = new Spinner(this);
            mode.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, modes));
            mode.setSelection(roadEventModePosition(profile.roadEventMode(spec.tag)));
            content.addView(mode);
            controls.add(new RoadEventControl(spec.tag, mode));
        }
        new AlertDialog.Builder(this)
                .setTitle("Дорожные события приборной панели")
                .setView(scroll)
                .setPositiveButton("Применить", (dialog, which) -> {
                    for (RoadEventControl control : controls) {
                        profile.setRoadEventMode(control.tag, roadEventModeValue(
                                control.mode.getSelectedItemPosition()));
                    }
                    persistNavigationConfiguration(navigation, preferences);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static int roadEventModePosition(
            @NonNull NavigationIntegrationConfig.RoadEventMode mode) {
        if (mode == NavigationIntegrationConfig.RoadEventMode.ALWAYS) return 1;
        if (mode == NavigationIntegrationConfig.RoadEventMode.ROUTE_ONLY) return 2;
        return 0;
    }

    @NonNull
    private static NavigationIntegrationConfig.RoadEventMode roadEventModeValue(int position) {
        if (position == 1) return NavigationIntegrationConfig.RoadEventMode.ALWAYS;
        if (position == 2) return NavigationIntegrationConfig.RoadEventMode.ROUTE_ONLY;
        return NavigationIntegrationConfig.RoadEventMode.HIDDEN;
    }

    private static final class RoadEventControl {
        @NonNull final String tag;
        @NonNull final Spinner mode;

        RoadEventControl(@NonNull String tag, @NonNull Spinner mode) {
            this.tag = tag;
            this.mode = mode;
        }
    }

    private void refresh(@Nullable String selectionId, boolean persist) {
        config.normalize();
        preview.updateConfig(config);
        preview.instruments().select(selectionId);
        if (persist) persist(false);
    }

    private void persist(boolean toast) {
        if (store == null || config == null) return;
        store.save(config);
        sendBroadcast(new android.content.Intent(InstrumentPanelStore.ACTION_CONFIG_CHANGED)
                .setPackage(getPackageName()));
        if (toast) Toast.makeText(this, "Панель сохранена", Toast.LENGTH_SHORT).show();
    }

    private boolean containsMap() {
        for (InstrumentElementConfig element : config.elements) {
            if (element.type == InstrumentElementType.NAV_MAP) return true;
        }
        return false;
    }

    private void updateSelection(@Nullable InstrumentElementConfig element) {
        selection.setText(element == null ? "Элемент не выбран"
                : element.type.label + " · " + element.style.label
                + " · " + element.x + "," + element.y
                + " · " + element.width + "×" + element.height);
    }

    @NonNull private LinearLayout dialogColumn() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        return content;
    }

    @NonNull private static String[] styleLabels(@NonNull InstrumentStyleFamily[] styles) {
        String[] labels = new String[styles.length];
        for (int index = 0; index < styles.length; index++) labels[index] = styles[index].label;
        return labels;
    }

    @NonNull private static String[] infoMetricLabels(@NonNull InstrumentInfoMetric[] metrics) {
        String[] labels = new String[metrics.length];
        for (int index = 0; index < metrics.length; index++) labels[index] = metrics[index].label;
        return labels;
    }

    private static boolean isDigitalValueElement(@NonNull InstrumentElementType type) {
        switch (type) {
            case DIGITAL_SPEEDOMETER:
            case DIGITAL_TACHOMETER:
            case ODOMETER:
            case FUEL_GAUGE:
            case BATTERY_GAUGE:
            case RANGE:
            case AMBIENT_TEMPERATURE:
            case COOLANT_TEMPERATURE:
            case INSTANT_CONSUMPTION:
            case AVERAGE_CONSUMPTION:
            case TRIP_CONSUMPTION:
                return true;
            default:
                return false;
        }
    }

    /** Fine-grained editor for the stock-like route summary card. */
    private final class NavigationInfoControls {
        @NonNull private final LinearLayout parent;
        @NonNull private final InstrumentElementConfig source;
        @NonNull private final Map<String, SliderField> numbers = new LinkedHashMap<>();
        @NonNull private final Map<String, ColorField> colors = new LinkedHashMap<>();
        private Switch showIcon;
        private Switch reserveIconSpace;

        NavigationInfoControls(@NonNull LinearLayout parent,
                               @NonNull InstrumentElementConfig source) {
            this.parent = parent;
            this.source = source;
        }

        void addViews() {
            parent.addView(section("Исходный знак Навигатора"), marginTop(12));
            showIcon = switchView("Показывать исходный знак слева",
                    source.options.optBoolean("showManeuverIcon", true));
            reserveIconSpace = switchView("Сохранять место, пока знак ещё не пришёл",
                    source.options.optBoolean("reserveManeuverIconSpace", true));
            parent.addView(showIcon);
            parent.addView(reserveIconSpace);
            number("maneuverIconAreaPercent", "Ширина области знака",
                    15, 5, 40, 1, " %");
            number("maneuverIconScalePercent", "Размер исходного знака",
                    100, 25, 250, 5, " %");
            number("maneuverIconGapPx", "Расстояние от знака до данных",
                    10, 0, 100, 1, " px");
            color("maneuverIconBackgroundColor", "Фон области знака",
                    "#FF2B2E35");
            number("maneuverIconBackgroundOpacityPercent", "Непрозрачность фона знака",
                    100, 0, 100, 1, " %");
            number("maneuverIconCornerRadiusPx", "Скругление фона знака",
                    12, 0, 100, 1, " px");

            parent.addView(section("Отступы знака"), marginTop(12));
            padding("maneuverIconPadding", 5, 5, 5, 5, 160);

            parent.addView(section("Шрифты данных"), marginTop(12));
            number("distanceTextSizeSp", "Оставшееся расстояние",
                    25, 8, 120, 1, " sp");
            number("arrivalTextSizeSp", "Время прибытия",
                    25, 8, 120, 1, " sp");
            number("durationTextSizeSp", "Оставшееся время",
                    25, 8, 120, 1, " sp");
            number("metricGapPx", "Расстояние между значениями",
                    10, 0, 100, 1, " px");
            number("metricsVerticalPercent", "Положение значений по высоте",
                    44, 0, 100, 1, " %");

            parent.addView(section("Прогресс маршрута"), marginTop(12));
            number("progressBarHeightPx", "Толщина прогресс-бара",
                    14, 2, 80, 1, " px");
            number("progressBarTopGapPx", "Отступ над прогресс-баром",
                    9, 0, 100, 1, " px");
            number("progressBarCornerRadiusPx", "Скругление прогресс-бара",
                    7, 0, 60, 1, " px");
            number("progressMarkerScalePercent", "Размер маркера на прогресс-баре",
                    100, 25, 250, 5, " %");

            parent.addView(section("Карточка и внутренние отступы"), marginTop(12));
            color("faceColor", "Цвет карточки", "#FF15171B");
            number("faceOpacityPercent", "Непрозрачность карточки",
                    93, 0, 100, 1, " %");
            number("faceCornerRadiusPx", "Скругление карточки",
                    18, 0, 160, 1, " px");
            color("faceBorderColor", "Цвет рамки карточки", "#00000000");
            number("faceBorderWidthPx", "Толщина рамки карточки",
                    0, 0, 24, 1, " px");
            padding("contentPadding", 14, 10, 14, 10, 160);
        }

        private void number(@NonNull String key, @NonNull String title, int fallback,
                            int minimum, int maximum, int step, @NonNull String suffix) {
            numbers.put(key, slider(parent, title, source.options.optInt(key, fallback),
                    minimum, maximum, step, suffix));
        }

        private void padding(@NonNull String prefix, int left, int top,
                             int right, int bottom, int maximum) {
            String[] suffixes = {"LeftPx", "TopPx", "RightPx", "BottomPx"};
            String[] labels = {"Слева", "Сверху", "Справа", "Снизу"};
            int[] fallbacks = {left, top, right, bottom};
            for (int index = 0; index < suffixes.length; index++) {
                String key = prefix + suffixes[index];
                number(key, labels[index], fallbacks[index], 0, maximum, 1, " px");
            }
        }

        private void color(@NonNull String key, @NonNull String title,
                           @NonNull String fallback) {
            colors.put(key, colorField(parent, title,
                    source.options.optString(key, fallback)));
        }

        void apply(@NonNull InstrumentElementConfig target) {
            setOption(target, "showManeuverIcon", showIcon.isChecked());
            setOption(target, "reserveManeuverIconSpace", reserveIconSpace.isChecked());
            for (Map.Entry<String, SliderField> entry : numbers.entrySet()) {
                setOption(target, entry.getKey(), entry.getValue().intValue());
            }
            for (Map.Entry<String, ColorField> entry : colors.entrySet()) {
                setOption(target, entry.getKey(), entry.getValue().value);
            }
        }
    }

    private static void setOption(@NonNull InstrumentElementConfig element,
                                  @NonNull String key, boolean value) {
        try {
            element.options.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void setOption(@NonNull InstrumentElementConfig element,
                                  @NonNull String key, int value) {
        try {
            element.options.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void setOption(@NonNull InstrumentElementConfig element,
                                  @NonNull String key, @NonNull String value) {
        try {
            element.options.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @NonNull private SeekBar.OnSeekBarChangeListener seekListener(@NonNull IntConsumer consumer) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                consumer.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private interface IntConsumer { void accept(int value); }

    /** Bounded visual map value; no keyboard and no malformed numeric input. */
    @NonNull private SliderField slider(@NonNull LinearLayout parent, @NonNull String title,
                                        double initial, double minimum, double maximum,
                                        double step, @NonNull String suffix) {
        TextView valueLabel = label("");
        parent.addView(valueLabel, marginTop(7));
        SeekBar control = new SeekBar(this);
        int steps = Math.max(1, (int) Math.round((maximum - minimum) / step));
        control.setMax(steps);
        SliderField result = new SliderField(
                title, suffix, minimum, step, control, valueLabel);
        result.setValue(initial);
        control.setOnSeekBarChangeListener(seekListener(ignored -> result.updateLabel()));
        parent.addView(control);
        return result;
    }

    private static final class SliderField {
        @NonNull final String title;
        @NonNull final String suffix;
        final double minimum;
        final double step;
        @NonNull final SeekBar control;
        @NonNull final TextView valueLabel;

        SliderField(@NonNull String title, @NonNull String suffix, double minimum,
                    double step, @NonNull SeekBar control, @NonNull TextView valueLabel) {
            this.title = title;
            this.suffix = suffix;
            this.minimum = minimum;
            this.step = step;
            this.control = control;
            this.valueLabel = valueLabel;
        }

        double value() { return minimum + control.getProgress() * step; }

        int intValue() { return (int) Math.round(value()); }

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
            String rendered = Math.abs(current - Math.rint(current)) < 0.0001
                    ? Integer.toString((int) Math.rint(current))
                    : String.format(Locale.ROOT, "%.2f", current)
                            .replaceAll("0+$", "").replaceAll("\\.$", "");
            valueLabel.setText(title + ": " + rendered + suffix);
        }
    }

    private void addCameraPresets(@NonNull LinearLayout parent, @NonNull Spinner cameraMode,
                                  @NonNull SliderField zoom, @NonNull SliderField tilt,
                                  @NonNull SliderField focusX, @NonNull SliderField focusY) {
        parent.addView(label("Быстрые варианты камеры"), marginTop(7));
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

    @NonNull private Spinner navigationCameraModeSpinner(@NonNull String mode) {
        Spinner result = spinner(new String[]{
                "По маршруту", "Север всегда сверху", "По направлению движения",
                "Свободное положение"
        });
        if ("NORTH_UP".equals(mode)) result.setSelection(1);
        else if ("HEADING_UP".equals(mode)) result.setSelection(2);
        else if ("FREE".equals(mode)) result.setSelection(3);
        else result.setSelection(0);
        return result;
    }

    @NonNull private static String navigationCameraModeValue(int position) {
        if (position == 1) return "NORTH_UP";
        if (position == 2) return "HEADING_UP";
        if (position == 3) return "FREE";
        return "FOLLOW_ROUTE";
    }

    @NonNull private Spinner dayNightSpinner(boolean automatic, boolean night) {
        Spinner result = spinner(new String[]{"Автоматически", "Всегда день", "Всегда ночь"});
        result.setSelection(automatic ? 0 : night ? 2 : 1);
        return result;
    }

    private static void applyDayNight(@NonNull Spinner control,
                                      @NonNull NavigationIntegrationConfig.MapProfile map) {
        int selection = control.getSelectedItemPosition();
        map.automaticDayNight = selection == 0;
        map.nightMode = selection == 2;
    }

    @NonNull private Spinner spinner(@NonNull String[] values) {
        Spinner result = new Spinner(this);
        result.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
        return result;
    }

    @NonNull private ColorField navigationColorField(
            @NonNull LinearLayout parent,
            @NonNull String title,
            @NonNull String initial,
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull Preferences preferences,
            @NonNull ColorSelectionListener setter) {
        return colorField(parent, title, initial, selected -> {
            setter.onSelected(selected);
            persistNavigationConfiguration(navigation, preferences);
        });
    }

    @NonNull private ColorField opaqueNavigationColorField(
            @NonNull LinearLayout parent,
            @NonNull String title,
            @NonNull String initial,
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull Preferences preferences,
            @NonNull ColorSelectionListener setter) {
        return colorField(parent, title, initial,
                AppleColorPickerDialog.Options.opaque(), selected -> {
                    setter.onSelected(selected);
                    persistNavigationConfiguration(navigation, preferences);
                });
    }

    @NonNull private InheritedColorField inheritableNavigationColorField(
            @NonNull LinearLayout parent,
            @NonNull String title,
            @Nullable String initial,
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull Preferences preferences,
            @NonNull InheritedColorSelectionListener setter) {
        InheritedColorField field = new InheritedColorField(initial);
        MaterialButton button = new MaterialButton(this);
        AppleColorPickerDialog.decorateButton(
                button, title, initial, "Штатный цвет Яндекса");
        button.setOnClickListener(view -> AppleColorPickerDialog.show(
                this, title, field.value,
                AppleColorPickerDialog.Options.opaqueInheritable(),
                new AppleColorPickerDialog.Listener() {
                    private void apply(@Nullable String selected) {
                        field.value = selected;
                        AppleColorPickerDialog.decorateButton(
                                button, title, selected, "Штатный цвет Яндекса");
                    }

                    @Override public void onPreview(@Nullable String selected) {
                        apply(selected);
                    }

                    @Override public void onSelected(@Nullable String selected) {
                        apply(selected);
                        setter.onSelected(selected);
                        persistNavigationConfiguration(navigation, preferences);
                    }
                }));
        LinearLayout.LayoutParams params = marginTop(5);
        params.height = dp(52);
        parent.addView(button, params);
        return field;
    }

    private boolean persistNavigationConfiguration(
            @NonNull NavigationIntegrationConfig navigation,
            @NonNull Preferences preferences) {
        try {
            navigation.normalize();
            String encoded = navigation.toJson().toString();
            boolean stored = preferences.navigationIntegrationConfigJson.commit(encoded);
            String verified = preferences.navigationIntegrationConfigJson.get();
            if (!stored || !encoded.equals(verified)) {
                throw new IllegalStateException(
                        "Записанное значение не прошло контрольное чтение");
            }
            NavigationHudEndpointService.requestConfigurationRefresh(this);
            return true;
        } catch (Exception failure) {
            Toast.makeText(this, "Не удалось сохранить настройки карты: "
                    + failure.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @NonNull private ColorField colorField(@NonNull LinearLayout parent, @NonNull String title,
                                           @NonNull String initial) {
        return colorField(parent, title, initial,
                AppleColorPickerDialog.Options.standard(), null);
    }

    @NonNull private ColorField colorField(@NonNull LinearLayout parent, @NonNull String title,
                                           @NonNull String initial,
                                           @Nullable ColorSelectionListener selectionListener) {
        return colorField(parent, title, initial,
                AppleColorPickerDialog.Options.standard(), selectionListener);
    }

    @NonNull private ColorField colorField(@NonNull LinearLayout parent, @NonNull String title,
                                           @NonNull String initial,
                                           @NonNull AppleColorPickerDialog.Options options,
                                           @Nullable ColorSelectionListener selectionListener) {
        ColorField field = new ColorField(initial);
        MaterialButton button = new MaterialButton(this);
        button.setText(title);
        button.setAllCaps(false);
        AppleColorPickerDialog.decorateButton(button, title, initial);
        button.setOnClickListener(view -> AppleColorPickerDialog.show(
                this, title, field.value, options,
                new AppleColorPickerDialog.Listener() {
                    private boolean apply(@Nullable String selected) {
                        if (selected == null || selected.trim().isEmpty()) return false;
                        field.value = selected;
                        AppleColorPickerDialog.decorateButton(button, title, field.value);
                        return true;
                    }

                    @Override public void onPreview(@Nullable String selected) { apply(selected); }

                    @Override public void onSelected(@Nullable String selected) {
                        if (apply(selected) && selectionListener != null) {
                            selectionListener.onSelected(field.value);
                        }
                    }
                }));
        LinearLayout.LayoutParams params = marginTop(5);
        params.height = dp(52);
        parent.addView(button, params);
        return field;
    }

    private static final class ColorField {
        @NonNull String value;

        ColorField(@NonNull String value) { this.value = value; }
    }

    private static final class InheritedColorField {
        @Nullable String value;

        InheritedColorField(@Nullable String value) { this.value = value; }
    }

    private interface ColorSelectionListener {
        void onSelected(@NonNull String selected);
    }

    private interface InheritedColorSelectionListener {
        void onSelected(@Nullable String selected);
    }

    @NonNull private Button button(@NonNull String title) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setTextSize(12f);
        return view;
    }

    @NonNull private Switch switchView(@NonNull String title, boolean checked) {
        Switch view = new Switch(this);
        view.setText(title);
        view.setTextColor(Color.WHITE);
        view.setChecked(checked);
        view.setPadding(dp(8), 0, dp(8), 0);
        return view;
    }

    @NonNull private TextView label(@NonNull String value) {
        return text(value, 13, 0xFFD5DCE6);
    }

    @NonNull private TextView section(@NonNull String value) {
        TextView view = text(value, 16, Color.WHITE);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    @NonNull private TextView text(@NonNull String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    @NonNull private LinearLayout.LayoutParams fixed(int widthDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @NonNull private LinearLayout.LayoutParams marginTop(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Keeps the live editor at the exact physical 1920:720 aspect ratio. */
    private static final class DesignPreview extends FrameLayout {
        DesignPreview(@NonNull android.content.Context context) { super(context); }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
            int maxHeight = MeasureSpec.getSize(heightMeasureSpec);
            int width = maxWidth;
            int height = Math.round(width * InstrumentPanelConfig.DESIGN_HEIGHT
                    / (float) InstrumentPanelConfig.DESIGN_WIDTH);
            if (height > maxHeight) {
                height = maxHeight;
                width = Math.round(height * InstrumentPanelConfig.DESIGN_WIDTH
                        / (float) InstrumentPanelConfig.DESIGN_HEIGHT);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }
}
