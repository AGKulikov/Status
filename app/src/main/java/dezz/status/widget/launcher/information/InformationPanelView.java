/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarTelemetryDescriptor;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.LauncherIconResolver;

/**
 * Read-only HOME surface combining current vehicle/system and smart-home values.
 *
 * <p>Every session starts with empty maps. Stale connector entries and old vehicle samples are
 * never rendered as current values; this intentionally differs from diagnostic screens that keep
 * a last-known value for troubleshooting.</p>
 */
public final class InformationPanelView extends FrameLayout {
    public interface ContentListener {
        void onContentChanged(boolean hasConfiguredItems);
    }

    private static final long TICK_MS = 1_000L;
    private static final long SERVICE_RETRY_MS = 500L;
    private static final long DEFAULT_CAR_STALE_MS = 15_000L;

    private final CarIntegration carIntegration;
    private final InformationPanelConfigStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, CarTelemetryDescriptor> vehicleCatalog = new LinkedHashMap<>();
    private final Map<String, CarIntegration.TelemetryValue> vehicleValues =
            new LinkedHashMap<>();
    private final Map<String, ConnectorValue> connectorValues = new HashMap<>();
    private final Map<String, ItemViews> itemViews = new LinkedHashMap<>();
    private InformationPanelConfig config;
    private boolean started;
    private int catalogGeneration;
    private int connectorGeneration;
    @Nullable private WidgetService subscribedService;
    @Nullable private ConnectorValueRegistry.Listener connectorListener;
    @Nullable private ContentListener contentListener;

