/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Places Natro map objects into the same feature-aware slots used by Navigator 30.3.0.
 *
 * <p>A root MapObjectLayer may expose several MapKit sublayers. Looking it up only by id can
 * therefore select the wrong feature type (a camera polygon instead of its placemark, for
 * example). Every lookup here includes {@code SublayerFeatureType}, and only Natro-owned
 * sublayers are moved. Yandex substrate, traffic, labels and navigation layers remain in their
 * stock relative order.</p>
 */
final class MapSublayerOrder {
    static final String CAMERA_SECTORS = "ru.natro.navigation.camera_sectors";
    static final String CAMERA_SIGNS = "ru.natro.navigation.camera_signs";
    static final String ROUTE = "ru.natro.navigation.route";
    static final String DESTINATION = "ru.natro.navigation.destination";
    static final String SPEED_BUMPS = "ru.natro.navigation.speed_bumps";
    static final String ROUTE_TRAFFIC_LIGHTS = "ru.natro.navigation.route_traffic_lights";
    static final String TRAFFIC_LIGHTS = "ru.natro.navigation.traffic_lights";
    static final String LANE_GUIDANCE = "ru.natro.navigation.lane_guidance";
    static final String CURSOR = "ru.natro.navigation.cursor";

    private static final String STOCK_ROUTE_POLYLINE = "navi_route_polyline_layer";
    private static final String STOCK_ROUTE_PINS = "navi_route_pins_layer_name";
    private static final String STOCK_MANUAL_LOCATION =
            "manual_guidance_my_location_placemark";
    private static final String STOCK_GUIDANCE_BALLOONS = "navi_guidance_balloons";
    private static final String STOCK_ARRIVAL_DESTINATION =
            "arrival_points_destination_points_layer";

    private static final String GROUND = "GROUND";
    private static final String PLACEMARKS = "PLACEMARKS_AND_LABELS";

    private MapSublayerOrder() {}

    static void apply(Object map, NavigationMapProfile profile) throws Exception {
        Object manager = invoke(map, "getSublayerManager", new Class<?>[0]);
        Class<?> ids = Class.forName("com.yandex.mapkit.map.LayerIds");
        String mapBase = layerId(ids, "getMapLayerId");
        String traffic = layerId(ids, "getJamsLayerId");
        String routeObjects = layerId(ids, "getRouteMapObjectsLayerId");
        String navigationBase = layerId(ids, "getDrivingNavigationBaseLayerId");
        String routePins = layerId(ids, "getDrivingNavigationRoutePinsLayerId");
        String userLocation = layerId(ids, "getUserLocationLayerId");
        String roadEvents = layerId(ids, "getRoadEventsLayerId");

        if (profile.manualLayerPrioritiesEnabled) {
            applyManualGround(manager, mapBase, traffic, routeObjects, navigationBase, profile);
            applyManualPlacemarks(manager, mapBase, traffic, roadEvents, profile);
        } else {
            applyAutomaticGround(manager, mapBase, traffic, routeObjects, navigationBase);
            applyAutomaticPlacemarks(
                    manager, mapBase, traffic, roadEvents, routePins, userLocation);
        }
    }

    /** Camera sectors remain under the route by default; arrows share the route sublayer. */
    private static void applyAutomaticGround(Object manager, String mapBase, String traffic,
                                             String routeObjects, String navigationBase)
            throws Exception {
        ArrayList<LayerRef> layers = new ArrayList<>();
        layers.add(new LayerRef(CAMERA_SECTORS, GROUND, 0, 0));
        layers.add(new LayerRef(ROUTE, GROUND, 0, 1));
        placeSequence(manager, layers,
                firstExisting(manager,
                        ref(STOCK_ROUTE_POLYLINE, GROUND),
                        ref(routeObjects, GROUND),
                        ref(traffic, GROUND),
                        ref(mapBase, GROUND)),
                firstExisting(manager,
                        ref(navigationBase, GROUND),
                        ref(mapBase, PLACEMARKS)));
    }

    private static void applyManualGround(Object manager, String mapBase, String traffic,
                                          String routeObjects, String navigationBase,
                                          NavigationMapProfile profile) throws Exception {
        ArrayList<LayerRef> layers = new ArrayList<>();
        layers.add(new LayerRef(CAMERA_SECTORS, GROUND,
                profile.effectiveCameraPriority(), 0));
        layers.add(new LayerRef(ROUTE, GROUND,
                profile.effectiveRoutePriority(), 1));
        sort(layers);
        placeSequence(manager, layers,
                firstExisting(manager,
                        ref(STOCK_ROUTE_POLYLINE, GROUND),
                        ref(routeObjects, GROUND),
                        ref(traffic, GROUND),
                        ref(mapBase, GROUND)),
                firstExisting(manager,
                        ref(navigationBase, GROUND),
                        ref(mapBase, PLACEMARKS)));
    }

