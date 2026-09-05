/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;

/**
 * Synchronous, signature-checked fallback for the latest Navigator configuration.
 *
 * <p>The normal Messenger bridge continues to push live changes.  KX11 can delay or kill that
 * background connection while MapActivity is starting, so the patched Navigator also reads one
 * current snapshot here on every resume.  This prevents the floating controller from silently
 * falling back to its unlocked, all-controls-visible defaults.</p>
 */
public final class NavigationConfigurationProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override public Bundle call(@NonNull String method, @Nullable String argument,
                                 @Nullable Bundle extras) {
        Context context = getContext();
        if (context == null
                || !NavigationBridgeContract.CONFIGURATION_PROVIDER_METHOD.equals(method)
                || !NavigationBridgeCallerVerifier.isTrustedNavigator(
                        context, Binder.getCallingUid())) {
            return new Bundle();
        }
        String raw = new Preferences(context).navigationIntegrationConfigJson.get();
        Bundle result = new Bundle();
        if (raw != null && raw.length() <= NavigationHudEndpointService.MAX_CONFIGURATION_CHARS
                && raw.indexOf('\u0000') < 0) {
            result.putString(NavigationBridgeContract.KEY_CONFIGURATION_JSON, raw);
        }
        return result;
    }

    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                                             @Nullable String selection,
                                             @Nullable String[] selectionArgs,
                                             @Nullable String sortOrder) {
        return null;
    }

    @Nullable @Override public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int delete(@NonNull Uri uri, @Nullable String selection,
                                @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
                                @Nullable String selection,
                                @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
