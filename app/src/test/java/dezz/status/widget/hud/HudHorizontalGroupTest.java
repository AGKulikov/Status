/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class HudHorizontalGroupTest {
    @Test public void membershipIsOrderedUniqueAndAllowsZeroSpacing() {
        HudElementConfig group = new HudElementConfig(
                "hud_horizontal_group", HudElementType.HORIZONTAL_GROUP);
        group.applyTypeDefaults();
        HudHorizontalGroup.setMemberIds(group,
                Arrays.asList("clock", "speed", "clock", "", group.id));
        put(group, "gapPx", 0);
        put(group, "paddingBottomPx", 0);

        HudHorizontalGroup.normalizeOptions(group);

        assertEquals(Arrays.asList("clock", "speed"),
                HudHorizontalGroup.memberIds(group));
        assertEquals(0, HudHorizontalGroup.gapPx(group));
        assertEquals(0, HudHorizontalGroup.paddingBottomPx(group));
    }

    private static void put(HudElementConfig group, String key, int value) {
        try {
            group.options.put(key, value);
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
