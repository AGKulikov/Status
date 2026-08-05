/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import dezz.status.widget.launcher.panels.PanelContentEditOverlay;
import dezz.status.widget.phone.PhoneNotificationAutomation;
import dezz.status.widget.phone.PhoneNotificationCardView;
import dezz.status.widget.phone.PhoneNotificationEditorPreviewSession;
import dezz.status.widget.phone.PhoneNotificationLayoutConfig;
import dezz.status.widget.phone.PhoneNotificationLayoutConfigStore;
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Dedicated WYSIWYG editor for the single-piece CarPlay notification hierarchy. */
public final class PhoneNotificationLayoutEditorActivity extends AppCompatActivity {
    private static final String EXTRA_OVERLAY_ID = "phone_notification_layout_overlay_id";

    private Preferences prefs;
    private PhoneNotificationLayoutConfigStore layoutStore;
    private PopupOverlayConfigStore overlayStore;
    private PhoneNotificationLayoutConfig config;
    private PopupOverlayConfig overlay;
    private FrameLayout previewSurface;
    private PhoneNotificationCardView previewCard;
    private PanelContentEditOverlay editOverlay;
    private PhoneNotificationEditorPreviewSession previewSession;

    @NonNull
    public static Intent intent(@NonNull Context context, @NonNull String overlayId) {
        if (!PhoneNotificationAutomation.isNotificationOverlayId(overlayId)) {
            throw new IllegalArgumentException("Unsupported phone notification overlay");
        }
        return new Intent(context, PhoneNotificationLayoutEditorActivity.class)
                .putExtra(EXTRA_OVERLAY_ID, overlayId);
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        String overlayId = getIntent().getStringExtra(EXTRA_OVERLAY_ID);
        if (!PhoneNotificationAutomation.isNotificationOverlayId(overlayId)) {
            finish();
            return;
        }
        prefs = new Preferences(this);
        try {
            PhoneNotificationAutomation.ensureConfigured(prefs);
        } catch (Exception failure) {
            Toast.makeText(this, "Не удалось подготовить оверлей: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        layoutStore = new PhoneNotificationLayoutConfigStore(prefs);
        overlayStore = new PopupOverlayConfigStore(prefs);
        config = layoutStore.load(overlayId);
        overlay = overlayStore.find(overlayId);
        if (overlay == null) {
            finish();
            return;
        }
        previewSession = new PhoneNotificationEditorPreviewSession(this, overlayId);
        View screen = buildScreen();
        setContentView(screen);
        SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    @Override protected void onResume() {
        super.onResume();
        if (previewSession != null) previewSession.onResume();
    }

    @Override protected void onPause() {
        if (previewSession != null) previewSession.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (previewSession != null) previewSession.close();
        super.onDestroy();
    }

    @NonNull
    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(16), dp(20), dp(48));
        scroll.addView(page, new ViewGroup.LayoutParams(match(), wrap()));

        LinearLayout header = row();
        Button back = button("‹ Назад");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(160), dp(52)));
        header.addView(text("Уведомление в стиле CarPlay", 25, Color.WHITE),
                new LinearLayout.LayoutParams(0, wrap(), 1f));
        page.addView(header);
        page.addView(text("Тестовое уведомление одновременно показано в реальном оверлее. "
                + "Ниже перемещайте любой элемент и тяните его углы для изменения размера.",
                14, 0xFFC7C7CC), top(8));

        FrameLayout preview = new FrameLayout(this);
        previewSurface = preview;
        preview.setClipChildren(false);
        GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setColor(0xFF111827);
        previewBackground.setCornerRadius(dp(18));
        preview.setBackground(previewBackground);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        previewCard = new PhoneNotificationCardView(this);
        previewCard.setPresentation(config, PhoneNotificationCardView.Model.preview());
        preview.addView(previewCard, new FrameLayout.LayoutParams(match(), match()));
        editOverlay = new PanelContentEditOverlay(this);
        editOverlay.setModel(new LayoutModel(), new PanelContentEditOverlay.Listener() {
            @Override public void onPlacementChanged(@NonNull String id, boolean finished) {
                previewCard.setPresentation(config, PhoneNotificationCardView.Model.preview());
                if (finished) persistLayout();
            }

            @Override public void onItemClicked(@NonNull String id) {
                PhoneNotificationLayoutConfig.Element element = config.element(id);
                if (element != null) Toast.makeText(
                        PhoneNotificationLayoutEditorActivity.this,
                        "Точные параметры «" + element.label + "» находятся ниже",
                        Toast.LENGTH_SHORT).show();
            }
        });
        editOverlay.setEditing(true);
        preview.addView(editOverlay, new FrameLayout.LayoutParams(match(), match()));
        page.addView(preview, topSize(14, overlay.width, overlay.height));

