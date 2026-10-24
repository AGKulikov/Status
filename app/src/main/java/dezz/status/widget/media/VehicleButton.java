/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

/** MConfig 46.1 groups; DM directions and the two SRC codes have independent counters. */
public enum VehicleButton {
    MEDIA("MEDIA", "media", true), SRC("SRC / WeChat", "src", true),
    STAR("★", "asterisk", true), VA("VA", "va", true),
    DM("DM", "knob", false), POWER("POWER", "power", false);
    public final String title, key;
    public final boolean longPress;
    VehicleButton(String title, String key, boolean longPress) {
        this.title = title; this.key = key; this.longPress = longPress;
    }
    public static VehicleButton fromCode(int code) {
        switch (code) {
            // KX11 XSF c4e38040: physical R keys, not synthesized Android media pairs.
            case 200085: case 200087: case 200088: return MEDIA;
            case 210004: case 200400: return SRC;
            case 119: return STAR;
            case 200231: return VA;
            case 300001: case 300002: return DM;
            case 26: return POWER;
            default: return null;
        }
    }
    public static String bindingGroup(int code) {
        if (code == 200085) return "mediaPlayPause";
        if (code == 200087) return "mediaNext";
        if (code == 200088) return "mediaPrevious";
        if (code == 300001) return "knobLeft";
        if (code == 300002) return "knobRight";
        VehicleButton button = fromCode(code);
        return button == null ? "" : button.key;
    }
    public static int mediaPhysicalCode(int androidCode) {
        switch (androidCode) {
            case 85: return 200085;
            case 87: case 90: return 200087;
            case 88: case 89: return 200088;
            default: return 0;
        }
    }
    public static int mediaCode(String group) {
        if ("mediaPlayPause".equals(group)) return 85;
        if ("mediaNext".equals(group)) return 87;
        if ("mediaPrevious".equals(group)) return 88;
        return 0;
    }
    public static int defaultAction(String group, String gesture) {
        // Stable ButtonAction.MEDIA_DEFAULT ID; keep this input mapping Android/catalog-free.
        return mediaCode(group) != 0 && "1".equals(gesture) ? 109 : 0;
    }
}
