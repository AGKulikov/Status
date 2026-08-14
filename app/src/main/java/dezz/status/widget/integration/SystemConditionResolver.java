/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.Scenario;
import dezz.status.widget.scenario.ValueReference;
import dezz.status.widget.scenario.ValueResolver;

/**
 * Read-only local sources for automation conditions that do not belong to a cloud connector.
 *
 * <p>Stable resource ids:
 * <ul>
 *   <li>{@code time.range:HHmm-HHmm}</li>
 *   <li>{@code vehicle.passenger_present}</li>
 *   <li>{@code hwgps.route_lost}</li>
 *   <li>{@code automation.visible:SCOPE:element_id}</li>
 * </ul>
 */
public final class SystemConditionResolver implements ValueResolver {
    public static final String CONNECTOR_TYPE = "SYSTEM";
    public static final String CONNECTOR_ID = "default";
    public static final String PASSENGER_RESOURCE = "vehicle.passenger_present";
    public static final String HWGPS_ROUTE_LOST_RESOURCE = "hwgps.route_lost";
    public static final String TIME_RANGE_PREFIX = "time.range:";
    public static final String AUTOMATION_VISIBLE_PREFIX = "automation.visible:";
    public static final String PASSENGER_METRIC =
            "ISensor.seat_occupation_status_passenger";

    private final AutomationStateStore states;
    private final PopupOverlayConfigStore overlays;
    @Nullable private final CarIntegration carIntegration;
    @Nullable private final HwgpsIntegration.RouteStateSubscription hwgpsRouteState;
    private final Runnable changeListener;
    private final CarIntegration.TelemetryListener passengerListener;

    private boolean passengerSubscribed;
    private boolean passengerKnown;
    private boolean passengerPresent;
    private boolean hwgpsSubscribed;

    public SystemConditionResolver(@NonNull Preferences prefs,
                                   @NonNull AutomationStateStore states,
                                   @Nullable CarIntegration carIntegration,
                                   @NonNull Runnable changeListener) {
        this(null, prefs, states, carIntegration, changeListener);
    }

    public SystemConditionResolver(@Nullable Context context,
                                   @NonNull Preferences prefs,
                                   @NonNull AutomationStateStore states,
                                   @Nullable CarIntegration carIntegration,
                                   @NonNull Runnable changeListener) {
        this.states = states;
        this.overlays = new PopupOverlayConfigStore(prefs);
        this.carIntegration = carIntegration;
        this.changeListener = changeListener;
        this.hwgpsRouteState = context == null ? null
                : new HwgpsIntegration.RouteStateSubscription(context,
                ignored -> this.changeListener.run());
        this.passengerListener = value -> {
            if (!PASSENGER_METRIC.equals(value.id)) return;
            boolean next = value.value > .5d;
            boolean changed = !passengerKnown || passengerPresent != next;
            passengerKnown = true;
            passengerPresent = next;
            if (changed) this.changeListener.run();
        };
    }

    public void configure(@NonNull List<Scenario> scenarios) {
        boolean passengerNeeded = false;
        boolean hwgpsNeeded = false;
        for (Scenario scenario : scenarios) {
            if (!scenario.enabled) continue;
            for (dezz.status.widget.scenario.Condition condition : scenario.conditions) {
                ValueReference reference = condition.reference;
                if (!isSystem(reference)) continue;
                if (PASSENGER_RESOURCE.equals(reference.resourceId)) passengerNeeded = true;
                if (HWGPS_ROUTE_LOST_RESOURCE.equals(reference.resourceId)) hwgpsNeeded = true;
            }
            if (passengerNeeded && hwgpsNeeded) break;
        }
        if (passengerNeeded != passengerSubscribed && carIntegration != null) {
            passengerSubscribed = passengerNeeded;
            if (passengerNeeded) {
                carIntegration.subscribeTelemetry(
                        Collections.singleton(PASSENGER_METRIC), passengerListener);
            } else {
                carIntegration.unsubscribeTelemetry(passengerListener);
                passengerKnown = false;
                passengerPresent = false;
            }
        }
        if (hwgpsNeeded != hwgpsSubscribed && hwgpsRouteState != null) {
            hwgpsSubscribed = hwgpsNeeded;
            if (hwgpsNeeded) hwgpsRouteState.start(); else hwgpsRouteState.stop();
        }
    }