        Button preset = button("Восстановить компоновку CarPlay");
        preset.setOnClickListener(view -> {
            config = PhoneNotificationLayoutConfig.carPlay(config.overlayId);
            persistLayout();
            // Rebuild once so both the visual model and every exact-value control point at the
            // newly restored object. Temporarily replacing the edit-overlay listener used to
            // leave a short interval in which a touch could no longer be persisted.
            rebuild();
        });
        page.addView(preset, top(10));
        if (!PhoneNotificationAutomation.isIconOverlayId(config.overlayId)) {
            Button copyStyle = button("Копировать стиль из уведомления со значком");
            copyStyle.setOnClickListener(view -> {
                PhoneNotificationLayoutConfig source = layoutStore.load(
                        PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
                config.copyStyleFrom(source);
                persistLayout();
                Toast.makeText(this, "Стиль скопирован; компоновка без значка сохранена",
                        Toast.LENGTH_SHORT).show();
                rebuild();
            });
            page.addView(copyStyle, top(8));
        }

        page.addView(text("Окно", 20, Color.WHITE), top(20));
        slider(page, "Ширина", 400, 1600, overlay.width, value -> overlay.width = value);
        slider(page, "Высота", 110, 500, overlay.height, value -> overlay.height = value);
        slider(page, "Положение X", -200, 1800, overlay.x, value -> overlay.x = value);
        slider(page, "Положение Y", -100, 700, overlay.y, value -> overlay.y = value);
        page.addView(checkBox("По центру экрана по горизонтали", overlay.centerHorizontally,
                checked -> overlay.centerHorizontally = checked), top(4));
        page.addView(checkBox("По центру экрана по вертикали", overlay.centerVertically,
                checked -> overlay.centerVertically = checked), top(2));
        slider(page, "Скругление карточки", 0, 160, config.cornerRadiusPx,
                value -> config.cornerRadiusPx = value);
        slider(page, "Толщина обводки", 0, 40, config.borderWidthPx,
                value -> config.borderWidthPx = value);
        slider(page, "Прозрачность фона", 0, 255, config.backgroundAlpha,
                value -> config.backgroundAlpha = value);
        slider(page, "Скругление аватара", 0, 120, config.avatarCornerRadiusPx,
                value -> config.avatarCornerRadiusPx = value);
        slider(page, "Скругление иконки приложения", 0, 120, config.iconCornerRadiusPx,
                value -> config.iconCornerRadiusPx = value);
        colorButton(page, "Цвет карточки", () -> config.backgroundColor,
                value -> config.backgroundColor = value);
        colorButton(page, "Цвет обводки", () -> config.borderColor,
                value -> config.borderColor = value);
        colorButton(page, "Цвет аватара", () -> config.avatarColor,
                value -> config.avatarColor = value);

        page.addView(text("Элементы", 20, Color.WHITE), top(20));
        page.addView(text("Видимость, размер текста и цвет независимы. Положение и площадь "
                + "задаются прямо на тестовой карточке выше.", 14, 0xFFC7C7CC), top(4));
        for (PhoneNotificationLayoutConfig.Element element : config.elements()) {
            page.addView(elementCard(element), top(9));
        }
        return scroll;
    }

    private void rebuild() {
        View screen = buildScreen();
        setContentView(screen);
        SettingsBackNavigation.applySafeTopInset(this, screen);
    }

