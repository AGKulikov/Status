/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unified camera signs and source-backed translucent viewing sectors.
 *
 * <p>Yandex data comes directly from Windshield. HUD Speed data arrives through Natro's pinned
 * bridge. Nearby records are merged into one physical marker while their exact control tags and
 * both viewing directions are retained. A sector is created only when a source supplied it.</p>
 */
final class CameraDirectionMapLayer {
    private static final String TAG = "NatroCameraLayer";
    private static final long YANDEX_FRESH_MS = 3_000L;
    private static final long EXTERNAL_FRESH_MS = 3_500L;
    private static final int MAX_CAMERAS = 32;
    private static final int MAX_EXTERNAL_JSON_CHARS = 96 * 1024;
    private static final double HUD_SPEED_DUPLICATE_DISTANCE_METERS = 65d;
    /** Do not collapse two consecutive physical cameras just because their pins are nearby. */
    private static final double SAME_SOURCE_DUPLICATE_DISTANCE_METERS = 12d;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;
    private static final double BASE_SECTOR_LENGTH_METERS = 105d;
    /** Matches the former 13 degree half-angle at the default 105 metre length. */
    private static final double BASE_SECTOR_WIDTH_METERS = 48.5d;
    private static final int DEFAULT_SECTOR_RGB = 0x00168BFF;
    private static final int STANDARD_SIGN_RED = 0xFFF04444;
    private static final int STANDARD_SIGN_TEXT = 0xFF24272C;
    /** Preserve number and vector-plate detail when a 40dp camera sign is reduced on KX11. */
    private static final int MIN_CAMERA_TEXTURE_DIAMETER_PX = 80;

    private static final String SOURCE_YANDEX = "YANDEX";
    private static final String SOURCE_HUD_SPEED = "HUD_SPEED";

    private final Context context;
    private final MapOverlayPlacementCoordinator placementCoordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Bitmap> iconBitmaps = new ArrayList<>();
    private final ArrayList<Object> imageProviders = new ArrayList<>();
    private final ArrayList<CameraSign> cameraSigns = new ArrayList<>();
    private final ArrayList<CameraMarker> visibleScratch = new ArrayList<>(MAX_CAMERAS);
    private Object map;
    /** Ground polygons and placemark signs must never share one ambiguous MapKit root layer. */
    private Object sectorCollection;
    private Object signCollection;
    private boolean yandexEnabled;
    private boolean externalEnabled = true;
    private int scalePercent = 100;
    private int directionLengthPercent = 100;
    private int directionWidthPercent = 100;
    private int directionRgb = DEFAULT_SECTOR_RGB;
    private int directionOpacityPercent = 30;
    private float zIndex = NavigationMapProfile.layerZ(20);
    private boolean latestRouteActive;
    private List<NavigatorStatePublisher.CameraDirectionFrame> latestYandex =
            Collections.emptyList();
    private List<ExternalCamera> latestExternal = Collections.emptyList();
    private long latestYandexSampleElapsedMs;
    private long latestExternalSampleElapsedMs;
    private long latestVisualFingerprint = Long.MIN_VALUE;
    private long renderedFingerprint = Long.MIN_VALUE;
    private boolean expiryPosted;

    private final Runnable expire = new Runnable() {
        @Override public void run() {
            expiryPosted = false;
            discardExpiredSources();
            refreshFingerprintAndRender();
            scheduleExpiryIfNeeded();
        }
    };

    CameraDirectionMapLayer(Context context) {
        this(context, new MapOverlayPlacementCoordinator());
    }

