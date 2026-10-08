/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** Presentation only; enabled flags, player and Surface leases are left unchanged. */
public final class HudTransientVisibility {
    private HudTransientVisibility() { }
    public static boolean visible(HudElementConfig item, HudPanelConfig panel,
                                  boolean editor, boolean volumeVisible) {
        if (editor || !volumeVisible) return true;
        if (item.options.optBoolean("hideDuringVolume", false)) return false;
        for (HudElementConfig group : panel.elements) {
            if (group.enabled && group.type == HudElementType.HORIZONTAL_GROUP
                    && group.options.optBoolean("hideDuringVolume", false)
                    && HudHorizontalGroup.memberIds(group).contains(item.id)) return false;
        }
        return true;
    }
}
