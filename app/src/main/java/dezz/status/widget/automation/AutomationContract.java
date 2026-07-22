/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * Copyright © 2026 Status Widget HA contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.automation;

import android.content.Intent;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/** Public, versioned protocol shared by Broadcast and MQTT inputs. */
public final class AutomationContract {
    private AutomationContract() {}

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_PAYLOAD_CHARS = 65_536;

    public static final String ACTION_UPDATE = "ru.natro.statuswidget.HA_UPDATE";
    public static final String ACTION_REFRESH = "ru.natro.statuswidget.HA_REFRESH";
    public static final String ACTION_CLEAR = "ru.natro.statuswidget.HA_CLEAR";
    public static final String ACTION_VISIBILITY = "ru.natro.statuswidget.HA_VISIBILITY";
    public static final String ACTION_BUILTIN_VISIBILITY =
            "ru.natro.statuswidget.HA_BUILTIN_VISIBILITY";
    public static final String ACTION_POPUP_UPDATE = "ru.natro.statuswidget.HA_POPUP_UPDATE";
    public static final String ACTION_POPUP_CLEAR = "ru.natro.statuswidget.HA_POPUP_CLEAR";
    public static final String ACTION_POPUP_VISIBILITY =
            "ru.natro.statuswidget.HA_POPUP_VISIBILITY";
    public static final String ACTION_SYNC_REQUEST = "ru.natro.statuswidget.HA_SYNC_REQUEST";
    public static final String ACTION_SYNC_RESPONSE = "ru.natro.statuswidget.HA_SYNC_RESPONSE";

    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_SCOPE = "scope";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_BRICK_ID = "brick_id"; // legacy HA automation alias
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_COLOR = "color";
    public static final String EXTRA_VISIBLE = "visible";
    public static final String EXTRA_UPDATED_AT = "updated_at";
    public static final String EXTRA_EXPIRES_AT = "expires_at";
    public static final String EXTRA_REPLY_PACKAGE = "reply_package";

    public static final String SCOPE_MAIN = "main";
    public static final String SCOPE_BUILTIN = "builtin";
    public static final String SCOPE_POPUP = "popup";
    public static final String SCOPE_OVERLAY = "overlay";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    @NonNull
    public static String requireSafeId(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid automation id: " + raw);
        }
        return id;
    }

    @NonNull
    public static String normalizeScope(String raw) {
        String scope = raw == null ? SCOPE_MAIN : raw.trim().toLowerCase(Locale.ROOT);
        switch (scope) {
            case SCOPE_MAIN:
            case SCOPE_BUILTIN:
            case SCOPE_POPUP:
            case SCOPE_OVERLAY:
                return scope;
            default:
                throw new IllegalArgumentException("Invalid automation scope: " + raw);
        }
    }

    /**
     * Converts both the new JSON form and the original Companion intent extras into one payload.
     * Explicit extras override identically named JSON fields, which makes manual testing simple.
     */
    @NonNull
    public static IncomingUpdate fromIntent(@NonNull Intent intent) throws JSONException {
        JSONObject payload = new JSONObject();
        String json = intent.getStringExtra(EXTRA_PAYLOAD);
        if (json != null && !json.trim().isEmpty()) {
            if (json.length() > MAX_PAYLOAD_CHARS) {
                throw new IllegalArgumentException("Automation payload is too large");
            }
            payload = new JSONObject(json);
        }

        String id = firstNonBlank(intent.getStringExtra(EXTRA_ID),
                intent.getStringExtra(EXTRA_BRICK_ID), payload.optString(EXTRA_ID, null),
                payload.optString(EXTRA_BRICK_ID, null));
        String scope = firstNonBlank(intent.getStringExtra(EXTRA_SCOPE),
                payload.optString(EXTRA_SCOPE, null), SCOPE_MAIN);

        copyStringExtra(intent, payload, EXTRA_TEXT);
        copyStringExtra(intent, payload, EXTRA_COLOR);
        if (intent.hasExtra(EXTRA_VISIBLE)) {
            Object value = intent.getExtras() == null ? null : intent.getExtras().get(EXTRA_VISIBLE);
            payload.put(EXTRA_VISIBLE, parseBoolean(value));
        }
        copyLongExtra(intent, payload, EXTRA_UPDATED_AT);
        copyLongExtra(intent, payload, EXTRA_EXPIRES_AT);

        String action = intent.getAction();
        if (ACTION_CLEAR.equals(action) || ACTION_POPUP_CLEAR.equals(action)) {
            payload.put("clear", true);
        }
        if (ACTION_BUILTIN_VISIBILITY.equals(action)) scope = SCOPE_BUILTIN;
        if (ACTION_POPUP_UPDATE.equals(action) || ACTION_POPUP_CLEAR.equals(action)) {
            scope = SCOPE_POPUP;
        }
        if (ACTION_POPUP_VISIBILITY.equals(action)) {
            scope = SCOPE_OVERLAY;
            if (id == null || id.trim().isEmpty()) id = "popup";
        }

        return new IncomingUpdate(normalizeScope(scope), requireSafeId(id), payload);
    }

    private static void copyStringExtra(Intent intent, JSONObject out, String name)
            throws JSONException {
        if (intent.hasExtra(name)) out.put(name, intent.getStringExtra(name));
    }

    private static void copyLongExtra(Intent intent, JSONObject out, String name)
            throws JSONException {
        if (!intent.hasExtra(name)) return;
        Object raw = intent.getExtras() == null ? null : intent.getExtras().get(name);
        if (raw instanceof Number) {
            out.put(name, ((Number) raw).longValue());
        } else if (raw != null) {
            out.put(name, Long.parseLong(String.valueOf(raw)));
        }
    }

    public static boolean parseBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value == null) return false;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "1".equals(text) || "true".equals(text) || "on".equals(text)
                || "show".equals(text) || "visible".equals(text) || "yes".equals(text);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
        }
        return "";
    }

    public static final class IncomingUpdate {
        public final String scope;
        public final String id;
        public final JSONObject payload;

        IncomingUpdate(String scope, String id, JSONObject payload) {
            this.scope = scope;
            this.id = id;
            this.payload = payload;
        }
    }
}