    private final CarIntegration.TelemetryListener vehicleListener = value -> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> acceptVehicleValue(value));
        } else {
            acceptVehicleValue(value);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            reconcileConnectorService();
            updateValues();
            // This runnable is the sole owner of scheduling. Connector reconciliation must never
            // create a second retry chain when WidgetService appears/disappears during boot.
            handler.removeCallbacks(this);
            handler.postDelayed(this,
                    subscribedService == null ? SERVICE_RETRY_MS : TICK_MS);
        }
    };

    public InformationPanelView(@NonNull Context context,
                                @NonNull CarIntegration carIntegration,
                                @NonNull InformationPanelConfigStore store) {
        super(context);
        this.carIntegration = carIntegration;
        this.store = store;
        config = store.load();
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        rebuild();
    }

    public void start() {
        if (started) return;
        started = true;
        vehicleValues.clear();
        connectorValues.clear();
        requestVehicleCatalog();
        subscribeVehicleValues();
        reconcileConnectorService();
        handler.removeCallbacks(tick);
        handler.post(tick);
        updateValues();
    }

    public void stop() {
        if (!started && subscribedService == null) return;
        started = false;
        catalogGeneration++;
        handler.removeCallbacks(tick);
        carIntegration.unsubscribeTelemetry(vehicleListener);
        disconnectConnectorService();
        vehicleValues.clear();
        connectorValues.clear();
        updateValues();
    }

    public void reloadConfig() {
        setConfig(store.load());
    }

    public void setConfig(@NonNull InformationPanelConfig source) {
        Set<String> oldVehicleIds = requestedVehicleIds();
        config = source.copy();
        config.normalize();
        rebuild();
        if (started && !oldVehicleIds.equals(requestedVehicleIds())) {
            subscribeVehicleValues();
        }
    }

    @NonNull
    public InformationPanelConfig currentConfig() {
        return config.copy();
    }

    public boolean hasConfiguredItems() {
        return config.hasEnabledItems();
    }

    public void setContentListener(@Nullable ContentListener listener) {
        contentListener = listener;
        if (listener != null) listener.onContentChanged(hasConfiguredItems());
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void requestVehicleCatalog() {
        final int generation = ++catalogGeneration;
        carIntegration.requestTelemetryCatalog(values -> {
            if (generation != catalogGeneration) return;
            vehicleCatalog.clear();
            for (CarTelemetryDescriptor value : values) vehicleCatalog.put(value.id, value);
            updateValues();
        });
    }

    private void subscribeVehicleValues() {
        carIntegration.unsubscribeTelemetry(vehicleListener);
        Set<String> ids = requestedVehicleIds();
        if (started && !ids.isEmpty()) {
            carIntegration.subscribeTelemetry(ids, vehicleListener);
        }
    }

    @NonNull
    private Set<String> requestedVehicleIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (InformationPanelConfig.Item item : config.mutableItems()) {
            if (item.enabled && item.sourceKind == InformationPanelConfig.SourceKind.VEHICLE
                    && !item.sourceId.isEmpty()) ids.add(item.sourceId);
        }
        return ids;
    }

    private void acceptVehicleValue(@NonNull CarIntegration.TelemetryValue value) {
        if (!started) return;
        vehicleValues.put(value.id, value);
        updateValues();
    }

    private void reconcileConnectorService() {
        if (!started) return;
        WidgetService current = WidgetService.getInstance();
        if (current == subscribedService) return;
        disconnectConnectorService();
        subscribedService = current;
        if (current != null) {
            final int generation = connectorGeneration;
            final WidgetService capturedService = current;
            ConnectorValueRegistry.Listener listener = changed -> {
                List<ConnectorValue> copy = new ArrayList<>(changed);
                post(() -> acceptConnectorChanges(
                        copy, generation, capturedService));
            };
            connectorListener = listener;
            List<ConnectorValue> snapshot =
                    current.addConnectorValueListener(listener);
            for (ConnectorValue value : snapshot) {
                connectorValues.put(connectorKey(value), value);
            }
        }
        updateValues();
    }

    private void disconnectConnectorService() {
        // Invalidate already queued callbacks before removing the listener or clearing values.
        // A quick stop→start may reconnect to the same WidgetService instance, so service identity
        // alone cannot distinguish an old transport session.
        connectorGeneration++;
        WidgetService current = subscribedService;
        ConnectorValueRegistry.Listener listener = connectorListener;
        subscribedService = null;
        connectorListener = null;
        if (current != null && listener != null) {
            current.removeConnectorValueListener(listener);
        }
        connectorValues.clear();
    }

    private void acceptConnectorChanges(@NonNull Collection<ConnectorValue> changed,
                                        int generation,
                                        @NonNull WidgetService capturedService) {
        if (!started || generation != connectorGeneration
                || subscribedService != capturedService) return;
        for (ConnectorValue value : changed) connectorValues.put(connectorKey(value), value);
        updateValues();
    }

    private void rebuild() {
        removeAllViews();
        itemViews.clear();
        applySurface();
        List<InformationPanelConfig.Item> visible = new ArrayList<>();
        for (InformationPanelConfig.Item item : config.mutableItems()) {
            if (item.enabled) visible.add(item);
        }
        if (visible.isEmpty()) {
            TextView empty = text("Добавьте статусы в настройках панели «Информация»",
                    15f, Color.WHITE, true);
            empty.setGravity(Gravity.CENTER);
            empty.setAlpha(.68f);
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            addView(empty, new FrameLayout.LayoutParams(match(), match()));
            notifyContent();
            return;
        }

        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(config.columns);
        grid.setRowCount(config.rows);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        grid.setPadding(config.contentPaddingPx, config.contentPaddingPx,
                config.contentPaddingPx, config.contentPaddingPx);
        for (InformationPanelConfig.Item item : visible) {
            View tile = buildItem(item);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(item.row, item.rowSpan, 1f),
                    GridLayout.spec(item.column, item.columnSpan, 1f));
            lp.width = 0;
            lp.height = 0;
            int first = config.gapPx / 2;
            lp.setMargins(first, first, config.gapPx - first, config.gapPx - first);
            grid.addView(tile, lp);
        }
        addView(grid, new FrameLayout.LayoutParams(match(), match()));
        updateValues();
        notifyContent();
    }

    @NonNull
    private View buildItem(@NonNull InformationPanelConfig.Item item) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(scaledDp(10, item.scalePercent), scaledDp(7, item.scalePercent),
                scaledDp(10, item.scalePercent), scaledDp(7, item.scalePercent));
        tile.setClickable(false);
        tile.setLongClickable(false);
        tile.setFocusable(false);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(58, 255, 255, 255));
        background.setCornerRadius(Math.max(4f, config.cornerRadiusPx * .48f));
        tile.setBackground(background);

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageDrawable(LauncherIconResolver.resolvePreset(getContext(),
                InformationIconPolicy.resolve(item), item.iconColor));
        icon.setContentDescription(item.displayLabel());
        int iconSize = scaledDp(32, item.scalePercent);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = scaledDp(9, item.scalePercent);
        tile.addView(icon, iconLp);
        icon.setVisibility(item.showIcon ? View.VISIBLE : View.GONE);

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(item.displayLabel(), scaledSp(11.5f, item.scalePercent),
                color(item.labelColor, Color.LTGRAY), false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setVisibility(item.showLabel ? View.VISIBLE : View.GONE);
        TextView value = text("—", scaledSp(20f, item.scalePercent),
                color(item.valueColor, Color.WHITE), true);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(label, new LinearLayout.LayoutParams(match(), wrap()));
        texts.addView(value, new LinearLayout.LayoutParams(match(), wrap()));
        tile.addView(texts, new LinearLayout.LayoutParams(0, wrap(), 1f));

        itemViews.put(item.id, new ItemViews(tile, icon, label, value, background));
        return tile;
    }

    private void updateValues() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(this::updateValues);
            return;
        }
        for (InformationPanelConfig.Item item : config.mutableItems()) {
            ItemViews views = itemViews.get(item.id);
            if (views == null) continue;
            Value value = resolve(item);
            views.tile.setVisibility(InformationValuePolicy.isVisible(
                    item.visibility, value.known, value.active) ? View.VISIBLE : View.INVISIBLE);
            views.label.setText(item.displayLabel());
            views.value.setText(value.known ? value.display : "—");
            views.value.setAlpha(value.known ? 1f : .48f);
            views.icon.setAlpha(value.known ? (value.active ? 1f : .58f) : .28f);
            views.background.setColor(value.known && value.active
                    ? Color.argb(82, 84, 168, 255)
                    : Color.argb(58, 255, 255, 255));
            views.tile.setContentDescription(item.displayLabel() + ": "
                    + (value.known ? value.display : "нет актуальных данных"));
        }
    }

    @NonNull
    private Value resolve(@NonNull InformationPanelConfig.Item item) {
        if (item.sourceKind == InformationPanelConfig.SourceKind.SYSTEM) {
            return resolveSystem(item);
        }
        if (item.sourceKind == InformationPanelConfig.SourceKind.VEHICLE) {
            CarIntegration.TelemetryValue sample = vehicleValues.get(item.sourceId);
            if (sample == null || isVehicleStale(sample)) return Value.unknown();
            double normalized = normalizeVehicle(item.sourceId, sample.value);
            String unit = unit(item, sample.unit);
            return Value.known(formatNumber(normalized, item.decimals, unit), normalized);
        }
        SourceBinding binding = item.binding;
        if (binding == null || !binding.isBound()) return Value.unknown();
        ConnectorValue source = connectorValues.get(connectorKey(binding));
        if (source == null) return Value.unknown();
        Object raw = source.resolveValue(binding.valuePath);
        if (!InformationValuePolicy.isConnectorKnown(source.fresh, source.available,
                source.readable, raw)) return Value.unknown();
        String suffix = unit(item, binding.unitSuffix.isEmpty() ? source.unit
                : binding.unitSuffix);
        return Value.known(formatConnector(raw, item, binding, suffix), raw);
    }

    @NonNull
    private Value resolveSystem(@NonNull InformationPanelConfig.Item item) {
        switch (item.sourceId) {
            case "system.time":
                return Value.known(DateFormat.getTimeFormat(getContext()).format(new Date()), true);
            case "system.date":
                return Value.known(DateFormat.getDateFormat(getContext()).format(new Date()), true);
            case "system.battery.level": {
                Intent battery = getContext().registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery == null) return Value.unknown();
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level < 0 || scale <= 0) return Value.unknown();
                double percent = level * 100d / scale;
                return Value.known(formatNumber(percent, 0, unit(item, "%")), percent);
            }
            case "system.battery.charging": {
                Intent battery = getContext().registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery == null) return Value.unknown();
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (status < 0) return Value.unknown();
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                return Value.known(charging ? "Заряжается" : "Не заряжается", charging);
            }
            case "system.network": {
                try {
                    ConnectivityManager manager = (ConnectivityManager) getContext()
                            .getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
                    boolean connected = active != null && active.isConnected();
                    String type = connected && active.getTypeName() != null
                            ? active.getTypeName() : "Нет сети";
                    return Value.known(type, connected);
                } catch (RuntimeException ignored) {
                    return Value.unknown();
                }
            }
            case "system.storage.free": {
                try {
                    StatFs stats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
                    double gigabytes = stats.getAvailableBytes() / 1_073_741_824d;
                    return Value.known(formatNumber(gigabytes, 1, unit(item, "ГБ")), gigabytes);
                } catch (RuntimeException ignored) {
                    return Value.unknown();
                }
            }
            default:
                return Value.unknown();
        }
    }

    private boolean isVehicleStale(@NonNull CarIntegration.TelemetryValue sample) {
        CarTelemetryDescriptor descriptor = vehicleCatalog.get(sample.id);
        long timeout = descriptor == null ? DEFAULT_CAR_STALE_MS : descriptor.staleAfterMillis;
        return timeout > 0L && System.currentTimeMillis() - sample.observedAtMillis > timeout;
    }

    @NonNull
    private String formatConnector(@NonNull Object raw,
                                   @NonNull InformationPanelConfig.Item item,
                                   @NonNull SourceBinding binding,
                                   @NonNull String unit) {
        if (SourceBinding.PRESENTATION_BOOLEAN.equals(binding.presentation)) {
            return InformationValuePolicy.isActive(raw) ? "Вкл" : "Выкл";
        }
        if (SourceBinding.PRESENTATION_COVER.equals(binding.presentation)) {
            String state = String.valueOf(raw).toLowerCase(Locale.ROOT);
            if ("open".equals(state) || "opening".equals(state)) return "Открыто";
            if ("closed".equals(state) || "closing".equals(state)) return "Закрыто";
        }
        if (raw instanceof Number) {
            return formatNumber(((Number) raw).doubleValue(), item.decimals, unit);
        }
        if (raw instanceof Boolean) return (Boolean) raw ? "Вкл" : "Выкл";
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return "—";
        return unit.isEmpty() ? value : value + " " + unit;
    }

    @NonNull
    private static String formatNumber(double value, int decimals, @NonNull String unit) {
        if (!Double.isFinite(value)) return "—";
        StringBuilder pattern = new StringBuilder("0");
        if (decimals > 0) {
            pattern.append('.');
            for (int index = 0; index < decimals; index++) pattern.append('0');
        }
        DecimalFormat format = new DecimalFormat(pattern.toString(),
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
        String number = format.format(value);
        return unit.isEmpty() ? number : number + " " + unit;
    }

    private static double normalizeVehicle(@NonNull String id, double value) {
        if ("ISensor.fuel_level".equals(id)) return value / 1_000d;
        if ("ISensor.speed".equals(id)) return value * 3.72d;
        if (id.startsWith("TPMS.pressure.") && Math.abs(value) >= 40d) return value / 100d;
        return value;
    }

    @NonNull
    private static String unit(@NonNull InformationPanelConfig.Item item,
                               @Nullable String fallback) {
        if (!item.unitOverride.isEmpty()) return item.unitOverride;
        if (!item.sourceUnit.isEmpty() && !"raw".equalsIgnoreCase(item.sourceUnit)) {
            return item.sourceUnit;
        }
        String value = fallback == null ? "" : fallback.trim();
        return "raw".equalsIgnoreCase(value) ? "" : value;
    }

    private void applySurface() {
        GradientDrawable surface = new GradientDrawable();
        int color = color(config.backgroundColor, Color.rgb(17, 24, 34));
        surface.setColor(Color.argb(config.backgroundAlpha, Color.red(color),
                Color.green(color), Color.blue(color)));
        surface.setCornerRadius(config.cornerRadiusPx);
        setBackground(surface);
    }

    private void notifyContent() {
        ContentListener listener = contentListener;
        if (listener != null) listener.onContentChanged(hasConfiguredItems());
    }

    @NonNull
    private TextView text(@NonNull String value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int scaledDp(int value, int scalePercent) {
        return Math.max(1, dp(value) * scalePercent / 100);
    }

    private static float scaledSp(float value, int scalePercent) {
        return value * scalePercent / 100f;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int color(@Nullable String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }

    @NonNull
    private static String connectorKey(@NonNull ConnectorValue value) {
        return value.connectorType.name() + '\u0001' + value.connectorId + '\u0001'
                + value.resourceId;
    }

    @NonNull
    private static String connectorKey(@NonNull SourceBinding binding) {
        return binding.connectorType.name() + '\u0001' + binding.connectorId + '\u0001'
                + binding.resourceId;
    }

    private static final class ItemViews {
        final View tile;
        final ImageView icon;
        final TextView label;
        final TextView value;
        final GradientDrawable background;

        ItemViews(View tile, ImageView icon, TextView label, TextView value,
                  GradientDrawable background) {
            this.tile = tile;
            this.icon = icon;
            this.label = label;
            this.value = value;
            this.background = background;
        }
    }

    private static final class Value {
        final boolean known;
        final boolean active;
        @NonNull final String display;

        private Value(boolean known, boolean active, @NonNull String display) {
            this.known = known;
            this.active = active;
            this.display = display;
        }

        @NonNull static Value unknown() { return new Value(false, false, "—"); }

        @NonNull static Value known(@NonNull String display, @Nullable Object raw) {
            return new Value(true, InformationValuePolicy.isActive(raw), display);
        }
    }
}
