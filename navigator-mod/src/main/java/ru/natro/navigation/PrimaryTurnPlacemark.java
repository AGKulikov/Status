/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Bitmap;
import android.graphics.PointF;

/** Owns one exact, positively observed primary-screen maneuver bitmap on one external map. */
final class PrimaryTurnPlacemark {
    private static final String OWNER = "primary_turn";
    private Object placemark;
    private PrimaryTurnSample displayed;
    private int displayedScale;
    private float displayedZ;
    private LaneGuidanceAppearance displayedStyle;

    static boolean isCurrent(PrimaryTurnSample sample, long routeEpoch, boolean active, long now, long maxAge) {
        return active && sample != null && sample.routeEpoch == routeEpoch && sample.sampledAt > 0L
                && now >= sample.sampledAt && now - sample.sampledAt < maxAge;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void show(Object collection, PrimaryTurnSample sample, int scale, float zIndex,
              MapOverlayPlacementCoordinator coordinator, LaneGuidanceAppearance appearance) throws Exception {
        StockTurnTextures.Texture texture = sample.texture;
        NavigatorStatePublisher.RouteTurnFrame turn = sample.turn;
        if (!turn.hasContent() || texture.bitmap == null || texture.bitmap.isRecycled()
                || !normalized(texture.anchorX) || !normalized(texture.anchorY))
            throw new IllegalArgumentException("Invalid primary maneuver artwork or anchor");

        if (displayed != null && displayed.routeEpoch == sample.routeEpoch
                && displayed.turn.id.equals(turn.id) && displayed.turn.latitude == turn.latitude
                && displayed.turn.longitude == turn.longitude && displayed.texture == texture
                && displayedScale == scale && displayedZ == zIndex
                && (!texture.combined || appearance.same(displayedStyle))) {
            displayed = sample;
            return;
        }
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(turn.latitude, turn.longitude);
        if (placemark == null) {
            placemark = invoke(collection, "addEmptyPlacemark", new Class<?>[]{pointClass}, point);
            MapObjectLayerFactory.hideUntilTextured(placemark);
        }
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> imageClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Class<?> rotation = Class.forName("com.yandex.mapkit.map.RotationType");
        Object style = styleClass.getConstructor().newInstance();
        invoke(style, "setAnchor", new Class<?>[]{PointF.class}, new PointF(texture.anchorX, texture.anchorY));
        invoke(style, "setRotationType", new Class<?>[]{rotation}, Enum.valueOf((Class) rotation, "NO_ROTATION"));
        invoke(style, "setScale", new Class<?>[]{Float.class}, scale / 100f);
        invoke(style, "setFlat", new Class<?>[]{Boolean.class}, false);
        invoke(style, "setZIndex", new Class<?>[]{Float.class}, zIndex);
        // A fresh bitmap provider guarantees the exact pixels that passed the primary-surface check.
        Bitmap bitmap = texture.artwork == null ? texture.bitmap : texture.artwork.render(texture.bitmap, appearance);
        Object provider = imageClass.getMethod("fromBitmap", Bitmap.class).invoke(null, bitmap);
        invoke(placemark, "setIcon", new Class<?>[]{imageClass, styleClass}, provider, style);
        invoke(placemark, "setGeometry", new Class<?>[]{pointClass}, point);
        invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        displayed = sample;
        displayedStyle = appearance;
        displayedScale = scale;
        displayedZ = zIndex;
        reserve(coordinator);
    }

    boolean replaces(NavigatorStatePublisher.LaneGuidanceFrame lane) {
        if (displayed == null || !displayed.texture.combined) return false;
        NavigatorStatePublisher.RouteTurnFrame turn = displayed.turn;
        if (turn.routeSegmentIndex < 0 || lane.routeSegmentIndex < 0
                || !normalized(turn.routeSegmentPosition) || !normalized(lane.routeSegmentPosition))
            return false;
        // Segment i at 1.0 and segment i+1 at 0.0 denote the same vertex. Coordinate equality
        // alone is ambiguous on self-crossings, parallel carriageways and repeated route legs.
        return Math.abs((turn.routeSegmentIndex - (long) lane.routeSegmentIndex)
                + turn.routeSegmentPosition - lane.routeSegmentPosition) <= 1e-6
                && Math.abs(turn.latitude - lane.latitude) <= 1e-6
                && Math.abs(turn.longitude - lane.longitude) <= 1e-6;
    }

    void reserve(MapOverlayPlacementCoordinator coordinator) {
        coordinator.clearOwner(OWNER);
        if (displayed == null) return;
        NavigatorStatePublisher.RouteTurnFrame turn = displayed.turn;
        StockTurnTextures.Texture texture = displayed.texture;
        coordinator.reserveFixed(OWNER, turn.id, turn.latitude, turn.longitude,
                Math.round(texture.bitmap.getWidth() * displayedScale / 100f),
                Math.round(texture.bitmap.getHeight() * displayedScale / 100f),
                texture.anchorX, texture.anchorY);
    }

    void clear(Object collection, MapOverlayPlacementCoordinator coordinator) {
        remove(collection, placemark);
        placemark = null;
        displayed = null;
        coordinator.clearOwner(OWNER);
    }

    static void remove(Object collection, Object placemark) {
        if (placemark == null || collection == null) return;
        try { invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, false); }
        catch (Exception invalidated) { /* The map may already have been destroyed. */ }
        try { invoke(collection, "remove", new Class<?>[]{Class.forName("com.yandex.mapkit.map.MapObject")}, placemark); }
        catch (Exception invalidated) { /* Collection cleanup on map destruction is sufficient. */ }
    }

    private static boolean normalized(double n) { return n >= 0d && n <= 1d; }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        return ReflectMethods.publicMethod(target.getClass(), name, types).invoke(target, args);
    }
}
