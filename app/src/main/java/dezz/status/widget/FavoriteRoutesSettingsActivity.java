/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.panels.PanelEditScheduler;
import dezz.status.widget.launcher.routes.FavoriteRouteConfig;
import dezz.status.widget.launcher.routes.FavoriteRoutesConfigStore;
import dezz.status.widget.launcher.routes.FavoriteRoutesPanelView;
import dezz.status.widget.launcher.routes.RouteDestinationParser;

/** Visual autosaving editor for the idle state of the combined navigation HOME panel. */
public final class FavoriteRoutesSettingsActivity extends AppCompatActivity {
    private interface IntChange { void set(int value); }

    private Preferences preferences;
    private FavoriteRoutesConfigStore store;
    private final List<FavoriteRouteConfig> routes = new ArrayList<>();
    private LinearLayout listHost;
    private LinearLayout editorHost;
    private FrameLayout previewHost;
    private TextView savedStatus;
    private MaterialSwitch panelVisibleSwitch;
    @Nullable private FavoriteRouteConfig selected;
    @Nullable private FavoriteRoutesPanelView preview;
    @Nullable private PanelEditScheduler editScheduler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        store = new FavoriteRoutesConfigStore(preferences);
        routes.addAll(store.load());
        editScheduler = PanelEditScheduler.onMainThread(this::applyPreviewState, () -> {
            store.save(routes);
            markSaved();
            rebuildList();
        });
        setTitle("Маршрут и избранное HOME");
        setContentView(buildScreen());
        rebuildList();
        if (!routes.isEmpty()) select(routes.get(0));
        else showEmptyEditor();
        rebuildPreview();
    }

    @Override
    protected void onStop() {
        if (editScheduler != null) editScheduler.flush();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (editScheduler != null) editScheduler.cancel();
        super.onDestroy();
    }

    @NonNull
    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        ScrollView listScroll = new ScrollView(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(14), dp(28));
        listScroll.addView(left, new ScrollView.LayoutParams(match(), wrap()));
        addTitle(left, "МАРШРУТ И ИЗБРАННОЕ");
        addHint(left, "Пока маршрут не построен, здесь видны кнопки «Домой», «Работа» и другие адреса. После построения маршрута плитка сама переключится на выбранную навигационную информацию.");

        panelVisibleSwitch = new MaterialSwitch(this);
        panelVisibleSwitch.setText("Показывать объединённую плитку на HOME");
        panelVisibleSwitch.setChecked(preferences.launcherNavigationVisible.get()
                || preferences.launcherFavoriteRoutesVisible.get());
        panelVisibleSwitch.setMinHeight(dp(48));
        panelVisibleSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.launcherNavigationVisible.set(checked);
            preferences.launcherFavoriteRoutesVisible.set(checked);
            markSaved();
        });
        left.addView(panelVisibleSwitch, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(left, "Выбрать данные активного маршрута…", v ->
                startActivity(new Intent(this, PanelElementSettingsActivity.class)
                        .putExtra(PanelElementSettingsActivity.EXTRA_PANEL_ID,
                                dezz.status.widget.launcher.LauncherLayoutStore.NAVIGATION)));
        addSlider(left, "Столбцов в панели", preferences.launcherFavoriteRoutesColumns.get(),
                1, 6, value -> {
                    preferences.launcherFavoriteRoutesColumns.set(value);
                    rebuildPreview();
                    markSaved();
                }, "");

        LinearLayout addRow = new LinearLayout(this);
        MaterialButton addHome = button("＋ Домой");
        MaterialButton addWork = button("＋ Работа");
        MaterialButton addOther = button("＋ Другой");
        addHome.setOnClickListener(v -> addRoute("Домой", "home"));
        addWork.setOnClickListener(v -> addRoute("Работа", "work"));
        addOther.setOnClickListener(v -> addRoute("Новый маршрут", "navigation"));
        addRow.addView(addHome, new LinearLayout.LayoutParams(0, dp(48), 1f));
        addRow.addView(addWork, new LinearLayout.LayoutParams(0, dp(48), 1f));
        addRow.addView(addOther, new LinearLayout.LayoutParams(0, dp(48), 1f));
        left.addView(addRow, new LinearLayout.LayoutParams(match(), wrap()));

        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        left.addView(listHost, new LinearLayout.LayoutParams(match(), wrap()));
        addButton(left, "Размер и положение объединённой плитки на HOME…", v ->
                startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_EDIT_MODE, true)));

        ScrollView editorScroll = new ScrollView(this);
        editorHost = new LinearLayout(this);
        editorHost.setOrientation(LinearLayout.VERTICAL);
        editorHost.setPadding(dp(16), 0, dp(16), dp(30));
        editorScroll.addView(editorHost, new ScrollView.LayoutParams(match(), wrap()));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(10), 0, 0, 0);
        addTitle(right, "ЖИВОЙ ПРЕДПРОСМОТР");
        previewHost = new FrameLayout(this);
        GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setColor(Color.rgb(8, 12, 18));
        previewBackground.setCornerRadius(dp(24));
        previewHost.setBackground(previewBackground);
        previewHost.setPadding(dp(12), dp(12), dp(12), dp(12));
        right.addView(previewHost, new LinearLayout.LayoutParams(match(), 0, 1f));
        savedStatus = text("✓ Сохраняется автоматически", 13, false);
        savedStatus.setGravity(Gravity.CENTER);
        savedStatus.setAlpha(.72f);
        right.addView(savedStatus, new LinearLayout.LayoutParams(match(), dp(42)));

        root.addView(listScroll, new LinearLayout.LayoutParams(0, match(), .30f));
        root.addView(editorScroll, new LinearLayout.LayoutParams(0, match(), .36f));
        root.addView(right, new LinearLayout.LayoutParams(0, match(), .34f));
        return root;
    }

    private void addRoute(@NonNull String title, @NonNull String icon) {
        FavoriteRouteConfig value = store.defaultNew();
        value.title = title;
        value.icon = icon;
        value.normalize();
        routes.add(value);
        persist();
        rebuildList();
        select(value);
        if (panelVisibleSwitch != null) panelVisibleSwitch.setChecked(true);
    }

    private void rebuildList() {
        if (listHost == null) return;
        listHost.removeAllViews();
        if (routes.isEmpty()) {
            TextView empty = text("Маршрутов пока нет. Добавьте «Домой» или «Работа».", 14, false);
            empty.setAlpha(.7f);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            listHost.addView(empty);
            return;
        }
        for (int index = 0; index < routes.size(); index++) {
            FavoriteRouteConfig route = routes.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(3), dp(4), dp(3));
            MaterialSwitch enabled = new MaterialSwitch(this);
            enabled.setText(route.title.isEmpty() ? "Без названия" : route.title);
            enabled.setChecked(route.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                route.enabled = checked;
                persist();
            });
            enabled.setOnClickListener(v -> select(route));
            row.addView(enabled, new LinearLayout.LayoutParams(0, dp(52), 1f));
            int current = index;
            MaterialButton up = compact("↑");
            MaterialButton down = compact("↓");
            MaterialButton edit = compact("✎");
            MaterialButton remove = compact("×");
            up.setOnClickListener(v -> move(current, -1));
            down.setOnClickListener(v -> move(current, 1));
            edit.setOnClickListener(v -> select(route));
            remove.setOnClickListener(v -> confirmDelete(route));
            row.addView(up); row.addView(down); row.addView(edit); row.addView(remove);
            listHost.addView(row, new LinearLayout.LayoutParams(match(), dp(58)));
        }
    }

    private void move(int from, int direction) {
        int to = Math.max(0, Math.min(routes.size() - 1, from + direction));
        if (from == to) return;
        Collections.swap(routes, from, to);
        persist();
        rebuildList();
    }

    private void confirmDelete(@NonNull FavoriteRouteConfig route) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить маршрут?")
                .setMessage(route.title)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    routes.remove(route);
                    if (selected == route) selected = routes.isEmpty() ? null : routes.get(0);
                    persist();
                    rebuildList();
                    if (selected == null) showEmptyEditor(); else select(selected);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void select(@NonNull FavoriteRouteConfig route) {
        selected = route;
        renderEditor(route);
    }

    private void showEmptyEditor() {
        editorHost.removeAllViews();
        addTitle(editorHost, "НАСТРОЙКА КНОПКИ");
        addHint(editorHost, "Слева добавьте первый маршрут. Для максимальной точности используйте координаты.");
    }

    private void renderEditor(@NonNull FavoriteRouteConfig route) {
        editorHost.removeAllViews();
        addTitle(editorHost, "НАСТРОЙКА КНОПКИ");
        addHint(editorHost, "Координаты имеют приоритет над адресом. Формат: 55.7558,37.6176. Можно указать несколько точек через пробел.");

        EditText title = input("Название кнопки", route.title);
        editorHost.addView(title, fieldLp());
        watch(title, value -> {
            route.title = value;
            persistWithoutList();
        });
        EditText address = input("Адрес, например: Москва, Тверская 1", route.address);
        editorHost.addView(address, fieldLp());
        EditText coordinates = input("Координаты: широта,долгота", route.coordinates);
        editorHost.addView(coordinates, fieldLp());
        TextView destinationStatus = text("", 13, false);
        editorHost.addView(destinationStatus, new LinearLayout.LayoutParams(match(), dp(34)));
        Runnable validateDestination = () -> {
            String point = coordinates.getText().toString().trim();
            String addressValue = address.getText().toString().trim();
            if (!point.isEmpty()) {
                try {
                    int count = RouteDestinationParser.coordinateRouteText(point).split("~").length - 1;
                    destinationStatus.setText("✓ Координаты распознаны: " + count + " точка(и)");
                    destinationStatus.setTextColor(Color.rgb(90, 220, 145));
                } catch (IllegalArgumentException invalid) {
                    destinationStatus.setText("Проверьте формат и диапазон координат");
                    destinationStatus.setTextColor(Color.rgb(255, 105, 105));
                }
            } else if (!addressValue.isEmpty()) {
                destinationStatus.setText("✓ Адрес будет передан Яндекс Навигатору");
                destinationStatus.setTextColor(Color.rgb(90, 220, 145));
            } else {
                destinationStatus.setText("Укажите адрес или координаты");
                destinationStatus.setTextColor(Color.rgb(255, 180, 80));
            }
        };
        watch(address, value -> {
            route.address = value;
            persistWithoutList();
            validateDestination.run();
        });
        watch(coordinates, value -> {
            route.coordinates = value;
            persistWithoutList();
            validateDestination.run();
        });
        validateDestination.run();

        addLabel(editorHost, "Открывать в");
        Spinner product = new Spinner(this);
        String[] products = {"Яндекс Навигатор", "Яндекс Карты"};
        product.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, products));
        product.setSelection(route.product == FavoriteRouteConfig.Product.MAPS ? 1 : 0);
        product.setOnItemSelectedListener(new SimpleSelection(position -> {
            route.product = position == 1 ? FavoriteRouteConfig.Product.MAPS
                    : FavoriteRouteConfig.Product.NAVIGATOR;
            persistWithoutList();
        }));
        editorHost.addView(product, fieldLp());

        MaterialSwitch floating = new MaterialSwitch(this);
        floating.setText("Открывать в плавающем окне");
        floating.setChecked(route.floating);
        floating.setOnCheckedChangeListener((button, checked) -> {
            route.floating = checked;
            persistWithoutList();
        });
        editorHost.addView(floating, new LinearLayout.LayoutParams(match(), dp(52)));

        addLabel(editorHost, "Иконка");
        Spinner icon = new Spinner(this);
        List<LauncherIconResolver.Preset> presets = LauncherIconResolver.presets();
        icon.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, presets));
        icon.setSelection(indexOfIcon(presets, route.icon));
        icon.setOnItemSelectedListener(new SimpleSelection(position -> {
            route.icon = presets.get(position).key;
            persistWithoutList();
        }));
        editorHost.addView(icon, fieldLp());

        addSlider(editorHost, "Размер иконки", route.iconSizePx, 24, 180, value -> {
            route.iconSizePx = value;
            previewAndPersist();
        }, " px");
        addSlider(editorHost, "Размер подписи", route.labelSizeSp, 8, 36, value -> {
            route.labelSizeSp = value;
            previewAndPersist();
        }, " sp");
        addColorField(editorHost, "Цвет плитки", route.backgroundColor,
                value -> route.backgroundColor = value);
        addColorField(editorHost, "Цвет иконки", route.iconColor,
                value -> route.iconColor = value);
        addColorField(editorHost, "Цвет текста", route.textColor,
                value -> route.textColor = value);
    }

    private interface StringChange { void set(@NonNull String value); }

    private void addColorField(@NonNull LinearLayout parent, @NonNull String title,
                               @NonNull String initial, @NonNull StringChange update) {
        EditText field = input(title + " (#RRGGBB или #AARRGGBB)", initial);
        parent.addView(field, fieldLp());
        watch(field, value -> {
            if (value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
                update.set(value);
                persistWithoutList();
            }
        });
    }

    private void persist() {
        store.save(routes);
        rebuildPreview();
        markSaved();
    }

    private void persistWithoutList() {
        if (editScheduler != null) editScheduler.request();
    }

    /** Size controls are visual, so repaint them in this callback before the autosave debounce. */
    private void previewAndPersist() {
        applyPreviewState();
        if (editScheduler != null) editScheduler.request();
    }

    private void rebuildPreview() {
        if (previewHost == null) return;
        if (preview == null) {
            previewHost.removeAllViews();
            preview = new FavoriteRoutesPanelView(this, store,
                    Math.max(1, Math.min(6,
                            preferences.launcherFavoriteRoutesColumns.get())));
            preview.setPreviewMode(true);
            previewHost.addView(preview, new FrameLayout.LayoutParams(match(), match()));
        }
        applyPreviewState();
    }

    private void applyPreviewState() {
        if (preview == null) return;
        preview.setColumns(Math.max(1, Math.min(6,
                preferences.launcherFavoriteRoutesColumns.get())));
        preview.setPreviewRoutes(routes);
    }

    private void markSaved() {
        if (savedStatus != null) savedStatus.setText("✓ Сохранено автоматически");
    }

    private interface TextValue { void set(@NonNull String value); }

    private void watch(@NonNull EditText field, @NonNull TextValue listener) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                listener.set(String.valueOf(s).trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void addSlider(@NonNull LinearLayout parent, @NonNull String title, int initial,
                           int minimum, int maximum, @NonNull IntChange change,
                           @NonNull String suffix) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 15, false);
        TextView value = text(initial + suffix, 14, true);
        value.setGravity(Gravity.END);
        heading.addView(name, new LinearLayout.LayoutParams(0, wrap(), 1f));
        heading.addView(value, new LinearLayout.LayoutParams(dp(88), wrap()));
        SeekBar seek = new SeekBar(this);
        seek.setMax(maximum - minimum);
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, initial - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int selected = progress + minimum;
                value.setText(selected + suffix);
                if (user) change.set(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(heading);
        block.addView(seek, new LinearLayout.LayoutParams(match(), dp(42)));
        parent.addView(block, new LinearLayout.LayoutParams(match(), wrap()));
    }

    private int indexOfIcon(@NonNull List<LauncherIconResolver.Preset> values,
                            @NonNull String key) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).key.equals(key)) return index;
        }
        return 0;
    }

    private EditText input(@NonNull String hint, @NonNull String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        return field;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(54));
        lp.bottomMargin = dp(5);
        return lp;
    }

    private void addLabel(@NonNull LinearLayout parent, @NonNull String value) {
        TextView label = text(value, 14, true);
        label.setAlpha(.8f);
        parent.addView(label, new LinearLayout.LayoutParams(match(), dp(30)));
    }

    private void addTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = text(value, 18, true);
        title.setTextColor(Color.rgb(105, 165, 255));
        parent.addView(title, new LinearLayout.LayoutParams(match(), dp(40)));
    }

    private void addHint(@NonNull LinearLayout parent, @NonNull String value) {
        TextView hint = text(value, 13, false);
        hint.setAlpha(.72f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), wrap());
        lp.bottomMargin = dp(8);
        parent.addView(hint, lp);
    }

    private void addButton(@NonNull LinearLayout parent, @NonNull String label,
                           @NonNull View.OnClickListener click) {
        MaterialButton button = button(label);
        button.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(match(), dp(50));
        lp.topMargin = dp(8);
        parent.addView(button, lp);
    }

    @NonNull private MaterialButton button(@NonNull String value) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    @NonNull private MaterialButton compact(@NonNull String value) {
        MaterialButton button = button(value);
        button.setTextSize(17);
        button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        return button;
    }

    @NonNull private TextView text(@NonNull String value, float size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private interface PositionChange { void set(int position); }

    private static final class SimpleSelection implements android.widget.AdapterView.OnItemSelectedListener {
        private final PositionChange change;
        SimpleSelection(@NonNull PositionChange change) { this.change = change; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                             int position, long id) { change.set(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
