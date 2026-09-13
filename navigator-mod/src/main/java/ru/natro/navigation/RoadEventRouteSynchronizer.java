/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Retains the complete DrivingRoute inventory; the sink uses the verified MapObject API. */
final class RoadEventRouteSynchronizer {
    interface Sink { void render(List<Event> events) throws Exception; }
    static final class Event {
        final String id;
        final Object point;
        final List<?> tags;
        final String caption;
        Event(String id, Object point, List<?> tags, String caption) {
            this.id = id; this.point = point; this.tags = tags; this.caption = caption;
        }
    }
    private Sink layer;
    private List<?> drivingEvents = Collections.emptyList();
    private long routeEpoch = Long.MIN_VALUE;
    private long eventsFingerprint = Long.MIN_VALUE;
    private long appliedEpoch = Long.MIN_VALUE;
    private long appliedFingerprint = Long.MIN_VALUE;

    void attach(Sink nextLayer) {
        if (layer == nextLayer) return;
        layer = nextLayer;
        appliedEpoch = Long.MIN_VALUE;
        appliedFingerprint = Long.MIN_VALUE;
        apply();
    }

    void detach() {
        if (layer != null) {
            try { layer.render(Collections.emptyList()); } catch (Exception ignored) {}
        }
        layer = null;
        appliedEpoch = Long.MIN_VALUE;
        appliedFingerprint = Long.MIN_VALUE;
    }

    void update(long nextRouteEpoch, long nextFingerprint, List<?> nextDrivingEvents) {
        if (nextRouteEpoch < routeEpoch) return;
        if (nextRouteEpoch == routeEpoch && nextFingerprint == eventsFingerprint) {
            apply(); // Retry a failed sink; failed work is never marked as applied.
            return;
        }
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
        if (layer == null || appliedEpoch == routeEpoch && appliedFingerprint == eventsFingerprint) return;
        ArrayList<Event> converted = new ArrayList<>(drivingEvents.size());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Map<String, Integer> tagCounts = new TreeMap<>();
        int invalid = 0, duplicates = 0, captionsUnavailable = 0;
        for (Object source : drivingEvents) {
            try {
                String id = text(invoke(source, "getEventId"));
                Object point = invoke(source, "getLocation");
                Object tags = invoke(source, "getTags");
                if (id.isEmpty() || point == null || !(tags instanceof List<?>)) {
                    invalid++; continue;
                }
                if (ids.contains(id)) { duplicates++; continue; }
                String caption = "";
                try { caption = text(invoke(source, "getDescriptionText")); }
                catch (Exception optionalCaption) { captionsUnavailable++; }
                List<?> snapshot = Collections.unmodifiableList(new ArrayList<>((List<?>) tags));
                converted.add(new Event(id, point, snapshot, caption));
                ids.add(id);
                for (Object tag : snapshot) {
                    if (tag == null) continue;
                    String name = String.valueOf(tag);
                    Integer count = tagCounts.get(name);
                    tagCounts.put(name, count == null ? 1 : count + 1);
                }
            } catch (Exception invalidEvent) { invalid++; }
        }
        try {
            layer.render(Collections.unmodifiableList(converted));
            appliedEpoch = routeEpoch;
            appliedFingerprint = eventsFingerprint;
            NavigationBridgeClient.reportDiagnostic("route-events inventory epoch=" + routeEpoch
                    + ", source=" + drivingEvents.size() + ", unique=" + converted.size()
                    + ", invalid=" + invalid + ", duplicates=" + duplicates
                    + ", captions_unavailable=" + captionsUnavailable + ", tags=" + tagCounts);
        } catch (Exception failure) {
            NavigationBridgeClient.reportDiagnostic("route-events sink failed epoch=" + routeEpoch
                    + ", reason=" + failure.getClass().getSimpleName());
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static Object invoke(Object target, String name) throws Exception {
        if (target == null) throw new IllegalArgumentException("null event");
        return ReflectMethods.publicMethod(target.getClass(), name, new Class<?>[0]).invoke(target);
    }
}
