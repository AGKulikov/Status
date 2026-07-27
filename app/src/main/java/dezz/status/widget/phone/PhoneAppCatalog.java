/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dezz.status.widget.R;

/**
 * Privacy-friendly presentation catalog for iPhone notifications received through ANCS.
 *
 * <p>ANCS reports an iOS bundle identifier instead of an Android package. Known identifiers are
 * mapped to a stable display-name fallback and to a neutral semantic glyph. The glyphs deliberately
 * describe the kind of app (mail, chat, maps, music, and so on) rather than reproducing protected
 * brand artwork. Unknown identifiers fall back to the ANCS notification category.</p>
 */
public final class PhoneAppCatalog {
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_MAIL = "mail";
    private static final String KEY_CALENDAR = "calendar";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_CHAT = "chat";
    private static final String KEY_SOCIAL = "social";
    private static final String KEY_PHOTO = "photo";
    private static final String KEY_MUSIC = "music";
    private static final String KEY_MAPS = "maps";
    private static final String KEY_VIDEO = "video";
    private static final String KEY_WORK = "work";
    private static final String KEY_NOTIFICATION = "notification";
    private static final String KEY_MISSED_CALL = "missed_call";
    private static final String KEY_VOICEMAIL = "voicemail";
    private static final String KEY_NEWS = "news";
    private static final String KEY_HEALTH = "health";
    private static final String KEY_FINANCE = "finance";

    private static final int CATEGORY_INCOMING_CALL = 1;
    private static final int CATEGORY_MISSED_CALL = 2;
    private static final int CATEGORY_VOICEMAIL = 3;
    private static final int CATEGORY_SOCIAL = 4;
    private static final int CATEGORY_SCHEDULE = 5;
    private static final int CATEGORY_EMAIL = 6;
    private static final int CATEGORY_NEWS = 7;
    private static final int CATEGORY_HEALTH_AND_FITNESS = 8;
    private static final int CATEGORY_BUSINESS_AND_FINANCE = 9;
    private static final int CATEGORY_LOCATION = 10;
    private static final int CATEGORY_ENTERTAINMENT = 11;

    private static final Map<String, Entry> ENTRIES = createEntries();
    private static final List<FilterApp> FILTER_APPS = createFilterApps();

    private PhoneAppCatalog() {
    }

    /**
     * Returns a readable name when ANCS app attributes have not supplied the installed app name.
     */
    @NonNull
    public static String displayNameFallback(@Nullable String appIdentifier) {
        String normalized = normalize(appIdentifier);
        Entry entry = ENTRIES.get(normalized);
        if (entry != null) return entry.displayName;
        if (normalized.isEmpty()) return "Приложение iPhone";

        int separator = normalized.lastIndexOf('.');
        String tail = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (tail.isEmpty()) return "Приложение iPhone";
        return Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
    }

    /** Returns a scalable monochrome drawable for a known app or its ANCS category. */
    @DrawableRes
    public static int iconResource(@Nullable String appIdentifier, int categoryId) {
        switch (iconKey(appIdentifier, categoryId)) {
            case KEY_MESSAGES:
                return R.drawable.ic_phone_app_messages;
            case KEY_MAIL:
                return R.drawable.ic_phone_app_mail;
            case KEY_CALENDAR:
                return R.drawable.ic_phone_app_calendar;
            case KEY_PHONE:
                return R.drawable.ic_phone_app_phone;
            case KEY_CHAT:
                return R.drawable.ic_phone_app_chat;
            case KEY_SOCIAL:
                return R.drawable.ic_phone_app_social;
            case KEY_PHOTO:
                return R.drawable.ic_phone_app_photo;
            case KEY_MUSIC:
                return R.drawable.ic_phone_app_music;
            case KEY_MAPS:
                return R.drawable.ic_phone_app_maps;
            case KEY_VIDEO:
                return R.drawable.ic_phone_app_video;
            case KEY_WORK:
                return R.drawable.ic_phone_app_work;
            case KEY_MISSED_CALL:
                return R.drawable.ic_phone_app_missed_call;
            case KEY_VOICEMAIL:
                return R.drawable.ic_phone_app_voicemail;
            case KEY_NEWS:
                return R.drawable.ic_phone_app_news;
            case KEY_HEALTH:
                return R.drawable.ic_phone_app_health;
            case KEY_FINANCE:
                return R.drawable.ic_phone_app_finance;
            default:
                return R.drawable.ic_phone_app_notification;
        }
    }

    /**
     * Returns the stable semantic key used by the notification UI and persistence layer.
     */
    @NonNull
    public static String iconKey(@Nullable String appIdentifier, int categoryId) {
        Entry entry = ENTRIES.get(normalize(appIdentifier));
        return entry == null ? categoryIconKey(categoryId) : entry.iconKey;
    }

    /**
     * Stable application key used by notification allow/deny settings. Every known alias of the
     * same iOS application resolves to one key; an unknown app keeps its exact normalized bundle
     * identifier so it can still be selected after being observed.
     */
    @NonNull
    public static String filterKey(@Nullable String appIdentifier) {
        String normalized = normalize(appIdentifier);
        Entry entry = ENTRIES.get(normalized);
        return entry == null ? normalized : entry.filterKey;
    }

