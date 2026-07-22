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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts and persists navigation summary data published by Yandex Maps/Navigator. */
public final class NavigationDataRepository {
    public static final String ACTION_UPDATED = "ru.natro.statuswidget.NAVIGATION_UPDATED";
    private static final String PREFS = "launcher_navigation";
    private static final long STALE_MS = 30L * 60L * 1000L;
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern DISTANCE = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(км|km|м|m)\\b");
    private static final Pattern DURATION = Pattern.compile(
            "(?i)(?:(\\d+)\\s*(?:ч|час(?:а|ов)?|h|hr|hours?)\\s*)?"
                    + "(?:(\\d+)\\s*(?:мин(?:ут(?:а|ы)?)?|min|minutes?))");

    public static final class Snapshot {
        @NonNull public final String arrival;
        @NonNull public final String duration;
        @NonNull public final String distance;
        public final boolean available;

        Snapshot(String arrival, String duration, String distance, boolean available) {
            this.arrival = arrival;
            this.duration = duration;
            this.distance = distance;
            this.available = available;
        }
    }

    private NavigationDataRepository() {}

    public static void update(@NonNull Context context, @NonNull StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        if (!"ru.yandex.yandexmaps".equals(packageName)
                && !"ru.yandex.yandexnavi".equals(packageName)
                && !"com.google.android.apps.maps".equals(packageName)) return;

        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        if (extras == null) return;
        List<String> parts = new ArrayList<>();
        add(parts, extras.getCharSequence(Notification.EXTRA_TITLE));
        add(parts, extras.getCharSequence(Notification.EXTRA_TEXT));
        add(parts, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        add(parts, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence line : lines) add(parts, line);
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof String || value instanceof CharSequence
                    || value instanceof Number) add(parts, String.valueOf(value));
        }
        String joined = String.join(" · ", parts).replace('\n', ' ').trim();
        if (joined.isEmpty()) return;

        String arrival = findArrival(joined);
        String distance = matchValue(DISTANCE, joined);
        String duration = findDuration(joined);
        if (arrival.isEmpty() && distance.isEmpty() && duration.isEmpty()) return;

        SharedPreferences prefs = preferences(context);
        prefs.edit()
                .putString("arrival", arrival)
                .putString("distance", distance)
                .putString("duration", duration)
                .putLong("updatedAt", System.currentTimeMillis())
                .apply();
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences prefs = preferences(context);
        long updatedAt = prefs.getLong("updatedAt", 0L);
        boolean available = updatedAt > 0 && System.currentTimeMillis() - updatedAt <= STALE_MS;
        return new Snapshot(prefs.getString("arrival", ""),
                prefs.getString("duration", ""), prefs.getString("distance", ""), available);
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void add(@NonNull List<String> values, Object value) {
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isEmpty() && !values.contains(text)) values.add(text);
    }

    @NonNull
    private static String findArrival(@NonNull String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        int marker = Math.max(lower.indexOf("прибыт"), lower.indexOf("arrival"));
        Matcher matcher = CLOCK.matcher(marker >= 0 ? input.substring(marker) : input);
        return matcher.find() ? matcher.group() : "";
    }

    @NonNull
    private static String findDuration(@NonNull String input) {
        Matcher matcher = DURATION.matcher(input);
        if (!matcher.find()) return "";
        String hours = matcher.group(1);
        String minutes = matcher.group(2);
        StringBuilder result = new StringBuilder();
        if (hours != null && !hours.isEmpty()) result.append(hours).append(" ч ");
        if (minutes != null && !minutes.isEmpty()) result.append(minutes).append(" мин");
        return result.toString().trim();
    }

    @NonNull
    private static String matchValue(@NonNull Pattern pattern, @NonNull String input) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) return "";
        return (matcher.group(1).replace(',', '.') + " " + matcher.group(2).toLowerCase(Locale.ROOT));
    }
}
