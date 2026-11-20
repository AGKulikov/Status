/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.os.SystemClock;
import java.lang.reflect.Proxy;

/** Owns one exact, positively observed primary-screen maneuver bitmap on one external map. */
final class PrimaryTurnPlacemark {
    private static final String OWNER = "primary_turn";
    private Object placemark;
    private PrimaryTurnSample displayed;
    private int displayedScale;
    private float displayedZ;
    private LaneGuidanceAppearance displayedStyle;
    private Object loading, loadingCallback;
    private PrimaryTurnSample loadingSample;
    private LaneGuidanceAppearance loadingStyle;
    private int loadingScale, displayedWidth, displayedHeight;
    private float loadingZ;

    static boolean isCurrent(PrimaryTurnSample sample, long routeEpoch, boolean active, long now, long maxAge) {
        return active && sample != null && sample.routeEpoch == routeEpoch && sample.sampledAt > 0L
                && now >= sample.sampledAt && now - sample.sampledAt < maxAge;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void show(Object collection, PrimaryTurnSample sample, int scale, float zIndex,
              MapOverlayPlacementCoordinator coordinator, LaneGuidanceAppearance appearance, Runnable ready) throws Exception {
        StockTurnTextures.Texture texture = sample.texture;
        NavigatorStatePublisher.RouteTurnFrame turn = sample.turn;
        if (!turn.hasContent() || texture.bitmap == null || texture.bitmap.isRecycled()
                || !normalized(texture.anchorX) || !normalized(texture.anchorY))
            throw new IllegalArgumentException("Invalid primary maneuver artwork or anchor");

        if (displayed != null && displayed.routeEpoch == sample.routeEpoch
                && displayed.turn.id.equals(turn.id) && displayed.turn.latitude == turn.latitude
                && displayed.turn.longitude == turn.longitude && displayed.texture == texture
                && displayedScale == scale && displayedZ == zIndex
                && appearance.same(displayedStyle)) {
            remove(collection, loading);
            loading = null; loadingSample = null; loadingCallback = null;
            displayed = sample;
            return;
        }
        if (loadingSample != null && loadingSample.routeEpoch == sample.routeEpoch
                && loadingSample.turn.id.equals(turn.id) && loadingSample.texture == texture
                && loadingScale == scale && loadingZ == zIndex && appearance.same(loadingStyle)) {
            loadingSample = sample;
            return;
        }
        remove(collection, loading);
        loading = null;
        loadingCallback = null;
        loadingSample = null;
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(turn.latitude, turn.longitude);
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> imageClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Class<?> rotation = Class.forName("com.yandex.mapkit.map.RotationType");
        Object style = styleClass.getConstructor().newInstance();
        invoke(style, "setAnchor", new Class<?>[]{PointF.class}, new PointF(texture.anchorX, texture.anchorY));
        invoke(style, "setRotationType", new Class<?>[]{rotation}, Enum.valueOf((Class) rotation, "NO_ROTATION"));
        invoke(style, "setScale", new Class<?>[]{Float.class}, texture.artwork == null ? scale / 100f : 1f);
        invoke(style, "setFlat", new Class<?>[]{Boolean.class}, false);
        invoke(style, "setZIndex", new Class<?>[]{Float.class}, zIndex);
        Bitmap bitmap = texture.artwork == null ? texture.bitmap
                : texture.artwork.render(texture.bitmap, appearance, scale / 100f);
        Object provider = imageClass.getMethod("fromBitmap", Bitmap.class).invoke(null, bitmap);
        Class<?> callbackClass = Class.forName("com.yandex.mapkit.map.Callback");
        Object candidate = invoke(collection, "addEmptyPlacemark", new Class<?>[]{pointClass}, point);
        try { MapObjectLayerFactory.hideUntilTextured(candidate); }
        catch (Exception failure) { remove(collection, candidate); throw failure; }
        loading = candidate;
        loadingSample = sample;
        loadingStyle = appearance;
        loadingScale = scale;
        loadingZ = zIndex;
        loadingCallback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("onTaskFinished")) {
                        if (loading != candidate) return null;
                        if (!isCurrent(loadingSample, sample.routeEpoch, true,
                                SystemClock.elapsedRealtime(), PrimaryTurnSample.FRESH_MS)) {
                            remove(collection, loading);
                            loading = null; loadingSample = null; loadingCallback = null;
                            return null;
                        }
                        // Keep the old icon visible until the new GPU texture is ready.
                        // The swap occurs in one callback, without a blank map frame.
                        invoke(candidate, "setVisible", new Class<?>[]{boolean.class}, true);
                        Object previous = placemark;
                        placemark = candidate;
                        displayed = loadingSample;
                        displayedStyle = loadingStyle;
                        displayedScale = loadingScale;
                        displayedZ = loadingZ;
                        float rasterScale = texture.artwork == null ? scale / 100f : 1f;
                        displayedWidth = Math.round(bitmap.getWidth() * rasterScale);
                        displayedHeight = Math.round(bitmap.getHeight() * rasterScale);
                        loading = null; loadingSample = null; loadingCallback = null;
                        remove(collection, previous);
                        reserve(coordinator);
                        ready.run();
                        return null;
                    }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("toString")) return "NatroTurnTextureReady";
                    return null;
                });
        try {
            invoke(candidate, "setIcon", new Class<?>[]{imageClass, styleClass, callbackClass}, provider, style, loadingCallback);
        } catch (Exception failure) {
            remove(collection, candidate);
            if (loading == candidate) { loading = null; loadingSample = null; loadingCallback = null; }
            throw failure;
        }
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
                displayedWidth, displayedHeight,
                texture.anchorX, texture.anchorY);
    }

    void clear(Object collection, MapOverlayPlacementCoordinator coordinator) {
        remove(collection, loading);
        loading = null;
        loadingSample = null;
        loadingCallback = null;
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
