/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
    private static final String PREF_SOURCE_PACKAGE = "sourcePackage";
    private static final String PREF_SOURCE_KEY = "sourceKey";
    private static final String PREF_MANEUVER_TITLE = "maneuverTitle";
    private static final String PREF_MANEUVER_TEXT = "maneuverText";
    private static final String PREF_SPEED_LIMIT = "speedLimit";
    private static final String PREF_TRAFFIC_COLOR = "trafficColor";
    private static final String PREF_TRAFFIC_COUNTDOWN = "trafficCountdown";
    private static final String PREF_TRAFFIC_ARROW = "trafficArrow";
    private static final String PREF_TRAFFIC_UPDATED_AT = "trafficUpdatedAt";
    private static final long STALE_MS = 30L * 60L * 1000L;
    private static final long TRAFFIC_STALE_MS = 120_000L;
    private static final long TIMESTAMP_WRITE_INTERVAL_MS = 60_000L;
    private static final int MAX_REMOTE_VIEW_NODES = 1_000;

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
        @NonNull public final String speedLimit;
        @NonNull public final String trafficColor;
        @NonNull public final String trafficCountdown;
        @NonNull public final String trafficArrow;
        public final boolean trafficAvailable;
        public final boolean available;

        Snapshot(String arrival, String duration, String distance, String sourcePackage,
                boolean available, String maneuverTitle, String maneuverText, String speedLimit,
                String trafficColor, String trafficCountdown, String trafficArrow,
                boolean trafficAvailable) {
            this.arrival = arrival;
            this.duration = duration;
            this.distance = distance;
            this.sourcePackage = sourcePackage;
            this.sourceProduct = productForPackage(sourcePackage);
            this.maneuverTitle = maneuverTitle;
            this.maneuverText = maneuverText;
            this.speedLimit = speedLimit;
            this.trafficColor = trafficColor;
            this.trafficCountdown = trafficCountdown;
            this.trafficArrow = trafficArrow;
            this.trafficAvailable = trafficAvailable;
            this.available = available;
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
        if (ACTION_MONJARO_NAVIGATION_ENDED.equals(action)) {
            clear(context);
            return true;
        }
        if (ACTION_MONJARO_TRAFFIC_LIGHT_UPDATE.equals(action)) {
            return updateTrafficLight(context, intent);
        }
        if (ACTION_MONJARO_NAVIGATION_UPDATE.equals(action)) {
            return updateFromMonjaroNavigation(context, intent);
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
                    || prefs.getLong(PREF_TRAFFIC_UPDATED_AT, 0L) > 0;
            prefs.edit().remove(PREF_ARRIVAL).remove(PREF_DURATION).remove(PREF_DISTANCE)
                    .remove(PREF_UPDATED_AT).remove(PREF_SOURCE_PACKAGE)
                    .remove(PREF_SOURCE_KEY).remove(PREF_MANEUVER_TITLE)
                    .remove(PREF_MANEUVER_TEXT).remove(PREF_SPEED_LIMIT)
                    .remove(PREF_TRAFFIC_COLOR).remove(PREF_TRAFFIC_COUNTDOWN)
                    .remove(PREF_TRAFFIC_ARROW).remove(PREF_TRAFFIC_UPDATED_AT).apply();
            if (hadData) notifyChanged(context);
        }
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences prefs = preferences(context);
        long updatedAt = prefs.getLong(PREF_UPDATED_AT, 0L);
        boolean available = updatedAt > 0 && System.currentTimeMillis() - updatedAt <= STALE_MS;
        long trafficUpdatedAt = prefs.getLong(PREF_TRAFFIC_UPDATED_AT, 0L);
        boolean trafficAvailable = trafficUpdatedAt > 0
                && System.currentTimeMillis() - trafficUpdatedAt <= TRAFFIC_STALE_MS;
        return new Snapshot(prefs.getString(PREF_ARRIVAL, ""),
                prefs.getString(PREF_DURATION, ""), prefs.getString(PREF_DISTANCE, ""),
                prefs.getString(PREF_SOURCE_PACKAGE, ""), available,
                prefs.getString(PREF_MANEUVER_TITLE, ""),
                prefs.getString(PREF_MANEUVER_TEXT, ""),
                prefs.getString(PREF_SPEED_LIMIT, ""),
                prefs.getString(PREF_TRAFFIC_COLOR, ""),
                prefs.getString(PREF_TRAFFIC_COUNTDOWN, ""),
                prefs.getString(PREF_TRAFFIC_ARROW, ""), trafficAvailable);
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
        if (!parsed.hasData() && title.isEmpty() && maneuver.isEmpty() && speedLimit.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = preferences(context);
        long now = System.currentTimeMillis();
        boolean changed = !title.equals(prefs.getString(PREF_MANEUVER_TITLE, ""))
                || !maneuver.equals(prefs.getString(PREF_MANEUVER_TEXT, ""))
                || !speedLimit.equals(prefs.getString(PREF_SPEED_LIMIT, ""));
        prefs.edit().putString(PREF_MANEUVER_TITLE, title)
                .putString(PREF_MANEUVER_TEXT, maneuver)
                .putString(PREF_SPEED_LIMIT, speedLimit)
                .putString(PREF_SOURCE_PACKAGE, sourcePackage)
                .putString(PREF_SOURCE_KEY, "broadcast:plus.monjaro")
                .putLong(PREF_UPDATED_AT, now).apply();
        if (changed || !parsed.hasData()) notifyChanged(context);
        return true;
    }

    private static boolean updateTrafficLight(@NonNull Context context,
            @NonNull Intent intent) {
        Bundle extras = intent.getExtras();
        String color = text(value(extras, "tl_color")).toUpperCase(java.util.Locale.ROOT);
        String countdown = text(value(extras, "tl_countdown"));
        String arrow = text(value(extras, "tl_arrow"));
        if (!"RED".equals(color) && !"YELLOW".equals(color) && !"GREEN".equals(color)) {
            return false;
        }
        SharedPreferences prefs = preferences(context);
        boolean changed = !color.equals(prefs.getString(PREF_TRAFFIC_COLOR, ""))
                || !countdown.equals(prefs.getString(PREF_TRAFFIC_COUNTDOWN, ""))
                || !arrow.equals(prefs.getString(PREF_TRAFFIC_ARROW, ""));
        prefs.edit().putString(PREF_TRAFFIC_COLOR, color)
                .putString(PREF_TRAFFIC_COUNTDOWN, countdown)
                .putString(PREF_TRAFFIC_ARROW, arrow)
                .putLong(PREF_TRAFFIC_UPDATED_AT, System.currentTimeMillis()).apply();
        if (changed) notifyChanged(context);
        return true;
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
                    .putLong(PREF_UPDATED_AT, now)
                    .apply();
            if (changed) notifyChanged(context);
        }
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
