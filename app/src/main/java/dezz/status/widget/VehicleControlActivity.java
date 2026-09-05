/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.phone.transport.v2.CarRemoteControlRegistryV1;

/**
 * Vehicle-first control center for the head unit.
 *
 * <p>The overview is deliberately short. Controls live behind five contextual category tabs and
 * render as a two-column grid; range and finite-level controls expose their actual values instead
 * of pretending everything is a switch.</p>
 */
public final class VehicleControlActivity extends AppCompatActivity {
    private static final int BACKGROUND = Color.rgb(5, 8, 14);
    private static final int SURFACE = Color.rgb(16, 22, 33);
    private static final int SURFACE_ACTIVE = Color.rgb(12, 53, 72);
    private static final int PRIMARY = Color.rgb(67, 190, 241);
    private static final int SECONDARY = Color.rgb(144, 155, 174);

    private final Map<String, CarControlState> states = new LinkedHashMap<>();
    private final Map<String, ControlTile> tiles = new LinkedHashMap<>();
    private final List<CarControlDescriptor> catalog = new ArrayList<>();
    private final CarIntegration.ControlStateListener stateListener = this::acceptState;
    private CarIntegration car;
    private LinearLayout categories;
    private GridLayout controls;
    private TextView status;
    private String selectedCategory = "Климат";

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        car = CarIntegrations.get(this);
        setContentView(buildScreen());
        car.requestControlCatalog(this::acceptCatalog);
    }

    @Override protected void onDestroy() {
        if (car != null) car.unsubscribeControlStates(stateListener);
        super.onDestroy();
    }

    @NonNull private View buildScreen() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(24), dp(16), dp(24), dp(18));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_revert);
        back.setColorFilter(Color.WHITE);
        back.setBackground(rounded(Color.rgb(29, 36, 50), 18));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = vertical();
        titles.setPadding(dp(16), 0, 0, 0);
        TextView title = label("NATRO · АВТОМОБИЛЬ", 24, Color.WHITE, true);
        status = label("Синхронизация функций автомобиля…", 13, SECONDARY, false);
        titles.addView(title);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        LinearLayout hero = horizontal();
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(20), dp(10), dp(20), dp(10));
        hero.setBackground(rounded(SURFACE, 24));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(176));
        heroParams.topMargin = dp(14);
        root.addView(hero, heroParams);

        LinearLayout identity = vertical();
        TextView vehicle = label("GEELY MONJARO", 20, Color.WHITE, true);
        TextView caption = label("Центр управления · только подтверждённые возможности",
                13, SECONDARY, false);
        identity.addView(vehicle);
        identity.addView(caption);
        hero.addView(identity, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView carImage = new ImageView(this);
        carImage.setImageResource(R.drawable.ic_vehicle_suv_three_quarter_front);
        carImage.setColorFilter(Color.rgb(185, 225, 239));
        carImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        hero.addView(carImage, new LinearLayout.LayoutParams(dp(280), dp(150)));

        HorizontalScrollView categoryScroller = new HorizontalScrollView(this);
        categoryScroller.setHorizontalScrollBarEnabled(false);
        categories = horizontal();
        categories.setPadding(0, dp(14), 0, dp(10));
        categoryScroller.addView(categories);
        root.addView(categoryScroller);

        ScrollView scroll = new ScrollView(this);
        controls = new GridLayout(this);
        controls.setColumnCount(2);
        controls.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        controls.setUseDefaultMargins(false);
        scroll.addView(controls, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        dezz.status.widget.settings.SettingsBackNavigation.applySafeTopInset(this, root);
        return root;
    }

    private void acceptCatalog(@NonNull List<CarControlDescriptor> values) {
        runOnUiThread(() -> {
            catalog.clear();
            catalog.addAll(values);
            Set<String> ids = new LinkedHashSet<>();
            for (CarControlDescriptor value : values) ids.add(value.id);
            car.subscribeControlStates(ids, stateListener);
            rebuildCategories();
            rebuildControls();
            status.setText(values.isEmpty()
                    ? "Автомобиль ещё не вернул каталог функций"
                    : values.size() + " функций · выберите раздел");
        });
    }

    private void acceptState(@NonNull CarControlState state) {
        runOnUiThread(() -> {
            states.put(state.controlId, state);
            ControlTile tile = tiles.get(state.controlId);
            if (tile != null) tile.bind(state);
            int known = 0;
            for (CarControlState value : states.values()) if (value.known) known++;
            status.setText("Синхронизировано " + known + " из " + catalog.size()
                    + " · управление локально через ECARX");
        });
    }

    private void rebuildCategories() {
        categories.removeAllViews();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Collections.addAll(names, "Климат", "Сиденья", "Автомобиль", "Комфорт", "Медиа");
        for (CarControlDescriptor descriptor : catalog) names.add(descriptor.category);
        if (!names.contains(selectedCategory) && !names.isEmpty()) {
            selectedCategory = names.iterator().next();
        }
        for (String name : names) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(name);
            button.setTextSize(14);
            button.setTextColor(name.equals(selectedCategory) ? Color.BLACK : Color.WHITE);
            button.setBackground(rounded(name.equals(selectedCategory) ? PRIMARY : SURFACE, 18));
            button.setPadding(dp(18), 0, dp(18), 0);
            button.setOnClickListener(view -> {
                selectedCategory = name;
                rebuildCategories();
                rebuildControls();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            params.rightMargin = dp(8);
            categories.addView(button, params);
        }
    }

    private void rebuildControls() {
        controls.removeAllViews();
        tiles.clear();
        int row = 0;
        int column = 0;
        for (CarControlDescriptor descriptor : catalog) {
            if (!selectedCategory.equals(descriptor.category)) continue;
            boolean wide = descriptor.kind == CarControlDescriptor.Kind.RANGE;
            if (wide && column != 0) {
                row++;
                column = 0;
            }
            ControlTile tile = new ControlTile(descriptor);
            tiles.put(descriptor.id, tile);
            CarControlState state = states.get(descriptor.id);
            if (state != null) tile.bind(state);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(wide ? 112 : 94);
            params.rowSpec = GridLayout.spec(row);
            params.columnSpec = wide
                    ? GridLayout.spec(0, 2, 1f)
                    : GridLayout.spec(column, 1, 1f);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            controls.addView(tile, params);
            if (wide || ++column == 2) {
                row++;
                column = 0;
            }
        }
    }

    private final class ControlTile extends LinearLayout {
        final CarControlDescriptor descriptor;
        final ImageView icon;
        final TextView value;
        boolean active;

        ControlTile(CarControlDescriptor descriptor) {
            super(VehicleControlActivity.this);
            this.descriptor = descriptor;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(10), dp(14), dp(10));
            setBackground(rounded(SURFACE, 20));
            setClickable(true);
            setFocusable(true);
            icon = new ImageView(VehicleControlActivity.this);
            icon.setImageResource(iconFor(descriptor));
            icon.setColorFilter(PRIMARY);
            addView(icon, new LayoutParams(dp(34), dp(34)));
            LinearLayout text = vertical();
            text.setPadding(dp(13), 0, 0, 0);
            TextView name = label(descriptor.label, 15, Color.WHITE, true);
            name.setMaxLines(2);
            value = label(descriptor.availability == CarControlDescriptor.Availability.UNKNOWN
                    ? "Синхронизация…" : "Готово", 12, SECONDARY, false);
            value.setMaxLines(2);
            text.addView(name);
            text.addView(value);
            addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            setEnabled(descriptor.availability != CarControlDescriptor.Availability.UNSUPPORTED);
            setAlpha(isEnabled() ? 1f : .36f);
            setOnClickListener(view -> activate(descriptor));
        }

        void bind(@NonNull CarControlState state) {
            setEnabled(state.available);
            setAlpha(state.available ? 1f : .36f);
            value.setText(!state.available ? "Недоступно в комплектации"
                    : !state.known ? "Состояние уточняется" : state.valueLabel);
            active = state.known && state.active;
            setBackground(rounded(active ? SURFACE_ACTIVE : SURFACE, 20));
            icon.setColorFilter(active ? PRIMARY : Color.rgb(190, 201, 219));
        }
    }

    private void activate(@NonNull CarControlDescriptor descriptor) {
        if (descriptor.kind == CarControlDescriptor.Kind.LEVELS
                || descriptor.kind == CarControlDescriptor.Kind.OPTIONS) {
            chooseOption(descriptor);
        } else if (descriptor.kind == CarControlDescriptor.Kind.RANGE) {
            chooseRange(descriptor);
        } else {
            CarRemoteControlRegistryV1.Entry safety =
                    CarRemoteControlRegistryV1.forControlId(descriptor.id);
            Runnable send = () -> send(descriptor,
                    descriptor.kind == CarControlDescriptor.Kind.ACTION
                            ? CarControlCommand.Operation.ACTIVATE
                            : CarControlCommand.Operation.TOGGLE, 1d);
            if (safety != null && safety.requiresConfirmation) {
                new AlertDialog.Builder(this)
                        .setTitle(descriptor.label)
                        .setMessage("Убедитесь, что рядом с механизмом нет людей и препятствий.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Выполнить", (dialog, which) -> send.run())
                        .show();
            } else {
                send.run();
            }
        }
    }

    private void chooseOption(@NonNull CarControlDescriptor descriptor) {
        if (descriptor.options.isEmpty()) {
            send(descriptor, CarControlCommand.Operation.CYCLE, 0d);
            return;
        }
        String[] labels = new String[descriptor.options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = descriptor.options.get(i).label;
        new AlertDialog.Builder(this).setTitle(descriptor.label)
                .setItems(labels, (dialog, which) -> send(descriptor,
                        CarControlCommand.Operation.SET, descriptor.options.get(which).value))
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseRange(@NonNull CarControlDescriptor descriptor) {
        CarControlState state = states.get(descriptor.id);
        double initial = state != null && state.known ? state.value : descriptor.minimum;
        int steps = Math.max(1, (int) Math.round(
                (descriptor.maximum - descriptor.minimum) / descriptor.step));
        LinearLayout body = vertical();
        body.setPadding(dp(24), dp(6), dp(24), 0);
        TextView readout = label(formatValue(descriptor, initial), 24, PRIMARY, true);
        readout.setGravity(Gravity.CENTER);
        SeekBar seek = new SeekBar(this);
        seek.setMax(steps);
        seek.setProgress((int) Math.round((initial - descriptor.minimum) / descriptor.step));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                readout.setText(formatValue(descriptor,
                        descriptor.minimum + progress * descriptor.step));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        body.addView(readout);
        body.addView(seek);
        new AlertDialog.Builder(this).setTitle(descriptor.label).setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (dialog, which) -> send(descriptor,
                        CarControlCommand.Operation.SET,
                        descriptor.minimum + seek.getProgress() * descriptor.step))
                .show();
    }

    private void send(CarControlDescriptor descriptor, CarControlCommand.Operation operation,
                      double value) {
        car.executeControl(new CarControlCommand(descriptor.id, operation, value),
                (success, message) -> runOnUiThread(() -> Toast.makeText(this,
                        success ? "Команда выполнена"
                                : message == null ? "Команда отклонена" : message,
                        Toast.LENGTH_SHORT).show()));
    }

    private int iconFor(CarControlDescriptor descriptor) {
        String id = descriptor.id;
        if (id.contains("seat_heat")) return R.drawable.ic_car_seat_heat;
        if (id.contains("seat_vent")) return R.drawable.ic_car_seat_vent;
        if (id.contains("wheel_heat")) return R.drawable.ic_car_wheel_heat;
        if (id.contains("defrost_front")) return R.drawable.ic_car_defrost_front;
        if (id.contains("defrost_rear")) return R.drawable.ic_car_defrost_rear;
        if (id.contains("window")) return R.drawable.ic_car_window;
        if (id.contains("sunroof")) return R.drawable.ic_car_sunroof;
        if (id.contains("trunk")) return R.drawable.ic_car_trunk_closed;
        if (id.contains("drive_mode")) return R.drawable.ic_car_drive_mode;
        if (id.contains("wiper")) return R.drawable.ic_car_wiper;
        if (id.contains("fan")) return R.drawable.ic_car_fan;
        if (id.contains("circulation")) return R.drawable.ic_car_air_recirculation;
        if (id.contains("ac")) return R.drawable.ic_car_ac;
        if (descriptor.category.equals("Климат")) return R.drawable.ic_car_climate;
        if (descriptor.category.equals("Автомобиль")) return R.drawable.ic_smart_car;
        return R.drawable.ic_fluent_settings;
    }

    private String formatValue(CarControlDescriptor descriptor, double value) {
        return String.format(Locale.getDefault(), descriptor.step < 1d ? "%.1f%s" : "%.0f%s",
                value, descriptor.unit);
    }

    @NonNull private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    @NonNull private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    @NonNull private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    @NonNull private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
