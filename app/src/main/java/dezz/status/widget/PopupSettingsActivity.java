/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.scenario.ScenarioPresets;
import dezz.status.widget.settings.AppleColorPickerDialog;

/** Human-facing catalog and editor for any number of independent floating overlays. */
public final class PopupSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_OVERLAY_ID = "popup_overlay_id";

    private Preferences prefs;
    private PopupOverlayConfigStore overlayStore;
    private PopupItemConfigStore itemStore;
    private List<PopupOverlayConfig> overlays = new ArrayList<>();
    @Nullable private String selectedOverlayId;
    @Nullable private PopupOverlayConfig selectedOverlay;
    private List<PopupItemConfig> items = new ArrayList<>();
    private LinearLayout host;
    private TextView geometryPreview;
    private boolean firstResume = true;

    public static Intent editIntent(@NonNull Context context, @NonNull String overlayId) {
        return new Intent(context, PopupSettingsActivity.class)
                .putExtra(EXTRA_OVERLAY_ID, overlayId);
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = new Preferences(this);
        overlayStore = new PopupOverlayConfigStore(prefs);
        itemStore = new PopupItemConfigStore(prefs);
        selectedOverlayId = getIntent().getStringExtra(EXTRA_OVERLAY_ID);
        reload();
        showContent();
    }

    @Override protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        reload();
        showContent();
    }

    private void showContent() {
        View screen = selectedOverlayId == null ? buildCatalogScreen() : buildEditorScreen();
        setContentView(screen);
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    private void reload() {
        overlays = new ArrayList<>(overlayStore.load());
        selectedOverlay = null;
        if (selectedOverlayId != null) {
            for (PopupOverlayConfig overlay : overlays) {
                if (selectedOverlayId.equals(overlay.id)) {
                    selectedOverlay = overlay;
                    break;
                }
            }
            items = selectedOverlay == null ? new ArrayList<>()
                    : new ArrayList<>(itemStore.load(selectedOverlay.id));
        }
    }

    private View buildCatalogScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = page(scroll);
        page.addView(header("Всплывающие оверлеи", this::finish), matchWrap());
        page.addView(label("Каждый оверлей — отдельное плавающее окно со своей сеткой, "
                + "размером, положением и плитками. Сценарий показывает или скрывает окно "
                + "целиком."), topMargin(8));

        Button add = button("Добавить оверлей");
        add.setOnClickListener(v -> addOverlay());
        page.addView(add, topMargin(14));

        host = column();
        page.addView(host, topMargin(8));
        renderOverlayCards();
        return scroll;
    }

    private View buildEditorScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = page(scroll);
        if (selectedOverlay == null) {
            page.addView(header("Оверлей не найден", this::finish), matchWrap());
            page.addView(label("Возможно, он был удалён в другом окне."), topMargin(12));
            return scroll;
        }
        PopupOverlayConfig overlay = selectedOverlay;
        page.addView(header(overlay.name, this::finish), matchWrap());
        page.addView(label("ID: " + overlay.id
                + " · позиция сохраняется после перетаскивания окна"), topMargin(5));

        LinearLayout toggles = row();
        CheckBox enabled = check("Оверлей включён", overlay.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> {
            overlay.enabled = value;
            persistOverlay();
        });
        toggles.addView(enabled, weighted());
        CheckBox defaultVisible = check("Показывать без сценария", overlay.defaultVisible);
        defaultVisible.setOnCheckedChangeListener((v, value) -> {
            overlay.defaultVisible = value;
            persistOverlay();
        });
        toggles.addView(defaultVisible, weighted());
        page.addView(toggles, topMargin(8));
        CheckBox positionLocked = check(
                "Заблокировать перемещение оверлея", overlay.positionLocked);
        positionLocked.setOnCheckedChangeListener((v, value) -> {
            overlay.positionLocked = value;
            persistOverlay();
        });
        page.addView(positionLocked, topMargin(8));
        page.addView(label("Сначала переместите окно в нужное место, затем включите блокировку. "
                + "Плитки продолжат нажиматься по всей площади, но окно больше не сдвинется."),
                topMargin(3));
        page.addView(label("Если «Показывать без сценария» выключено, окно останется скрытым, "
                + "пока автоматизация явно его не покажет. Потеря связи не меняет последнее "
                + "определённое состояние."), topMargin(4));

        page.addView(heading("Размер и сетка", 20), topMargin(20));
        geometryPreview = label(geometryText(overlay));
        page.addView(geometryPreview, topMargin(5));
        addSlider(page, "Ширина оверлея", 200, 1600, overlay.width,
                value -> overlay.width = value, " px");
        addSlider(page, "Высота оверлея", 160, 1200, overlay.height,
                value -> overlay.height = value, " px");
        addSlider(page, "Столбцов в сетке", 1, 8, overlay.columns,
                value -> overlay.columns = value, "");
        addSlider(page, "Строк в сетке", 1, 8, overlay.rows,
                value -> overlay.rows = value, "");
        addSlider(page, "Промежуток между ячейками", 0, 60, overlay.cellGap,
                value -> overlay.cellGap = value, " px");
        addSlider(page, "Отступ сетки слева", 0, 100, overlay.paddingLeft,
                value -> overlay.paddingLeft = value, " px");
        addSlider(page, "Отступ сетки сверху", 0, 100, overlay.paddingTop,
                value -> overlay.paddingTop = value, " px");
        addSlider(page, "Отступ сетки справа", 0, 100, overlay.paddingRight,
                value -> overlay.paddingRight = value, " px");
        addSlider(page, "Отступ сетки снизу", 0, 100, overlay.paddingBottom,
                value -> overlay.paddingBottom = value, " px");
        addSlider(page, "Прозрачность фона", 0, 255, overlay.backgroundAlpha,
                value -> overlay.backgroundAlpha = value, " / 255");
        addSlider(page, "Скругление окна", 0, 120, overlay.cornerRadius,
                value -> overlay.cornerRadius = value, " px");
        MaterialButton background = new MaterialButton(this);
        AppleColorPickerDialog.decorateButton(background, "Цвет фона", overlay.backgroundColor);
        background.setOnClickListener(v -> {
            String original = overlay.backgroundColor;
            AppleColorPickerDialog.show(this, "Цвет фона", original,
                    AppleColorPickerDialog.Options.standard(),
                    new AppleColorPickerDialog.Listener() {
                        private void apply(@Nullable String selected) {
                            overlay.backgroundColor = selected == null ? original : selected;
                            AppleColorPickerDialog.decorateButton(background, "Цвет фона",
                                    overlay.backgroundColor);
                            persistOverlay();
                        }

                        @Override public void onPreview(@Nullable String selected) {
                            apply(selected);
                        }

                        @Override public void onSelected(@Nullable String selected) {
                            apply(selected);
                        }
                    });
        });
        page.addView(background, topMargin(8));

        page.addView(heading("Плитки", 20), topMargin(24));
        page.addView(label("Выберите источник, затем настройте каждую плитку визуально."),
                topMargin(5));
        LinearLayout add = row();
        Button ha = button("+ HA");
        ha.setOnClickListener(v -> startActivity(new Intent(this,
                HomeAssistantSettingsActivity.class).putExtra(EXTRA_OVERLAY_ID, overlay.id)));
        add.addView(ha, weighted());
        Button mqtt = button("+ MQTT");
        mqtt.setOnClickListener(v -> addMqtt());
        add.addView(mqtt, weighted());
        Button sprut = button("+ Sprut");
        sprut.setOnClickListener(v -> startActivity(new Intent(this,
                SprutHubSettingsActivity.class).putExtra(EXTRA_OVERLAY_ID, overlay.id)));
        add.addView(sprut, weighted());
        Button builtin = button("+ Штатный");
        builtin.setOnClickListener(v -> addBuiltin());
        add.addView(builtin, weighted());
        page.addView(add, topMargin(10));

        host = column();
        page.addView(host, topMargin(8));
        renderTileCards();
        return scroll;
    }

    private void renderOverlayCards() {
        if (host == null) return;
        host.removeAllViews();
        if (overlays.isEmpty()) {
            host.addView(label("Оверлеев пока нет. Нажмите «Добавить оверлей»."), topMargin(10));
            return;
        }
        for (int index = 0; index < overlays.size(); index++) {
            PopupOverlayConfig overlay = overlays.get(index);
            LinearLayout card = card();
            LinearLayout title = row();
            LinearLayout text = column();
            text.addView(heading(overlay.name, 18));
            int tileCount = itemStore.load(overlay.id).size();
            text.addView(label(overlay.width + "×" + overlay.height + " px · сетка "
                    + overlay.columns + "×" + overlay.rows + " · плиток: " + tileCount
                    + (overlay.positionLocked ? " · положение закреплено" : "")));
            title.addView(text, weighted());
            CheckBox enabled = check("Вкл.", overlay.enabled);
            enabled.setOnCheckedChangeListener((v, value) -> {
                overlay.enabled = value;
                persistOverlays();
            });
            title.addView(enabled);
            card.addView(title, matchWrap());
            LinearLayout actions = row();
            Button up = button("↑");
            int position = index;
            up.setEnabled(position > 0);
            up.setOnClickListener(v -> moveOverlay(overlay, -1));
            actions.addView(up);
            Button down = button("↓");
            down.setEnabled(position < overlays.size() - 1);
            down.setOnClickListener(v -> moveOverlay(overlay, 1));
            actions.addView(down);
            Button open = button("Настроить");
            open.setOnClickListener(v -> startActivity(editIntent(this, overlay.id)));
            actions.addView(open, weighted());
            Button rename = button("Название");
            rename.setOnClickListener(v -> renameOverlay(overlay));
            actions.addView(rename, weighted());
            Button copy = button("Копия");
            copy.setOnClickListener(v -> duplicateOverlay(overlay));
            actions.addView(copy, weighted());
            Button delete = button("Удалить");
            delete.setOnClickListener(v -> deleteOverlay(overlay));
            actions.addView(delete, weighted());
            card.addView(actions, topMargin(8));
            host.addView(card, topMargin(10));
        }
    }

    private void renderTileCards() {
        if (host == null) return;
        host.removeAllViews();
        if (items.isEmpty()) {
            host.addView(label("Плиток в этом оверлее пока нет."), topMargin(10));
            return;
        }
        for (PopupItemConfig item : items) host.addView(tileCard(item), topMargin(10));
    }

    private View tileCard(PopupItemConfig item) {
        LinearLayout card = card();
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout description = column();
        description.addView(heading(item.name, 18));
        description.addView(label(sourceLabel(item)));
        top.addView(description, weighted());
        CheckBox enabled = check("Показывать", item.enabled);
        enabled.setOnCheckedChangeListener((v, value) -> {
            item.enabled = value;
            persistItems();
        });
        top.addView(enabled);
        card.addView(top, matchWrap());
        card.addView(label("Сетка: " + item.columnSpan + "×" + item.rowSpan + " · иконка: "
                + item.icon + " · " + (item.actionBinding != null && item.actionBinding.isBound()
                ? "управление назначено" : "только отображение")), topMargin(5));
        LinearLayout actions = row();
        Button up = button("↑"); up.setOnClickListener(v -> moveItem(item, -1)); actions.addView(up);
        Button down = button("↓"); down.setOnClickListener(v -> moveItem(item, 1)); actions.addView(down);
        Button edit = button("Оформление и правила");
        edit.setOnClickListener(v -> startActivity(VisualBrickEditorActivity.intent(this,
                VisualBrickEditorActivity.SURFACE_POPUP, item.id)));
        actions.addView(edit, weighted());
        Button delete = button("Удалить");
        delete.setOnClickListener(v -> confirmDeleteItem(item));
        actions.addView(delete, weighted());
        card.addView(actions, topMargin(7));
        return card;
    }

    private void addOverlay() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Например: Подъезд к дому");
        new AlertDialog.Builder(this).setTitle("Новый всплывающий оверлей")
                .setView(name).setNegativeButton("Отмена", null)
                .setPositiveButton("Создать", (d, w) -> {
                    try {
                        String id = uniqueOverlayId();
                        PopupOverlayConfig overlay = PopupOverlayConfig.create(id,
                                value(name).isEmpty() ? "Оверлей " + (overlays.size() + 1)
                                        : value(name), overlays.size());
                        overlays.add(overlay);
                        persistOverlays();
                        startActivity(editIntent(this, overlay.id));
                    } catch (Exception error) {
                        showError(error);
                    }
                }).show();
    }

    private void renameOverlay(PopupOverlayConfig overlay) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(overlay.name);
        new AlertDialog.Builder(this).setTitle("Название оверлея")
                .setView(name).setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    try {
                        String next = value(name);
                        if (next.isEmpty()) return;
                        overlay.setName(next);
                        persistOverlays();
                        renderOverlayCards();
                    } catch (Exception error) {
                        showError(error);
                    }
                }).show();
    }

    private void duplicateOverlay(PopupOverlayConfig source) {
        try {
            String nextId = uniqueOverlayId();
            PopupOverlayConfig copy = source.copy(nextId, source.name + " — копия",
                    overlays.size());
            overlays.add(copy);

            List<PopupItemConfig> all = new ArrayList<>(itemStore.load());
            Set<String> ids = new HashSet<>();
            Set<String> automationIds = new HashSet<>();
            for (PopupItemConfig item : all) {
                ids.add(item.id);
                automationIds.add(item.automationId);
            }
            for (PopupItemConfig item : itemStore.load(source.id)) {
                JSONObject json = item.toJson();
                String id = uniqueValue(item.id + "_copy", ids);
                String automationId = uniqueValue(item.automationId + "_copy", automationIds);
                json.put("id", id).put("automationId", automationId)
                        .put("overlayId", nextId).put("order", all.size());
                if (!item.actionId.isEmpty()) json.put("actionId", id + "_action");
                all.add(PopupItemConfig.fromJson(json, all.size()));
            }
            overlayStore.save(overlays);
            itemStore.save(all);
            apply();
            renderOverlayCards();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void deleteOverlay(PopupOverlayConfig overlay) {
        new AlertDialog.Builder(this).setTitle("Удалить оверлей?")
                .setMessage(overlay.name + "\n\nБудут удалены все его плитки. Сценарии, "
                        + "которые ссылались на этот оверлей, потребуется перенастроить.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        overlays.remove(overlay);
                        overlayStore.save(overlays);
                        itemStore.deleteOverlay(overlay.id);
                        apply();
                        renderOverlayCards();
                    } catch (Exception error) {
                        showError(error);
                    }
                }).show();
    }

    private void moveOverlay(PopupOverlayConfig overlay, int delta) {
        int from = overlays.indexOf(overlay), to = from + delta;
        if (from < 0 || to < 0 || to >= overlays.size()) return;
        Collections.swap(overlays, from, to);
        persistOverlays();
        renderOverlayCards();
    }

    private void addMqtt() {
        if (selectedOverlay == null) return;
        LinearLayout form = column();
        form.setPadding(dp(18), dp(4), dp(18), 0);
        EditText name = field(form, "Название плитки", "MQTT");
        EditText topic = field(form, "Topic состояния", "");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Новая MQTT-плитка")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton("Далее", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (value(topic).isEmpty()) {
                        Toast.makeText(this, "Укажите topic состояния", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        PopupItemConfig item = PopupItemConfig.create(uniqueItemId("mqtt_popup"),
                                items.size());
                        item.overlayId = selectedOverlay.id;
                        item.name = value(name).isEmpty() ? "MQTT" : value(name);
                        item.title = item.name;
                        item.sourceBinding = new SourceBinding(ConnectorType.MQTT,
                                SourceBinding.DEFAULT_CONNECTOR_ID, value(topic), "",
                                SourceBinding.PRESENTATION_RAW, "");
                        item.displayRules = ScenarioPresets.raw();
                        item.actionBinding = ActionBinding.unbound();
                        items.add(item);
                        persistItems();
                        dialog.dismiss();
                        startActivity(VisualBrickEditorActivity.intent(this,
                                VisualBrickEditorActivity.SURFACE_POPUP, item.id));
                    } catch (Exception error) {
                        showError(error);
                    }
                }));
        dialog.show();
    }

    private void addBuiltin() {
        if (selectedOverlay == null) return;
        BrickType[] types = BrickType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = friendlyBrick(types[i]);
        new AlertDialog.Builder(this).setTitle("Штатный элемент")
                .setItems(labels, (d, which) -> {
                    try {
                        BrickType type = types[which];
                        PopupItemConfig item = PopupItemConfig.create(uniqueItemId("builtin"),
                                items.size());
                        item.overlayId = selectedOverlay.id;
                        item.type = PopupItemConfig.TYPE_BUILTIN;
                        item.builtinId = type.automationId();
                        item.name = labels[which];
                        item.title = labels[which];
                        item.sourceBinding = SourceBinding.unbound();
                        item.actionBinding = ActionBinding.unbound();
                        items.add(item);
                        persistItems();
                        startActivity(VisualBrickEditorActivity.intent(this,
                                VisualBrickEditorActivity.SURFACE_POPUP, item.id));
                    } catch (Exception error) {
                        showError(error);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void moveItem(PopupItemConfig item, int delta) {
        int from = items.indexOf(item), to = from + delta;
        if (from < 0 || to < 0 || to >= items.size()) return;
        Collections.swap(items, from, to);
        persistItems();
        renderTileCards();
    }

    private void confirmDeleteItem(PopupItemConfig item) {
        new AlertDialog.Builder(this).setTitle("Удалить плитку?").setMessage(item.name)
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (d, w) -> {
                    items.remove(item);
                    persistItems();
                    renderTileCards();
                }).show();
    }

    private void addSlider(LinearLayout parent, String title, int min, int max, int current,
                           IntConsumer setter, String suffix) {
        LinearLayout labels = row();
        labels.addView(label(title), weighted());
        TextView value = label(current + suffix);
        labels.addView(value);
        parent.addView(labels, topMargin(8));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(clamp(current, min, max) - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = min + progress;
                value.setText(selected + suffix);
                setter.accept(selected);
                if (geometryPreview != null) geometryPreview.setText(geometryText(selectedOverlay));
                persistOverlay();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        parent.addView(seek, matchWrap());
    }

    private void persistOverlay() {
        if (selectedOverlay == null) return;
        try {
            overlayStore.save(overlays);
            if (WidgetService.isRunning()) {
                WidgetService.getInstance().applyPopupPreferences();
            }
        } catch (Exception error) {
            showError(error);
        }
    }

    private void persistOverlays() {
        try {
            overlayStore.save(overlays);
            apply();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void persistItems() {
        if (selectedOverlay == null) return;
        try {
            itemStore.save(selectedOverlay.id, items);
            apply();
        } catch (Exception error) {
            showError(error);
        }
    }

    private void apply() {
        if (WidgetService.isRunning()) WidgetService.getInstance().applyPreferences();
    }

    private String uniqueOverlayId() {
        Set<String> used = new HashSet<>();
        for (PopupOverlayConfig overlay : overlays) used.add(overlay.id);
        int suffix = 1;
        while (used.contains("overlay_" + suffix)) suffix++;
        return "overlay_" + suffix;
    }

    private String uniqueItemId(String prefix) {
        Set<String> used = new HashSet<>();
        for (PopupItemConfig item : itemStore.load()) used.add(item.id);
        return uniqueValue(prefix, used);
    }

    private static String uniqueValue(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate)) candidate = base + "_" + suffix++;
        used.add(candidate);
        return candidate;
    }

    private static String geometryText(@Nullable PopupOverlayConfig overlay) {
        if (overlay == null) return "";
        int columns = Math.max(1, overlay.columns);
        int rows = Math.max(1, overlay.rows);
        int usableWidth = Math.max(1, overlay.width - overlay.paddingLeft - overlay.paddingRight
                - overlay.cellGap * (columns - 1));
        int usableHeight = Math.max(1, overlay.height - overlay.paddingTop - overlay.paddingBottom
                - overlay.cellGap * (rows - 1));
        return "Оверлей: " + overlay.width + "×" + overlay.height + " px · сетка: "
                + columns + "×" + rows + "\nРазмер одной ячейки: примерно "
                + (usableWidth / columns) + "×" + (usableHeight / rows) + " px";
    }

    private static String sourceLabel(PopupItemConfig item) {
        if (PopupItemConfig.TYPE_BUILTIN.equals(item.type)) {
            return "Штатный элемент · " + item.builtinId;
        }
        SourceBinding source = item.sourceBinding;
        if (source == null || !source.isBound()) return "Без источника";
        String connector;
        switch (source.connectorType) {
            case HOME_ASSISTANT: connector = "Home Assistant"; break;
            case SPRUTHUB: connector = "Sprut.hub"; break;
            case PHONE: connector = "Телефон"; break;
            case MQTT: connector = "MQTT"; break;
            default: connector = source.connectorType.jsonName(); break;
        }
        return connector + " · " + source.resourceId
                + (source.valuePath.isEmpty() ? "" : " · " + source.valuePath);
    }

    private static String friendlyBrick(BrickType type) {
        switch (type) {
            case TIME: return "Время";
            case DATE: return "Дата";
            case MEDIA: return "Мультимедиа";
            case WIFI: return "Wi‑Fi";
            case GPS: return "GPS";
            case BLUETOOTH: return "Bluetooth";
            case INDOOR_TEMP: return "Температура в салоне";
            case OUTDOOR_TEMP: return "Температура снаружи";
            case HOME_ASSISTANT: return "Устройства умного дома";
            default: return type.name();
        }
    }

    private void showError(Throwable error) {
        Toast.makeText(this, "Не удалось сохранить: " + safeMessage(error), Toast.LENGTH_LONG).show();
    }

    private LinearLayout page(NestedScrollView scroll) {
        LinearLayout page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(44));
        scroll.addView(page, matchWrap());
        return page;
    }

    private LinearLayout header(String title, Runnable backAction) {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹");
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> backAction.run());
        header.addView(back, new LinearLayout.LayoutParams(dp(60), dp(52)));
        header.addView(heading(title, 24), weighted());
        return header;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceColor());
        background.setStroke(dp(1), 0x557F7F7F);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        return card;
    }

    private EditText field(LinearLayout parent, String title, String text) {
        parent.addView(label(title), topMargin(8));
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(text);
        parent.addView(edit, matchWrap());
        return edit;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox view = new CheckBox(this);
        view.setText(text);
        view.setChecked(checked);
        return view;
    }

    private int surfaceColor() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFF202124 : 0xFFF5F5F5;
    }

    private TextView heading(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        return view;
    }

    private Button button(String text) {
        Button view = new Button(this);
        view.setText(text);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams topMargin(int value) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(value);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String value(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

}