    @NonNull
    private View elementCard(@NonNull PhoneNotificationLayoutConfig.Element element) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB5222733);
        background.setCornerRadius(dp(14));
        card.setBackground(background);
        CheckBox visible = new CheckBox(this);
        visible.setText(element.label);
        visible.setTextColor(Color.WHITE);
        visible.setChecked(element.visible);
        visible.setOnCheckedChangeListener((button, checked) -> {
            element.visible = checked;
            updatePreviewAndPersist();
        });
        card.addView(visible);
        if (!PhoneNotificationLayoutConfig.BADGE.equals(element.id)) {
            CheckBox bold = new CheckBox(this);
            bold.setText("Жирное начертание");
            bold.setTextColor(0xFFC7C7CC);
            bold.setChecked(element.bold);
            bold.setOnCheckedChangeListener((button, checked) -> {
                element.bold = checked;
                updatePreviewAndPersist();
            });
            card.addView(bold);
        }
        if (!PhoneNotificationLayoutConfig.BADGE.equals(element.id)) {
            slider(card, "Размер", 8, 160, element.textSizePx,
                    value -> element.textSizePx = value);
        } else {
            card.addView(checkBox("Сохранять пропорции иконки",
                    config.iconPreserveAspectRatio,
                    checked -> config.iconPreserveAspectRatio = checked));
        }
        if (PhoneNotificationLayoutConfig.isTextElement(element.id)) {
            slider(card, "Максимум строк", 1, 8, element.maxLines, " стр.",
                    value -> element.maxLines = value);
            card.addView(checkBox(
                    "Текст по центру поля по вертикали (выкл. = у верхнего края)",
                    PhoneNotificationLayoutConfig.TEXT_VERTICAL_CENTER.equals(
                            element.verticalAlignment),
                    checked -> element.verticalAlignment = checked
                            ? PhoneNotificationLayoutConfig.TEXT_VERTICAL_CENTER
                            : PhoneNotificationLayoutConfig.TEXT_VERTICAL_TOP));
            card.addView(checkBox("Автопрокрутка при переполнении (выкл. = …)",
                    PhoneNotificationLayoutConfig.OVERFLOW_SCROLL.equals(
                            element.overflowMode),
                    checked -> element.overflowMode = checked
                            ? PhoneNotificationLayoutConfig.OVERFLOW_SCROLL
                            : PhoneNotificationLayoutConfig.OVERFLOW_ELLIPSIS));
        }
        colorButton(card, "Цвет", () -> element.color, value -> element.color = value);
        return card;
    }

    private void slider(@NonNull LinearLayout host, @NonNull String label,
                        int min, int max, int current, @NonNull IntConsumer setter) {
        slider(host, label, min, max, current, " px", setter);
    }

    private void slider(@NonNull LinearLayout host, @NonNull String label,
                        int min, int max, int current, @NonNull String suffix,
                        @NonNull IntConsumer setter) {
        LinearLayout block = column();
        TextView value = text(label + ": " + current + suffix, 13, 0xFFC7C7CC);
        block.addView(value);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                int selected = min + progress;
                setter.accept(selected);
                value.setText(label + ": " + selected + suffix);
                updatePreviewAndPersist();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(40)));
        host.addView(block, top(5));
    }

    private interface StringSource { String get(); }
    private interface StringSink { void set(String value); }

    private void colorButton(@NonNull LinearLayout host, @NonNull String label,
                             @NonNull StringSource source, @NonNull StringSink sink) {
        MaterialButton button = new MaterialButton(this);
        AppleColorPickerDialog.decorateButton(button, label, source.get());
        button.setOnClickListener(view -> AppleColorPickerDialog.show(this, label, source.get(),
                AppleColorPickerDialog.Options.standard(), new AppleColorPickerDialog.Listener() {
                    private void apply(@Nullable String selected) {
                        if (selected == null) return;
                        sink.set(selected);
                        AppleColorPickerDialog.decorateButton(button, label, selected);
                        updatePreviewAndPersist();
                    }
                    @Override public void onPreview(@Nullable String selected) { apply(selected); }
                    @Override public void onSelected(@Nullable String selected) { apply(selected); }
                }));
        host.addView(button, top(5));
    }

    private void updatePreviewAndPersist() {
        updatePreviewGeometry();
        if (previewCard != null) {
            previewCard.setPresentation(config, PhoneNotificationCardView.Model.preview());
        }
        if (editOverlay != null) editOverlay.invalidate();
        persistLayout();
    }

    /** The local editor uses the exact physical popup dimensions used by WindowManager. */
    private void updatePreviewGeometry() {
        if (previewSurface == null || overlay == null) return;
        ViewGroup.LayoutParams raw = previewSurface.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) raw;
        int width = Math.max(100, Math.min(1600, overlay.width));
        int height = Math.max(100, Math.min(500, overlay.height));
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER_HORIZONTAL;
        previewSurface.setLayoutParams(params);
    }

    private void persistLayout() {
        if (config == null || overlay == null) return;
        try {
            layoutStore.save(config);
            // Position has its own atomic write path. Save it first so the catalog's stale-editor
            // protection preserves this slider value instead of restoring the previous one.
            overlayStore.savePosition(overlay.id, overlay.x, overlay.y);
            List<PopupOverlayConfig> overlays = new ArrayList<>(overlayStore.load());
            for (int index = 0; index < overlays.size(); index++) {
                if (overlays.get(index).id.equals(overlay.id)) {
                    overlays.set(index, overlay);
                    break;
                }
            }
            overlayStore.save(overlays);
            WidgetService service = WidgetService.getInstance();
            if (service != null) service.applyPopupPreferences();
        } catch (Exception failure) {
            Toast.makeText(this, "Не удалось сохранить: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private final class LayoutModel implements PanelContentEditOverlay.Model {
        @Override public int columns() { return PhoneNotificationLayoutConfig.GRID_COLUMNS; }
        @Override public int rows() { return PhoneNotificationLayoutConfig.GRID_ROWS; }
        // The generic blue item fill is useful for empty launcher cells, but over a notification
        // icon it paints a square back into the real transparent corners and visually turns a
        // correct four-corner mask into the flat-top shape seen on the head unit.
        @Override public boolean drawItemFill() { return false; }
        @NonNull @Override public List<PanelContentEditOverlay.Item> items() {
            List<PanelContentEditOverlay.Item> result = new ArrayList<>();
            for (PhoneNotificationLayoutConfig.Element element : config.elements()) {
                if (!element.visible) continue;
                result.add(new PanelContentEditOverlay.Item(element.id, element.label,
                        element.column, element.row, element.columnSpan, element.rowSpan));
            }
            return result;
        }
        @Override public boolean setPlacement(@NonNull String id, int column, int row,
                                              int columnSpan, int rowSpan) {
            PhoneNotificationLayoutConfig.Element element = config.element(id);
            if (element == null || column < 0 || row < 0 || columnSpan < 1 || rowSpan < 1
                    || column + columnSpan > columns() || row + rowSpan > rows()) return false;
            if (element.column == column && element.row == row
                    && element.columnSpan == columnSpan && element.rowSpan == rowSpan) return false;
            // Badge-on-avatar and text overlaps are valid CarPlay compositions; do not impose the
            // generic popup grid's collision rule on this dedicated visual hierarchy.
            element.column = column;
            element.row = row;
            element.columnSpan = columnSpan;
            element.rowSpan = rowSpan;
            return true;
        }
    }

    @NonNull private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }
    @NonNull private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }
    @NonNull private Button button(String label) {
        MaterialButton value = new MaterialButton(this);
        value.setText(label);
        return value;
    }
    @NonNull private CheckBox checkBox(@NonNull String label, boolean checked,
                                       @NonNull java.util.function.Consumer<Boolean> setter) {
        CheckBox value = new CheckBox(this);
        value.setText(label);
        value.setTextColor(0xFFC7C7CC);
        value.setChecked(checked);
        value.setOnCheckedChangeListener((button, selected) -> {
            setter.accept(selected);
            updatePreviewAndPersist();
        });
        return value;
    }
    @NonNull private TextView text(String value, float size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }
    private LinearLayout.LayoutParams top(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(match(), wrap());
        params.topMargin = dp(value);
        return params;
    }
    private LinearLayout.LayoutParams topSize(int value, int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                Math.max(100, Math.min(1600, width)),
                Math.max(100, Math.min(500, height)));
        params.topMargin = dp(value);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
