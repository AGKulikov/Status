/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Keeps the optional Yandex URL-signing credential outside the normal exported Status backup.
 * The private key is issued by Yandex specifically for Navigator URL signing.
 */
public final class YandexNavigatorAccessStore {
    private static final String FILE = "yandex_navigator_access";
    private static final String CLIENT = "client";
    private static final String PRIVATE_KEY = "private_key";

    private final SharedPreferences preferences;

    public YandexNavigatorAccessStore(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @NonNull public String clientId() {
        String value = preferences.getString(CLIENT, "");
        return value == null ? "" : value.trim();
    }

    @NonNull public String privateKeyPem() {
        String value = preferences.getString(PRIVATE_KEY, "");
        return value == null ? "" : value.trim();
    }

    public boolean isConfigured() {
        return !clientId().isEmpty() && !privateKeyPem().isEmpty();
    }

    public void save(@NonNull String clientId, @NonNull String privateKeyPem) {
        preferences.edit()
                .putString(CLIENT, clientId.trim())
                .putString(PRIVATE_KEY, privateKeyPem.trim())
                .apply();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }
}
