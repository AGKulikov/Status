/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.nio.charset.StandardCharsets;

/** Exact, equal-length XSFInputService VDEX routes from v46.1; no executable offsets guessed. */
public final class ButtonInputPatch {
    private static String[][] routes(VehicleButton button) {
        switch (button) {
            case SRC: return new String[][] {
                {"ecarx.intent.action.ECARX_KEY_RSRC_EVENT", "ecarx.intent.action.ECARX_KEY_ISRC_EVENT"},
                {"handle_we_chat_action", "handle_no_chat_action"}};
            case VA: return new String[][] {{"handle_voice_action", "handle_ioice_action"},
                {"yandexnavi://ask_alice", "iandexnavi://ask_alice"}};
            case DM: return new String[][] {{"ecarx.settings.service.DrivingModeService",
                "ecarx.settings.service.DrivingKnobService"}};
            case POWER: return new String[][] {{"com.ecarx.screensaver", "com.ecarx.icreensaver"}};
            default: throw new IllegalArgumentException("Button has no VDEX route");
        }
    }
    public static boolean state(byte[] data, VehicleButton button) {
        if (data.length < 16 || data.length > 64 * 1024 * 1024 || data[0] != 'v'
                || data[1] != 'd' || data[2] != 'e' || data[3] != 'x')
            throw new IllegalArgumentException("Unsupported XSFInputService cache");
        Boolean disabled = null;
        for (String[] pair : routes(button)) {
            boolean stock = count(data, bytes(pair[0])) > 0, custom = count(data, bytes(pair[1])) > 0;
            if (stock == custom || (disabled != null && disabled != custom))
                throw new IllegalArgumentException("Missing/mixed " + button + " route");
            disabled = custom;
        }
        return Boolean.TRUE.equals(disabled);
    }
    public static byte[] apply(byte[] original, VehicleButton button, boolean disabled) {
        boolean wasDisabled = state(original, button);
        byte[] result = original.clone();
        if (wasDisabled == disabled) return result;
        for (String[] pair : routes(button)) {
            byte[] from = bytes(pair[wasDisabled ? 1 : 0]), to = bytes(pair[disabled ? 1 : 0]);
            if (from.length != to.length) throw new IllegalArgumentException("Route length changed");
            for (int i = 0; i <= original.length - from.length; i++) {
                if (matches(original, i, from)) {
                    System.arraycopy(to, 0, result, i, to.length);
                    i += from.length - 1;
                }
            }
        }
        if (state(result, button) != disabled) throw new IllegalStateException("Route validation failed");
        return result;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static int count(byte[] data, byte[] value) {
        int count = 0;
        for (int i = 0; i <= data.length - value.length; i++)
            if (matches(data, i, value)) { count++; i += value.length - 1; }
        return count;
    }
    private static boolean matches(byte[] data, int at, byte[] value) {
        for (int i = 0; i < value.length; i++) if (data[at + i] != value[i]) return false;
        return true;
    }
}