    CameraDirectionMapLayer(Context context,
                            MapOverlayPlacementCoordinator placementCoordinator) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.placementCoordinator = placementCoordinator;
    }

    void attach(Object nextMap) {
        if (map == nextMap) return;
        detachMap();
        map = nextMap;
        discardExpiredSources();
        scheduleExpiryIfNeeded();
        refreshFingerprintAndRender();
    }

    void detachMap() {
        main.removeCallbacks(expire);
        expiryPosted = false;
        clearVisual();
        sectorCollection = null;
        signCollection = null;
        map = null;
    }

    void apply(boolean nextYandexEnabled, boolean nextExternalEnabled,
               int nextScalePercent, int nextDirectionLengthPercent,
               int nextDirectionWidthPercent, String nextDirectionColor,
               int nextDirectionOpacityPercent, int layerPriority) {
        int nextScale = Math.max(50, Math.min(250, nextScalePercent));
        int nextDirectionLength = Math.max(10, Math.min(300, nextDirectionLengthPercent));
        int nextDirectionWidth = Math.max(10, Math.min(300, nextDirectionWidthPercent));
        int nextDirectionRgb = opaqueRgb(nextDirectionColor, DEFAULT_SECTOR_RGB);
        int nextDirectionOpacity = Math.max(0, Math.min(100, nextDirectionOpacityPercent));
        float nextZ = NavigationMapProfile.layerZ(layerPriority);
        boolean presentationChanged = scalePercent != nextScale
                || directionLengthPercent != nextDirectionLength
                || directionWidthPercent != nextDirectionWidth
                || directionRgb != nextDirectionRgb
                || directionOpacityPercent != nextDirectionOpacity || zIndex != nextZ;
        boolean visibilityChanged = yandexEnabled != nextYandexEnabled
                || externalEnabled != nextExternalEnabled;
        if (!presentationChanged && !visibilityChanged) return;
        yandexEnabled = nextYandexEnabled;
        externalEnabled = nextExternalEnabled;
        scalePercent = nextScale;
        directionLengthPercent = nextDirectionLength;
        directionWidthPercent = nextDirectionWidth;
        directionRgb = nextDirectionRgb;
        directionOpacityPercent = nextDirectionOpacity;
        zIndex = nextZ;
        MapObjectLayerFactory.setZIndex(sectorCollection, nextZ);
        MapObjectLayerFactory.setZIndex(signCollection, nextZ);
        if (presentationChanged) renderedFingerprint = Long.MIN_VALUE;
        if (!yandexEnabled && !externalEnabled) {
            main.removeCallbacks(expire);
            expiryPosted = false;
        } else {
            discardExpiredSources();
            scheduleExpiryIfNeeded();
        }
        refreshFingerprintAndRender();
    }

    void update(boolean routeActive, long sampleElapsedMs,
                List<NavigatorStatePublisher.CameraDirectionFrame> values) {
        latestRouteActive = routeActive;
        long now = SystemClock.elapsedRealtime();
        boolean fresh = routeActive && sampleElapsedMs > 0L && now >= sampleElapsedMs
                && now - sampleElapsedMs <= YANDEX_FRESH_MS;
        latestYandex = fresh && values != null ? values : Collections.emptyList();
        latestYandexSampleElapsedMs = fresh ? sampleElapsedMs : 0L;
        scheduleExpiryIfNeeded();
        refreshFingerprintAndRender();
    }

    /** Accepts only the bounded, normalized frame forwarded by the Natro host. */
    void updateExternal(String raw) {
        ArrayList<ExternalCamera> parsed = new ArrayList<>();
        long sampledAt = 0L;
        try {
            if (raw == null || raw.isEmpty() || raw.length() > MAX_EXTERNAL_JSON_CHARS
                    || raw.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("empty or oversized external camera frame");
            }
            JSONObject root = new JSONObject(raw);
            if (root.optInt("schema", -1) != 1) {
                throw new IllegalArgumentException("external camera schema mismatch");
            }
            sampledAt = root.optLong("sampleElapsedMs", 0L);
            long now = SystemClock.elapsedRealtime();
            if (sampledAt <= 0L || now < sampledAt || now - sampledAt > EXTERNAL_FRESH_MS) {
                throw new IllegalArgumentException("stale external camera frame");
            }
            JSONArray cameras = root.optJSONArray("cameras");
            if (cameras != null) {
                for (int index = 0; index < cameras.length()
                        && parsed.size() < MAX_CAMERAS; index++) {
                    JSONObject item = cameras.optJSONObject(index);
                    ExternalCamera camera = ExternalCamera.fromJson(item);
                    if (camera != null) parsed.add(camera);
                }
            }
        } catch (Exception invalid) {
            parsed.clear();
            sampledAt = 0L;
        }
        latestExternal = parsed.isEmpty()
                ? Collections.emptyList() : Collections.unmodifiableList(parsed);
        latestExternalSampleElapsedMs = sampledAt;
        scheduleExpiryIfNeeded();
        refreshFingerprintAndRender();
    }

    /** Ends only Navigator's route-owned stream; HUD Speed remains useful in free drive. */
    void clearData() {
        latestYandex = Collections.emptyList();
        latestRouteActive = false;
        latestYandexSampleElapsedMs = 0L;
        refreshFingerprintAndRender();
        scheduleExpiryIfNeeded();
    }

    /** Keeps every camera centred while refreshing its fixed collision reservation. */
    void relayout() {
        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_CAMERAS);
        for (CameraSign sign : cameraSigns) {
            try {
                MapOverlayPlacementCoordinator.Placement next = reservePlacement(
                        sign.camera, sign.bitmapWidth, sign.bitmapHeight);
                if (next.sameSlot(sign.placement)) continue;
                sign.placement = next;
                invoke(sign.style, "setAnchor", new Class<?>[]{PointF.class},
                        new PointF(next.anchorX, next.anchorY));
                Class<?> providerClass = Class.forName(
                        "com.yandex.runtime.image.ImageProvider");
                Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
                invoke(sign.placemark, "setIcon",
                        new Class<?>[]{providerClass, styleClass},
                        sign.provider, sign.style);
            } catch (Throwable failure) {
                Log.w(TAG, "Camera sign reservation could not be refreshed", failure);
            }
        }
    }

    private void scheduleExpiryIfNeeded() {
        if (map == null || expiryPosted || (!yandexEnabled && !externalEnabled)) return;
        long now = SystemClock.elapsedRealtime();
        long delay = Long.MAX_VALUE;
        if (latestYandexSampleElapsedMs > 0L) {
            delay = Math.min(delay,
                    latestYandexSampleElapsedMs + YANDEX_FRESH_MS - now);
        }
        if (latestExternalSampleElapsedMs > 0L) {
            delay = Math.min(delay,
                    latestExternalSampleElapsedMs + EXTERNAL_FRESH_MS - now);
        }
        if (delay == Long.MAX_VALUE) return;
        expiryPosted = main.postDelayed(expire, Math.max(1L, delay));
    }

    private void discardExpiredSources() {
        long now = SystemClock.elapsedRealtime();
        if (latestYandexSampleElapsedMs > 0L
                && (now < latestYandexSampleElapsedMs
                || now - latestYandexSampleElapsedMs > YANDEX_FRESH_MS)) {
            latestYandex = Collections.emptyList();
            latestYandexSampleElapsedMs = 0L;
        }
        if (latestExternalSampleElapsedMs > 0L
                && (now < latestExternalSampleElapsedMs
                || now - latestExternalSampleElapsedMs > EXTERNAL_FRESH_MS)) {
            latestExternal = Collections.emptyList();
            latestExternalSampleElapsedMs = 0L;
        }
    }

    private void refreshFingerprintAndRender() {
        selectVisible(visibleScratch);
        long fingerprint = visualFingerprint(visibleScratch);
        boolean dataChanged = fingerprint != latestVisualFingerprint;
        latestVisualFingerprint = fingerprint;
        // Presentation-only edits (sign size, sector geometry/colour/opacity and z-order)
        // deliberately invalidate the rendered fingerprint without changing camera data.
        if (map != null && (dataChanged || renderedFingerprint != fingerprint)) render();
    }

    /** HUD Speed supplies the primary record; Yandex enriches it with exact event tags. */
    private void selectVisible(ArrayList<CameraMarker> target) {
        target.clear();
        if (externalEnabled && latestExternalSampleElapsedMs > 0L) {
            for (ExternalCamera value : latestExternal) {
                if (value == null || !value.hasMapPosition()) continue;
                CameraMarker candidate = CameraMarker.fromExternal(value);
                addOrMergeDuplicate(target, candidate,
                        SAME_SOURCE_DUPLICATE_DISTANCE_METERS);
                if (target.size() >= MAX_CAMERAS) break;
            }
        }
        if (yandexEnabled && latestRouteActive && latestYandexSampleElapsedMs > 0L) {
            for (NavigatorStatePublisher.CameraDirectionFrame value : latestYandex) {
                if (value == null || !value.hasMapPosition()) continue;
                CameraMarker candidate = CameraMarker.fromYandex(value);
                if (!mergeIntoNearbyHudSpeed(target, candidate)) {
                    if (target.size() < MAX_CAMERAS) {
                        addOrMergeDuplicate(target, candidate,
                                SAME_SOURCE_DUPLICATE_DISTANCE_METERS);
                    }
                }
            }
        }
    }

    private static boolean mergeIntoNearbyHudSpeed(ArrayList<CameraMarker> values,
                                                   CameraMarker candidate) {
        int nearestIndex = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (int index = 0; index < values.size(); index++) {
            CameraMarker accepted = values.get(index);
            if (!SOURCE_HUD_SPEED.equals(accepted.source)) continue;
            double distance = distanceMeters(accepted.latitude, accepted.longitude,
                    candidate.latitude, candidate.longitude);
            if (distance <= HUD_SPEED_DUPLICATE_DISTANCE_METERS
                    && distance < nearestDistance) {
                nearestIndex = index;
                nearestDistance = distance;
            }
        }
        if (nearestIndex < 0) return false;
        values.set(nearestIndex,
                CameraMarker.merge(values.get(nearestIndex), candidate));
        return true;
    }

    /** Collapses repeated source records into one physical camera marker. */
    private static void addOrMergeDuplicate(ArrayList<CameraMarker> values,
                                            CameraMarker candidate,
                                            double maximumDistanceMeters) {
        for (int index = 0; index < values.size(); index++) {
            CameraMarker accepted = values.get(index);
            if (!accepted.source.equals(candidate.source)) continue;
            boolean duplicateId = accepted.id.equals(candidate.id);
            boolean duplicatePoint = distanceMeters(accepted.latitude, accepted.longitude,
                    candidate.latitude, candidate.longitude) <= maximumDistanceMeters;
            if (!duplicateId && !duplicatePoint) continue;
            values.set(index, CameraMarker.merge(accepted, candidate));
            return;
        }
        values.add(candidate);
    }

    private void render() {
        if (map == null) return;
        if (visibleScratch.isEmpty()) {
            clearVisual();
            return;
        }
        if (renderedFingerprint == latestVisualFingerprint) return;
        try {
            Object currentSigns = signCollection;
            if (currentSigns == null) {
                currentSigns = MapObjectLayerFactory.create(map,
                        MapSublayerOrder.CAMERA_SIGNS,
                        MapObjectLayerFactory.EQUAL, zIndex);
                signCollection = currentSigns;
            }
            Object currentSectors = sectorCollection;
            if (hasDirections(visibleScratch) && currentSectors == null) {
                currentSectors = MapObjectLayerFactory.create(map,
                        MapSublayerOrder.CAMERA_SECTORS,
                        // Direction polygons are background geometry, not collision candidates.
                        MapObjectLayerFactory.IGNORE, zIndex);
                sectorCollection = currentSectors;
            }
            if (currentSectors != null) invoke(currentSectors, "clear", new Class<?>[0]);
            invoke(currentSigns, "clear", new Class<?>[0]);
            placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_CAMERAS);
            iconBitmaps.clear();
            imageProviders.clear();
            cameraSigns.clear();
            for (CameraMarker camera : visibleScratch) {
                // No direction supplied means exactly one sign and no guessed circular plane.
                for (Double direction : camera.directions) {
                    if (direction != null && Double.isFinite(direction)) {
                        addSector(currentSectors, camera.latitude, camera.longitude,
                                direction.doubleValue());
                    }
                }
            }
            for (CameraMarker camera : visibleScratch) addSign(currentSigns, camera);
            renderedFingerprint = latestVisualFingerprint;
        } catch (Throwable failure) {
            Log.w(TAG, "Camera sign/direction update failed", failure);
            clearVisual();
        }
    }

    private static boolean hasDirections(List<CameraMarker> cameras) {
        for (CameraMarker camera : cameras) {
            if (camera == null) continue;
            for (Double direction : camera.directions) {
                if (direction != null && Double.isFinite(direction)) return true;
            }
        }
        return false;
    }

    private void addSector(Object target, double latitude, double longitude,
                           double directionDegrees) throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        ArrayList<Object> points = new ArrayList<>(4);
        points.add(pointClass.getConstructor(double.class, double.class)
                .newInstance(latitude, longitude));
        double length = BASE_SECTOR_LENGTH_METERS * directionLengthPercent / 100d;
        double halfWidth = BASE_SECTOR_WIDTH_METERS * directionWidthPercent / 200d;
        // Length places the centre of the far edge. Width then moves only the two endpoints
        // sideways, so changing the broad edge cannot alter the apex or longitudinal reach.
        double[] farCenter = destination(latitude, longitude, directionDegrees, length);
        double[] left = destination(farCenter[0], farCenter[1],
                directionDegrees - 90d, halfWidth);
        double[] right = destination(farCenter[0], farCenter[1],
                directionDegrees + 90d, halfWidth);
        points.add(pointClass.getConstructor(double.class, double.class)
                .newInstance(left[0], left[1]));
        points.add(pointClass.getConstructor(double.class, double.class)
                .newInstance(right[0], right[1]));
        points.add(points.get(0));

        Class<?> ringClass = Class.forName("com.yandex.mapkit.geometry.LinearRing");
        Object ring = ringClass.getConstructor(List.class).newInstance(points);
        Class<?> polygonClass = Class.forName("com.yandex.mapkit.geometry.Polygon");
        Object polygon = polygonClass.getConstructor(ringClass, List.class)
                .newInstance(ring, Collections.emptyList());
        Object mapObject = invoke(target, "addPolygon",
                new Class<?>[]{polygonClass}, polygon);
        int fillAlpha = Math.round(255f * directionOpacityPercent / 100f);
        int strokeAlpha = Math.min(255, Math.round(fillAlpha * 1.30f));
        invoke(mapObject, "setFillColor", new Class<?>[]{int.class},
                (fillAlpha << 24) | directionRgb);
        invoke(mapObject, "setStrokeColor", new Class<?>[]{int.class},
                (strokeAlpha << 24) | directionRgb);
        invoke(mapObject, "setStrokeWidth", new Class<?>[]{float.class}, 0.8f);
        invoke(mapObject, "setGeodesic", new Class<?>[]{boolean.class}, false);
        invoke(mapObject, "setZIndex", new Class<?>[]{float.class}, zIndex);
        invoke(mapObject, "setVisible", new Class<?>[]{boolean.class}, true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addSign(Object target, CameraMarker camera) throws Exception {
        Class<?> pointClass = Class.forName("com.yandex.mapkit.geometry.Point");
        Object point = pointClass.getConstructor(double.class, double.class)
                .newInstance(camera.latitude, camera.longitude);
        Object placemark = invoke(target, "addPlacemark", new Class<?>[]{pointClass}, point);
        int displayDiameter = cameraDisplayDiameter();
        int textureDiameter = Math.max(displayDiameter, MIN_CAMERA_TEXTURE_DIAMETER_PX);
        float textureScale = displayDiameter / (float) textureDiameter;
        Bitmap bitmap = createCameraBitmap(camera, textureDiameter);
        int displayWidth = Math.max(1,
                (int) Math.ceil(bitmap.getWidth() * textureScale));
        int displayHeight = Math.max(1,
                (int) Math.ceil(bitmap.getHeight() * textureScale));
        Class<?> providerClass = Class.forName("com.yandex.runtime.image.ImageProvider");
        Object provider = providerClass.getMethod("fromBitmap", Bitmap.class)
                .invoke(null, bitmap);
        Class<?> styleClass = Class.forName("com.yandex.mapkit.map.IconStyle");
        Class<?> rotationClass = Class.forName("com.yandex.mapkit.map.RotationType");
        Object noRotation = Enum.valueOf((Class<? extends Enum>) rotationClass, "NO_ROTATION");
        Object style = styleClass.getConstructor().newInstance();
        MapOverlayPlacementCoordinator.Placement placement = reservePlacement(
                camera, displayWidth, displayHeight);
        invoke(style, "setAnchor", new Class<?>[]{PointF.class},
                new PointF(placement.anchorX, placement.anchorY));
        invoke(style, "setRotationType", new Class<?>[]{rotationClass}, noRotation);
        // MapKit receives an oversampled texture, then reduces it to the configured on-map size.
        // This retains glyph/vector detail instead of rasterising small speed text at 26-40 px.
        invoke(style, "setScale", new Class<?>[]{Float.class},
                Float.valueOf(textureScale));
        invoke(style, "setFlat", new Class<?>[]{Boolean.class}, Boolean.FALSE);
        invoke(style, "setVisible", new Class<?>[]{Boolean.class}, Boolean.TRUE);
        float sourceOffset = SOURCE_HUD_SPEED.equals(camera.source) ? 0.002f : 0.001f;
        invoke(style, "setZIndex", new Class<?>[]{Float.class},
                Float.valueOf(zIndex + sourceOffset));
        invoke(placemark, "setIcon", new Class<?>[]{providerClass, styleClass}, provider, style);
        invoke(placemark, "setVisible", new Class<?>[]{boolean.class}, true);
        iconBitmaps.add(bitmap);
        imageProviders.add(provider);
        cameraSigns.add(new CameraSign(camera, placemark, provider, style,
                displayWidth, displayHeight, placement));
    }

    private MapOverlayPlacementCoordinator.Placement reservePlacement(
            CameraMarker camera, int bitmapWidth, int bitmapHeight) {
        // A camera is a point object, not a balloon. Its sector apex and the visual centre of both
        // a speed-only and a composite sign must share the exact source coordinate on the route.
        // Other movable balloons still receive this centred footprint as a collision reservation.
        return placementCoordinator.reserveCentered(
                MapOverlayPlacementCoordinator.OWNER_CAMERAS, camera.id,
                camera.latitude, camera.longitude,
                bitmapWidth, bitmapHeight);
    }

    /**
     * One compact marker. A speed-only camera is one clean speed circle. When the same physical
     * event also controls lanes/crossroads/stopping, the exact stock 40dp control plate is joined
     * directly to that circle; no additional miniature camera badge is painted.
     */
    private int cameraDisplayDiameter() {
        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        float scale = scalePercent / 100f;
        return Math.max(26, Math.min(180, Math.round(40f * density * scale)));
    }

    private Bitmap createCameraBitmap(CameraMarker camera, int diameter) {
        float padding = Math.max(1f, diameter * .035f);
        String detailDrawableName = detailDrawableName(camera.controlTags);
        if (camera.speedLimit <= 0 && detailDrawableName == null) {
            detailDrawableName = "new_pin_alerts_camera_40";
        }
        Bitmap detail = detailDrawableName == null
                ? null : stockDrawableBitmap(detailDrawableName, diameter);
        boolean showSpeed = camera.speedLimit > 0;
        int overlap = showSpeed && detail != null
                ? Math.max(1, Math.round(diameter * .08f)) : 0;
        int contentWidth = (showSpeed ? diameter : 0)
                + (detail != null ? diameter : 0) - overlap;
        if (contentWidth <= 0) contentWidth = diameter;
        int bitmapWidth = Math.max(1, (int) Math.ceil(contentWidth + padding * 2f));
        int bitmapHeight = Math.max(1, (int) Math.ceil(diameter + padding * 2f));
        Bitmap bitmap = Bitmap.createBitmap(
                bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        float cy = bitmapHeight * .5f;
        float speedCx = padding + (detail == null ? 0f : diameter - overlap)
                + diameter * .5f;
        if (detail != null) canvas.drawBitmap(detail, padding, padding, paint);
        if (!showSpeed) return bitmap;
        float stroke = Math.max(2f, diameter * .085f);
        float radius = diameter * .5f - stroke * .5f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(speedCx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(STANDARD_SIGN_RED);
        canvas.drawCircle(speedCx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(STANDARD_SIGN_TEXT);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(diameter * (camera.speedLimit >= 100 ? .34f : .42f));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) * .5f;
        canvas.drawText(Integer.toString(camera.speedLimit),
                speedCx - diameter * .025f, baseline, paint);
        return bitmap;
    }

    private Bitmap stockDrawableBitmap(String name, int size) {
        try {
            int resource = context.getResources().getIdentifier(
                    name, "drawable", context.getPackageName());
            if (resource == 0) return null;
            Drawable drawable = context.getDrawable(resource);
            if (drawable == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static String detailDrawableName(List<String> tags) {
        if (tags.contains("LANE_CONTROL") || tags.contains("ROAD_MARKING_CONTROL")) {
            return "new_pin_alerts_lanecamera_40";
        }
        if (tags.contains("CROSS_ROAD_CONTROL") || tags.contains("TRAFFIC_CONTROL")) {
            return "new_pin_alerts_crossroad_camera_40";
        }
        if (tags.contains("NO_STOPPING_CONTROL")) {
            return "new_pin_alerts_camera_stop_40";
        }
        return null;
    }

    private void clearVisual() {
        placementCoordinator.clearOwner(MapOverlayPlacementCoordinator.OWNER_CAMERAS);
        if (sectorCollection != null) {
            try { invoke(sectorCollection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        if (signCollection != null) {
            try { invoke(signCollection, "clear", new Class<?>[0]); }
            catch (Throwable ignored) {}
        }
        iconBitmaps.clear();
        imageProviders.clear();
        cameraSigns.clear();
        renderedFingerprint = Long.MIN_VALUE;
    }

    private static long visualFingerprint(List<CameraMarker> values) {
        long result = 0x517cc1b727220a95L;
        int count = 0;
        for (CameraMarker value : values) {
            if (value == null || !value.hasMapPosition()) continue;
            if (count++ >= MAX_CAMERAS) break;
            result = mix(result, value.source.hashCode());
            result = mix(result, value.id.hashCode());
            result = mix(result, Math.round(value.latitude * 1_000_000d));
            result = mix(result, Math.round(value.longitude * 1_000_000d));
            result = mix(result, value.speedLimit);
            for (String tag : value.controlTags) result = mix(result, tag.hashCode());
            for (Double direction : value.directions) {
                result = mix(result, Math.round(direction.doubleValue() * 10d));
            }
        }
        return mix(result, count);
    }

    private static long mix(long value, long part) {
        return (value ^ part) * 0x100000001b3L;
    }

    /** Opacity is configured independently, therefore only the selected RGB reaches MapKit. */
    private static int opaqueRgb(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(value.trim()) & 0x00FFFFFF;
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }

    private static double[] destination(double latitude, double longitude,
                                        double bearingDegrees, double distanceMeters) {
        double angular = distanceMeters / EARTH_RADIUS_METERS;
        double bearing = Math.toRadians(bearingDegrees);
        double fromLatitude = Math.toRadians(latitude);
        double fromLongitude = Math.toRadians(longitude);
        double toLatitude = Math.asin(Math.sin(fromLatitude) * Math.cos(angular)
                + Math.cos(fromLatitude) * Math.sin(angular) * Math.cos(bearing));
        double toLongitude = fromLongitude + Math.atan2(
                Math.sin(bearing) * Math.sin(angular) * Math.cos(fromLatitude),
                Math.cos(angular) - Math.sin(fromLatitude) * Math.sin(toLatitude));
        return new double[]{Math.toDegrees(toLatitude), Math.toDegrees(toLongitude)};
    }

    private static double distanceMeters(double latitudeA, double longitudeA,
                                         double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta * .5d) * Math.sin(latitudeDelta * .5d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta * .5d) * Math.sin(longitudeDelta * .5d);
        return 2d * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private static double normalizedBearing(double value) {
        if (!Double.isFinite(value)) return Double.NaN;
        double normalized = value % 360d;
        return normalized < 0d ? normalized + 360d : normalized;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ReflectMethods.publicMethod(target.getClass(), name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static final class ExternalCamera {
        final String id;
        final double latitude;
        final double longitude;
        final int speedLimit;
        final List<String> controlTags;
        final List<Double> directions;

        ExternalCamera(String id, double latitude, double longitude, int speedLimit,
                       List<String> controlTags, List<Double> directions) {
            this.id = id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedLimit = speedLimit;
            this.controlTags = controlTags;
            this.directions = directions;
        }

        static ExternalCamera fromJson(JSONObject source) {
            if (source == null || !SOURCE_HUD_SPEED.equals(source.optString("source", ""))) {
                return null;
            }
            String id = source.optString("id", "");
            if (id.isEmpty() || id.length() > 96) return null;
            double latitude = source.optDouble("latitude", Double.NaN);
            double longitude = source.optDouble("longitude", Double.NaN);
            if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d
                    || !Double.isFinite(longitude) || longitude < -180d
                    || longitude > 180d) return null;
            int speed = CameraSpeedNormalizer.fromExternal(
                    source.optDouble("speedLimit", Double.NaN),
                    source.optString("speedUnit", "KPH"));
            ArrayList<String> tags = new ArrayList<>();
            JSONArray tagArray = source.optJSONArray("controlTags");
            if (tagArray != null) {
                for (int index = 0; index < tagArray.length() && tags.size() < 8; index++) {
                    String tag = tagArray.optString(index, "");
                    if (!tag.isEmpty() && tag.length() <= 48 && !tags.contains(tag)) {
                        tags.add(tag);
                    }
                }
            }
            ArrayList<Double> directions = new ArrayList<>(2);
            JSONArray directionArray = source.optJSONArray("directions");
            if (directionArray != null) {
                for (int index = 0; index < directionArray.length()
                        && directions.size() < 2; index++) {
                    double direction = normalizedBearing(
                            directionArray.optDouble(index, Double.NaN));
                    if (Double.isFinite(direction)) directions.add(direction);
                }
            }
            return new ExternalCamera(id, latitude, longitude, speed,
                    Collections.unmodifiableList(tags),
                    Collections.unmodifiableList(directions));
        }

        boolean hasMapPosition() {
            return Double.isFinite(latitude) && latitude >= -90d && latitude <= 90d
                    && Double.isFinite(longitude) && longitude >= -180d && longitude <= 180d;
        }
    }

    private static final class CameraMarker {
        final String source;
        final String id;
        final double latitude;
        final double longitude;
        final int speedLimit;
        final List<String> controlTags;
        final List<Double> directions;

        CameraMarker(String source, String id, double latitude, double longitude,
                     int speedLimit, List<String> controlTags, List<Double> directions) {
            this.source = source;
            this.id = id;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedLimit = speedLimit;
            this.controlTags = controlTags;
            this.directions = directions;
        }

        static CameraMarker fromYandex(NavigatorStatePublisher.CameraDirectionFrame value) {
            ArrayList<Double> directions = new ArrayList<>(2);
            if (value.inFace) directions.add(normalizedBearing(value.bearingDegrees + 180d));
            if (value.inBack) directions.add(normalizedBearing(value.bearingDegrees));
            return new CameraMarker(SOURCE_YANDEX, value.id, value.latitude, value.longitude,
                    value.speedLimitKmh, value.controlTags,
                    Collections.unmodifiableList(directions));
        }

        static CameraMarker fromExternal(ExternalCamera value) {
            return new CameraMarker(SOURCE_HUD_SPEED, value.id,
                    value.latitude, value.longitude, value.speedLimit,
                    value.controlTags, value.directions);
        }

        boolean hasMapPosition() {
            return Double.isFinite(latitude) && latitude >= -90d && latitude <= 90d
                    && Double.isFinite(longitude) && longitude >= -180d && longitude <= 180d;
        }

        static CameraMarker merge(CameraMarker primary, CameraMarker extra) {
            ArrayList<String> tags = new ArrayList<>(8);
            appendTags(tags, primary.controlTags);
            appendTags(tags, extra.controlTags);
            ArrayList<Double> directions = new ArrayList<>(4);
            appendDirections(directions, primary.directions);
            appendDirections(directions, extra.directions);
            int speed = primary.speedLimit > 0 ? primary.speedLimit : extra.speedLimit;
            return new CameraMarker(primary.source, primary.id,
                    primary.latitude, primary.longitude, speed,
                    Collections.unmodifiableList(tags),
                    Collections.unmodifiableList(directions));
        }

        private static void appendTags(ArrayList<String> target, List<String> source) {
            for (String value : source) {
                if (target.size() >= 8) return;
                if (value != null && !value.isEmpty() && !target.contains(value)) {
                    target.add(value);
                }
            }
        }

        private static void appendDirections(ArrayList<Double> target, List<Double> source) {
            for (Double value : source) {
                if (value == null || !Double.isFinite(value)) continue;
                double normalized = normalizedBearing(value.doubleValue());
                boolean duplicate = false;
                for (Double accepted : target) {
                    double delta = Math.abs(accepted.doubleValue() - normalized) % 360d;
                    if (Math.min(delta, 360d - delta) < 7d) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate && target.size() < 4) target.add(normalized);
            }
        }
    }

    private static final class CameraSign {
        final CameraMarker camera;
        final Object placemark;
        final Object provider;
        final Object style;
        final int bitmapWidth;
        final int bitmapHeight;
        MapOverlayPlacementCoordinator.Placement placement;

        CameraSign(CameraMarker camera, Object placemark, Object provider, Object style,
                   int bitmapWidth, int bitmapHeight,
                   MapOverlayPlacementCoordinator.Placement placement) {
            this.camera = camera;
            this.placemark = placemark;
            this.provider = provider;
            this.style = style;
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
            this.placement = placement;
        }
    }
}
