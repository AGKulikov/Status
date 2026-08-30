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
        LinearLayout content = dialogColumn();
        Switch mapEnabled = switchView("Показывать карту", map.enabled);
        Switch traffic = switchView("Все пробки", map.showTraffic);
        Switch routeTraffic = switchView("Пробки на маршруте", map.showRouteTraffic);
        Switch trafficLights = switchView(
                "Светофоры с отсчётом — отдельный слой", map.showTrafficLights);
        Switch pois = switchView("Объекты на карте", map.showPois);
        Switch buildings = switchView("Здания", map.showBuildings);
        Switch labels = switchView("Подписи дорог", map.showLabels);
        Switch models = switchView("3D-модели (повышенная нагрузка)", map.showModels);
        Switch roadsOnly = switchView("Только дороги — прозрачный фон", map.roadsOnly);
        content.addView(mapEnabled);
        content.addView(traffic);
        content.addView(routeTraffic);
        content.addView(trafficLights);
        content.addView(pois);
        content.addView(buildings);
        content.addView(labels);
        content.addView(models);
        content.addView(roadsOnly);
        Button roadEvents = button("Дорожные события — типы и режимы");
        roadEvents.setOnClickListener(view -> editClusterRoadEvents(
                finalNavigation, map, preferences));
        content.addView(roadEvents);
        TextView fpsValue = label("Максимум: " + map.maximumFps
                + " FPS · на стоянке автоматически 15 FPS");
        SeekBar fps = new SeekBar(this);
        fps.setMax(45);
        fps.setProgress(Math.max(15, map.maximumFps) - 15);
        fps.setOnSeekBarChangeListener(seekListener(value -> fpsValue.setText(
                "Максимум: " + (value + 15) + " FPS · на стоянке автоматически 15 FPS")));
        content.addView(fpsValue);
        content.addView(fps);

        new AlertDialog.Builder(this)
                .setTitle("Независимая карта приборной панели")
                .setView(content)
                .setPositiveButton("Применить", (dialog, which) -> {
                    map.enabled = mapEnabled.isChecked();
                    map.showTraffic = traffic.isChecked();
                    map.showRouteTraffic = routeTraffic.isChecked();
                    map.showTrafficLights = trafficLights.isChecked();
                    map.showPois = pois.isChecked();
                    map.showBuildings = buildings.isChecked();
                    map.showLabels = labels.isChecked();
                    map.showModels = models.isChecked();
                    map.roadsOnly = roadsOnly.isChecked();
                    map.maximumFps = fps.getProgress() + 15;
                    try {
                        preferences.navigationIntegrationConfigJson.set(
                                finalNavigation.toJson().toString());
                        NavigationHudEndpointService.requestConfigurationRefresh(this);
                        // Make the projected panel re-read map enable/opacity immediately. When
                        // disabled this revokes its Surface instead of retaining an idle lease.
                        sendBroadcast(new android.content.Intent(
                                InstrumentPanelStore.ACTION_CONFIG_CHANGED)
                                .setPackage(getPackageName()));
                        preview.updateConfig(config);
                    } catch (JSONException impossible) {
                        throw new IllegalStateException(impossible);
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
                + "при активном маршруте. Направление камер берётся из штатных данных Яндекса.",
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
                    try {
                        preferences.navigationIntegrationConfigJson.set(
                                navigation.toJson().toString());
                        NavigationHudEndpointService.requestConfigurationRefresh(this);
                    } catch (JSONException impossible) {
                        throw new IllegalStateException(impossible);
                    }
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
