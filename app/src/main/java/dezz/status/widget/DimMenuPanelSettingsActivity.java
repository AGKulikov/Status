/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.dim.DimMenuPanelConfig;
import dezz.status.widget.dim.DimMenuPanelService;
import dezz.status.widget.dim.DimMenuPanelStore;
import dezz.status.widget.dim.DimMenuPanelView;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.ShortcutActionPicker;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.settings.SettingsBackNavigation;

/** Complete editor for the independent mNavi-style steering-wheel menu. */
public final class DimMenuPanelSettingsActivity extends AppCompatActivity {
    private interface IntSetter { void set(int value); }
    private interface ColorSetter { void set(@NonNull String value); }

    private Preferences preferences;
    private DimMenuPanelStore panelStore;
    private LauncherShortcutStore actionStore;
    private DimMenuPanelConfig config;
    private ShortcutActionPicker actionPicker;
    private LinearLayout actionsHost;
    private FrameLayout previewHost;
    private TextView runtimeLabel;
    private final Runnable refreshRuntime = new Runnable() {
        @Override public void run() {
            if (runtimeLabel == null) return;
            runtimeLabel.setText("Состояние: " + DimMenuPanelService.runtimeDetail());
            runtimeLabel.postDelayed(this, 1_000L);
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        panelStore = new DimMenuPanelStore(preferences);
        actionStore = LauncherShortcutStore.forDimMenu(preferences);
        config = panelStore.load();
        actionPicker = new ShortcutActionPicker(this, preferences, actionStore, () -> {
            actionStore.load();
            refreshActions();
            refreshPreview();
            applyPanel();
        });
        setTitle("Меню экрана водителя");
        View content = buildContent();
        setContentView(content);
        SettingsBackNavigation.install(this, content);
        refreshActions();
        refreshPreview();
    }

    @Override protected void onResume() {
        super.onResume();
        actionStore.load();
        config = panelStore.load();
        refreshActions();
        refreshPreview();
        runtimeLabel.removeCallbacks(refreshRuntime);
        runtimeLabel.post(refreshRuntime);
        if (panelStore.isEnabled()) DimMenuPanelService.apply(this);
    }

    @Override protected void onPause() {
        runtimeLabel.removeCallbacks(refreshRuntime);
        super.onPause();
    }

    @NonNull
    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(10, 13, 18));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout settings = column();
        settings.setPadding(dp(8), 0, dp(20), dp(96));
        scroll.addView(settings, new ScrollView.LayoutParams(match(), wrap()));
        root.addView(scroll, new LinearLayout.LayoutParams(0, match(), 1.15f));

        LinearLayout right = column();
        right.setPadding(dp(16), 0, 0, dp(40));
        root.addView(right, new LinearLayout.LayoutParams(0, match(), 0.85f));

