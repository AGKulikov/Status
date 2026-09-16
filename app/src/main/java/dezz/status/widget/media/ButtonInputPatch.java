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
                {"yandexnavi://ask_alice", "iandexnavi://ask_alice"},
                // KX11 XSF c4e38040…: ECarXRVoiceAssistAction, key200231. All three
                // routes must be present; root entry point also verifies the source APK hash.
                {"ecarx.intent.action.vr_pressed", "ecarx.intent.action.nr_pressed"},
                {"ecarx.intent.action.vr_released", "ecarx.intent.action.nr_released"},
                {"ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT",
                 "ecarx.intent.action.ECARX_KEY_NVOICEASSIST_EVENT"}};
            case DM: return new String[][] {{"ecarx.settings.service.DrivingModeService",
                "ecarx.settings.service.DrivingKnobService"}};
            case POWER: return new String[][] {{"com.ecarx.screensaver", "com.ecarx.icreensaver"}};
            default: throw new IllegalArgumentException("Button has no VDEX route");
        }
    }
    public static boolean state(byte[] data, VehicleButton button) {
        validateCache(data);
        if (button == VehicleButton.VA) validateBroadcastVa(data);
        Boolean disabled = null;
        for (String[] pair : routes(button)) {
            boolean stock = count(data, bytes(pair[0])) > 0, custom = count(data, bytes(pair[1])) > 0;
            // Firmware may contain only one of the two VA/SRC implementations. MConfig's two
            // independent substitutions do not require the unused alternative to be present.
            if (!stock && !custom) continue;
            if (stock == custom || (disabled != null && disabled != custom))
                throw new IllegalArgumentException("Mixed " + button + " route");
            disabled = custom;
        }
        if (disabled == null) throw new IllegalArgumentException("Missing " + button + " routes");
        return disabled;
    }

    private static void validateCache(byte[] data) {
        if (data.length < 16 || data.length > 64 * 1024 * 1024 || data[0] != 'v'
                || data[1] != 'd' || data[2] != 'e' || data[3] != 'x')
            throw new IllegalArgumentException("Unsupported XSFInputService cache");
    }
    public static byte[] apply(byte[] original, VehicleButton button, boolean disabled) {
        validateCache(original);
        if (button == VehicleButton.VA) validateBroadcastVa(original);
        byte[] result = original.clone();
        for (String[] pair : routes(button)) {
            // Normalize every known spelling to the requested state, including a partially
            // applied earlier operation. No guessed offsets or other buttons are changed.
            byte[] from = bytes(pair[disabled ? 0 : 1]), to = bytes(pair[disabled ? 1 : 0]);
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

    public static boolean hasBroadcastVaRoute(byte[] data) {
        String[][] pairs = routes(VehicleButton.VA);
        for (int i = 2; i < pairs.length; i++)
            if (count(data, bytes(pairs[i][0])) + count(data, bytes(pairs[i][1])) > 0) return true;
        return false;
    }
    private static void validateBroadcastVa(byte[] data) {
        if (!hasBroadcastVaRoute(data)) return;
        if (count(data, bytes("Lecarx/xsf/inputservice/key/ECarXRVoiceAssistAction;")) == 0)
            throw new IllegalArgumentException("Unidentified VA broadcast owner");
        String[][] pairs = routes(VehicleButton.VA);
        for (int i = 2; i < pairs.length; i++)
            if (count(data, bytes(pairs[i][0])) + count(data, bytes(pairs[i][1])) == 0)
                throw new IllegalArgumentException("Incomplete VA down/up/click routes");
    }

    public static String coverage(byte[] data, VehicleButton button) {
        int present = 0;
        for (String[] pair : routes(button))
            if (count(data, bytes(pair[0])) + count(data, bytes(pair[1])) > 0) present++;
        return present + "/" + routes(button).length;
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
