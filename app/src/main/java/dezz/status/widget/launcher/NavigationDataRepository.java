/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Extracts and persists navigation summary data published by Maps/Navigator applications. */
public final class NavigationDataRepository {
    public static final String ACTION_UPDATED = "ru.natro.statuswidget.NAVIGATION_UPDATED";

    public static final String PACKAGE_YANDEX_MAPS = "ru.yandex.yandexmaps";
    public static final String PACKAGE_YANDEX_NAVIGATOR = "ru.yandex.yandexnavi";
    public static final String PACKAGE_YANDEX_YANGO = "com.yandex.yango";
    public static final String PACKAGE_GOOGLE_MAPS = "com.google.android.apps.maps";

    // Exact extras currently published by Yandex Maps and the standalone Yandex Navigator.
    public static final String EXTRA_YANDEX_ARRIVAL = "ru.yandex.maps.arrival";
    public static final String EXTRA_YANDEX_TIME = "ru.yandex.maps.time";
    public static final String EXTRA_YANDEX_DISTANCE = "ru.yandex.maps.distance";
    public static final String EXTRA_YANDEX_NAVIGATION_INFO = "yandex_navigation_info";
    public static final String EXTRA_NAVIGATION_DATA = "navigation_data";

    // Broadcasts published by navigation-enabled Yandex builds/MConfig integrations.
    public static final String ACTION_YANDEX_ARRIVAL = "com.yandex.ARRIVAL";
    public static final String ACTION_YANDEX_TIME = "com.yandex.TIME";
    public static final String ACTION_YANDEX_DISTANCE = "com.yandex.DISTANCE";
    public static final String EXTRA_BROADCAST_ARRIVAL = "Arrival_text";
    public static final String EXTRA_BROADCAST_TIME = "Time_text";
    public static final String EXTRA_BROADCAST_DISTANCE = "Distance_text";
    // Rich navigation contract consumed by mHUD-compatible MConfig/Plus Monjaro publishers.
    public static final String ACTION_MONJARO_NAVIGATION_UPDATE =
            "plus.monjaro.NAVIGATION_UPDATE";
    public static final String ACTION_MONJARO_NAVIGATION_ENDED =
            "plus.monjaro.NAVIGATION_ENDED";
    public static final String ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE =
            "plus.monjaro.TRAFFIC_LIGHT_UPDATE";
    public static final String ACTION_MONJARO_LANE_SIGN_DISTANCE =
            "plus.monjaro.LANE_SIGN_DIST";
    public static final String ACTION_YANDEX_LANE_SIGN = "com.yandex.LANE_SIGN";
    public static final String ACTION_YANDEX_LANE_DISTANCE = "com.yandex.LANE_DIST";
    public static final String ACTION_YANDEX_LANES_BITMAP = "com.yandex.LANES_BITMAP";
    public static final String ACTION_YANDEX_LANES_BITMAP_CLEAR =
            "com.yandex.LANES_BITMAP_CLEAR";
    public static final String ACTION_YANDEX_JAM_IMAGE = "com.yandex.JAM_IMAGE";
    public static final String ACTION_YANDEX_JAM_IMAGE_CLEAR = "com.yandex.JAM_IMAGE_CLEAR";
    public static final String ACTION_MONJARO_RAINBOW_IMAGE = "plus.monjaro.RAINBOW_IMAGE";
    public static final String ACTION_MONJARO_RAINBOW_IMAGE_CLEAR =
            "plus.monjaro.RAINBOW_IMAGE_CLEAR";
    public static final String ACTION_DEBUG_NAVIGATION_UPDATE =
            "debug.monjaro.NAVIGATION_UPDATE";
    public static final String ACTION_DEBUG_NAVIGATION_ENDED =
            "debug.monjaro.NAVIGATION_ENDED";
    public static final String ACTION_DEBUG_TRAFFIC_LIGHT_UPDATE =
            "debug.monjaro.TRAFFIC_LIGHT_UPDATE";
    public static final String ACTION_DEBUG_RAINBOW_IMAGE = "debug.monjaro.RAINBOW_IMAGE";
    public static final String ACTION_DEBUG_RAINBOW_IMAGE_CLEAR =
            "debug.monjaro.RAINBOW_IMAGE_CLEAR";

    public static final String PRODUCT_NAVIGATOR = "navigator";
    public static final String PRODUCT_MAPS = "maps";
    public static final String PRODUCT_YANGO = "yango";
    public static final String PRODUCT_GOOGLE_MAPS = "google_maps";
    public static final String PRODUCT_UNKNOWN = "unknown";

    private static final String PREFS = "launcher_navigation";
    private static final String PREF_ARRIVAL = "arrival";
    private static final String PREF_DURATION = "duration";
    private static final String PREF_DISTANCE = "distance";
    private static final String PREF_UPDATED_AT = "updatedAt";
    private static final String PREF_ROUTE_ACTIVE = "routeActive";
    private static final String PREF_SOURCE_PACKAGE = "sourcePackage";
    private static final String PREF_SOURCE_KEY = "sourceKey";
    private static final String PREF_MANEUVER_TITLE = "maneuverTitle";
    private static final String PREF_MANEUVER_TEXT = "maneuverText";
    private static final String PREF_MANEUVER_SUBTEXT = "maneuverSubtext";
    private static final String PREF_SPEED_LIMIT = "speedLimit";
    private static final String PREF_MANEUVER_IMAGE_UPDATED_AT = "maneuverImageUpdatedAt";
    private static final String PREF_LANES = "lanes";
    private static final String PREF_LANE_DISTANCE = "laneDistance";
    private static final String PREF_LANE_DISTANCE_METERS = "laneDistanceMeters";
    private static final String PREF_LANE_LATITUDE = "laneLatitude";
    private static final String PREF_LANE_LONGITUDE = "laneLongitude";
    private static final String PREF_LANE_ALONG_ROUTE = "laneAlongRoute";
    private static final String PREF_LANE_RECORDS_JSON = "laneRecordsJson";
    private static final String PREF_LANE_RAW_LANES = "laneRawLanes";
    private static final String PREF_LANE_RAW_LATITUDES = "laneRawLatitudes";
    private static final String PREF_LANE_RAW_LONGITUDES = "laneRawLongitudes";
    private static final String PREF_LANE_RAW_DISTANCES = "laneRawDistances";
    private static final String PREF_LANE_RAW_ALONG_ROUTE = "laneRawAlongRoute";
    private static final String PREF_LANE_UPDATED_AT = "laneUpdatedAt";
    private static final String PREF_LANES_IMAGE_UPDATED_AT = "lanesImageUpdatedAt";
    private static final String PREF_JAM_IMAGE_UPDATED_AT = "jamImageUpdatedAt";
    private static final String PREF_RAINBOW_IMAGE_UPDATED_AT = "rainbowImageUpdatedAt";
    private static final String PREF_TRAFFIC_LIGHTS_JSON = "trafficLightsJson";
    private static final String PREF_TRAFFIC_COLOR = "trafficColor";
    private static final String PREF_TRAFFIC_COUNTDOWN = "trafficCountdown";
    private static final String PREF_TRAFFIC_ARROW = "trafficArrow";
    private static final String PREF_TRAFFIC_UPDATED_AT = "trafficUpdatedAt";
    private static final String PREF_BOOT_COUNT = "bootCount";
    private static final String PREF_BOOT_EPOCH = "bootEpoch";
    private static final long STALE_MS = NavigationRouteStatePolicy.ROUTE_STALE_MS;
    private static final long TRAFFIC_STALE_MS = 120_000L;
    private static final long LANE_STALE_MS = 120_000L;
    private static final long MANEUVER_IMAGE_STALE_MS = STALE_MS;
    private static final long LANES_IMAGE_STALE_MS = 120_000L;
    private static final long JAM_IMAGE_STALE_MS = 5_000L;
    private static final long RAINBOW_IMAGE_STALE_MS = 120_000L;
    private static final long TIMESTAMP_WRITE_INTERVAL_MS = 60_000L;
    private static final int MAX_REMOTE_VIEW_NODES = 1_000;
    private static final int MAX_TRAFFIC_LIGHTS = 8;
    private static final int UNKNOWN_BOOT_COUNT = -1;
    private static final long BOOT_EPOCH_TOLERANCE_MS = 5L * 60L * 1000L;

