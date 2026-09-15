/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/** MConfig 46.1 groups; DM directions and the two SRC codes have independent counters. */
public enum VehicleButton {
    MEDIA("MEDIA", "media", false), SRC("SRC / WeChat", "src", true),
    STAR("★", "asterisk", true), VA("VA", "va", true),
    DM("DM", "knob", false), POWER("POWER", "power", false);
    public final String title, key;
    public final boolean longPress;
    VehicleButton(String title, String key, boolean longPress) {
        this.title = title; this.key = key; this.longPress = longPress;
    }
    public static VehicleButton fromCode(int code) {
        switch (code) {
            case 210004: case 200400: return SRC;
            case 119: return STAR;
            case 200231: return VA;
            case 300001: case 300002: return DM;
            case 26: return POWER;
            default: return null;
        }
    }
    public static String bindingGroup(int code) {
        if (code == 300001) return "knobLeft";
        if (code == 300002) return "knobRight";
        VehicleButton button = fromCode(code);
        return button == null ? "" : button.key;
    }
}