    public void destroy() {
        if (passengerSubscribed && carIntegration != null) {
            carIntegration.unsubscribeTelemetry(passengerListener);
        }
        passengerSubscribed = false;
        passengerKnown = false;
        if (hwgpsRouteState != null) hwgpsRouteState.stop();
        hwgpsSubscribed = false;
    }

    public static boolean isSystem(@Nullable ValueReference reference) {
        return reference != null
                && CONNECTOR_TYPE.equalsIgnoreCase(reference.connectorType)
                && CONNECTOR_ID.equals(reference.connectorId);
    }

    @Override
    @NonNull
    public Input resolve(@NonNull ValueReference reference) {
        if (!isSystem(reference)) return Input.unavailable();
        String resource = reference.resourceId;
        if (PASSENGER_RESOURCE.equals(resource)) {
            return passengerKnown
                    ? Input.value(passengerPresent, true, true)
                    : Input.unavailable();
        }
        if (HWGPS_ROUTE_LOST_RESOURCE.equals(resource)) {
            if (hwgpsRouteState == null) return Input.unavailable();
            switch (hwgpsRouteState.state()) {
                case ROUTE_LOST: return Input.value(true, true, true);
                case ROUTE_AVAILABLE: return Input.value(false, true, true);
                case UNAVAILABLE:
                default: return Input.unavailable();
            }
        }
        if (resource.startsWith(TIME_RANGE_PREFIX)) {
            Boolean inside = inTimeRange(resource.substring(TIME_RANGE_PREFIX.length()),
                    System.currentTimeMillis());
            return inside == null ? Input.unavailable() : Input.value(inside, true, true);
        }
        if (resource.startsWith(AUTOMATION_VISIBLE_PREFIX)) {
            String target = resource.substring(AUTOMATION_VISIBLE_PREFIX.length());
            int separator = target.indexOf(':');
            if (separator <= 0 || separator >= target.length() - 1) {
                return Input.unavailable();
            }
            String scope;
            String id;
            try {
                scope = AutomationContract.normalizeScope(target.substring(0, separator));
                id = AutomationContract.requireSafeId(target.substring(separator + 1));
            } catch (IllegalArgumentException invalid) {
                return Input.unavailable();
            }
            boolean visible;
            if (AutomationContract.SCOPE_OVERLAY.equals(scope)) {
                PopupOverlayConfig config = overlays.find(id);
                if (config == null) return Input.value(false, true, true);
                visible = config.enabled
                        && states.effectiveVisibility(scope, id, config.defaultVisible);
            } else {
                AutomationState state = states.get(scope, id);
                visible = state.visible;
            }
            return Input.value(visible, true, true);
        }
        return Input.unavailable();
    }

    /** Package-visible pure helper for JVM tests; ranges crossing midnight are supported. */
    @Nullable
    static Boolean inTimeRange(@Nullable String encodedRange, long nowMillis) {
        String range = encodedRange == null ? "" : encodedRange.trim();
        int separator = range.indexOf('-');
        if (separator <= 0 || separator >= range.length() - 1) return null;
        Integer start = parseMinutes(range.substring(0, separator));
        Integer end = parseMinutes(range.substring(separator + 1));
        if (start == null || end == null) return null;
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(nowMillis);
        int current = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + calendar.get(java.util.Calendar.MINUTE);
        if (start.equals(end)) return true;
        return start < end
                ? current >= start && current < end
                : current >= start || current < end;
    }

    @Nullable
    private static Integer parseMinutes(String raw) {
        String value = raw == null ? "" : raw.trim().replace(":", "");
        if (value.length() != 4) return null;
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(2, 4));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return hour * 60 + minute;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    @NonNull
    public static String timeRangeResource(@NonNull String start, @NonNull String end) {
        Integer startMinutes = parseMinutes(start);
        Integer endMinutes = parseMinutes(end);
        if (startMinutes == null || endMinutes == null) {
            throw new IllegalArgumentException("Время нужно указать в формате ЧЧ:ММ");
        }
        return TIME_RANGE_PREFIX + String.format(Locale.ROOT, "%02d%02d-%02d%02d",
                startMinutes / 60, startMinutes % 60,
                endMinutes / 60, endMinutes % 60);
    }
}