    /** One independently addressed traffic light supplied by the mHUD-compatible contract. */
    public static final class TrafficLight {
        @NonNull public final String id;
        @NonNull public final String source;
        @NonNull public final String color;
        @NonNull public final String countdown;
        @NonNull public final String arrow;
        public final int position;
        public final long updatedAt;
        @NonNull final String phaseOrigin;

        TrafficLight(String id, String source, String color, String countdown, String arrow,
                int position, long updatedAt, String phaseOrigin) {
            this.id = id;
            this.source = source;
            this.color = color;
            this.countdown = countdown;
            this.arrow = arrow;
            this.position = position;
            this.updatedAt = updatedAt;
            this.phaseOrigin = phaseOrigin;
        }
    }

    /** One raw semicolon-list lane junction retained alongside the selected visible record. */
    public static final class LaneRecord {
        public final int rawIndex;
        @NonNull public final String lanes;
        public final double latitude;
        public final double longitude;
        public final double distanceMeters;
        public final boolean alongRoute;

        LaneRecord(int rawIndex, String lanes, double latitude, double longitude,
                double distanceMeters, boolean alongRoute) {
            this.rawIndex = rawIndex;
            this.lanes = lanes;
            this.latitude = latitude;
            this.longitude = longitude;
            this.distanceMeters = distanceMeters;
            this.alongRoute = alongRoute;
        }
    }

    public static final class Snapshot {
        @NonNull public final String arrival;
        @NonNull public final String duration;
        @NonNull public final String distance;
        /** Package that most recently supplied route data; useful for opening the same product. */
        @NonNull public final String sourcePackage;
        /** Stable product id: navigator, maps, yango, google_maps or unknown. */
        @NonNull public final String sourceProduct;
        @NonNull public final String maneuverTitle;
        @NonNull public final String maneuverText;
        @NonNull public final String maneuverSubtext;
        @NonNull public final String speedLimit;
        @NonNull public final String trafficColor;
        @NonNull public final String trafficCountdown;
        @NonNull public final String trafficArrow;
        @NonNull public final List<TrafficLight> trafficLights;
        @NonNull public final String lanes;
        @NonNull public final String laneDistance;
        public final double laneDistanceMeters;
        public final double laneLatitude;
        public final double laneLongitude;
        public final boolean laneAlongRoute;
        @NonNull public final List<LaneRecord> laneRecords;
        @NonNull public final String laneRawLanes;
        @NonNull public final String laneRawLatitudes;
        @NonNull public final String laneRawLongitudes;
        @NonNull public final String laneRawDistances;
        @NonNull public final String laneRawAlongRoute;
        @Nullable public final Bitmap maneuverImage;
        @Nullable public final Bitmap lanesImage;
        @Nullable public final Bitmap jamImage;
        @Nullable public final Bitmap rainbowImage;
        public final boolean laneAvailable;
        public final boolean trafficAvailable;
        /** True only for a fresh main route in this Android boot. */
        public final boolean routeActive;
        /** Compatibility alias retained for existing navigation panel code. */
        public final boolean available;

        Snapshot(String arrival, String duration, String distance, String sourcePackage,
                String maneuverTitle, String maneuverText, String speedLimit,
                String trafficColor, String trafficCountdown, String trafficArrow,
                boolean trafficAvailable, String maneuverSubtext,
                List<TrafficLight> trafficLights, String lanes, String laneDistance,
                double laneDistanceMeters, double laneLatitude, double laneLongitude,
                boolean laneAlongRoute, List<LaneRecord> laneRecords, String laneRawLanes,
                String laneRawLatitudes, String laneRawLongitudes, String laneRawDistances,
                String laneRawAlongRoute, boolean laneAvailable, boolean routeActive,
                Bitmap maneuverImage,
                Bitmap lanesImage, Bitmap jamImage, Bitmap rainbowImage) {
            this.arrival = arrival;
            this.duration = duration;
            this.distance = distance;
            this.sourcePackage = sourcePackage;
            this.sourceProduct = productForPackage(sourcePackage);
            this.maneuverTitle = maneuverTitle;
            this.maneuverText = maneuverText;
            this.maneuverSubtext = maneuverSubtext;
            this.speedLimit = speedLimit;
            this.trafficColor = trafficColor;
            this.trafficCountdown = trafficCountdown;
            this.trafficArrow = trafficArrow;
            this.trafficLights = Collections.unmodifiableList(new ArrayList<>(trafficLights));
            this.lanes = lanes;
            this.laneDistance = laneDistance;
            this.laneDistanceMeters = laneDistanceMeters;
            this.laneLatitude = laneLatitude;
            this.laneLongitude = laneLongitude;
            this.laneAlongRoute = laneAlongRoute;
            this.laneRecords = Collections.unmodifiableList(new ArrayList<>(laneRecords));
            this.laneRawLanes = laneRawLanes;
            this.laneRawLatitudes = laneRawLatitudes;
            this.laneRawLongitudes = laneRawLongitudes;
            this.laneRawDistances = laneRawDistances;
            this.laneRawAlongRoute = laneRawAlongRoute;
            this.laneAvailable = laneAvailable;
            this.maneuverImage = maneuverImage;
            this.lanesImage = lanesImage;
            this.jamImage = jamImage;
            this.rainbowImage = rainbowImage;
            this.trafficAvailable = trafficAvailable;
            this.routeActive = routeActive;
            this.available = routeActive;
        }
    }

    /** Parsed notification candidate used to choose one route without persisting intermediates. */
    public static final class NotificationCandidate {
        @NonNull public final String packageName;
        @NonNull public final String sourceKey;
        public final long postTime;
        @NonNull private final NavigationDataParser.Parsed parsed;

        private NotificationCandidate(@NonNull String packageName, @NonNull String sourceKey,
                long postTime, @NonNull NavigationDataParser.Parsed parsed) {
            this.packageName = packageName;
            this.sourceKey = sourceKey;
            this.postTime = postTime;
            this.parsed = parsed;
        }

        public int fieldCount() {
            return parsed.fieldCount();
        }
    }

    private NavigationDataRepository() {}

    public static boolean isSupportedPackage(@Nullable String packageName) {
        return PACKAGE_YANDEX_MAPS.equals(packageName)
                || PACKAGE_YANDEX_NAVIGATOR.equals(packageName)
                || PACKAGE_YANDEX_YANGO.equals(packageName)
                || PACKAGE_GOOGLE_MAPS.equals(packageName);
    }

    public static boolean isYandexPackage(@Nullable String packageName) {
        return PACKAGE_YANDEX_MAPS.equals(packageName)
                || PACKAGE_YANDEX_NAVIGATOR.equals(packageName)
                || PACKAGE_YANDEX_YANGO.equals(packageName);
    }

    /** Higher value wins when multiple navigation notifications coexist. */
    public static int notificationPriority(@Nullable String packageName) {
        if (PACKAGE_YANDEX_NAVIGATOR.equals(packageName)) return 40;
        if (PACKAGE_YANDEX_MAPS.equals(packageName)) return 30;
        if (PACKAGE_YANDEX_YANGO.equals(packageName)) return 20;
        if (PACKAGE_GOOGLE_MAPS.equals(packageName)) return 10;
        return 0;
    }

    @NonNull
    public static String productForPackage(@Nullable String packageName) {
        if (PACKAGE_YANDEX_NAVIGATOR.equals(packageName)) return PRODUCT_NAVIGATOR;
        if (PACKAGE_YANDEX_MAPS.equals(packageName)) return PRODUCT_MAPS;
        if (PACKAGE_YANDEX_YANGO.equals(packageName)) return PRODUCT_YANGO;
        if (PACKAGE_GOOGLE_MAPS.equals(packageName)) return PRODUCT_GOOGLE_MAPS;
        return PRODUCT_UNKNOWN;
    }

    /** Reads one notification. Returns true when it contained at least one route value. */
    public static boolean update(@NonNull Context context, @NonNull StatusBarNotification sbn) {
        NotificationCandidate candidate = inspectNotification(context, sbn);
        if (candidate == null) return false;
        persistNotification(context, candidate);
        return true;
    }

