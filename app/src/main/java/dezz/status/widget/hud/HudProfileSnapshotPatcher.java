/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Safely changes the legacy ECARX HUD AR flag inside a complete user-profile snapshot.
 *
 * <p>The vendor {@code IUserProfile.applyUserProfileData()} implementation does not patch an
 * existing profile. It constructs a fresh {@code Profileclouddata} protobuf and leaves every
 * field missing from the supplied JSON at zero. Consequently a one-key object would silently
 * erase unrelated seat, climate, lighting and vehicle preferences. This helper rejects obviously
 * incomplete/not-yet-loaded snapshots and changes exactly one value in the complete object.</p>
 */
public final class HudProfileSnapshotPatcher {
    /** {@code IHUD.SETTING_FUNC_HUD_AR_ENGINE}; kept vendor-free for the main source set. */
    public static final String HUD_AR_PROFILE_KEY = "654443008";

    /**
     * The inspected ECARX SDK exposes 73 profile annotations. Keep a conservative lower bound so
     * a future compatible SDK may add/remove a few entries without allowing a tiny partial object.
     */
    static final int ABSOLUTE_MINIMUM_FIELD_COUNT = 32;

    private HudProfileSnapshotPatcher() {
    }

    /**
     * Return a full profile JSON with only the AR value replaced.
     *
     * @param completeJson snapshot returned by {@code IProfile.toJOSNString()}
     * @param expectedFunctionCount number of distinct functions reported by
     *                              {@code IProfile.getContainsProfileFuncIds()}
     * @throws JSONException when the JSON cannot be decoded
     * @throws IllegalArgumentException when the snapshot is partial, non-scalar or appears to be
     *                                  the SDK's all-zero placeholder produced before PA data loads
     */
    @NonNull
    public static String patchHudAr(@NonNull String completeJson, boolean enabled,
                                    int expectedFunctionCount) throws JSONException {
        JSONObject snapshot = new JSONObject(completeJson);
        int minimum = Math.max(ABSOLUTE_MINIMUM_FIELD_COUNT,
                Math.max(0, expectedFunctionCount));
        if (snapshot.length() < minimum) {
            throw new IllegalArgumentException("ECARX profile snapshot is incomplete: "
                    + snapshot.length() + " fields, expected at least " + minimum);
        }
        if (!snapshot.has(HUD_AR_PROFILE_KEY)) {
            throw new IllegalArgumentException(
                    "ECARX profile does not expose the legacy HUD AR key");
        }

        boolean hasNonZeroValue = false;
        Iterator<String> keys = snapshot.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = snapshot.get(key);
            if (!(raw instanceof String) && !(raw instanceof Number)) {
                throw new IllegalArgumentException(
                        "ECARX profile contains a non-scalar value for " + key);
            }
            String value = String.valueOf(raw);
            final long numeric;
            try {
                numeric = Long.parseLong(value);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "ECARX profile contains a non-integer value for " + key, invalid);
            }
            if (numeric != 0L) hasNonZeroValue = true;
        }
        if (!hasNonZeroValue) {
            throw new IllegalArgumentException(
                    "ECARX profile is an all-zero placeholder; PA data is not ready");
        }

        snapshot.put(HUD_AR_PROFILE_KEY, enabled ? "1" : "0");
        return snapshot.toString();
    }
}