    /**
     * Mirrors the relevant stock order: events, route pins, user cursor, guidance, destination.
     * Missing stock layers are normal on an independent MapWindow and are skipped.
     */
    private static void applyAutomaticPlacemarks(Object manager, String mapBase, String traffic,
                                                  String roadEvents, String routePins,
                                                  String userLocation) throws Exception {
        LayerRef base = firstExisting(manager,
                ref(traffic, PLACEMARKS), ref(mapBase, PLACEMARKS));
        LayerRef events = firstExisting(manager, ref(roadEvents, PLACEMARKS), base);
        placeAfter(manager, ref(CAMERA_SIGNS, PLACEMARKS), events);

        LayerRef routePinsAnchor = firstExisting(manager,
                ref(routePins, PLACEMARKS),
                ref(STOCK_ROUTE_PINS, PLACEMARKS),
                ref(CAMERA_SIGNS, PLACEMARKS),
                events);
        ArrayList<LayerRef> routePinsFeatures = new ArrayList<>();
        routePinsFeatures.add(ref(SPEED_BUMPS, PLACEMARKS));
        routePinsFeatures.add(ref(ROUTE_TRAFFIC_LIGHTS, PLACEMARKS));
        LayerRef lastRoutePin = placeSequence(manager, routePinsFeatures,
                routePinsAnchor, null);

        LayerRef cursorAnchor = firstExisting(manager,
                lastRoutePin, ref(ROUTE_TRAFFIC_LIGHTS, PLACEMARKS),
                ref(SPEED_BUMPS, PLACEMARKS), routePinsAnchor);
        placeAfter(manager, ref(CURSOR, PLACEMARKS), cursorAnchor);

        LayerRef guidanceAnchor = firstExisting(manager,
                ref(STOCK_GUIDANCE_BALLOONS, PLACEMARKS),
                ref(STOCK_MANUAL_LOCATION, PLACEMARKS),
                ref(userLocation, PLACEMARKS),
                ref(CURSOR, PLACEMARKS),
                cursorAnchor);
        ArrayList<LayerRef> guidance = new ArrayList<>();
        guidance.add(ref(TRAFFIC_LIGHTS, PLACEMARKS));
        guidance.add(ref(LANE_GUIDANCE, PLACEMARKS));
        LayerRef lastGuidance = placeSequence(manager, guidance, guidanceAnchor, null);

        LayerRef destinationAnchor = firstExisting(manager,
                ref(STOCK_ARRIVAL_DESTINATION, PLACEMARKS),
                lastGuidance,
                ref(STOCK_GUIDANCE_BALLOONS, PLACEMARKS),
                guidanceAnchor);
        placeAfter(manager, ref(DESTINATION, PLACEMARKS), destinationAnchor);
    }

    /** Manual values are compared only inside their compatible MapKit feature group. */
    private static void applyManualPlacemarks(Object manager, String mapBase, String traffic,
                                               String roadEvents,
                                               NavigationMapProfile profile) throws Exception {
        ArrayList<LayerRef> custom = new ArrayList<>();
        custom.add(new LayerRef(CAMERA_SIGNS, PLACEMARKS,
                profile.effectiveCameraPriority(), 0));
        custom.add(new LayerRef(SPEED_BUMPS, PLACEMARKS,
                profile.effectiveSpeedBumpPriority(), 1));
        custom.add(new LayerRef(ROUTE_TRAFFIC_LIGHTS, PLACEMARKS,
                profile.effectiveRouteTrafficLightPriority(), 2));
        custom.add(new LayerRef(CURSOR, PLACEMARKS,
                profile.effectiveCursorPriority(), 3));
        custom.add(new LayerRef(TRAFFIC_LIGHTS, PLACEMARKS,
                profile.effectiveTrafficLightPriority(), 4));
        custom.add(new LayerRef(LANE_GUIDANCE, PLACEMARKS,
                profile.effectiveLanePriority(), 5));
        custom.add(new LayerRef(DESTINATION, PLACEMARKS,
                profile.effectiveDestinationPriority(), 6));
        sort(custom);

        LayerRef base = firstExisting(manager,
                ref(traffic, PLACEMARKS), ref(mapBase, PLACEMARKS));
        LayerRef eventLayer = existing(manager, ref(roadEvents, PLACEMARKS));
        if (eventLayer == null) {
            placeSequence(manager, custom, base, null);
            return;
        }

        ArrayList<LayerRef> belowEvents = new ArrayList<>();
        ArrayList<LayerRef> aboveEvents = new ArrayList<>();
        int eventPriority = profile.effectiveRoadEventPriority();
        for (LayerRef layer : custom) {
            // Equal values keep the stable stock event layer below Natro overlays.
            (layer.priority < eventPriority ? belowEvents : aboveEvents).add(layer);
        }
        placeSequence(manager, belowEvents, base, eventLayer);
        placeSequence(manager, aboveEvents, eventLayer, null);
    }

