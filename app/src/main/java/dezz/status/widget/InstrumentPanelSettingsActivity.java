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
import java.util.List;
import java.util.Locale;

import dezz.status.widget.instrument.InstrumentClusterView;
import dezz.status.widget.instrument.InstrumentDisplayLauncher;
import dezz.status.widget.instrument.InstrumentElementConfig;
import dezz.status.widget.instrument.InstrumentElementType;
import dezz.status.widget.instrument.InstrumentPanelConfig;
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
        Button theme = button("Стиль панели");
        theme.setOnClickListener(view -> choosePanelStyle());
        row.addView(theme);
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

    private void choosePanelStyle() {
        InstrumentStyleFamily[] styles = InstrumentStyleFamily.values();
        String[] labels = styleLabels(styles);
        new AlertDialog.Builder(this)
                .setTitle("Стиль всей панели")
                .setSingleChoiceItems(labels, config.defaultStyle.ordinal(), (dialog, which) -> {
                    config.defaultStyle = styles[which];
                    for (InstrumentElementConfig element : config.elements) {
                        element.style = styles[which];
                    }
                    dialog.dismiss();
                    refresh(null, true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editSelected() {
        InstrumentElementConfig element = preview.instruments().selected();
        if (element == null) return;
        LinearLayout content = dialogColumn();
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
                .setView(content)
                .setPositiveButton("Применить", (dialog, which) -> {
                    element.style = styles[style.getSelectedItemPosition()];
                    element.enabled = visible.isChecked();
                    element.responseMillis = response.getProgress();
                    element.opacityPercent = opacity.getProgress() + 10;
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
        SliderField tilt = slider(content,
                "Наклон: 0° — сверху, 60° — перспектива",
                map.tiltDegrees, 0, 80, 1, "°");
        SliderField focusX = slider(content, "Автомобиль по горизонтали",
                map.focusXPercent, 0, 100, 1, " %");
        SliderField focusY = slider(content, "Автомобиль по вертикали",
                map.focusYPercent, 0, 100, 1, " %");
        SliderField mapScale = slider(content, "Размер подписей и объектов",
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
        Switch routeTraffic = switchView("Пробки на линии маршрута", map.showRouteTraffic);
        Switch traffic = switchView("Пробки на остальных дорогах", map.showTraffic);
        Switch trafficLights = switchView(
                "Светофоры с отсчётом — отдельный слой", map.showTrafficLights);
        Switch laneGuidance = switchView(
                "Подсказки по полосам — слой на маршруте", map.showLaneGuidance);
        Switch labels = switchView("Подписи дорог", map.showLabels);
        Switch pois = switchView("Полезные места", map.showPois);
        Switch buildings = switchView("Здания", map.showBuildings);
        Switch parks = switchView("Парки", map.showParks);
        Switch water = switchView("Вода", map.showWater);
        Switch models = switchView("3D-модели (повышенная нагрузка)", map.showModels);
        Switch cursor = switchView("Курсор автомобиля", map.showCursor);
        Switch roadsOnly = switchView("Только дороги — прозрачный фон", map.roadsOnly);
        content.addView(route);
        content.addView(traffic);
        content.addView(routeTraffic);
        content.addView(trafficLights);
        content.addView(laneGuidance);
        content.addView(labels);
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

        content.addView(section("Маршрут и курсор"), marginTop(14));
        SliderField cursorScale = slider(content, "Размер курсора",
                map.cursorScalePercent, 25, 300, 5, " %");
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
        SliderField routeWidth = slider(content, "Толщина маршрута",
                map.routeWidth, 1, 40, 0.5, " px");
        SliderField routeOutlineWidth = slider(content, "Толщина контура маршрута",
                map.routeOutlineWidth, 0, 20, 0.5, " px");

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
                    map.tiltDegrees = tilt.intValue();
                    map.focusXPercent = focusX.intValue();
                    map.focusYPercent = focusY.intValue();
                    map.mapScalePercent = mapScale.intValue();
                    map.maximumFps = maximumFps.intValue();
                    applyDayNight(dayNight, map);
                    map.showRoute = route.isChecked();
                    map.showTraffic = traffic.isChecked();
                    map.showRouteTraffic = routeTraffic.isChecked();
                    map.showTrafficLights = trafficLights.isChecked();
                    map.showLaneGuidance = laneGuidance.isChecked();
                    map.showPois = pois.isChecked();
                    map.showBuildings = buildings.isChecked();
                    map.showLabels = labels.isChecked();
                    map.showParks = parks.isChecked();
                    map.showWater = water.isChecked();
                    map.showModels = models.isChecked();
                    map.showCursor = cursor.isChecked();
                    map.roadsOnly = roadsOnly.isChecked();
                    map.cursorScalePercent = cursorScale.intValue();
                    map.cursorColor = cursorColor.value;
                    map.cursorOutlineColor = cursorOutline.value;
                    map.routeColor = routeColor.value;
                    map.routeOutlineColor = routeOutline.value;
                    map.routeWidth = routeWidth.value();
                    map.routeOutlineWidth = routeOutlineWidth.value();
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
        return colorField(parent, title, initial, null);
    }

    @NonNull private ColorField colorField(@NonNull LinearLayout parent, @NonNull String title,
                                           @NonNull String initial,
                                           @Nullable ColorSelectionListener selectionListener) {
        ColorField field = new ColorField(initial);
        MaterialButton button = new MaterialButton(this);
        button.setText(title);
        button.setAllCaps(false);
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

    private interface ColorSelectionListener {
        void onSelected(@NonNull String selected);
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
