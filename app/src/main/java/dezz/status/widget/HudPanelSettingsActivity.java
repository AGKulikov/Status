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
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.hud.HudCanvasView;
import dezz.status.widget.hud.HudDisplaySelector;
import dezz.status.widget.hud.HudElementConfig;
import dezz.status.widget.hud.HudElementType;
import dezz.status.widget.hud.HudPanelConfig;
import dezz.status.widget.hud.HudPanelStore;
import dezz.status.widget.hud.HudPresentationService;
import dezz.status.widget.hud.HudRuntimeData;
import dezz.status.widget.hud.HudViewportPolicy;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
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
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
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
        EditText fontSize = field(form, "Размер текста", Integer.toString(item.fontSizeSp), true);
        EditText fontWeight = field(form, "Насыщенность шрифта 100–900",
                Integer.toString(item.fontWeight), true);

        LinearLayout geometry = new LinearLayout(this);
        EditText x = compactNumber("X", item.x);
        EditText y = compactNumber("Y", item.y);
        EditText width = compactNumber("W", item.width);
        EditText height = compactNumber("H", item.height);
        geometry.addView(x, weighted());
        geometry.addView(y, weighted());
        geometry.addView(width, weighted());
        geometry.addView(height, weighted());
        form.addView(label("Ячейки сетки"), marginTop(10));
        form.addView(geometry);

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
        EditText options = field(form,
                "Все параметры (JSON): стрелки, светофоры, пороги, мигание, ориентация",
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
                        item.fontSizeSp = integer(fontSize, item.fontSizeSp);
                        item.fontWeight = integer(fontWeight, item.fontWeight);
                        item.x = integer(x, item.x);
                        item.y = integer(y, item.y);
                        item.width = integer(width, item.width);
                        item.height = integer(height, item.height);
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
                        item.options = new JSONObject(value(options).isEmpty()
                                ? "{}" : value(options));
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
        dialog.show();
    }

    private void editBackdrop(@NonNull HudElementConfig item) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(form);

        EditText title = field(form, "Название", item.title, false);
        LinearLayout geometry = new LinearLayout(this);
        EditText x = compactNumber("X", item.x);
        EditText y = compactNumber("Y", item.y);
        EditText width = compactNumber("W", item.width);
        EditText height = compactNumber("H", item.height);
        geometry.addView(x, weighted());
        geometry.addView(y, weighted());
        geometry.addView(width, weighted());
        geometry.addView(height, weighted());
        form.addView(label("Положение и размер"), marginTop(10));
        form.addView(geometry);

        EditText color = field(form, "Цвет подложки", item.backgroundColor, false);
        EditText opacity = field(form, "Прозрачность заливки 0–100 %",
                Integer.toString(item.backgroundOpacityPercent), true);
        EditText corner = field(form, "Скругление, px",
                Integer.toString(item.cornerRadiusPx), true);
        EditText borderColor = field(form, "Цвет рамки", item.borderColor, false);
        EditText borderOpacity = field(form, "Прозрачность рамки 0–100 %",
                Integer.toString(item.borderOpacityPercent), true);
        EditText borderWidth = field(form, "Толщина рамки, px",
                Integer.toString(item.borderWidthPx), true);
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
                    item.x = integer(x, item.x);
                    item.y = integer(y, item.y);
                    item.width = integer(width, item.width);
                    item.height = integer(height, item.height);
                    item.backgroundColor = value(color);
                    item.backgroundOpacityPercent =
                            integer(opacity, item.backgroundOpacityPercent);
                    item.cornerRadiusPx = integer(corner, item.cornerRadiusPx);
                    item.borderColor = value(borderColor);
                    item.borderOpacityPercent =
                            integer(borderOpacity, item.borderOpacityPercent);
                    item.borderWidthPx = integer(borderWidth, item.borderWidthPx);
                    item.enabled = itemEnabled.isChecked();
                    item.normalize(config.gridColumns, config.gridRows);
                    canvas.updateConfig(config);
                    updateSelection(item);
                    persist(false);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void editGlobalOptions() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(form);
        EditText columns = field(form, "Колонки сетки", Integer.toString(config.gridColumns), true);
        EditText rows = field(form, "Строки сетки", Integer.toString(config.gridRows), true);
        TextView hardwareBounds = text("Аппаратная область (зафиксирована): "
                + HudViewportPolicy.SAFE_WIDTH + "×" + HudViewportPolicy.SAFE_HEIGHT
                + " px, X=" + HudViewportPolicy.SAFE_LEFT
                + ", Y=" + HudViewportPolicy.SAFE_TOP + ".\n"
                + "Полная поверхность выбранного Display ID проверяется во время работы. "
                + "Панель и каждый виджет жёстко обрезаются по этой области.",
                13, 0xFFFFCC66);
        form.addView(hardwareBounds, marginTop(10));
        Spinner background = spinner(new String[]{"TRANSPARENT", "BLACK", "DIM"},
                config.backgroundMode);
        form.addView(label("Фон"), marginTop(10));
        form.addView(background);
        EditText brightness = field(form, "Общая яркость 0–100",
                Integer.toString(config.globalBrightness), true);
        EditText globalColor = field(form, "Общий цвет текста", config.globalTextColor, false);
        EditText globalUnit = field(form, "Общий цвет единиц", config.globalUnitColor, false);
        EditText fontWeight = field(form, "Общая насыщенность шрифта",
                Integer.toString(config.globalFontWeight), true);
        EditText fontUri = field(form, "URI пользовательского шрифта",
                config.customFontUri, false);
        EditText navThreshold = field(form, "Показывать навигацию до расстояния, м",
                Integer.toString(config.navigationDisplayThresholdMeters), true);
        EditText navDelay = field(form, "Задержка скрытия навигации, с",
                Integer.toString(config.navigationHideDelaySeconds), true);
        Switch showGrid = switchView("Показывать сетку в редакторе", config.showGrid);
        Switch free = switchView("Свободное перемещение между линиями", config.freeMovement);
        Switch maskStockHud = switchView(
                "Скрывать штатные машинку и скорость (AR + HUD-маска)",
                config.maskStockHud);
        TextView maskHint = text(
                "Машинка и дорога отключаются старым штатным AR-флагом через полный активный "
                        + "профиль автомобиля; цифровая скорость закрывается чёрной маской. "
                        + "AR-флаг применяется и при выключенной пользовательской HUD-панели. "
                        + "Для элементов ecarx_daemon дополнительно используется отдельный "
                        + "SurfaceFlinger-слой через локальный ADB/Telnet; при его недоступности "
                        + "остаётся обычный overlay.",
                12, 0xFFB8C0CC);
        Switch snow = switchView("Снежный режим", config.snowMode);
        Switch sync = switchView("Один цвет для всех элементов", config.syncElementColors);
        Switch autostart = switchView("Запускать HUD после перезагрузки",
                preferences.hudPanelAutostart.get());
        form.addView(showGrid, marginTop(8));
        form.addView(free);
        form.addView(maskStockHud);
        form.addView(maskHint);
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
                    config.gridColumns = integer(columns, config.gridColumns);
                    config.gridRows = integer(rows, config.gridRows);
                    config.backgroundMode = String.valueOf(background.getSelectedItem());
                    config.globalBrightness = integer(brightness, config.globalBrightness);
                    config.globalTextColor = value(globalColor);
                    config.globalUnitColor = value(globalUnit);
                    config.globalFontWeight = integer(fontWeight, config.globalFontWeight);
                    config.customFontUri = value(fontUri);
                    config.navigationDisplayThresholdMeters =
                            integer(navThreshold, config.navigationDisplayThresholdMeters);
                    config.navigationHideDelaySeconds =
                            integer(navDelay, config.navigationHideDelaySeconds);
                    config.showGrid = showGrid.isChecked();
                    config.freeMovement = free.isChecked();
                    config.maskStockHud = maskStockHud.isChecked();
                    if (!preferences.hudPanelEnabled.get()) {
                        applyStockHudPreference(config.maskStockHud);
                    }
                    config.snowMode = snow.isChecked();
                    config.syncElementColors = sync.isChecked();
                    preferences.hudPanelAutostart.set(autostart.isChecked());
                    config.normalize();
                    canvas.updateConfig(config);
                    persist(false);
                    dialog.dismiss();
                }));
        dialog.show();
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
        dialog.show();
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

    private void applyStockHudPreference(boolean hidden) {
        CarIntegrations.get(this).setStockHudCarHidden(hidden, (success, message) -> {
            if (success) return;
            String detail = message == null || message.trim().isEmpty()
                    ? "ECARX не подтвердил изменение"
                    : message.trim();
            Toast.makeText(getApplicationContext(),
                    "Штатный HUD AR: " + detail, Toast.LENGTH_LONG).show();
        });
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
                + HudPresentationService.runtimeDetail());
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

    private EditText compactNumber(String hint, int value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(Integer.toString(value));
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFF778190);
        field.setGravity(Gravity.CENTER);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
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

    private LinearLayout.LayoutParams fixed(int widthDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    private static int integer(EditText field, int fallback) {
        try { return Integer.parseInt(value(field)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