        title(settings, "Меню на экране водителя");
        hint(settings, "Отдельная панель в штатной вкладке «Навигация». Прокрутка и выбор — "
                + "кнопками руля; маршруты, приложения, функции автомобиля, умный дом и звонки "
                + "настраиваются независимо.");
        addSwitch(settings, "Включить панель", panelStore.isEnabled(), value -> {
            panelStore.setEnabled(value);
            applyPanel();
        });
        addSwitch(settings, "Запускать автоматически", panelStore.isAutostart(), value -> {
            panelStore.setAutostart(value);
            applyPanel();
        });
        runtimeLabel = text("", 14, 0xFF9FB1C7);
        settings.addView(runtimeLabel, margins(match(), wrap(), 0, 4, 0, 10));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            MaterialButton calls = button("Разрешить прямые звонки");
            calls.setOnClickListener(v -> ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE}, 4702));
            settings.addView(calls, margins(match(), dp(50), 0, 0, 0, 8));
            hint(settings, "Без этого разрешения пункт звонка безопасно откроет штатный набор "
                    + "номера и потребует подтверждения.");
        }

        section(settings, "Поведение штатного экрана");
        addSwitch(settings, "Только вкладка «Навигация»", config.navigationTabOnly,
                value -> change(c -> c.navigationTabOnly = value));
        addSwitch(settings, "Не конфликтовать с mNavi", config.hideForMnav,
                value -> change(c -> c.hideForMnav = value));
        addSwitch(settings, "Скрывать при звонке и штатном медиаменю",
                config.hideForControlCenter,
                value -> change(c -> c.hideForControlCenter = value));
        addSwitch(settings, "Замыкать список", config.wrapSelection,
                value -> change(c -> c.wrapSelection = value));
        addSwitch(settings, "Поменять направление прокрутки", config.invertScroll,
                value -> change(c -> c.invertScroll = value));
        addSwitch(settings, "Скрывать после выполнения", config.closeAfterAction,
                value -> change(c -> c.closeAfterAction = value));
        hint(settings, "Меню открывается поверх панели приборов Natro. При выключенном "
                + "зажигании оно не рисуется.");

        section(settings, "Положение и размеры");
        slider(settings, "Дисплей", 0, 4, config.displayId, "", value ->
                change(c -> c.displayId = value));
        slider(settings, "X", 0, 1500, config.x, " px", value ->
                change(c -> c.x = value));
        slider(settings, "Y", 0, 1000, config.y, " px", value ->
                change(c -> c.y = value));
        slider(settings, "Ширина", 220, 900, config.width, " px", value ->
                change(c -> c.width = value));
        slider(settings, "Высота", 120, 700, config.height, " px", value ->
                change(c -> c.height = value));
        slider(settings, "Видимых строк", 1, 10, config.visibleRows, "", value ->
                change(c -> c.visibleRows = value));
        slider(settings, "Высота строки", 28, 120, config.rowHeightPx, " px", value ->
                change(c -> c.rowHeightPx = value));
        slider(settings, "Расстояние между строками", 0, 30, config.rowGapPx, " px", value ->
                change(c -> c.rowGapPx = value));
        slider(settings, "Внутренний отступ", 0, 50, config.contentPaddingPx, " px", value ->
                change(c -> c.contentPaddingPx = value));

        section(settings, "Состав и оформление");
        addSwitch(settings, "Оформление списка как в mNavi", config.mnaviStyle,
                value -> change(c -> c.mnaviStyle = value));
        hint(settings, "В режиме mNavi оформление повторяется точно: прозрачная подложка, серый "
                + "#6C7984, синее выделение #197BC5, белый текст выбранной строки, "
                + "заголовок 14 sp, строки 24 sp и скругление выделения 6 dp. "
                + "Параметры ниже применяются только после отключения этого режима.");
        addSwitch(settings, "Показывать заголовок", config.showTitle,
                value -> change(c -> c.showTitle = value));
        addSwitch(settings, "Показывать иконки", config.showIcons,
                value -> change(c -> c.showIcons = value));
        addSwitch(settings, "Показывать подписи", config.showText,
                value -> change(c -> c.showText = value));
        MaterialButton editTitle = button("Заголовок: " + config.title);
        editTitle.setOnClickListener(v -> editTitle());
        settings.addView(editTitle, margins(match(), dp(50), 0, 4, 0, 8));
        slider(settings, "Прозрачность фона", 10, 100,
                config.panelOpacityPercent, "%", value ->
                        change(c -> c.panelOpacityPercent = value));
        slider(settings, "Скругление", 0, 80, config.cornerRadiusPx, " px", value ->
                change(c -> c.cornerRadiusPx = value));
        slider(settings, "Толщина рамки", 0, 12, config.borderWidthPx, " px", value ->
                change(c -> c.borderWidthPx = value));
        slider(settings, "Размер заголовка", 10, 42, config.titleTextSizeSp, " sp", value ->
                change(c -> c.titleTextSizeSp = value));
        slider(settings, "Размер текста", 10, 44, config.rowTextSizeSp, " sp", value ->
                change(c -> c.rowTextSizeSp = value));
        slider(settings, "Размер иконок", 16, 88, config.iconSizePx, " px", value ->
                change(c -> c.iconSizePx = value));
        colorButton(settings, "Фон", config.backgroundColor,
                value -> config.backgroundColor = value);
        colorButton(settings, "Выбранная строка", config.selectedColor,
                value -> config.selectedColor = value);
        colorButton(settings, "Основной текст", config.textColor,
                value -> config.textColor = value);
        colorButton(settings, "Обычный текст и иконки", config.mutedTextColor,
                value -> config.mutedTextColor = value);
        colorButton(settings, "Рамка", config.borderColor,
                value -> config.borderColor = value);

        MaterialButton reset = button("Вернуть расположение и оформление по умолчанию");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Сбросить оформление панели?")
                .setMessage("Действия и главный переключатель сохранятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    config = new DimMenuPanelConfig();
                    saveAndApply();
                    recreate();
                }).show());
        settings.addView(reset, margins(match(), dp(52), 0, 12, 0, 8));

        title(right, "Предпросмотр");
        previewHost = new FrameLayout(this);
        previewHost.setBackgroundColor(0xFF202631);
        right.addView(previewHost, margins(match(), dp(300), 0, 8, 0, 16));
        section(right, "Действия");
        hint(right, "Порядок здесь совпадает с прокруткой на руле.");
        MaterialButton add = button("Добавить действие");
        add.setOnClickListener(v -> actionPicker.showNew());
        right.addView(add, margins(match(), dp(52), 0, 4, 0, 10));
        actionsHost = column();
        right.addView(actionsHost, new LinearLayout.LayoutParams(match(), wrap()));
        return root;
    }

    private interface ConfigChange { void apply(@NonNull DimMenuPanelConfig value); }

    private void change(@NonNull ConfigChange change) {
        change.apply(config);
        saveAndApply();
    }

    private void saveAndApply() {
        config.normalize();
        panelStore.save(config);
        refreshPreview();
        applyPanel();
    }

    private void applyPanel() {
        if (panelStore.isEnabled()) {
            WidgetServiceStarter.startIfNeeded(this);
            DimMenuPanelService.apply(this);
        } else {
            DimMenuPanelService.stop(this);
        }
    }

    private void refreshPreview() {
        if (previewHost == null) return;
        previewHost.removeAllViews();
        DimMenuPanelView preview = new DimMenuPanelView(this, config, interactiveActions());
        previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
    }

    private void refreshActions() {
        if (actionsHost == null) return;
        actionsHost.removeAllViews();
        List<LauncherShortcutStore.Shortcut> values = actionStore.all();
        if (values.isEmpty()) {
            actionsHost.addView(text("Действий пока нет", 15, 0xFF9AA8BC));
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            LauncherShortcutStore.Shortcut value = values.get(index);
            MaterialCardView card = new MaterialCardView(this);
            card.setCardBackgroundColor(0xFF171D26);
            card.setStrokeColor(0xFF354154);
            card.setStrokeWidth(dp(1));
            card.setRadius(dp(14));
            LinearLayout content = column();
            content.setPadding(dp(12), dp(8), dp(12), dp(8));
            content.addView(text(value.title, 17, Color.WHITE));
            content.addView(text(type(value), 12, 0xFF9FB0C7));
            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            MaterialButton edit = smallButton("Изменить");
            edit.setOnClickListener(v -> actionPicker.showPrimary(value));
            controls.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1f));
            MaterialButton up = smallButton("↑");
            int position = index;
            up.setEnabled(position > 0);
            up.setOnClickListener(v -> move(value.id, -1));
            controls.addView(up, new LinearLayout.LayoutParams(dp(52), dp(44)));
            MaterialButton down = smallButton("↓");
            down.setEnabled(position < values.size() - 1);
            down.setOnClickListener(v -> move(value.id, 1));
            controls.addView(down, new LinearLayout.LayoutParams(dp(52), dp(44)));
            MaterialButton delete = smallButton("×");
            delete.setOnClickListener(v -> {
                actionStore.remove(value.id);
                refreshActions();
                refreshPreview();
                applyPanel();
            });
            controls.addView(delete, new LinearLayout.LayoutParams(dp(52), dp(44)));
            content.addView(controls, margins(match(), dp(44), 0, 6, 0, 0));
            card.addView(content);
            actionsHost.addView(card, margins(match(), wrap(), 0, 0, 0, 8));
        }
    }

    private void move(@NonNull String id, int delta) {
        actionStore.move(id, delta);
        refreshActions();
        refreshPreview();
        applyPanel();
    }

    @NonNull
    private List<LauncherShortcutStore.Shortcut> interactiveActions() {
        List<LauncherShortcutStore.Shortcut> result = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut value : actionStore.all()) {
            if (value.enabled && LauncherShortcutStore.isInteractive(value)) result.add(value);
        }
        return result;
    }

    @NonNull
    private static String type(@NonNull LauncherShortcutStore.Shortcut value) {
        switch (value.kind) {
            case APP: return "Приложение";
            case PHONE: return "Телефон · " + value.target;
            case RULE: return "Умный дом / сценарий";
            case CAR: return "Функция автомобиля";
            case INTENT: return "Android Intent";
            case INFO: return "Информация";
            case DIVIDER: return "Разделитель";
            case BUILTIN:
            default: return LauncherShortcutStore.Builtin.fromKey(value.target).label;
        }
    }

    private void editTitle() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(config.title);
        field.setSelectAllOnFocus(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Заголовок панели").setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    config.title = field.getText().toString();
                    saveAndApply();
                    recreate();
                }).show();
    }

    private void colorButton(@NonNull LinearLayout parent, @NonNull String title,
                             @NonNull String initial, @NonNull ColorSetter setter) {
        MaterialButton button = button("");
        AppleColorPickerDialog.decorateButton(button, title, initial);
        button.setOnClickListener(v -> AppleColorPickerDialog.show(this, title,
                colorValue(title), AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {
                        if (value == null) return;
                        setter.set(value);
                        AppleColorPickerDialog.decorateButton(button, title, value);
                        refreshPreview();
                    }

                    @Override public void onSelected(@Nullable String value) {
                        if (value == null) return;
                        setter.set(value);
                        saveAndApply();
                        AppleColorPickerDialog.decorateButton(button, title, value);
                    }

                    @Override public void onCancelled(@Nullable String original) {
                        if (original == null) return;
                        setter.set(original);
                        AppleColorPickerDialog.decorateButton(button, title, original);
                        refreshPreview();
                    }
                }));
        parent.addView(button, margins(match(), dp(58), 0, 4, 0, 6));
    }

    @NonNull
    private String colorValue(@NonNull String title) {
        switch (title) {
            case "Фон": return config.backgroundColor;
            case "Выбранная строка": return config.selectedColor;
            case "Основной текст": return config.textColor;
            case "Обычный текст и иконки": return config.mutedTextColor;
            case "Рамка": return config.borderColor;
            default: return "#FFFFFFFF";
        }
    }

    private void slider(@NonNull LinearLayout parent, @NonNull String label,
                        int minimum, int maximum, int current, @NonNull String suffix,
                        @NonNull IntSetter setter) {
        LinearLayout block = column();
        TextView title = text("", 14, 0xFFE5EAF2);
        int safe = Math.max(minimum, Math.min(maximum, current));
        title.setText(label + ": " + safe + suffix);
        block.addView(title);
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(safe - minimum);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                    boolean fromUser) {
                int value = minimum + progress;
                title.setText(label + ": " + value + suffix);
                if (fromUser) setter.set(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        parent.addView(block, margins(match(), wrap(), 0, 3, 0, 2));
    }

    private void addSwitch(@NonNull LinearLayout parent, @NonNull String label,
                           boolean checked, @NonNull java.util.function.Consumer<Boolean> action) {
        MaterialSwitch control = new MaterialSwitch(this);
        control.setText(label);
        control.setTextColor(Color.WHITE);
        control.setTextSize(15);
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> action.accept(value));
        parent.addView(control, margins(match(), dp(48), 0, 1, 0, 1));
    }

    private void title(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = text(value, 25, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        parent.addView(title, margins(match(), wrap(), 0, 0, 0, 5));
    }

    private void section(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = text(value, 18, 0xFF65A9FF);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        parent.addView(title, margins(match(), wrap(), 0, 16, 0, 5));
    }

    private void hint(@NonNull LinearLayout parent, @NonNull String value) {
        parent.addView(text(value, 13, 0xFF9AA8BC),
                margins(match(), wrap(), 0, 0, 0, 7));
    }

    @NonNull private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    @NonNull private MaterialButton button(@NonNull String value) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return button;
    }

    @NonNull private MaterialButton smallButton(@NonNull String value) {
        MaterialButton button = button(value);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    @NonNull private TextView text(@NonNull String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    @NonNull
    private LinearLayout.LayoutParams margins(int width, int height,
                                               int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(width, height);
        value.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