    /**
     * Parses a notification without changing the visible route. The listener inspects every
     * active navigation notification first, then persists only the most complete candidate.
     */
    @Nullable
    public static NotificationCandidate inspectNotification(@NonNull Context context,
            @NonNull StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        if (!isSupportedPackage(packageName)) return null;

        Notification notification = sbn.getNotification();
        if (notification == null) return null;
        Bundle extras = notification.extras;
        List<String> values = new ArrayList<>();

        Object arrival = value(extras, EXTRA_YANDEX_ARRIVAL);
        Object duration = value(extras, EXTRA_YANDEX_TIME);
        Object distance = value(extras, EXTRA_YANDEX_DISTANCE);

        if (extras != null) {
            add(values, extras.getCharSequence(Notification.EXTRA_TITLE));
            add(values, extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
            add(values, extras.getCharSequence(Notification.EXTRA_TEXT));
            add(values, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            add(values, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            add(values, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            add(values, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) for (CharSequence line : lines) add(values, line);
            addBundleValues(values, extras, 0);
            addSpecificObject(values, value(extras, EXTRA_YANDEX_NAVIGATION_INFO));
            addSpecificObject(values, value(extras, EXTRA_NAVIGATION_DATA));
        }
        add(values, notification.tickerText);

        NavigationDataParser.Parsed exact = NavigationDataParser.parse(
                arrival, duration, distance, new ArrayList<>());
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(
                arrival, duration, distance, values);
        if (parsed.fieldCount() < 3) {
            // Some standalone Navigator versions put ETA/distance only into their custom
            // RemoteViews. Inflate those relatively expensive layouts only as a fallback.
            int[] visited = new int[] {0};
            addRemoteViews(context, values, notification.contentView, visited);
            addRemoteViews(context, values, notification.bigContentView, visited);
            addRemoteViews(context, values, notification.headsUpContentView, visited);
            parsed = NavigationDataParser.parse(arrival, duration, distance, values);
        }
        if (!parsed.hasData()) return null;
        // Exact Yandex extras are authoritative even when sent one at a time. Generic
        // notification text needs either two route fields or an explicit navigation category,
        // otherwise a promotional notice containing a lone distance could become a fake route.
        if (!exact.hasData() && parsed.fieldCount() < 2
                && !"navigation".equals(notification.category)) return null;
        return new NotificationCandidate(packageName, "notification:" + sbn.getKey(),
                sbn.getPostTime(), parsed);
    }

    /** Persists a candidate previously returned by {@link #inspectNotification}. */
    public static void persistNotification(@NonNull Context context,
            @NonNull NotificationCandidate candidate) {
        persist(context, candidate.parsed, candidate.packageName, candidate.sourceKey, true);
    }

    /** Updates route data harvested from a Yandex Accessibility tree. */
    public static boolean updateFromText(@NonNull Context context, @NonNull String packageName,
            @NonNull List<String> values) {
        if (!isYandexPackage(packageName)) return false;
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(values);
        if (!parsed.hasData()) return false;
        if (parsed.fieldCount() < 2 && !NavigationDataParser.hasRouteMarker(values)) return false;

        SharedPreferences prefs = preferences(context);
        String previousKey = prefs.getString(PREF_SOURCE_KEY, "");
        String previousPackage = prefs.getString(PREF_SOURCE_PACKAGE, "");
        long previousUpdatedAt = prefs.getLong(PREF_UPDATED_AT, 0L);
        boolean freshNotification = previousKey.startsWith("notification:")
                && previousUpdatedAt > 0
                && System.currentTimeMillis() - previousUpdatedAt <= STALE_MS;
        if (freshNotification) {
            // Notification/exact-extra transport has stronger provenance than a view tree.
            // Never mix another application's Accessibility values into that active route.
            if (!previousPackage.equals(packageName)) return false;
            if (previousPackage.equals(packageName)) {
                // Accessibility is fallback-only while an exact/notification source is alive:
                // fill absent fields but never replace its current values or ownership key.
                NavigationDataParser.Parsed missingOnly = new NavigationDataParser.Parsed(
                        prefs.getString(PREF_ARRIVAL, "").isEmpty() ? parsed.arrival : "",
                        prefs.getString(PREF_DURATION, "").isEmpty() ? parsed.duration : "",
                        prefs.getString(PREF_DISTANCE, "").isEmpty() ? parsed.distance : "");
                if (!missingOnly.hasData()) return true;
                persist(context, missingOnly, previousPackage, previousKey, true);
                return true;
            }
        }
        persist(context, parsed, packageName, "accessibility:" + packageName, true);
        return true;
    }

    /** Consumes scalar Yandex broadcasts and the richer mHUD-compatible Plus Monjaro contract. */
    public static boolean updateFromYandexBroadcast(@NonNull Context context,
            @NonNull Intent intent) {
        String action = intent.getAction();
        if (ACTION_MONJARO_NAVIGATION_ENDED.equals(action)
                || ACTION_DEBUG_NAVIGATION_ENDED.equals(action)) {
            clear(context);
            return true;
        }
        if (ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE.equals(action)
                || ACTION_DEBUG_TRAFFIC_LIGHT_UPDATE.equals(action)) {
            return updateTrafficLight(context, intent);
        }
        if (ACTION_MONJARO_NAVIGATION_UPDATE.equals(action)
                || ACTION_DEBUG_NAVIGATION_UPDATE.equals(action)) {
            return updateFromMonjaroNavigation(context, intent);
        }
        if (ACTION_MONJARO_LANE_SIGN_DISTANCE.equals(action)
                || ACTION_YANDEX_LANE_SIGN.equals(action)
                || ACTION_YANDEX_LANE_DISTANCE.equals(action)) {
            return updateLaneData(context, intent);
        }
        if (ACTION_YANDEX_LANES_BITMAP.equals(action)) {
            return updateGraphic(context, NavigationGraphicStore.LANES, intent,
                    PREF_LANES_IMAGE_UPDATED_AT, "lanes_bitmap", "image_bytes");
        }
        if (ACTION_YANDEX_LANES_BITMAP_CLEAR.equals(action)) {
            return clearGraphic(context, NavigationGraphicStore.LANES,
                    PREF_LANES_IMAGE_UPDATED_AT);
        }
        if (ACTION_YANDEX_JAM_IMAGE.equals(action)) {
            return updateGraphic(context, NavigationGraphicStore.JAM, intent,
                    PREF_JAM_IMAGE_UPDATED_AT, "jam_bitmap", "image_bytes");
        }
        if (ACTION_YANDEX_JAM_IMAGE_CLEAR.equals(action)) {
            return clearGraphic(context, NavigationGraphicStore.JAM,
                    PREF_JAM_IMAGE_UPDATED_AT);
        }
        if (ACTION_MONJARO_RAINBOW_IMAGE.equals(action)
                || ACTION_DEBUG_RAINBOW_IMAGE.equals(action)) {
            return updateGraphic(context, NavigationGraphicStore.RAINBOW, intent,
                    PREF_RAINBOW_IMAGE_UPDATED_AT, "rainbow_bitmap", "image_bytes");
        }
        if (ACTION_MONJARO_RAINBOW_IMAGE_CLEAR.equals(action)
                || ACTION_DEBUG_RAINBOW_IMAGE_CLEAR.equals(action)) {
            return clearGraphic(context, NavigationGraphicStore.RAINBOW,
                    PREF_RAINBOW_IMAGE_UPDATED_AT);
        }
        if (!ACTION_YANDEX_ARRIVAL.equals(action) && !ACTION_YANDEX_TIME.equals(action)
                && !ACTION_YANDEX_DISTANCE.equals(action)) return false;

        Bundle extras = intent.getExtras();
        Object arrival = first(value(extras, EXTRA_BROADCAST_ARRIVAL),
                value(extras, EXTRA_YANDEX_ARRIVAL), value(extras, "arrival_time"));
        Object duration = first(value(extras, EXTRA_BROADCAST_TIME),
                value(extras, EXTRA_YANDEX_TIME), value(extras, "remaining_time"));
        Object distance = first(value(extras, EXTRA_BROADCAST_DISTANCE),
                value(extras, EXTRA_YANDEX_DISTANCE), value(extras, "remaining_distance"));
        List<String> values = new ArrayList<>();
        addBundleValues(values, extras, 0);
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(
                arrival, duration, distance, values);
        if (!parsed.hasData()) return false;

        // These broadcasts are a Navigator compatibility API. If a prior exact package is known,
        // preserve it; otherwise prefer the standalone Navigator when the route panel is opened.
        String sourcePackage = preferences(context).getString(PREF_SOURCE_PACKAGE,
                PACKAGE_YANDEX_NAVIGATOR);
        if (!isYandexPackage(sourcePackage)) sourcePackage = PACKAGE_YANDEX_NAVIGATOR;
        // Three actions form one logical snapshot and may arrive in any order.
        persist(context, parsed, sourcePackage, "broadcast:yandex", true);
        return true;
    }

    /** Clears data only if the removed notification was the current source. */
    public static void removeIfSource(@NonNull Context context,
            @NonNull StatusBarNotification sbn) {
        if (!isSupportedPackage(sbn.getPackageName())) return;
        String expected = "notification:" + sbn.getKey();
        SharedPreferences prefs = preferences(context);
        if (expected.equals(prefs.getString(PREF_SOURCE_KEY, ""))) clear(context);
    }

    /** Removes a notification-sourced route after the owning notification disappeared. */
    public static void clearIfNotificationSourceMissing(@NonNull Context context,
            @NonNull Collection<String> activeNotificationKeys) {
        SharedPreferences prefs = preferences(context);
        String source = prefs.getString(PREF_SOURCE_KEY, "");
        if (!source.startsWith("notification:")) return;
        String key = source.substring("notification:".length());
        if (!activeNotificationKeys.contains(key)) clear(context);
    }

    /** Clears a still-present notification which no longer contains any route values. */
    public static void clearNotificationSourceIfNoRoute(@NonNull Context context) {
        if (preferences(context).getString(PREF_SOURCE_KEY, "")
                .startsWith("notification:")) clear(context);
    }

    /** True when current data came from a Yandex view tree which is no longer visible. */
    public static boolean isAccessibilitySourceMissing(@NonNull Context context,
            @NonNull Collection<String> visiblePackages) {
        SharedPreferences prefs = preferences(context);
        String source = prefs.getString(PREF_SOURCE_KEY, "");
        if (!source.startsWith("accessibility:")) return false;
        return !visiblePackages.contains(prefs.getString(PREF_SOURCE_PACKAGE, ""));
    }

    public static void clearIfAccessibilitySourceMissing(@NonNull Context context,
            @NonNull Collection<String> visiblePackages) {
        if (isAccessibilitySourceMissing(context, visiblePackages)) clear(context);
    }

    public static void clear(@NonNull Context context) {
        synchronized (NavigationDataRepository.class) {
            SharedPreferences prefs = preferences(context);
            boolean hadData = prefs.getLong(PREF_UPDATED_AT, 0L) > 0
                    || !prefs.getString(PREF_ARRIVAL, "").isEmpty()
                    || !prefs.getString(PREF_DURATION, "").isEmpty()
                    || !prefs.getString(PREF_DISTANCE, "").isEmpty()
                    || !prefs.getString(PREF_MANEUVER_TITLE, "").isEmpty()
                    || !prefs.getString(PREF_MANEUVER_TEXT, "").isEmpty()
                    || !prefs.getString(PREF_SPEED_LIMIT, "").isEmpty()
                    || prefs.getLong(PREF_TRAFFIC_UPDATED_AT, 0L) > 0
                    || prefs.getLong(PREF_LANE_UPDATED_AT, 0L) > 0
                    || prefs.getLong(PREF_MANEUVER_IMAGE_UPDATED_AT, 0L) > 0
                    || prefs.getLong(PREF_LANES_IMAGE_UPDATED_AT, 0L) > 0
                    || prefs.getLong(PREF_JAM_IMAGE_UPDATED_AT, 0L) > 0
                    || prefs.getLong(PREF_RAINBOW_IMAGE_UPDATED_AT, 0L) > 0;
            prefs.edit().remove(PREF_ARRIVAL).remove(PREF_DURATION).remove(PREF_DISTANCE)
                    .remove(PREF_UPDATED_AT).remove(PREF_ROUTE_ACTIVE)
                    .remove(PREF_SOURCE_PACKAGE)
                    .remove(PREF_SOURCE_KEY).remove(PREF_MANEUVER_TITLE)
                    .remove(PREF_MANEUVER_TEXT).remove(PREF_MANEUVER_SUBTEXT)
                    .remove(PREF_SPEED_LIMIT).remove(PREF_MANEUVER_IMAGE_UPDATED_AT)
                    .remove(PREF_LANES).remove(PREF_LANE_DISTANCE)
                    .remove(PREF_LANE_DISTANCE_METERS).remove(PREF_LANE_LATITUDE)
                    .remove(PREF_LANE_LONGITUDE).remove(PREF_LANE_ALONG_ROUTE)
                    .remove(PREF_LANE_RECORDS_JSON).remove(PREF_LANE_RAW_LANES)
                    .remove(PREF_LANE_RAW_LATITUDES).remove(PREF_LANE_RAW_LONGITUDES)
                    .remove(PREF_LANE_RAW_DISTANCES).remove(PREF_LANE_RAW_ALONG_ROUTE)
                    .remove(PREF_LANE_UPDATED_AT).remove(PREF_LANES_IMAGE_UPDATED_AT)
                    .remove(PREF_JAM_IMAGE_UPDATED_AT).remove(PREF_RAINBOW_IMAGE_UPDATED_AT)
                    .remove(PREF_TRAFFIC_LIGHTS_JSON)
                    .remove(PREF_TRAFFIC_COLOR).remove(PREF_TRAFFIC_COUNTDOWN)
                    .remove(PREF_TRAFFIC_ARROW).remove(PREF_TRAFFIC_UPDATED_AT).apply();
            NavigationGraphicStore.clear(context, NavigationGraphicStore.MANEUVER);
            NavigationGraphicStore.clear(context, NavigationGraphicStore.LANES);
            NavigationGraphicStore.clear(context, NavigationGraphicStore.JAM);
            NavigationGraphicStore.clear(context, NavigationGraphicStore.RAINBOW);
            if (hadData) notifyChanged(context);
        }
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences prefs = preferences(context);
        long updatedAt = prefs.getLong(PREF_UPDATED_AT, 0L);
        long now = System.currentTimeMillis();
        boolean hasMainRouteEvidence = hasMainRouteEvidence(prefs);
        boolean routeActive = NavigationRouteStatePolicy.isRouteActive(
                prefs.contains(PREF_ROUTE_ACTIVE), prefs.getBoolean(PREF_ROUTE_ACTIVE, false),
                hasMainRouteEvidence, updatedAt, now, true);
        List<TrafficLight> trafficLights = readTrafficLights(prefs, now);
        long trafficUpdatedAt = prefs.getLong(PREF_TRAFFIC_UPDATED_AT, 0L);
        boolean legacyTrafficAvailable = !prefs.contains(PREF_TRAFFIC_LIGHTS_JSON)
                && isFresh(trafficUpdatedAt, TRAFFIC_STALE_MS, now);
        boolean trafficAvailable = !trafficLights.isEmpty() || legacyTrafficAvailable;
        TrafficLight primary = trafficLights.isEmpty() ? null : trafficLights.get(0);
        String trafficColor = primary == null ? prefs.getString(PREF_TRAFFIC_COLOR, "")
                : primary.color;
        String trafficCountdown = primary == null
                ? prefs.getString(PREF_TRAFFIC_COUNTDOWN, "") : primary.countdown;
        String trafficArrow = primary == null ? prefs.getString(PREF_TRAFFIC_ARROW, "")
                : primary.arrow;
        long laneUpdatedAt = prefs.getLong(PREF_LANE_UPDATED_AT, 0L);
        boolean laneAvailable = isFresh(laneUpdatedAt, LANE_STALE_MS, now);
        Bitmap maneuverImage = loadFreshGraphic(context, prefs,
                PREF_MANEUVER_IMAGE_UPDATED_AT, MANEUVER_IMAGE_STALE_MS,
                NavigationGraphicStore.MANEUVER, now);
        Bitmap lanesImage = loadFreshGraphic(context, prefs, PREF_LANES_IMAGE_UPDATED_AT,
                LANES_IMAGE_STALE_MS, NavigationGraphicStore.LANES, now);
        Bitmap jamImage = loadFreshGraphic(context, prefs, PREF_JAM_IMAGE_UPDATED_AT,
                JAM_IMAGE_STALE_MS, NavigationGraphicStore.JAM, now);
        Bitmap rainbowImage = loadFreshGraphic(context, prefs, PREF_RAINBOW_IMAGE_UPDATED_AT,
                RAINBOW_IMAGE_STALE_MS, NavigationGraphicStore.RAINBOW, now);
        return new Snapshot(prefs.getString(PREF_ARRIVAL, ""),
                prefs.getString(PREF_DURATION, ""), prefs.getString(PREF_DISTANCE, ""),
                prefs.getString(PREF_SOURCE_PACKAGE, ""),
                prefs.getString(PREF_MANEUVER_TITLE, ""),
                prefs.getString(PREF_MANEUVER_TEXT, ""),
                prefs.getString(PREF_SPEED_LIMIT, ""),
                trafficColor, trafficCountdown, trafficArrow, trafficAvailable,
                prefs.getString(PREF_MANEUVER_SUBTEXT, ""), trafficLights,
                prefs.getString(PREF_LANES, ""), prefs.getString(PREF_LANE_DISTANCE, ""),
                doublePreference(prefs, PREF_LANE_DISTANCE_METERS, Double.NaN),
                doublePreference(prefs, PREF_LANE_LATITUDE, Double.NaN),
                doublePreference(prefs, PREF_LANE_LONGITUDE, Double.NaN),
                prefs.getBoolean(PREF_LANE_ALONG_ROUTE, false), readLaneRecords(prefs),
                prefs.getString(PREF_LANE_RAW_LANES, ""),
                prefs.getString(PREF_LANE_RAW_LATITUDES, ""),
                prefs.getString(PREF_LANE_RAW_LONGITUDES, ""),
                prefs.getString(PREF_LANE_RAW_DISTANCES, ""),
                prefs.getString(PREF_LANE_RAW_ALONG_ROUTE, ""), laneAvailable, routeActive,
                maneuverImage, lanesImage, jamImage, rainbowImage);
    }

    /** Main-route evidence used only to migrate snapshots written before routeActive existed. */
    private static boolean hasMainRouteEvidence(SharedPreferences prefs) {
        return !prefs.getString(PREF_ARRIVAL, "").isEmpty()
                || !prefs.getString(PREF_DURATION, "").isEmpty()
                || !prefs.getString(PREF_DISTANCE, "").isEmpty()
                || !prefs.getString(PREF_MANEUVER_TITLE, "").isEmpty()
                || !prefs.getString(PREF_MANEUVER_TEXT, "").isEmpty()
                || !prefs.getString(PREF_MANEUVER_SUBTEXT, "").isEmpty()
                || !prefs.getString(PREF_SPEED_LIMIT, "").isEmpty()
                || prefs.getLong(PREF_MANEUVER_IMAGE_UPDATED_AT, 0L) > 0L;
    }

    private static boolean updateFromMonjaroNavigation(@NonNull Context context,
            @NonNull Intent intent) {
        if (!intent.getBooleanExtra("route_active", true)) {
            clear(context);
            return true;
        }
        Bundle extras = intent.getExtras();
        String title = text(value(extras, "title"));
        String maneuver = text(value(extras, "text"));
        String subtext = text(value(extras, "subtext"));
        String speedLimit = text(value(extras, "speedlimit"));
        List<String> values = new ArrayList<>();
        if (!subtext.isEmpty()) values.add(subtext);
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(values);
        String sourcePackage = sourcePackageFromHint(text(value(extras, "source")));
        if (parsed.hasData()) {
            persist(context, parsed, sourcePackage, "broadcast:plus.monjaro", true);
        }
        boolean imageChanged = false;
        boolean imageStored = false;
        boolean hasImageFlag = intent.hasExtra("has_image");
        boolean hasImage = intent.getBooleanExtra("has_image", false);
        if (hasImage || intent.hasExtra("image_bytes")) {
            imageChanged = NavigationGraphicStore.saveFromIntent(context,
                    NavigationGraphicStore.MANEUVER, intent, "image_bytes", "maneuver_bitmap",
                    "image");
            imageStored = imageChanged;
        } else if (hasImageFlag) {
            imageChanged = clearGraphicWithoutNotify(context, NavigationGraphicStore.MANEUVER,
                    PREF_MANEUVER_IMAGE_UPDATED_AT);
        }
        if (!parsed.hasData() && title.isEmpty() && maneuver.isEmpty() && subtext.isEmpty()
                && speedLimit.isEmpty()) {
            boolean explicitActive = intent.hasExtra("route_active");
            if (imageStored) {
                long now = System.currentTimeMillis();
                preferences(context).edit().putLong(PREF_MANEUVER_IMAGE_UPDATED_AT, now)
                        .putLong(PREF_UPDATED_AT, now)
                        .putBoolean(PREF_ROUTE_ACTIVE, true).apply();
            } else if (explicitActive) {
                long now = System.currentTimeMillis();
                preferences(context).edit().putLong(PREF_UPDATED_AT, now)
                        .putBoolean(PREF_ROUTE_ACTIVE, true).apply();
            }
            if (imageChanged || explicitActive) notifyChanged(context);
            return imageChanged || explicitActive;
        }
        SharedPreferences prefs = preferences(context);
        long now = System.currentTimeMillis();
        boolean changed = !title.equals(prefs.getString(PREF_MANEUVER_TITLE, ""))
                || !maneuver.equals(prefs.getString(PREF_MANEUVER_TEXT, ""))
                || !subtext.equals(prefs.getString(PREF_MANEUVER_SUBTEXT, ""))
                || !speedLimit.equals(prefs.getString(PREF_SPEED_LIMIT, ""));
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_MANEUVER_TITLE, title)
                .putString(PREF_MANEUVER_TEXT, maneuver)
                .putString(PREF_MANEUVER_SUBTEXT, subtext)
                .putString(PREF_SPEED_LIMIT, speedLimit)
                .putString(PREF_SOURCE_PACKAGE, sourcePackage)
                .putString(PREF_SOURCE_KEY, "broadcast:plus.monjaro")
                .putBoolean(PREF_ROUTE_ACTIVE, true)
                .putLong(PREF_UPDATED_AT, now);
        if (imageStored) editor.putLong(PREF_MANEUVER_IMAGE_UPDATED_AT, now);
        editor.apply();
        if (changed || imageChanged || !parsed.hasData()) notifyChanged(context);
        return true;
    }

    private static boolean updateTrafficLight(@NonNull Context context,
            @NonNull Intent intent) {
        Bundle extras = intent.getExtras();
        String color = text(value(extras, "tl_color")).toUpperCase(Locale.ROOT);
        String countdown = text(value(extras, "tl_countdown"));
        String arrow = text(value(extras, "tl_arrow"));
        String id = text(value(extras, "tl_id"));
        String source = text(first(value(extras, "tl_source"), value(extras, "source")));
        int position = integer(value(extras, "tl_position"), -1);
        String key = !id.isEmpty() ? "id:" + id
                : position >= 0 ? "pos:" + position + "|src:" + source : "single";
        SharedPreferences prefs = preferences(context);
        LinkedHashMap<String, TrafficLight> values = trafficLightsMap(prefs);
        String before = encodeTrafficLights(values);
        long now = System.currentTimeMillis();
        if (color.isEmpty()) {
            if (id.isEmpty() && position < 0) {
                removeTrafficOwnedBy(values, source, now);
            } else {
                TrafficLight incumbent = freshTrafficForSlot(values.values(), id, position, now);
                if (incumbent == null
                        || NavigationSignalPolicy.sourceMayReplace(incumbent.source, source)) {
                    removeTrafficSlot(values, id, position, null);
                }
            }
        } else {
            if (!NavigationSignalPolicy.validTrafficColor(color)) return false;
            boolean routeActive = isFresh(prefs.getLong(PREF_UPDATED_AT, 0L), STALE_MS, now);
            if (NavigationSignalPolicy.isUiSource(source)) {
                // mHUD treats UI recognition as fallback only: it is not allowed to invent a
                // light outside navigation. A standalone UI publisher remains valid when there
                // is no stronger source for the same slot.
                if (!routeActive) return true;
            }

            TrafficLight incumbent = freshTrafficForSlot(values.values(), id, position, now);
            if (incumbent != null) {
                if (!NavigationSignalPolicy.sourceMayReplace(incumbent.source, source)) {
                    return true;
                }
                if (NavigationSignalPolicy.sourceRequiresTransitionValidation(
                        incumbent.source, source)
                        && !NavigationSignalPolicy.acceptsTransition(incumbent.color,
                        incumbent.countdown, incumbent.phaseOrigin, incumbent.updatedAt,
                        color, countdown, now)) return true;
            }

            String phaseOrigin = NavigationSignalPolicy.phaseOriginAfter(
                    incumbent == null ? "" : incumbent.color,
                    incumbent == null ? "" : incumbent.phaseOrigin, color);
            // A higher-provenance source owns the logical slot; remove a UI fallback so sorting
            // cannot make the visible primary light oscillate between duplicate records.
            if (incumbent != null) {
                removeTrafficSlot(values, id, position, null);
            }
            values.put(key, new TrafficLight(id, source, color, countdown, arrow, position,
                    now, phaseOrigin));
        }
        trimTrafficLights(values);
        boolean changed = !before.equals(encodeTrafficLights(values));
        List<TrafficLight> sorted = sortedTrafficLights(values.values());
        TrafficLight primary = sorted.isEmpty() ? null : sorted.get(0);
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_TRAFFIC_LIGHTS_JSON, encodeTrafficLights(values));
        if (primary == null) {
            editor.remove(PREF_TRAFFIC_COLOR).remove(PREF_TRAFFIC_COUNTDOWN)
                    .remove(PREF_TRAFFIC_ARROW).remove(PREF_TRAFFIC_UPDATED_AT);
        } else {
            editor.putString(PREF_TRAFFIC_COLOR, primary.color)
                    .putString(PREF_TRAFFIC_COUNTDOWN, primary.countdown)
                    .putString(PREF_TRAFFIC_ARROW, primary.arrow)
                    .putLong(PREF_TRAFFIC_UPDATED_AT, primary.updatedAt);
        }
        editor.apply();
        if (changed) notifyChanged(context);
        return true;
    }

    private static boolean updateLaneData(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        SharedPreferences prefs = preferences(context);
        long now = System.currentTimeMillis();
        boolean wasStale = !isFresh(prefs.getLong(PREF_LANE_UPDATED_AT, 0L),
                LANE_STALE_MS, now);
        String oldLanes = prefs.getString(PREF_LANES, "");
        String oldDistance = prefs.getString(PREF_LANE_DISTANCE, "");
        double oldMeters = doublePreference(prefs, PREF_LANE_DISTANCE_METERS, Double.NaN);
        double oldLatitude = doublePreference(prefs, PREF_LANE_LATITUDE, Double.NaN);
        double oldLongitude = doublePreference(prefs, PREF_LANE_LONGITUDE, Double.NaN);
        boolean oldAlongRoute = prefs.getBoolean(PREF_LANE_ALONG_ROUTE, false);
        String oldRecordsJson = prefs.getString(PREF_LANE_RECORDS_JSON, "");
        String oldRawLanes = prefs.getString(PREF_LANE_RAW_LANES, oldLanes);
        String oldRawLatitudes = prefs.getString(PREF_LANE_RAW_LATITUDES, "");
        String oldRawLongitudes = prefs.getString(PREF_LANE_RAW_LONGITUDES, "");
        String oldRawDistances = prefs.getString(PREF_LANE_RAW_DISTANCES, "");
        String oldRawAlongRoute = prefs.getString(PREF_LANE_RAW_ALONG_ROUTE, "");

        String lanes = oldLanes;
        String distance = oldDistance;
        double meters = oldMeters;
        double latitude = oldLatitude;
        double longitude = oldLongitude;
        boolean alongRoute = oldAlongRoute;
        String rawLanes = oldRawLanes;
        String rawLatitudes = oldRawLatitudes;
        String rawLongitudes = oldRawLongitudes;
        String rawDistances = oldRawDistances;
        String rawAlongRoute = oldRawAlongRoute;
        boolean supplied = false;

        if (ACTION_MONJARO_LANE_SIGN_DISTANCE.equals(action)) {
            String value = text(value(extras, "lanes"));
            if (!value.isEmpty()) { rawLanes = value; supplied = true; }
            String distanceText = text(value(extras, "dist_text"));
            if (!distanceText.isEmpty()) { distance = distanceText; supplied = true; }
            if (intent.hasExtra("dist_m")) {
                rawDistances = text(value(extras, "dist_m"));
                meters = number(value(extras, "dist_m"), Double.NaN); supplied = true;
            }
            if (intent.hasExtra("lat")) {
                rawLatitudes = text(value(extras, "lat")); supplied = true;
            }
            if (intent.hasExtra("lon")) {
                rawLongitudes = text(value(extras, "lon")); supplied = true;
            }
            if (intent.hasExtra("along_route")) {
                rawAlongRoute = text(value(extras, "along_route")); supplied = true;
            }
        } else if (ACTION_YANDEX_LANE_SIGN.equals(action)) {
            String value = text(value(extras, "lanes"));
            if (!value.isEmpty()) { rawLanes = value; supplied = true; }
            if (intent.hasExtra("lat")) {
                rawLatitudes = text(value(extras, "lat")); supplied = true;
            }
            if (intent.hasExtra("lon")) {
                rawLongitudes = text(value(extras, "lon")); supplied = true;
            }
            if (intent.hasExtra("dist_m")) {
                rawDistances = text(value(extras, "dist_m"));
                meters = number(value(extras, "dist_m"), Double.NaN); supplied = true;
            }
        } else if (ACTION_YANDEX_LANE_DISTANCE.equals(action)) {
            String rawDistance = text(value(extras, "dist"));
            String metrics = text(value(extras, "metrics"));
            String value = (rawDistance + metrics).trim();
            if (!value.isEmpty()) { distance = value; supplied = true; }
            if (intent.hasExtra("dist_m")) {
                rawDistances = text(value(extras, "dist_m"));
                meters = number(value(extras, "dist_m"), Double.NaN); supplied = true;
            }
        }
        if (!supplied) return false;

        NavigationSignalPolicy.LaneSelection selection =
                NavigationSignalPolicy.selectLanes(rawLanes, rawLatitudes, rawLongitudes,
                        rawDistances, rawAlongRoute, oldLanes, oldLatitude, oldLongitude, meters);
        if (selection.selected != null) {
            NavigationSignalPolicy.LaneRecord selected = selection.selected;
            lanes = selected.lanes;
            latitude = selected.latitude;
            longitude = selected.longitude;
            alongRoute = selected.alongRoute;
            if (Double.isFinite(selected.distanceMeters) && selected.distanceMeters >= 0.0d) {
                meters = selected.distanceMeters;
            }
        }
        String recordsJson = encodeLaneRecords(selection.records);

        boolean changed = wasStale || !lanes.equals(oldLanes) || !distance.equals(oldDistance)
                || !sameNumber(meters, oldMeters) || !sameNumber(latitude, oldLatitude)
                || !sameNumber(longitude, oldLongitude) || alongRoute != oldAlongRoute
                || !recordsJson.equals(oldRecordsJson)
                || !rawLanes.equals(oldRawLanes) || !rawLatitudes.equals(oldRawLatitudes)
                || !rawLongitudes.equals(oldRawLongitudes)
                || !rawDistances.equals(oldRawDistances)
                || !rawAlongRoute.equals(oldRawAlongRoute);
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_LANES, lanes)
                .putString(PREF_LANE_DISTANCE, distance)
                .putBoolean(PREF_LANE_ALONG_ROUTE, alongRoute)
                .putString(PREF_LANE_RECORDS_JSON, recordsJson)
                .putString(PREF_LANE_RAW_LANES, rawLanes)
                .putString(PREF_LANE_RAW_LATITUDES, rawLatitudes)
                .putString(PREF_LANE_RAW_LONGITUDES, rawLongitudes)
                .putString(PREF_LANE_RAW_DISTANCES, rawDistances)
                .putString(PREF_LANE_RAW_ALONG_ROUTE, rawAlongRoute)
                .putLong(PREF_LANE_UPDATED_AT, now);
        putDouble(editor, PREF_LANE_DISTANCE_METERS, meters);
        putDouble(editor, PREF_LANE_LATITUDE, latitude);
        putDouble(editor, PREF_LANE_LONGITUDE, longitude);
        editor.apply();
        if (changed) notifyChanged(context);
        return true;
    }

    private static boolean updateGraphic(Context context, String slot, Intent intent,
            String timestampPreference, String... keys) {
        boolean changed = NavigationGraphicStore.saveFromIntent(context, slot, intent, keys);
        if (!changed) return false;
        preferences(context).edit().putLong(timestampPreference,
                System.currentTimeMillis()).apply();
        notifyChanged(context);
        return true;
    }

    private static boolean clearGraphic(Context context, String slot,
            String timestampPreference) {
        boolean changed = clearGraphicWithoutNotify(context, slot, timestampPreference);
        if (changed) notifyChanged(context);
        return true;
    }

    private static boolean clearGraphicWithoutNotify(Context context, String slot,
            String timestampPreference) {
        SharedPreferences prefs = preferences(context);
        boolean changed = prefs.getLong(timestampPreference, 0L) > 0
                || NavigationGraphicStore.exists(context, slot);
        NavigationGraphicStore.clear(context, slot);
        prefs.edit().remove(timestampPreference).apply();
        return changed;
    }

    @Nullable
    private static Bitmap loadFreshGraphic(Context context, SharedPreferences prefs,
            String timestampPreference, long ttl, String slot, long now) {
        long updatedAt = prefs.getLong(timestampPreference, 0L);
        if (isFresh(updatedAt, ttl, now)) return NavigationGraphicStore.load(context, slot);
        // Expired transient graphics must release their cached ARGB bitmap. The bounded file is
        // retained only as the previous atomic-replace target and can never load without a fresh
        // timestamp; it is deleted by CLEAR/route end/next boot.
        NavigationGraphicStore.evict(context, slot);
        if (updatedAt > 0L) prefs.edit().remove(timestampPreference).apply();
        return null;
    }

    private static LinkedHashMap<String, TrafficLight> trafficLightsMap(
            SharedPreferences prefs) {
        LinkedHashMap<String, TrafficLight> result = new LinkedHashMap<>();
        String raw = prefs.getString(PREF_TRAFFIC_LIGHTS_JSON, "");
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            int count = Math.min(array.length(), MAX_TRAFFIC_LIGHTS * 2);
            for (int index = 0; index < count; index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                String key = item.optString("key", "");
                String color = item.optString("color", "");
                if (key.isEmpty() || color.isEmpty()) continue;
                result.put(key, new TrafficLight(item.optString("id", ""),
                        item.optString("source", ""), color,
                        item.optString("countdown", ""), item.optString("arrow", ""),
                        item.optInt("position", -1), item.optLong("updatedAt", 0L),
                        item.optString("phaseOrigin", "")));
            }
        } catch (JSONException ignored) {
            // A partially written/imported value must not break the HOME screen.
        }
        return result;
    }

