/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Version-independent options contract for a real HUD horizontal group.
 *
 * <p>The group has geometry of its own and never paints a background. Its members retain their
 * individual text styles; resizing the group only redistributes their visual frames.</p>
 */
public final class HudHorizontalGroup {
    public static final int MAX_MEMBERS = 128;

    private HudHorizontalGroup() {
    }

    @NonNull
    public static List<String> memberIds(@NonNull HudElementConfig group) {
        ArrayList<String> result = new ArrayList<>();
        JSONArray source = group.options.optJSONArray("memberIds");
        if (source == null) return result;
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < source.length() && unique.size() < MAX_MEMBERS; index++) {
            String id = source.optString(index, "").trim();
            if (!id.isEmpty() && !id.equals(group.id)) unique.add(id);
        }
        result.addAll(unique);
        return result;
    }

    public static void setMemberIds(@NonNull HudElementConfig group,
                                    @NonNull List<String> memberIds) {
        JSONArray encoded = new JSONArray();
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : memberIds) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty() || id.equals(group.id) || !unique.add(id)) continue;
            encoded.put(id);
            if (unique.size() >= MAX_MEMBERS) break;
        }
        try {
            group.options.put("memberIds", encoded);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static int gapPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("gapPx", 0), 0, 500);
    }

    public static int paddingLeftPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("paddingLeftPx", 0), 0, 500);
    }

    public static int paddingTopPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("paddingTopPx", 0), 0, 500);
    }

    public static int paddingRightPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("paddingRightPx", 0), 0, 500);
    }

    public static int paddingBottomPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("paddingBottomPx", 0), 0, 500);
    }

    public static int marginLeftPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("marginLeftPx", 0), 0, 500);
    }

    public static int marginTopPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("marginTopPx", 0), 0, 500);
    }

    public static int marginRightPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("marginRightPx", 0), 0, 500);
    }

    public static int marginBottomPx(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("marginBottomPx", 0), 0, 500);
    }

    /** 0=start, 1=center, 2=end. */
    public static int horizontalAlignment(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("horizontalAlignment", 0), 0, 2);
    }

    /** 0=top, 1=center, 2=bottom. */
    public static int verticalAlignment(@NonNull HudElementConfig group) {
        return clamp(group.options.optInt("verticalAlignment", 1), 0, 2);
    }

    /** 0=compact, 1=equal cells. */
    public static int distribution(@NonNull HudElementConfig group) {
        return group.options.optInt("distribution", 0) == 1 ? 1 : 0;
    }

    public static void normalizeOptions(@NonNull HudElementConfig group) {
        List<String> members = memberIds(group);
        try {
            setMemberIds(group, members);
            group.options.put("gapPx", gapPx(group));
            group.options.put("paddingLeftPx", paddingLeftPx(group));
            group.options.put("paddingTopPx", paddingTopPx(group));
            group.options.put("paddingRightPx", paddingRightPx(group));
            group.options.put("paddingBottomPx", paddingBottomPx(group));
            group.options.put("marginLeftPx", marginLeftPx(group));
            group.options.put("marginTopPx", marginTopPx(group));
            group.options.put("marginRightPx", marginRightPx(group));
            group.options.put("marginBottomPx", marginBottomPx(group));
            group.options.put("horizontalAlignment", horizontalAlignment(group));
            group.options.put("verticalAlignment", verticalAlignment(group));
            group.options.put("distribution", distribution(group));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
