/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.R;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlDescriptor;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;

/**
 * A standalone, responsive HOME climate surface backed by confirmed ECARX control state.
 * Unsupported functions disappear after the catalog probe; UNKNOWN controls stay visible but
 * disabled until the vehicle service confirms them.
 */
public final class ClimatePanelView extends FrameLayout {
    /** Geely integration verifies controls every 30 s; older cache is presentation-only. */
    private static final long STATE_FRESH_MS = 75_000L;
    private final CarIntegration integration;
    private final ClimatePanelConfigStore configStore;
    private final Map<String, CarControlDescriptor> catalog = new LinkedHashMap<>();
    private final Map<String, CarControlState> states = new LinkedHashMap<>();
    private final Map<String, ControlBinding> bindings = new LinkedHashMap<>();
    private final Map<String, Long> pending = new LinkedHashMap<>();
    private ClimatePanelConfig config;
    private TextView connectionLabel;
    private boolean started;
    private int catalogGeneration;
    private boolean catalogRefreshPending;
    private int catalogRetryAttempts;
    private long sessionStartedAtMillis;
    private long nextCommandToken;

    private final CarIntegration.ControlStateListener stateListener = state -> {
        CarControlState previous = states.put(state.controlId, state);
        applyState(state.controlId);
        updateConnectionLabel();
        CarControlDescriptor descriptor = catalog.get(state.controlId);
        if (started && state.available
                && (previous == null || !previous.available)
                && descriptor != null
                && descriptor.availability != CarControlDescriptor.Availability.SUPPORTED) {
            // ECARX may answer UNKNOWN while its Binder service is still booting. Re-probe once
            // the state stream proves that the function is alive so level controls receive the
            // vehicle-filtered option list instead of boot-time defaults.
            requestCatalog(false);
        }
    };

    public ClimatePanelView(@NonNull Context context, @NonNull CarIntegration integration,
                            @NonNull ClimatePanelConfigStore configStore) {
        super(context);
        this.integration = integration;
        this.configStore = configStore;
        this.config = configStore.load();
        setClipChildren(false);
        setClipToPadding(false);
        renderLoading();
    }

    /** Reloads settings written by the visual editor and immediately redraws the panel. */
    public void reloadConfig() {
        setConfig(configStore.load());
    }

    /** Used by the editor's live preview; the caller persists the same value separately. */
    public void setConfig(@NonNull ClimatePanelConfig value) {
        Set<String> previousIds = visibleControlIds();
        config = value.copy();
        config.normalize();
        if (catalog.isEmpty()) renderLoading();
        else rebuildControls();
        if (started && !previousIds.equals(visibleControlIds())) subscribeVisibleControls();
    }

    /** Begin catalog discovery and confirmed-state streaming. Idempotent across lifecycle calls. */
    public void start() {
        if (started) {
            subscribeVisibleControls();
            return;
        }
        started = true;
        catalogRetryAttempts = 0;
        sessionStartedAtMillis = System.currentTimeMillis();
        states.clear();
        if (catalog.isEmpty()) renderLoading();
        else {
            rebuildControls();
            subscribeVisibleControls();
        }
        requestCatalog(false);
    }

    private void requestCatalog(boolean showLoading) {
        if (!started || catalogRefreshPending) return;
        catalogRefreshPending = true;
        final int generation = ++catalogGeneration;
        if (showLoading && catalog.isEmpty()) renderLoading();
        integration.requestControlCatalog(controls -> {
            if (!started || generation != catalogGeneration) return;
            catalogRefreshPending = false;
            catalog.clear();
            for (CarControlDescriptor control : controls) {
                if (isClimateControl(control.id)
                        && control.availability != CarControlDescriptor.Availability.UNSUPPORTED) {
                    catalog.put(control.id, control);
                }
            }
            rebuildControls();
            subscribeVisibleControls();
            if (needsCatalogRetry() && catalogRetryAttempts < 4) {
                catalogRetryAttempts++;
                final int completedGeneration = generation;
                postDelayed(() -> {
                    if (started && completedGeneration == catalogGeneration) {
                        requestCatalog(false);
                    }
                }, 2_000L);
            } else if (!needsCatalogRetry()) {
                catalogRetryAttempts = 0;
            }
        });
    }

    public void stop() {
        if (!started) return;
        started = false;
        catalogGeneration++;
        catalogRefreshPending = false;
        sessionStartedAtMillis = 0;
        integration.unsubscribeControlStates(stateListener);
        states.clear();
    }

    @NonNull
    public ClimatePanelConfig currentConfig() {
        return config.copy();
    }