    /** Places all present targets consecutively after lower, or before upper when lower is absent. */
    private static LayerRef placeSequence(Object manager, List<LayerRef> requested,
                                          LayerRef lower, LayerRef upper) throws Exception {
        ArrayList<LayerRef> present = new ArrayList<>();
        for (LayerRef layer : requested) {
            LayerRef value = existing(manager, layer);
            if (value != null) present.add(value);
        }
        if (present.isEmpty()) return lower;
        if (lower != null) {
            LayerRef anchor = lower;
            for (LayerRef layer : present) {
                placeAfter(manager, layer, anchor);
                anchor = layer;
            }
            return anchor;
        }
        if (upper != null) {
            LayerRef anchor = upper;
            for (int index = present.size() - 1; index >= 0; index--) {
                LayerRef layer = present.get(index);
                placeBefore(manager, layer, anchor);
                anchor = layer;
            }
            return present.get(present.size() - 1);
        }
        return present.get(present.size() - 1);
    }

    private static void placeAfter(Object manager, LayerRef target, LayerRef anchor)
            throws Exception {
        if (target == null || anchor == null || target.same(anchor)) return;
        Integer targetIndex = index(manager, target);
        Integer anchorIndex = index(manager, anchor);
        if (targetIndex == null || anchorIndex == null
                || targetIndex.intValue() == anchorIndex.intValue() + 1) return;
        invoke(manager, "moveAfter", new Class<?>[]{int.class, int.class},
                targetIndex.intValue(), anchorIndex.intValue());
    }

    private static void placeBefore(Object manager, LayerRef target, LayerRef anchor)
            throws Exception {
        if (target == null || anchor == null || target.same(anchor)) return;
        Integer targetIndex = index(manager, target);
        Integer anchorIndex = index(manager, anchor);
        if (targetIndex == null || anchorIndex == null
                || targetIndex.intValue() + 1 == anchorIndex.intValue()) return;
        invoke(manager, "moveBefore", new Class<?>[]{int.class, int.class},
                targetIndex.intValue(), anchorIndex.intValue());
    }

    private static LayerRef firstExisting(Object manager, LayerRef... candidates)
            throws Exception {
        for (LayerRef candidate : candidates) {
            LayerRef value = existing(manager, candidate);
            if (value != null) return value;
        }
        return null;
    }

    private static LayerRef existing(Object manager, LayerRef layer) throws Exception {
        return layer != null && index(manager, layer) != null ? layer : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Integer index(Object manager, LayerRef layer) throws Exception {
        if (layer == null || layer.id == null || layer.id.isEmpty()) return null;
        Class<?> featureClass = Class.forName("com.yandex.mapkit.map.SublayerFeatureType");
        Object feature = Enum.valueOf((Class<? extends Enum>) featureClass, layer.feature);
        Object value = invoke(manager, "findFirstOf",
                new Class<?>[]{String.class, featureClass}, layer.id, feature);
        if (!(value instanceof Integer) || ((Integer) value).intValue() < 0) return null;
        return (Integer) value;
    }

    private static String layerId(Class<?> ids, String getter) {
        try {
            Object value = ids.getMethod(getter).invoke(null);
            return value instanceof String ? (String) value : "";
        } catch (Throwable unavailable) {
            return "";
        }
    }

    private static LayerRef ref(String id, String feature) {
        return new LayerRef(id, feature, 0, 0);
    }

    private static void sort(List<LayerRef> values) {
        Collections.sort(values, new Comparator<LayerRef>() {
            @Override public int compare(LayerRef left, LayerRef right) {
                int priority = Integer.compare(left.priority, right.priority);
                return priority != 0 ? priority : Integer.compare(left.rank, right.rank);
            }
        });
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class LayerRef {
        final String id;
        final String feature;
        final int priority;
        final int rank;

        LayerRef(String id, String feature, int priority, int rank) {
            this.id = id;
            this.feature = feature;
            this.priority = priority;
            this.rank = rank;
        }

        boolean same(LayerRef other) {
            return other != null && id != null && id.equals(other.id)
                    && feature.equals(other.feature);
        }
    }
}
