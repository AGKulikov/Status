/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import org.json.JSONException;

/**
 * Disabled compatibility stub for the rejected public-profile JSON route.
 *
 * <p>AdaptAPI exposes only 85 of the underlying 150 fields. Re-applying even its complete JSON
 * can therefore clear 65 hidden vehicle preferences. Runtime code must use
 * {@link HudProfileWirePatcher}; this method always fails closed.</p>
 */
public final class HudProfileSnapshotPatcher {
    /** {@code IHUD.SETTING_FUNC_HUD_AR_ENGINE}; kept vendor-free for the main source set. */
    public static final String HUD_AR_PROFILE_KEY = "654443008";

    private HudProfileSnapshotPatcher() {
    }

    /**
     * @deprecated JSON profile writes cannot preserve all ECARX fields.
     */
    @Deprecated
    @NonNull
    public static String patchHudAr(@NonNull String completeJson, boolean enabled,
                                    int expectedFunctionCount) throws JSONException {
        throw new UnsupportedOperationException(
                "ECARX profile JSON omits hidden fields; use the raw PA wire patcher");
    }
}