    private static String encodeTrafficLights(Map<String, TrafficLight> values) {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, TrafficLight> entry : values.entrySet()) {
            TrafficLight value = entry.getValue();
            try {
                JSONObject item = new JSONObject();
                item.put("key", entry.getKey());
                item.put("id", value.id);
                item.put("source", value.source);
                item.put("color", value.color);
                item.put("countdown", value.countdown);
                item.put("arrow", value.arrow);
                item.put("position", value.position);
                item.put("updatedAt", value.updatedAt);
                item.put("phaseOrigin", value.phaseOrigin);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    private static void removeTrafficSlot(LinkedHashMap<String, TrafficLight> values,
            String id, int position, @Nullable String source) {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, TrafficLight> entry : values.entrySet()) {
            TrafficLight value = entry.getValue();
            if (!NavigationSignalPolicy.sameSlot(value.id, value.position, id, position)) {
                continue;
            }
            if (id.isEmpty() && source != null && !source.equalsIgnoreCase(value.source)) {
                continue;
            }
            remove.add(entry.getKey());
        }
        for (String key : remove) values.remove(key);
    }

    private static void removeTrafficOwnedBy(LinkedHashMap<String, TrafficLight> values,
            String source, long now) {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, TrafficLight> entry : values.entrySet()) {
            TrafficLight value = entry.getValue();
            if (!NavigationSignalPolicy.fresh(value.updatedAt, value.countdown, now)
                    || NavigationSignalPolicy.sourceMayReplace(value.source, source)) {
                remove.add(entry.getKey());
            }
        }
        for (String key : remove) values.remove(key);
    }

