/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** All Guidance alternatives: passive polylines plus collision-safe fork callouts. */
final class AlternativeRouteMapLayer {
    private static final String TAG = "NatroAlternatives";
    private static final String OWNER = "alternative_routes";
    private static final int MAX_ROUTE_NAME_CHARS = 48;

    private final Context context;
    private final MapOverlayPlacementCoordinator placement;
    private final ArrayList<Marker> markers = new ArrayList<>();
    private Object map;
    private Object polylineCollection;
    private Object calloutCollection;
    private NavigationMapProfile profile = new NavigationMapProfile();
    private Object activeRoute;
    private List<?> alternatives = Collections.emptyList();
    private long routeEpoch = Long.MIN_VALUE;
    private long dataFingerprint = Long.MIN_VALUE;
    private long appearanceFingerprint = Long.MIN_VALUE;

    AlternativeRouteMapLayer(Context context, MapOverlayPlacementCoordinator placement) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.placement = placement;
    }

    void attach(Object nextMap) {
        if (map == nextMap) return;
        detachMap();
        map = nextMap;
        render();
    }

    void detachMap() {
        placement.clearOwner(OWNER);
        markers.clear();
        polylineCollection = null;
        calloutCollection = null;
        map = null;
    }

    void apply(NavigationMapProfile nextProfile) {
        if (nextProfile == null) return;
        long nextAppearance = appearanceFingerprint(nextProfile);
        profile = nextProfile;
        MapObjectLayerFactory.setZIndex(polylineCollection,
                NavigationMapProfile.layerZ(profile.effectiveAlternativeRoutePriority()));
        MapObjectLayerFactory.setZIndex(calloutCollection,
                NavigationMapProfile.layerZ(profile.effectiveAlternativeCalloutPriority()));
        if (nextAppearance != appearanceFingerprint) {
            appearanceFingerprint = nextAppearance;
            render();
        } else if (!profile.showAlternativeRoutes) {
            clearVisual();
        }
    }

    void update(long nextRouteEpoch, Object nextActiveRoute, List<?> nextAlternatives) {
        if (nextRouteEpoch < routeEpoch) return;
        List<?> safe = nextAlternatives == null
                ? Collections.emptyList() : new ArrayList<>(nextAlternatives);
        long nextFingerprint = dataFingerprint(nextRouteEpoch, nextActiveRoute, safe);
        routeEpoch = nextRouteEpoch;
        activeRoute = nextActiveRoute;
        alternatives = safe;
        if (nextFingerprint == dataFingerprint) return;
        dataFingerprint = nextFingerprint;
        render();
    }

    void clearData() {
        activeRoute = null;
        alternatives = Collections.emptyList();
        dataFingerprint = Long.MIN_VALUE;
        render();
    }

    /** Called last: required route guidance and safety signs retain every contested slot. */
    void relayout() {
        placement.clearOwner(OWNER);
        if (!profile.showAlternativeRoutes || activeRoute == null || markers.isEmpty()) return;
        for (Marker marker : markers) {
            try {
                PreparedText text = prepare(marker.model);
                List<MapOverlayPlacementCoordinator.Footprint> footprints = footprints(text);
                MapOverlayPlacementCoordinator.Placement next = placement.reserveIfClear(
                        OWNER, marker.model.key,
                        marker.model.latitude, marker.model.longitude,
                        text.bodyWidth, text.bodyHeight, true,
                        marker.model.routeSegmentIndex, marker.model.routeSegmentPosition,
                        marker.placement, footprints);
                if (next == null) {
                    invoke(marker.placemark, "setVisible",
                            new Class<?>[]{boolean.class}, false);
                    marker.placement = null;
                    continue;
                }
                if (marker.placement == null || !marker.placement.sameSlot(next)
                        || marker.paletteVersion != StockAlternativePalette.version()) {
                    applyTexture(marker, text, next);
                }
                invoke(marker.placemark, "setVisible",
                        new Class<?>[]{boolean.class}, true);
                marker.placement = next;
            } catch (Throwable failure) {
                hide(marker);
                Log.w(TAG, "Alternative callout could not be placed", failure);
            }
        }
    }

    private void render() {
        if (map == null) return;
        clearVisual();
        if (!profile.showAlternativeRoutes || activeRoute == null || alternatives.isEmpty()) return;
        try {
            ensureCollections();
            Class<?> polylineClass = Class.forName("com.yandex.mapkit.geometry.Polyline");
            Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
            int index = 0;
            for (Object alternative : alternatives) {
                try {
                    Object route = invoke(alternative, "getAlternative", new Class<?>[0]);
                    if (route == null) continue;
                    Object geometry = invoke(route, "getGeometry", new Class<?>[0]);
                    if (geometry == null) continue;
                    Object line = invoke(polylineCollection, "addPolyline",
                            new Class<?>[]{polylineClass}, geometry);
                    invoke(line, "setStrokeWidth", new Class<?>[]{float.class},
                            (float) profile.alternativeRouteWidth);
                    invoke(line, "setStrokeColor", new Class<?>[]{int.class},
                            Color.parseColor(profile.alternativeRouteColor));
                    invoke(line, "setOutlineWidth", new Class<?>[]{float.class}, 0f);
                    invoke(line, "setVisible", new Class<?>[]{boolean.class}, true);

                    CalloutModel model = readCallout(alternative, route, index++);
                    if (model == null) continue;
                    Object point = pointClass.getConstructor(double.class, double.class)
                            .newInstance(model.latitude, model.longitude);
                    Object placemark = invoke(calloutCollection, "addPlacemark",
                            new Class<?>[]{pointClass}, point);
                    invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, false);
                    markers.add(new Marker(placemark, model));
                } catch (Throwable invalidAlternative) {
                    Log.w(TAG, "One Guidance alternative was skipped", invalidAlternative);
                }
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Alternative route layer could not be rendered", failure);
            clearVisual();
        }
    }

    private void ensureCollections() throws Exception {
        if (polylineCollection == null) {
            polylineCollection = MapObjectLayerFactory.create(map,
                    MapSublayerOrder.ALTERNATIVE_ROUTES,
                    // The contextual line must survive even when its optional callout loses a
                    // collision. Only the callout participates as a MINOR map object below.
                    MapObjectLayerFactory.IGNORE,
                    NavigationMapProfile.layerZ(
                            profile.effectiveAlternativeRoutePriority()));
        }
        if (calloutCollection == null) {
            calloutCollection = MapObjectLayerFactory.create(map,
                    MapSublayerOrder.ALTERNATIVE_CALLOUTS,
                    MapObjectLayerFactory.MINOR,
                    NavigationMapProfile.layerZ(
                            profile.effectiveAlternativeCalloutPriority()));
        }
    }

    private void clearVisual() {
        placement.clearOwner(OWNER);
        clear(polylineCollection);
        clear(calloutCollection);
        markers.clear();
    }

    private static void clear(Object collection) {
        if (collection == null) return;
        try {
            invoke(collection, "clear", new Class<?>[0]);
        } catch (Throwable ignored) {}
    }

    private CalloutModel readCallout(Object alternative, Object route, int index)
            throws Exception {
        Object forkOnAlternative = invoke(alternative,
                "getForkPositionOnAlternative", new Class<?>[0]);
        Object forkOnCurrent = invoke(alternative,
                "getForkPositionOnCurrentRoute", new Class<?>[0]);
        if (forkOnAlternative == null || forkOnCurrent == null) return null;
        Object point = invoke(forkOnCurrent, "getPoint", new Class<?>[0]);
        if (point == null) return null;
        double latitude = number(invoke(point, "getLatitude", new Class<?>[0]));
        double longitude = number(invoke(point, "getLongitude", new Class<?>[0]));
        if (!validCoordinate(latitude, longitude)) return null;

        Double alternativeTime = finiteNumber(forkOnAlternative, "timeToFinish");
        Double currentTime = finiteNumber(forkOnCurrent, "timeToFinish");
        Double alternativeDistance = finiteNumber(forkOnAlternative, "distanceToFinish");
        Double currentDistance = finiteNumber(forkOnCurrent, "distanceToFinish");
        Integer timeDelta = alternativeTime == null || currentTime == null ? null
                : (int) Math.round(alternativeTime - currentTime);
        Integer distanceDelta = alternativeDistance == null || currentDistance == null ? null
                : (int) Math.round(alternativeDistance - currentDistance);
        if (timeDelta == null && distanceDelta == null) return null;

        RouteProgress progress = routeProgress(forkOnCurrent, activeRoute);
        String routeId = routeId(route);
        String key = routeId.isEmpty() ? "alternative:" + routeEpoch + ':' + index : routeId;
        String name = sectionName(route, forkOnAlternative);
        return new CalloutModel(key, latitude, longitude, name,
                timeDelta, distanceDelta, progress.segmentIndex, progress.segmentPosition);
    }

    private void applyTexture(Marker marker, PreparedText text,
                              MapOverlayPlacementCoordinator.Placement placementValue)
            throws Exception {
        Texture texture = texture(text, placementValue.legName);
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
        Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                .invoke(null, texture.bitmap);
        Object style = styleClass.getConstructor().newInstance();
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object noRotation = Enum.valueOf((Class<? extends Enum>) rotationClass, "NO_ROTATION");
        invoke(style, "setAnchor", new Class<?>[]{PointF.class}, texture.anchor);
        invoke(style, "setRotationType", new Class<?>[]{rotationClass}, noRotation);
        invoke(style, "setScale", new Class<?>[]{Float.class}, Float.valueOf(1f));
        invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
        invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
        invoke(style, "setZIndex", new Class<?>[]{Float.class}, Float.valueOf(
                NavigationMapProfile.layerZ(profile.effectiveAlternativeCalloutPriority())));
        invoke(marker.placemark, "setIcon",
                new Class<?>[]{providerClass, styleClass}, provider, style);
        marker.paletteVersion = StockAlternativePalette.version();
    }

    private PreparedText prepare(CalloutModel model) {
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float scaledDensity = Math.max(density,
                context.getResources().getDisplayMetrics().scaledDensity);
        float scale = profile.alternativeCalloutScalePercent / 100f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize((float) profile.alternativeCalloutTextSizeSp * scaledDensity * scale);
        ArrayList<TextPiece> pieces = new ArrayList<>();
        String time = model.timeDeltaSeconds == null ? ""
                : formatTimeDelta(model.timeDeltaSeconds);
        String distance = model.distanceDeltaMeters == null ? ""
                : formatDistanceDelta(model.distanceDeltaMeters);
        int base = Color.parseColor(profile.alternativeCalloutTextColor);
        if (!model.name.isEmpty()) pieces.add(new TextPiece(model.name, base));
        appendDelta(pieces, time, model.timeDeltaSeconds, base);
        appendDelta(pieces, distance, model.distanceDeltaMeters, base);

        float horizontalPadding = (float) profile.alternativeCalloutHorizontalPaddingDp
                * density * scale;
        float verticalPadding = (float) profile.alternativeCalloutVerticalPaddingDp
                * density * scale;
        float maximumBodyWidth = 320f * density * scale;
        if (!model.name.isEmpty() && measuredWidth(paint, pieces) + 2f * horizontalPadding
                > maximumBodyWidth) {
            float reserved = 0f;
            for (int index = 1; index < pieces.size(); index++) {
                reserved += paint.measureText(pieces.get(index).text);
            }
            float allowed = Math.max(0f, maximumBodyWidth - 2f * horizontalPadding - reserved);
            pieces.get(0).text = ellipsize(paint, model.name, allowed);
        }
        Paint.FontMetrics metrics = paint.getFontMetrics();
        int bodyWidth = Math.max(1, (int) Math.ceil(
                measuredWidth(paint, pieces) + 2f * horizontalPadding));
        int bodyHeight = Math.max(1, (int) Math.ceil(
                metrics.descent - metrics.ascent + 2f * verticalPadding));
        int leader = Math.max(1, Math.round((float) profile.alternativeCalloutLeaderLengthDp
                * density * scale));
        return new PreparedText(paint, pieces, horizontalPadding, verticalPadding,
                bodyWidth, bodyHeight, leader);
    }

    private static void appendDelta(List<TextPiece> pieces, String value,
                                    Integer delta, int baseColor) {
        if (value.isEmpty()) return;
        if (!pieces.isEmpty()) pieces.add(new TextPiece("  ", baseColor));
        int color = delta == null || delta == 0 ? baseColor
                : delta < 0 ? StockAlternativePalette.negativeColor()
                : StockAlternativePalette.positiveColor();
        pieces.add(new TextPiece(value, color));
    }

    private List<MapOverlayPlacementCoordinator.Footprint> footprints(PreparedText text) {
        ArrayList<MapOverlayPlacementCoordinator.Footprint> result = new ArrayList<>(8);
        for (String leg : MapOverlayPlacementCoordinator.placementLegNames()) {
            Geometry geometry = geometry(text, leg);
            result.add(new MapOverlayPlacementCoordinator.Footprint(
                    leg, geometry.width, geometry.height,
                    geometry.tipX / geometry.width, geometry.tipY / geometry.height));
        }
        return result;
    }

    private Texture texture(PreparedText text, String leg) {
        Geometry geometry = geometry(text, leg);
        Bitmap bitmap = Bitmap.createBitmap(geometry.width, geometry.height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int background = withOpacity(Color.parseColor(
                profile.alternativeCalloutBackgroundColor),
                profile.alternativeCalloutOpacityPercent);
        int borderColor = Color.parseColor(profile.alternativeCalloutBorderColor);
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float scale = profile.alternativeCalloutScalePercent / 100f;
        float borderWidth = (float) profile.alternativeCalloutBorderWidthDp * density * scale;
        float radius = (float) profile.alternativeCalloutCornerRadiusDp * density * scale;

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(background);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(borderWidth);
        border.setColor(borderColor);
        Path tail = new Path();
        float halfTail = Math.max(2f, Math.min(text.bodyHeight * .22f, text.leader * .35f));
        tail.moveTo(geometry.tipX, geometry.tipY);
        tail.lineTo(geometry.attachX + geometry.normalY * halfTail,
                geometry.attachY - geometry.normalX * halfTail);
        tail.lineTo(geometry.attachX - geometry.normalY * halfTail,
                geometry.attachY + geometry.normalX * halfTail);
        tail.close();
        canvas.drawPath(tail, fill);
        if (borderWidth > 0f) canvas.drawPath(tail, border);

        RectF body = new RectF(geometry.bodyLeft, geometry.bodyTop,
                geometry.bodyLeft + text.bodyWidth, geometry.bodyTop + text.bodyHeight);
        canvas.drawRoundRect(body, radius, radius, fill);
        if (borderWidth > 0f) canvas.drawRoundRect(body, radius, radius, border);

        Paint.FontMetrics metrics = text.paint.getFontMetrics();
        float x = geometry.bodyLeft + text.horizontalPadding;
        float baseline = geometry.bodyTop + text.verticalPadding - metrics.ascent;
        for (TextPiece piece : text.pieces) {
            text.paint.setColor(piece.color);
            canvas.drawText(piece.text, x, baseline, text.paint);
            x += text.paint.measureText(piece.text);
        }
        return new Texture(bitmap, new PointF(
                geometry.tipX / geometry.width, geometry.tipY / geometry.height));
    }

    private static Geometry geometry(PreparedText text, String leg) {
        int diagonal = Math.max(1, Math.round(text.leader * .72f));
        int width = text.bodyWidth;
        int height = text.bodyHeight;
        float bodyLeft = 0f;
        float bodyTop = 0f;
        float tipX;
        float tipY;
        float attachX;
        float attachY;
        switch (leg) {
            case "RIGHT_CENTER":
                width += text.leader; tipX = width; tipY = height / 2f;
                attachX = text.bodyWidth; attachY = tipY; break;
            case "BOTTOM_LEFT":
                width += diagonal; height += diagonal; bodyLeft = diagonal;
                tipX = 0f; tipY = height; attachX = bodyLeft; attachY = text.bodyHeight; break;
            case "BOTTOM_RIGHT":
                width += diagonal; height += diagonal;
                tipX = width; tipY = height; attachX = text.bodyWidth;
                attachY = text.bodyHeight; break;
            case "TOP_LEFT":
                width += diagonal; height += diagonal; bodyLeft = diagonal; bodyTop = diagonal;
                tipX = 0f; tipY = 0f; attachX = bodyLeft; attachY = bodyTop; break;
            case "TOP_RIGHT":
                width += diagonal; height += diagonal; bodyTop = diagonal;
                tipX = width; tipY = 0f; attachX = text.bodyWidth; attachY = bodyTop; break;
            case "BOTTOM_CENTER":
                height += text.leader; tipX = width / 2f; tipY = height;
                attachX = tipX; attachY = text.bodyHeight; break;
            case "TOP_CENTER":
                height += text.leader; bodyTop = text.leader; tipX = width / 2f; tipY = 0f;
                attachX = tipX; attachY = bodyTop; break;
            case "LEFT_CENTER":
            default:
                width += text.leader; bodyLeft = text.leader; tipX = 0f; tipY = height / 2f;
                attachX = bodyLeft; attachY = tipY; break;
        }
        float dx = attachX - tipX;
        float dy = attachY - tipY;
        float length = Math.max(1f, (float) Math.hypot(dx, dy));
        return new Geometry(width, height, bodyLeft, bodyTop, tipX, tipY,
                attachX, attachY, dx / length, dy / length);
    }

    private static String sectionName(Object route, Object routePosition) {
        try {
            RouteProgress fork = routeProgress(routePosition, route);
            List<?> sections = list(invoke(route, "getSections", new Class<?>[0]));
            for (Object section : sections) {
                Object geometry = invoke(section, "getGeometry", new Class<?>[0]);
                Object begin = invoke(geometry, "getBegin", new Class<?>[0]);
                Object end = invoke(geometry, "getEnd", new Class<?>[0]);
                int first = ((Number) invoke(begin, "getSegmentIndex", new Class<?>[0])).intValue();
                int last = ((Number) invoke(end, "getSegmentIndex", new Class<?>[0])).intValue();
                if (fork.segmentIndex < first || fork.segmentIndex > last) continue;
                Object metadata = invoke(section, "getMetadata", new Class<?>[0]);
                Object annotation = invoke(metadata, "getAnnotation", new Class<?>[0]);
                String name = text(invoke(annotation, "getToponym", new Class<?>[0]));
                if (name.isEmpty()) {
                    name = text(invoke(annotation, "getDescriptionText", new Class<?>[0]));
                }
                if (!name.isEmpty()) return bounded(name, MAX_ROUTE_NAME_CHARS);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static RouteProgress routeProgress(Object position, Object route) {
        if (position == null || route == null) return RouteProgress.UNKNOWN;
        try {
            Object polylinePosition = invoke(position, "positionOnRoute",
                    new Class<?>[]{String.class}, routeId(route));
            if (polylinePosition == null) return RouteProgress.UNKNOWN;
            return new RouteProgress(
                    ((Number) invoke(polylinePosition, "getSegmentIndex",
                            new Class<?>[0])).intValue(),
                    ((Number) invoke(polylinePosition, "getSegmentPosition",
                            new Class<?>[0])).doubleValue());
        } catch (Throwable ignored) {
            return RouteProgress.UNKNOWN;
        }
    }

    private static String routeId(Object route) {
        try { return text(invoke(route, "getRouteId", new Class<?>[0])); }
        catch (Throwable ignored) { return ""; }
    }

    private static Double finiteNumber(Object target, String method) {
        try {
            double value = number(invoke(target, method, new Class<?>[0]));
            return Double.isFinite(value) && value >= 0d ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatTimeDelta(int seconds) {
        int absoluteMinutes = Math.max(seconds == 0 ? 0 : 1,
                (int) Math.round(Math.abs(seconds) / 60d));
        return sign(seconds) + absoluteMinutes + " мин";
    }

    private static String formatDistanceDelta(int meters) {
        int absolute = Math.abs(meters);
        if (absolute < 1_000) {
            return sign(meters) + Math.round(absolute / 10f) * 10 + " м";
        }
        String value = String.format(Locale.ROOT, "%.1f", absolute / 1_000d)
                .replace('.', ',');
        if (value.endsWith(",0")) value = value.substring(0, value.length() - 2);
        return sign(meters) + value + " км";
    }

    private static String sign(int value) {
        return value < 0 ? "\u2212" : value > 0 ? "+" : "";
    }

    private static float measuredWidth(Paint paint, List<TextPiece> pieces) {
        float result = 0f;
        for (TextPiece piece : pieces) result += paint.measureText(piece.text);
        return result;
    }

    private static String ellipsize(Paint paint, String text, float width) {
        if (paint.measureText(text) <= width) return text;
        String ellipsis = "\u2026";
        float available = width - paint.measureText(ellipsis);
        if (available <= 0f) return "";
        int count = paint.breakText(text, true, available, null);
        return count <= 0 ? "" : text.substring(0, count).trim() + ellipsis;
    }

    private static int withOpacity(int color, int opacityPercent) {
        int alpha = Math.round(Color.alpha(color) * Math.max(0, Math.min(100, opacityPercent))
                / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static long appearanceFingerprint(NavigationMapProfile value) {
        long result = 17L;
        result = mix(result, value.showAlternativeRoutes ? 1 : 0);
        result = mix(result, value.alternativeRouteColor.hashCode());
        result = mix(result, Double.doubleToLongBits(value.alternativeRouteWidth));
        result = mix(result, value.alternativeCalloutScalePercent);
        result = mix(result, value.alternativeCalloutBackgroundColor.hashCode());
        result = mix(result, value.alternativeCalloutOpacityPercent);
        result = mix(result, value.alternativeCalloutTextColor.hashCode());
        result = mix(result, value.alternativeCalloutBorderColor.hashCode());
        result = mix(result, Double.doubleToLongBits(value.alternativeCalloutBorderWidthDp));
        result = mix(result, Double.doubleToLongBits(value.alternativeCalloutTextSizeSp));
        result = mix(result, Double.doubleToLongBits(value.alternativeCalloutCornerRadiusDp));
        result = mix(result, Double.doubleToLongBits(value.alternativeCalloutHorizontalPaddingDp));
        result = mix(result, Double.doubleToLongBits(value.alternativeCalloutVerticalPaddingDp));
        result = mix(result, Double.doubleToLongBits(value.alternativeCalloutLeaderLengthDp));
        result = mix(result, value.effectiveAlternativeRoutePriority());
        return mix(result, value.effectiveAlternativeCalloutPriority());
    }

    private static long dataFingerprint(long epoch, Object route, List<?> values) {
        long result = mix(17L, epoch);
        result = mix(result, routeId(route).hashCode());
        result = mix(result, StockAlternativePalette.version());
        result = mix(result, values.size());
        for (Object alternative : values) {
            try {
                Object candidate = invoke(alternative, "getAlternative", new Class<?>[0]);
                result = mix(result, routeId(candidate).hashCode());
                Object fork = invoke(alternative,
                        "getForkPositionOnCurrentRoute", new Class<?>[0]);
                Object alternativeFork = invoke(alternative,
                        "getForkPositionOnAlternative", new Class<?>[0]);
                Double distance = finiteNumber(fork, "distanceToFinish");
                Double alternativeDistance = finiteNumber(
                        alternativeFork, "distanceToFinish");
                result = mix(result, distance == null || alternativeDistance == null
                        ? Long.MIN_VALUE
                        : Math.round(alternativeDistance - distance));
                Double time = finiteNumber(fork, "timeToFinish");
                Double alternativeTime = finiteNumber(alternativeFork, "timeToFinish");
                result = mix(result, time == null || alternativeTime == null
                        ? Long.MIN_VALUE + 1L
                        : Math.round(alternativeTime - time));
                Object point = invoke(fork, "getPoint", new Class<?>[0]);
                result = mix(result, Double.doubleToLongBits(number(invoke(
                        point, "getLatitude", new Class<?>[0]))));
                result = mix(result, Double.doubleToLongBits(number(invoke(
                        point, "getLongitude", new Class<?>[0]))));
            } catch (Throwable invalid) {
                result = mix(result, System.identityHashCode(alternative));
            }
        }
        return result;
    }

    private static long mix(long seed, long value) {
        return seed * 1_000_003L + value;
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit).trim();
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && latitude >= -90d && latitude <= 90d
                && Double.isFinite(longitude) && longitude >= -180d && longitude <= 180d;
    }

    private static void hide(Marker marker) {
        try { invoke(marker.placemark, "setVisible", new Class<?>[]{boolean.class}, false); }
        catch (Throwable ignored) {}
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class Marker {
        final Object placemark;
        final CalloutModel model;
        MapOverlayPlacementCoordinator.Placement placement;
        long paletteVersion = Long.MIN_VALUE;
        Marker(Object placemark, CalloutModel model) {
            this.placemark = placemark;
            this.model = model;
        }
    }

    private static final class CalloutModel {
        final String key;
        final double latitude;
        final double longitude;
        final String name;
        final Integer timeDeltaSeconds;
        final Integer distanceDeltaMeters;
        final int routeSegmentIndex;
        final double routeSegmentPosition;
        CalloutModel(String key, double latitude, double longitude, String name,
                     Integer timeDeltaSeconds, Integer distanceDeltaMeters,
                     int routeSegmentIndex, double routeSegmentPosition) {
            this.key = key;
            this.latitude = latitude;
            this.longitude = longitude;
            this.name = name;
            this.timeDeltaSeconds = timeDeltaSeconds;
            this.distanceDeltaMeters = distanceDeltaMeters;
            this.routeSegmentIndex = routeSegmentIndex;
            this.routeSegmentPosition = routeSegmentPosition;
        }
    }

    private static final class RouteProgress {
        static final RouteProgress UNKNOWN = new RouteProgress(-1, Double.NaN);
        final int segmentIndex;
        final double segmentPosition;
        RouteProgress(int segmentIndex, double segmentPosition) {
            this.segmentIndex = segmentIndex;
            this.segmentPosition = segmentPosition;
        }
    }

    private static final class TextPiece {
        String text;
        final int color;
        TextPiece(String text, int color) { this.text = text; this.color = color; }
    }

    private static final class PreparedText {
        final Paint paint;
        final List<TextPiece> pieces;
        final float horizontalPadding;
        final float verticalPadding;
        final int bodyWidth;
        final int bodyHeight;
        final int leader;
        PreparedText(Paint paint, List<TextPiece> pieces, float horizontalPadding,
                     float verticalPadding, int bodyWidth, int bodyHeight, int leader) {
            this.paint = paint;
            this.pieces = pieces;
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
            this.bodyWidth = bodyWidth;
            this.bodyHeight = bodyHeight;
            this.leader = leader;
        }
    }

    private static final class Geometry {
        final int width;
        final int height;
        final float bodyLeft;
        final float bodyTop;
        final float tipX;
        final float tipY;
        final float attachX;
        final float attachY;
        final float normalX;
        final float normalY;
        Geometry(int width, int height, float bodyLeft, float bodyTop,
                 float tipX, float tipY, float attachX, float attachY,
                 float normalX, float normalY) {
            this.width = width;
            this.height = height;
            this.bodyLeft = bodyLeft;
            this.bodyTop = bodyTop;
            this.tipX = tipX;
            this.tipY = tipY;
            this.attachX = attachX;
            this.attachY = attachY;
            this.normalX = normalX;
            this.normalY = normalY;
        }
    }

    private static final class Texture {
        final Bitmap bitmap;
        final PointF anchor;
        Texture(Bitmap bitmap, PointF anchor) { this.bitmap = bitmap; this.anchor = anchor; }
    }
}