    private void renderLoading() {
        removeAllViews();
        bindings.clear();
        connectionLabel = null;
        applySurface();
        LinearLayout center = new LinearLayout(getContext());
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_car_climate);
        icon.setColorFilter(color(config.accentColor, Color.CYAN));
        int iconSize = scaledDp(42);
        center.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView label = label("Подключение к климату…", scaledSp(16), true);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = scaledDp(8);
        center.addView(label, labelLp);
        addView(center, matchFrame());
    }

    private void rebuildControls() {
        removeAllViews();
        bindings.clear();
        connectionLabel = null;
        applySurface();

        if (catalog.isEmpty()) {
            LinearLayout empty = new LinearLayout(getContext());
            empty.setGravity(Gravity.CENTER);
            TextView message = label("Климат автомобиля недоступен", scaledSp(16), true);
            message.setGravity(Gravity.CENTER);
            empty.addView(message);
            addView(empty, matchFrame());
            return;
        }

        ScrollView verticalScroll = new ScrollView(getContext());
        verticalScroll.setFillViewport(true);
        verticalScroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = scaledDp(13);
        root.setPadding(padding, scaledDp(9), padding, padding);
        verticalScroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (config.showTitle) root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scaledDp(50)));

        LinearLayout quick = new LinearLayout(getContext());
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER_VERTICAL);
        addActionIfVisible(quick, ClimatePanelConfig.POWER, CarControlCommand.Operation.TOGGLE,
                "Климат");
        addActionIfVisible(quick, ClimatePanelConfig.AC, CarControlCommand.Operation.TOGGLE,
                "A/C");
        addActionIfVisible(quick, ClimatePanelConfig.AUTO, CarControlCommand.Operation.TOGGLE,
                "AUTO");
        if (quick.getChildCount() > 0) {
            root.addView(quick, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, scaledDp(76)));
        }

        LinearLayout steppers = new LinearLayout(getContext());
        steppers.setOrientation(LinearLayout.HORIZONTAL);
        steppers.setGravity(Gravity.CENTER_VERTICAL);
        addStepperIfVisible(steppers, ClimatePanelConfig.TEMP_DRIVER, "Водитель");
        addStepperIfVisible(steppers, ClimatePanelConfig.FAN, "Вентилятор");
        addStepperIfVisible(steppers, ClimatePanelConfig.TEMP_PASSENGER, "Пассажир");
        if (steppers.getChildCount() > 0) {
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, scaledDp(88));
            rowLp.topMargin = scaledDp(5);
            root.addView(steppers, rowLp);
        }

        LinearLayout features = new LinearLayout(getContext());
        features.setOrientation(LinearLayout.HORIZONTAL);
        features.setGravity(Gravity.CENTER_VERTICAL);
        addCycleIfVisible(features, ClimatePanelConfig.SEAT_HEAT_DRIVER, "Сиденье\nводителя");
        addCycleIfVisible(features, ClimatePanelConfig.SEAT_VENT_DRIVER, "Вентиляция\nводителя");
        addCycleIfVisible(features, ClimatePanelConfig.WHEEL_HEAT, "Руль");
        addCycleIfVisible(features, ClimatePanelConfig.DEFROST_FRONT, "Лобовое");
        addCycleIfVisible(features, ClimatePanelConfig.DEFROST_REAR, "Заднее");
        addCycleIfVisible(features, ClimatePanelConfig.SEAT_HEAT_PASSENGER,
                "Сиденье\nпассажира");
        addCycleIfVisible(features, ClimatePanelConfig.SEAT_VENT_PASSENGER,
                "Вентиляция\nпассажира");
        if (features.getChildCount() > 0) {
            HorizontalScrollView featureScroll = new HorizontalScrollView(getContext());
            featureScroll.setHorizontalScrollBarEnabled(false);
            featureScroll.setFillViewport(true);
            featureScroll.addView(features, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, scaledDp(92));
            rowLp.topMargin = scaledDp(6);
            root.addView(featureScroll, rowLp);
        }

        addView(verticalScroll, matchFrame());
        for (String id : new ArrayList<>(bindings.keySet())) applyState(id);
        updateConnectionLabel();
    }

    @NonNull
    private View buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_car_climate);
        icon.setColorFilter(color(config.accentColor, Color.CYAN));
        header.addView(icon, new LinearLayout.LayoutParams(scaledDp(30), scaledDp(30)));
        TextView title = label("КЛИМАТ", scaledSp(19), true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = scaledDp(10);
        header.addView(title, titleLp);
        connectionLabel = label("Подключение…", scaledSp(13), false);
        connectionLabel.setAlpha(.76f);
        connectionLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(connectionLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return header;
    }

    private void addActionIfVisible(@NonNull LinearLayout row, @NonNull String id,
                                    @NonNull CarControlCommand.Operation operation,
                                    @NonNull String shortLabel) {
        CarControlDescriptor descriptor = visibleDescriptor(id);
        if (descriptor == null) return;
        MaterialCardView card = tileCard();
        LinearLayout content = tileContent();
        ImageView icon = tileIcon(id);
        TextView title = label(shortLabel, scaledSp(12), true);
        title.setGravity(Gravity.CENTER);
        TextView value = label("…", scaledSp(11), true);
        value.setGravity(Gravity.CENTER);
        content.addView(icon, new LinearLayout.LayoutParams(scaledDp(25), scaledDp(25)));
        content.addView(title);
        content.addView(value);
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOnClickListener(v -> execute(id, operation, 0));
        ControlBinding binding = new ControlBinding(descriptor, card, icon, value,
                Collections.emptyList());
        bindings.put(id, binding);
        LinearLayout.LayoutParams lp = weightedTileLp();
        row.addView(card, lp);
    }

    private void addStepperIfVisible(@NonNull LinearLayout row, @NonNull String id,
                                     @NonNull String shortLabel) {
        CarControlDescriptor descriptor = visibleDescriptor(id);
        if (descriptor == null) return;
        MaterialCardView card = tileCard();
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(scaledDp(3), scaledDp(4), scaledDp(3), scaledDp(4));
        TextView minus = stepButton("−");
        TextView plus = stepButton("+");
        LinearLayout center = new LinearLayout(getContext());
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        ImageView icon = tileIcon(id);
        TextView title = label(shortLabel, scaledSp(11), true);
        title.setGravity(Gravity.CENTER);
        TextView value = label("…", scaledSp(17), true);
        value.setGravity(Gravity.CENTER);
        center.addView(icon, new LinearLayout.LayoutParams(scaledDp(23), scaledDp(23)));
        center.addView(value);
        center.addView(title);
        content.addView(minus, new LinearLayout.LayoutParams(scaledDp(40),
                ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(center, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        content.addView(plus, new LinearLayout.LayoutParams(scaledDp(40),
                ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        minus.setOnClickListener(v -> adjust(id, -1));
        plus.setOnClickListener(v -> adjust(id, 1));
        List<View> interactive = new ArrayList<>();
        interactive.add(minus);
        interactive.add(plus);
        bindings.put(id, new ControlBinding(descriptor, card, icon, value, interactive));
        row.addView(card, weightedTileLp());
    }

    private void addCycleIfVisible(@NonNull LinearLayout row, @NonNull String id,
                                   @NonNull String shortLabel) {
        CarControlDescriptor descriptor = visibleDescriptor(id);
        if (descriptor == null) return;
        MaterialCardView card = tileCard();
        LinearLayout content = tileContent();
        ImageView icon = tileIcon(id);
        TextView title = label(shortLabel, scaledSp(10), true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        TextView value = label(descriptor.kind == CarControlDescriptor.Kind.ACTION ? "" : "…",
                scaledSp(10), true);
        value.setGravity(Gravity.CENTER);
        content.addView(icon, new LinearLayout.LayoutParams(scaledDp(29), scaledDp(29)));
        content.addView(title);
        content.addView(value);
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        CarControlCommand.Operation operation = descriptor.kind == CarControlDescriptor.Kind.ACTION
                ? CarControlCommand.Operation.ACTIVATE : CarControlCommand.Operation.CYCLE;
        card.setOnClickListener(v -> execute(id, operation, 0));
        bindings.put(id, new ControlBinding(descriptor, card, icon, value,
                Collections.emptyList()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                scaledDp(104), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(scaledDp(4), scaledDp(3), scaledDp(4), scaledDp(3));
        row.addView(card, lp);
    }

    @Nullable
    private CarControlDescriptor visibleDescriptor(@NonNull String id) {
        if (!config.isElementEnabled(id)) return null;
        CarControlDescriptor descriptor = catalog.get(id);
        return descriptor != null
                && descriptor.availability != CarControlDescriptor.Availability.UNSUPPORTED
                ? descriptor : null;
    }

    private void subscribeVisibleControls() {
        if (!started) return;
        Set<String> ids = visibleControlIds();
        if (ids.isEmpty()) integration.unsubscribeControlStates(stateListener);
        else integration.subscribeControlStates(ids, stateListener);
    }

    @NonNull
    private Set<String> visibleControlIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            if (visibleDescriptor(element.id) != null) ids.add(element.id);
        }
        return ids;
    }

    private boolean needsCatalogRetry() {
        for (Map.Entry<String, CarControlDescriptor> entry : catalog.entrySet()) {
            if (entry.getValue().availability == CarControlDescriptor.Availability.UNKNOWN) {
                CarControlState state = states.get(entry.getKey());
                if (isFresh(state) && state.available) return true;
            }
        }
        return false;
    }

    private void adjust(@NonNull String id, int direction) {
        ControlBinding binding = bindings.get(id);
        CarControlState state = states.get(id);
        if (binding == null || !isFresh(state) || !state.available || !state.known
                || !Double.isFinite(state.value)) return;
        CarControlDescriptor descriptor = binding.descriptor;
        double target;
        if (!descriptor.options.isEmpty()) {
            List<CarControlDescriptor.Option> options = new ArrayList<>(descriptor.options);
            options.sort(Comparator.comparingDouble(value -> value.value));
            int nearest = 0;
            double distance = Double.MAX_VALUE;
            for (int index = 0; index < options.size(); index++) {
                double candidate = Math.abs(options.get(index).value - state.value);
                if (candidate < distance) { distance = candidate; nearest = index; }
            }
            int next = Math.max(0, Math.min(options.size() - 1, nearest + direction));
            target = options.get(next).value;
        } else {
            double step = descriptor.step > 0 ? descriptor.step : 1;
            target = Math.max(descriptor.minimum,
                    Math.min(descriptor.maximum, state.value + step * direction));
            if (step > 0) {
                target = descriptor.minimum
                        + Math.round((target - descriptor.minimum) / step) * step;
            }
        }
        execute(id, CarControlCommand.Operation.SET, target);
    }

    private void execute(@NonNull String id, @NonNull CarControlCommand.Operation operation,
                         double value) {
        ControlBinding binding = bindings.get(id);
        CarControlState state = states.get(id);
        if (binding == null || pending.containsKey(id)) return;
        boolean available = isFresh(state) && state.available;
        if (!available) {
            Toast.makeText(getContext(), "Функция пока недоступна", Toast.LENGTH_SHORT).show();
            return;
        }
        final long token = ++nextCommandToken;
        pending.put(id, token);
        applyState(id);
        integration.executeControl(new CarControlCommand(id, operation, value),
                (success, message) -> {
                    Long current = pending.get(id);
                    if (current == null || current != token) return;
                    pending.remove(id);
                    applyState(id);
                    if (!success) {
                        Toast.makeText(getContext(), message == null
                                ? "Команда не выполнена" : message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void applyState(@NonNull String id) {
        ControlBinding binding = bindings.get(id);
        if (binding == null) return;
        CarControlState state = states.get(id);
        boolean available = isFresh(state) && state.available;
        boolean known = available && state.known;
        boolean active = known && state.active;
        int accent = color(config.accentColor, Color.CYAN);
        if (active && config.useVehicleStateColors && state.suggestedColor != null) {
            accent = color(state.suggestedColor, accent);
        }
        int inactive = color(config.inactiveColor, Color.LTGRAY);
        int tint = active ? accent : inactive;
        binding.icon.setColorFilter(tint);
        binding.value.setText(pending.containsKey(id) || !isFresh(state) ? "…" : state.valueLabel);
        binding.value.setTextColor(tint);
        binding.card.setCardBackgroundColor(active
                ? withAlpha(accent, 72) : Color.argb(92, 255, 255, 255));
        binding.card.setStrokeColor(active ? accent : Color.argb(52, 255, 255, 255));
        binding.card.setStrokeWidth(active ? scaledDp(2) : scaledDp(1));
        float alpha = pending.containsKey(id) ? .60f : available ? 1f : .42f;
        binding.card.setAlpha(alpha);
        binding.card.setEnabled(available && !pending.containsKey(id));
        boolean optionsReady = binding.descriptor.options.isEmpty()
                || binding.descriptor.availability == CarControlDescriptor.Availability.SUPPORTED;
        for (View interactive : binding.interactive) {
            interactive.setEnabled(known && optionsReady && !pending.containsKey(id));
            interactive.setAlpha(known && optionsReady ? 1f : .38f);
        }
        binding.card.setContentDescription(binding.descriptor.label + ", "
                + (state == null ? "состояние неизвестно" : state.valueLabel));
    }

    private void updateConnectionLabel() {
        if (connectionLabel == null) return;
        CarControlState power = states.get(ClimatePanelConfig.POWER);
        if (isFresh(power) && power.available && power.known) {
            connectionLabel.setText(power.active ? "Климат включён" : "Климат выключен");
            return;
        }
        boolean online = false;
        for (CarControlState state : states.values()) {
            if (isFresh(state) && state.available) { online = true; break; }
        }
        connectionLabel.setText(online ? "Автомобиль подключён" : "Подключение…");
    }

    private void applySurface() {
        int base = color(config.backgroundColor, Color.rgb(20, 26, 36));
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(base, config.backgroundAlpha),
                        withAlpha(blend(base, color(config.accentColor, Color.CYAN), .13f),
                                config.backgroundAlpha)});
        surface.setCornerRadius(config.cornerRadiusPx);
        surface.setStroke(scaledDp(1), Color.argb(Math.min(150, config.backgroundAlpha),
                255, 255, 255));
        setBackground(surface);
    }

    @NonNull
    private MaterialCardView tileCard() {
        MaterialCardView card = new MaterialCardView(getContext());
        card.setRadius(scaledDp(15));
        card.setCardElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setRippleColor(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)));
        return card;
    }

    @NonNull
    private LinearLayout tileContent() {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(scaledDp(5), scaledDp(5), scaledDp(5), scaledDp(5));
        return content;
    }

    @NonNull
    private ImageView tileIcon(@NonNull String id) {
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconFor(id));
        icon.setColorFilter(color(config.inactiveColor, Color.LTGRAY));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return icon;
    }

    @NonNull
    private TextView stepButton(@NonNull String symbol) {
        TextView button = label(symbol, scaledSp(26), true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(32, 255, 255, 255));
        background.setCornerRadius(scaledDp(12));
        button.setBackground(background);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(symbol.equals("+") ? "Увеличить" : "Уменьшить");
        button.setPadding(scaledDp(6), 0, scaledDp(6), 0);
        return button;
    }

    @NonNull
    private TextView label(@NonNull String value, float size, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextColor(color(config.textColor, Color.WHITE));
        text.setTextSize(size);
        text.setMaxLines(2);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private LinearLayout.LayoutParams weightedTileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(scaledDp(4), scaledDp(3), scaledDp(4), scaledDp(3));
        return lp;
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int scaledDp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(value * density * config.scalePercent / 100f));
    }

    private float scaledSp(int value) {
        return value * config.scalePercent / 100f;
    }

    private static boolean isClimateControl(@NonNull String id) {
        return id.startsWith("climate.");
    }

    private boolean isFresh(@Nullable CarControlState state) {
        if (state == null || state.observedAtMillis <= 0) return false;
        long age = System.currentTimeMillis() - state.observedAtMillis;
        return sessionStartedAtMillis > 0 && state.observedAtMillis >= sessionStartedAtMillis
                && age >= 0 && age <= STATE_FRESH_MS;
    }

    private static int iconFor(@NonNull String id) {
        if (id.contains("seat_heat")) return R.drawable.ic_car_seat_heat;
        if (id.contains("seat_vent")) return R.drawable.ic_car_seat_vent;
        if (id.equals(ClimatePanelConfig.WHEEL_HEAT)) return R.drawable.ic_car_wheel_heat;
        if (id.equals(ClimatePanelConfig.DEFROST_FRONT)) return R.drawable.ic_car_defrost_front;
        if (id.equals(ClimatePanelConfig.DEFROST_REAR)) return R.drawable.ic_car_defrost_rear;
        if (id.contains("temp")) return R.drawable.ic_popup_temperature;
        if (id.equals(ClimatePanelConfig.POWER)) return R.drawable.ic_popup_power;
        return R.drawable.ic_car_climate;
    }

    private static int color(@Nullable String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return fallback; }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    private static int blend(int first, int second, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(Math.round(Color.red(first) * inverse + Color.red(second) * amount),
                Math.round(Color.green(first) * inverse + Color.green(second) * amount),
                Math.round(Color.blue(first) * inverse + Color.blue(second) * amount));
    }

    private static final class ControlBinding {
        @NonNull final CarControlDescriptor descriptor;
        @NonNull final MaterialCardView card;
        @NonNull final ImageView icon;
        @NonNull final TextView value;
        @NonNull final List<View> interactive;

        ControlBinding(@NonNull CarControlDescriptor descriptor,
                       @NonNull MaterialCardView card, @NonNull ImageView icon,
                       @NonNull TextView value, @NonNull List<View> interactive) {
            this.descriptor = descriptor;
            this.card = card;
            this.icon = icon;
            this.value = value;
            this.interactive = interactive;
        }
    }
}