    @Nullable
    private static TrafficLight freshTrafficForSlot(Collection<TrafficLight> values,
            String id, int position, long now) {
        TrafficLight result = null;
        for (TrafficLight value : values) {
            if (!NavigationSignalPolicy.sameSlot(value.id, value.position, id, position)
                    || !NavigationSignalPolicy.fresh(value.updatedAt, value.countdown, now)) {
                continue;
            }
            if (result == null
                    || NavigationSignalPolicy.sourceRank(value.source)
                    > NavigationSignalPolicy.sourceRank(result.source)
                    || (NavigationSignalPolicy.sourceRank(value.source)
                    == NavigationSignalPolicy.sourceRank(result.source)
                    && value.updatedAt > result.updatedAt)) {
                result = value;
            }
        }
        return result;
    }

    private static String encodeLaneRecords(List<NavigationSignalPolicy.LaneRecord> values) {
        JSONArray array = new JSONArray();
        for (NavigationSignalPolicy.LaneRecord value : values) {
            try {
                JSONObject item = new JSONObject();
                item.put("rawIndex", value.rawIndex);
                item.put("lanes", value.lanes);
                if (Double.isFinite(value.latitude)) item.put("latitude", value.latitude);
                if (Double.isFinite(value.longitude)) item.put("longitude", value.longitude);
                if (Double.isFinite(value.distanceMeters)) {
                    item.put("distanceMeters", value.distanceMeters);
                }
                item.put("alongRoute", value.alongRoute);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    private static List<LaneRecord> readLaneRecords(SharedPreferences prefs) {
        List<LaneRecord> result = new ArrayList<>();
        String raw = prefs.getString(PREF_LANE_RECORDS_JSON, "");
        if (raw == null || raw.trim().isEmpty()) {
            // Migration path for a lane snapshot persisted by the first rich-navigation build.
            String lanes = prefs.getString(PREF_LANES, "");
            if (!lanes.isEmpty()) {
                result.add(new LaneRecord(0, lanes,
                        doublePreference(prefs, PREF_LANE_LATITUDE, Double.NaN),
                        doublePreference(prefs, PREF_LANE_LONGITUDE, Double.NaN),
                        doublePreference(prefs, PREF_LANE_DISTANCE_METERS, Double.NaN),
                        prefs.getBoolean(PREF_LANE_ALONG_ROUTE, false)));
            }
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            int count = Math.min(array.length(), 64);
            for (int index = 0; index < count; index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                String lanes = item.optString("lanes", "");
                if (lanes.isEmpty()) continue;
                result.add(new LaneRecord(item.optInt("rawIndex", index), lanes,
                        item.has("latitude") ? item.optDouble("latitude", Double.NaN)
                                : Double.NaN,
                        item.has("longitude") ? item.optDouble("longitude", Double.NaN)
                                : Double.NaN,
                        item.has("distanceMeters")
                                ? item.optDouble("distanceMeters", Double.NaN) : Double.NaN,
                        item.optBoolean("alongRoute", false)));
            }
        } catch (JSONException ignored) {
            // Raw parallel strings remain available even if imported JSON was malformed.
        }
        return result;
    }

    private static List<TrafficLight> readTrafficLights(SharedPreferences prefs, long now) {
        List<TrafficLight> values = new ArrayList<>();
        for (TrafficLight value : trafficLightsMap(prefs).values()) {
            if (NavigationSignalPolicy.fresh(value.updatedAt, value.countdown, now)) {
                values.add(value);
            }
        }
        values = sortedTrafficLights(values);
        if (values.size() > MAX_TRAFFIC_LIGHTS) {
            return new ArrayList<>(values.subList(0, MAX_TRAFFIC_LIGHTS));
        }
        return values;
    }

    private static List<TrafficLight> sortedTrafficLights(Collection<TrafficLight> values) {
        List<TrafficLight> result = new ArrayList<>(values);
        result.sort(Comparator
                .comparingInt((TrafficLight value) -> value.position < 0
                        ? Integer.MAX_VALUE : value.position)
                .thenComparing(value -> value.id)
                .thenComparing(value -> value.source));
        return result;
    }

    private static void trimTrafficLights(LinkedHashMap<String, TrafficLight> values) {
        while (values.size() > MAX_TRAFFIC_LIGHTS) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, TrafficLight> entry : values.entrySet()) {
                if (entry.getValue().updatedAt < oldest) {
                    oldest = entry.getValue().updatedAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) break;
            values.remove(oldestKey);
        }
    }

    private static boolean isFresh(long timestamp, long ttl, long now) {
        return timestamp > 0L && now >= timestamp && now - timestamp <= ttl;
    }

    private static boolean sameNumber(double first, double second) {
        return (Double.isNaN(first) && Double.isNaN(second))
                || Double.compare(first, second) == 0;
    }

    private static void putDouble(SharedPreferences.Editor editor, String key, double value) {
        if (Double.isFinite(value)) editor.putLong(key, Double.doubleToRawLongBits(value));
        else editor.remove(key);
    }

    private static double doublePreference(SharedPreferences prefs, String key,
            double fallback) {
        return prefs.contains(key) ? Double.longBitsToDouble(prefs.getLong(key, 0L)) : fallback;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return fallback;
        try { return Double.parseDouble(String.valueOf(value).trim().replace(',', '.')); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    @NonNull
    private static String sourcePackageFromHint(@NonNull String source) {
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("maps") || lower.contains("карты")
                ? PACKAGE_YANDEX_MAPS : PACKAGE_YANDEX_NAVIGATOR;
    }

    @NonNull
    private static String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void persist(Context context, NavigationDataParser.Parsed parsed,
            String sourcePackage, String sourceKey, boolean mergeMissing) {
        synchronized (NavigationDataRepository.class) {
            SharedPreferences prefs = preferences(context);
            String previousArrival = prefs.getString(PREF_ARRIVAL, "");
            String previousDuration = prefs.getString(PREF_DURATION, "");
            String previousDistance = prefs.getString(PREF_DISTANCE, "");
            String previousPackage = prefs.getString(PREF_SOURCE_PACKAGE, "");
            String previousSourceKey = prefs.getString(PREF_SOURCE_KEY, "");
            long previousUpdatedAt = prefs.getLong(PREF_UPDATED_AT, 0L);

            long now = System.currentTimeMillis();
            boolean sameSource = Objects.equals(sourcePackage, previousPackage);
            boolean sameLogicalSource = sameSource && Objects.equals(sourceKey, previousSourceKey);
            boolean previousFresh = previousUpdatedAt > 0
                    && now - previousUpdatedAt <= STALE_MS;
            boolean mayMerge = mergeMissing && sameLogicalSource && previousFresh;
            String arrival = mayMerge && parsed.arrival.isEmpty()
                    ? previousArrival : parsed.arrival;
            String duration = mayMerge && parsed.duration.isEmpty()
                    ? previousDuration : parsed.duration;
            String distance = mayMerge && parsed.distance.isEmpty()
                    ? previousDistance : parsed.distance;
            if (arrival.isEmpty() && duration.isEmpty() && distance.isEmpty()) return;

            boolean wasUnavailable = previousUpdatedAt <= 0
                    || now - previousUpdatedAt > STALE_MS;
            boolean changed = !Objects.equals(arrival, previousArrival)
                    || !Objects.equals(duration, previousDuration)
                    || !Objects.equals(distance, previousDistance)
                    || !Objects.equals(sourcePackage, previousPackage)
                    || wasUnavailable;
            // Accessibility is a fallback representation of the same active notification. Keep
            // notification ownership so removal of that notification clears a finished route.
            String sourceKeyToWrite = sourceKey;
            if (sameSource && sourceKey.startsWith("accessibility:")
                    && previousSourceKey.startsWith("notification:")) {
                sourceKeyToWrite = previousSourceKey;
            }
            boolean sourceKeyChanged = !Objects.equals(sourceKeyToWrite, previousSourceKey);
            boolean timestampDue = now - previousUpdatedAt >= TIMESTAMP_WRITE_INTERVAL_MS;
            if (!changed && !sourceKeyChanged && !timestampDue) return;

            prefs.edit().putString(PREF_ARRIVAL, arrival)
                    .putString(PREF_DURATION, duration)
                    .putString(PREF_DISTANCE, distance)
                    .putString(PREF_SOURCE_PACKAGE, sourcePackage)
                    .putString(PREF_SOURCE_KEY, sourceKeyToWrite)
                    .putBoolean(PREF_ROUTE_ACTIVE, true)
                    .putLong(PREF_UPDATED_AT, now)
                    .apply();
            if (changed) notifyChanged(context);
        }
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        SharedPreferences prefs = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureCurrentBootSession(context, prefs);
        return prefs;
    }

    /**
     * Route broadcasts are live telemetry, not durable user settings. Scope them to one Android
     * boot so a route/image cached shortly before power-off cannot appear after the next start.
     */
    private static void ensureCurrentBootSession(Context context, SharedPreferences prefs) {
        synchronized (NavigationDataRepository.class) {
            int currentBootCount = bootCount(context);
            long currentBootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            int storedBootCount = prefs.getInt(PREF_BOOT_COUNT, UNKNOWN_BOOT_COUNT);
            long storedBootEpoch = prefs.getLong(PREF_BOOT_EPOCH, Long.MIN_VALUE);
            if (sameBootSession(storedBootCount, storedBootEpoch,
                    currentBootCount, currentBootEpoch)) return;

            // Missing markers are also stale. This safely migrates builds which persisted route
            // data without a boot identity, before a partial new update gets a chance to merge it.
            prefs.edit().clear()
                    .putInt(PREF_BOOT_COUNT, currentBootCount)
                    .putLong(PREF_BOOT_EPOCH, currentBootEpoch)
                    .apply();
            NavigationGraphicStore.clear(context, NavigationGraphicStore.MANEUVER);
            NavigationGraphicStore.clear(context, NavigationGraphicStore.LANES);
            NavigationGraphicStore.clear(context, NavigationGraphicStore.JAM);
            NavigationGraphicStore.clear(context, NavigationGraphicStore.RAINBOW);
        }
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT);
        } catch (RuntimeException ignored) {
            return UNKNOWN_BOOT_COUNT;
        }
    }

    /** Pure helper kept package-visible so boot-scope behavior can be unit-tested on the JVM. */
    static boolean sameBootSession(int storedCount, long storedEpoch,
            int currentCount, long currentEpoch) {
        if (storedCount >= 0 && currentCount >= 0) return storedCount == currentCount;
        if (storedEpoch == Long.MIN_VALUE || currentEpoch == Long.MIN_VALUE) return false;
        return storedEpoch >= currentEpoch - BOOT_EPOCH_TOLERANCE_MS
                && storedEpoch <= currentEpoch + BOOT_EPOCH_TOLERANCE_MS;
    }

    private static void notifyChanged(Context context) {
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
    }

    @Nullable
    private static Object value(@Nullable Bundle extras, @NonNull String key) {
        return extras == null ? null : extras.get(key);
    }

    @Nullable
    private static Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    private static void addBundleValues(List<String> values, @Nullable Bundle bundle, int depth) {
        if (bundle == null || depth > 2) return;
        for (String key : bundle.keySet()) {
            Object item;
            try {
                item = bundle.get(key);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (item instanceof Bundle) {
                addBundleValues(values, (Bundle) item, depth + 1);
            } else if (item != null && item.getClass().isArray()) {
                int length = Math.min(Array.getLength(item), 100);
                for (int i = 0; i < length; i++) add(values, Array.get(item, i));
            } else if (item instanceof Iterable<?>) {
                int count = 0;
                for (Object value : (Iterable<?>) item) {
                    if (count++ >= 100) break;
                    add(values, value);
                }
            } else {
                add(values, item);
            }
        }
    }

    private static void addRemoteViews(Context context, List<String> values,
            @Nullable RemoteViews remoteViews, int[] visited) {
        if (remoteViews == null || visited[0] >= MAX_REMOTE_VIEW_NODES) return;
        try {
            // A parent is required by multiple Yandex custom notification layouts because their
            // root LayoutParams are resolved during inflation. The view is never attached.
            FrameLayout parent = new FrameLayout(context);
            View root = remoteViews.apply(context, parent);
            addViewText(values, root, 0, visited);
        } catch (RuntimeException | LinkageError ignored) {
            // RemoteViews belongs to another package and may reference unavailable resources.
            // Exact extras and Accessibility remain available as fallbacks.
        }
    }

    private static void addViewText(List<String> values, @Nullable View view, int depth,
            int[] visited) {
        if (view == null || depth > 40 || visited[0]++ >= MAX_REMOTE_VIEW_NODES
                || view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) add(values, ((TextView) view).getText());
        add(values, view.getContentDescription());
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int children = Math.min(group.getChildCount(), 200);
        for (int i = 0; i < children; i++) {
            addViewText(values, group.getChildAt(i), depth + 1, visited);
        }
    }

    private static void add(@NonNull List<String> values, Object value) {
        if (!(value instanceof String) && !(value instanceof CharSequence)
                && !(value instanceof Number)) return;
        String text = String.valueOf(value).trim();
        if (!text.isEmpty() && !values.contains(text)) values.add(text);
    }

    private static void addSpecificObject(@NonNull List<String> values, @Nullable Object value) {
        if (value != null) add(values, String.valueOf(value));
    }
}