    /** Known applications shown before dynamically observed bundle identifiers in settings. */
    @NonNull
    public static List<FilterApp> filterApps() {
        return FILTER_APPS;
    }

    @NonNull
    private static String categoryIconKey(int categoryId) {
        switch (categoryId) {
            case CATEGORY_INCOMING_CALL:
                return KEY_PHONE;
            case CATEGORY_MISSED_CALL:
                return KEY_MISSED_CALL;
            case CATEGORY_VOICEMAIL:
                return KEY_VOICEMAIL;
            case CATEGORY_SOCIAL:
                return KEY_SOCIAL;
            case CATEGORY_SCHEDULE:
                return KEY_CALENDAR;
            case CATEGORY_EMAIL:
                return KEY_MAIL;
            case CATEGORY_NEWS:
                return KEY_NEWS;
            case CATEGORY_HEALTH_AND_FITNESS:
                return KEY_HEALTH;
            case CATEGORY_BUSINESS_AND_FINANCE:
                return KEY_FINANCE;
            case CATEGORY_LOCATION:
                return KEY_MAPS;
            case CATEGORY_ENTERTAINMENT:
                return KEY_VIDEO;
            default:
                return KEY_NOTIFICATION;
        }
    }

    @NonNull
    private static Map<String, Entry> createEntries() {
        Map<String, Entry> entries = new LinkedHashMap<>();

        register(entries, "Сообщения", KEY_MESSAGES,
                "com.apple.mobilesms",
                "com.apple.messages");
        register(entries, "Почта", KEY_MAIL,
                "com.apple.mobilemail");
        register(entries, "Календарь", KEY_CALENDAR,
                "com.apple.mobilecal",
                "com.apple.calendar");
        register(entries, "Телефон", KEY_PHONE,
                "com.apple.mobilephone");
        register(entries, "FaceTime", KEY_PHONE,
                "com.apple.facetime");

        register(entries, "WhatsApp", KEY_CHAT,
                "net.whatsapp.whatsapp");
        register(entries, "Telegram", KEY_CHAT,
                "ph.telegra.telegraph",
                "org.telegram.telegram");
        register(entries, "Signal", KEY_CHAT,
                "org.whispersystems.signal");
        register(entries, "Viber", KEY_CHAT,
                "com.viber");
        register(entries, "ВКонтакте", KEY_SOCIAL,
                "com.vk.vkclient",
                "com.vk.vk");
        register(entries, "Instagram", KEY_PHOTO,
                "com.burbn.instagram");
        register(entries, "Facebook", KEY_SOCIAL,
                "com.facebook.facebook");
        register(entries, "Messenger", KEY_CHAT,
                "com.facebook.messenger");

        register(entries, "Gmail", KEY_MAIL,
                "com.google.gmail");
        register(entries, "Outlook", KEY_MAIL,
                "com.microsoft.office.outlook");
        register(entries, "Яндекс Почта", KEY_MAIL,
                "ru.yandex.mobile.mail",
                "com.yandex.mobile.mail");
        register(entries, "Яндекс Музыка", KEY_MUSIC,
                "ru.yandex.mobile.music",
                "com.yandex.mobile.music");
        register(entries, "Яндекс Карты", KEY_MAPS,
                "ru.yandex.mobile.maps",
                "com.yandex.mobile.maps");
        register(entries, "Google Карты", KEY_MAPS,
                "com.google.maps");
        register(entries, "YouTube", KEY_VIDEO,
                "com.google.ios.youtube");
        register(entries, "TikTok", KEY_VIDEO,
                "com.zhiliaoapp.musically",
                "com.ss.iphone.ugc.aweme");

        register(entries, "Slack", KEY_WORK,
                "com.tinyspeck.chatlyio");
        register(entries, "Microsoft Teams", KEY_WORK,
                "com.microsoft.skype.teams",
                "com.microsoft.teams");
        register(entries, "Discord", KEY_CHAT,
                "com.hammerandchisel.discord");

        return Collections.unmodifiableMap(entries);
    }

    @NonNull
    private static List<FilterApp> createFilterApps() {
        LinkedHashMap<String, FilterApp> result = new LinkedHashMap<>();
        for (Entry entry : ENTRIES.values()) {
            result.put(entry.filterKey, new FilterApp(entry.filterKey, entry.displayName));
        }
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }

    private static void register(@NonNull Map<String, Entry> entries,
                                 @NonNull String displayName,
                                 @NonNull String iconKey,
                                 @NonNull String... identifiers) {
        if (identifiers.length == 0) return;
        Entry entry = new Entry(displayName, iconKey, normalize(identifiers[0]));
        for (String identifier : identifiers) entries.put(normalize(identifier), entry);
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        @NonNull final String displayName;
        @NonNull final String iconKey;
        @NonNull final String filterKey;

        Entry(@NonNull String displayName, @NonNull String iconKey,
              @NonNull String filterKey) {
            this.displayName = displayName;
            this.iconKey = iconKey;
            this.filterKey = filterKey;
        }
    }

    public static final class FilterApp {
        @NonNull public final String key;
        @NonNull public final String label;

        private FilterApp(@NonNull String key, @NonNull String label) {
            this.key = key;
            this.label = label;
        }
    }
}
