/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.PointF;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Exact route events rendered with Navigator's own stock images through its real 30.3 API. */
final class RouteRoadEventMapLayer implements RoadEventRouteSynchronizer.Sink {
    private Object map, collection, stockProvider;
    private final String displayName;
    private List<RoadEventRouteSynchronizer.Event> events = Collections.emptyList();
    private RoadEventVisibility visibility;
    private boolean night, unifiedCameras;
    private int scalePercent = 100, cameraScalePercent = 100;
    private float zIndex;
    private final List<Object> images = new ArrayList<>();

    RouteRoadEventMapLayer(String displayName) { this.displayName = displayName; }

    void attach(Object map, Object provider) throws Exception {
        this.map = map;
        stockProvider = provider;
        render(events);
    }

    void detach() { clear(); collection = null; map = null; stockProvider = null; }

    void configure(Map<String, String> modes, boolean active, boolean night,
                   boolean unifiedCameras, int scale, int cameraScale, int priority) {
        float nextZ = NavigationMapProfile.layerZ(priority);
        if (visibility != null && visibility.matches(modes, active) && this.night == night
                && this.unifiedCameras == unifiedCameras && scalePercent == scale
                && cameraScalePercent == cameraScale && zIndex == nextZ) return;
        visibility = new RoadEventVisibility(modes, active);
        this.night = night; this.unifiedCameras = unifiedCameras;
        scalePercent = scale; cameraScalePercent = cameraScale; zIndex = nextZ;
        try { render(events); }
        catch (Exception failure) { report("configuration_failed=" + failure.getClass().getSimpleName()); }
    }

    @Override public void render(List<RoadEventRouteSynchronizer.Event> next) throws Exception {
        events = next;
        if (map == null || stockProvider == null || visibility == null) return;
        if (collection == null) collection = MapObjectLayerFactory.create(map,
                "natro-route-road-events", MapObjectLayerFactory.EQUAL, zIndex);
        clear();
        MapObjectLayerFactory.setZIndex(collection, zIndex);
        int hidden = 0, replaced = 0, submitted = 0, unavailable = 0;
        String firstFailure = "none";
        Map<String, Integer> submittedTags = new java.util.TreeMap<>();
        for (RoadEventRouteSynchronizer.Event event : next) {
            ArrayList<Object> allowed = new ArrayList<>();
            for (Object tag : event.tags) {
                if (visibility.allows(Collections.singletonList(tag), true)) allowed.add(tag);
            }
            if (allowed.isEmpty()) { hidden++; continue; }
            ArrayList<Object> tags = new ArrayList<>();
            for (Object tag : allowed) {
                if (!unifiedCameras || !RouteCameraPolicy.isControl(String.valueOf(tag))) tags.add(tag);
            }
            if (tags.isEmpty()) { replaced++; continue; }
            try {
                Style style = stockStyle(tags);
                if (style.image == null) { unavailable++; continue; }
                Object placemark = invoke(collection, "addPlacemark", new Class<?>[0]);
                invoke(placemark, "setGeometry", new Class<?>[]{Class.forName(
                        "com.yandex.mapkit.geometry.Point")}, event.point);
                Class<?> iconClass = Class.forName("com.yandex.mapkit.map.IconStyle");
                Object icon = iconClass.getConstructor().newInstance();
                if (style.anchor != null) invoke(icon, "setAnchor", new Class<?>[]{PointF.class}, style.anchor);
                invoke(icon, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(zIndex));
                int scale = scalePercent;
                for (Object tag : tags) if (RouteCameraPolicy.isControl(String.valueOf(tag))) {
                    scale = cameraScalePercent; break;
                }
                invoke(icon, "setScale", new Class<?>[]{Float.class}, Float.valueOf(scale / 100f));
                invoke(placemark, "setIcon", new Class<?>[]{Class.forName(
                        "com.yandex.runtime.image.ImageProvider"), iconClass}, style.image, icon);
                images.add(style.image);
                if (!style.zoomFunction.isEmpty()) {
                    invoke(placemark, "setScaleFunction", new Class<?>[]{List.class}, style.zoomFunction);
                }
                submitted++;
                for (Object tag : tags) {
                    String name = String.valueOf(tag);
                    Integer count = submittedTags.get(name);
                    submittedTags.put(name, count == null ? 1 : count + 1);
                }
            } catch (Exception eventFailure) {
                unavailable++;
                if ("none".equals(firstFailure)) {
                    Throwable cause = eventFailure;
                    for (int i = 0; i < 4 && cause.getCause() != null; i++) cause = cause.getCause();
                    firstFailure = cause.getClass().getSimpleName();
                }
            }
        }
        report("source=" + next.size() + ", submitted=" + submitted + ", hidden=" + hidden
                + ", camera_replacements=" + replaced + ", unavailable=" + unavailable
                + ", first_failure=" + firstFailure + ", submitted_tags=" + submittedTags);
    }

    private Style stockStyle(List<?> tags) throws Exception {
        Class<?> propertiesClass = Class.forName("com.yandex.mapkit.road_events_layer.RoadEventStylingProperties");
        Object properties = Proxy.newProxyInstance(propertiesClass.getClassLoader(),
                new Class<?>[]{propertiesClass}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTags": return tags;
                        case "isOnRoute": case "isValid": case "hasSignificanceGreaterOrEqual": return true;
                        case "isSelected": case "isUserEvent": case "isInFuture": return false;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "NatroDrivingRouteEvent";
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        Class<?> styleClass = Class.forName("com.yandex.mapkit.road_events_layer.RoadEventStyle");
        Style captured = new Style();
        Object style = Proxy.newProxyInstance(styleClass.getClassLoader(), new Class<?>[]{styleClass},
                (proxy, method, args) -> captured.call(proxy, method, args));
        Object accepted = invoke(stockProvider, "provideStyle",
                new Class<?>[]{propertiesClass, boolean.class, float.class, styleClass},
                properties, night, 1f, style);
        if (!Boolean.TRUE.equals(accepted)) captured.image = null;
        return captured;
    }

    private void clear() {
        if (collection != null) {
            try { invoke(collection, "clear", new Class<?>[0]); } catch (Exception ignored) {}
        }
        images.clear();
    }
    private void report(String text) {
        NavigationBridgeClient.reportDiagnostic("route-events display=" + displayName + ", " + text);
    }

    private static final class Style {
        Object image, caption;
        PointF anchor;
        int zoomMin;
        List<?> zoomFunction = Collections.emptyList();
        Object call(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setIconImage": image = args[0]; return null;
                case "setIconAnchor": anchor = (PointF) args[0]; return null;
                case "getIconAnchor": return anchor;
                case "setZoomScaleFunction": zoomFunction = (List<?>) args[0]; return null;
                case "getZoomScaleFunction": return zoomFunction;
                case "setCaptionStyle": caption = args[0]; return null;
                case "getCaptionStyle": return caption;
                case "setZoomMin": zoomMin = ((Number) args[0]).intValue(); return null;
                case "getZoomMin": return zoomMin;
                case "isValid": return true;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "NatroCapturedStockEventStyle";
                default: throw new UnsupportedOperationException(method.getName());
            }
        }
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return ReflectMethods.publicMethod(target.getClass(), name, types).invoke(target, args);
    }
}
