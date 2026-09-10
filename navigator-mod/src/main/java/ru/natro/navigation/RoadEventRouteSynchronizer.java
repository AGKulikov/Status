/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Supplies the standalone RoadEventsLayer with positive active-route membership evidence. */
final class RoadEventRouteSynchronizer {
    private static final String TAG = "NatroRouteEvents";

    private Object layer;
    private List<?> drivingEvents = Collections.emptyList();
    private long routeEpoch = Long.MIN_VALUE;
    private long eventsFingerprint = Long.MIN_VALUE;
    private long appliedEpoch = Long.MIN_VALUE;
    private long appliedFingerprint = Long.MIN_VALUE;

    void attach(Object nextLayer) {
        if (layer == nextLayer) return;
        layer = nextLayer;
        appliedEpoch = Long.MIN_VALUE;
        appliedFingerprint = Long.MIN_VALUE;
        apply();
    }

    void detach() {
        if (layer != null) {
            try {
                invoke(layer, "setRoadEventsOnRoute",
                        new Class<?>[]{List.class}, Collections.emptyList());
            } catch (Throwable ignored) {}
        }
        layer = null;
        appliedEpoch = Long.MIN_VALUE;
        appliedFingerprint = Long.MIN_VALUE;
    }

    void update(long nextRouteEpoch, long nextFingerprint, List<?> nextDrivingEvents) {
        if (nextRouteEpoch < routeEpoch) return;
        routeEpoch = nextRouteEpoch;
        eventsFingerprint = nextFingerprint;
        drivingEvents = nextDrivingEvents == null
                ? Collections.emptyList() : new ArrayList<>(nextDrivingEvents);
        apply();
    }

    void clearData() {
        drivingEvents = Collections.emptyList();
        eventsFingerprint = 0L;
        apply();
    }

    private void apply() {
        Object currentLayer = layer;
        if (currentLayer == null
                || appliedEpoch == routeEpoch && appliedFingerprint == eventsFingerprint) {
            return;
        }
        try {
            List<?> converted = convert(drivingEvents);
            invoke(currentLayer, "setRoadEventsOnRoute",
                    new Class<?>[]{List.class}, converted);
            appliedEpoch = routeEpoch;
            appliedFingerprint = eventsFingerprint;
        } catch (Throwable failure) {
            // A failed conversion must never leave membership inherited from the previous route.
            try {
                invoke(currentLayer, "setRoadEventsOnRoute",
                        new Class<?>[]{List.class}, Collections.emptyList());
            } catch (Throwable ignored) {}
            appliedEpoch = routeEpoch;
            appliedFingerprint = eventsFingerprint;
            Log.w(TAG, "Active-route road-event membership could not be synchronized", failure);
        }
    }

    private static List<?> convert(List<?> source) throws Exception {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        Class<?> layerEventClass = Class.forName(
                "com.yandex.mapkit.road_events_layer.RoadEvent");
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Constructor<?> constructor = layerEventClass.getConstructor(
                String.class, pointClass, List.class, String.class, boolean.class);
        ArrayList<Object> result = new ArrayList<>(source.size());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object event : source) {
            if (event == null) continue;
            try {
                String id = text(invoke(event, "getEventId", new Class<?>[0]));
                Object position = invoke(event, "getLocation", new Class<?>[0]);
                Object tags = invoke(event, "getTags", new Class<?>[0]);
                if (id.isEmpty() || position == null || !(tags instanceof List<?>)
                        || !ids.add(id)) {
                    continue;
                }
                String caption = text(invoke(event, "getDescriptionText", new Class<?>[0]));
                result.add(constructor.newInstance(id, position, tags, caption, false));
            } catch (Throwable invalidEvent) {
                // One transient route event must not invalidate positive evidence for the rest.
            }
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }
}
