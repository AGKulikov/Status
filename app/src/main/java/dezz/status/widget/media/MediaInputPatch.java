/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.nio.charset.StandardCharsets;

/** Fixed-size VDEX string patch. No regex, text decoding, guessed offset or checksum changes. */
public final class MediaInputPatch {
    public static final String PATH = "/data/dalvik-cache/arm64/"
            + "system@app@XSFInputService@XSFInputService.apk@classes.vdex";
    public static String state(byte[] data) {
        if (data.length < 16 || data.length > 64 * 1024 * 1024
                || data[0] != 'v' || data[1] != 'd' || data[2] != 'e' || data[3] != 'x') {
            throw new IllegalArgumentException("Unsupported XSFInputService cache");
        }
        String found = null;
        for (String action : new String[]{MediaKeyPolicy.STOCK, MediaKeyPolicy.MCONFIG,
                MediaKeyPolicy.NATRO}) {
            if (count(data, action.getBytes(StandardCharsets.US_ASCII)) > 0) {
                if (found != null) throw new IllegalArgumentException("Mixed input routes");
                found = action;
            }
        }
        if (found == null) throw new IllegalArgumentException("Input action missing");
        return found;
    }

    public static byte[] apply(byte[] original, boolean isolated) {
        String current = state(original);
        byte[] from = current.getBytes(StandardCharsets.US_ASCII);
        byte[] to = (isolated ? MediaKeyPolicy.NATRO : MediaKeyPolicy.STOCK)
                .getBytes(StandardCharsets.US_ASCII);
        if (from.length != to.length) throw new IllegalArgumentException("Length changed");
        byte[] result = original.clone();
        for (int i = 0; i <= result.length - from.length; i++) {
            if (matches(original, i, from)) {
                System.arraycopy(to, 0, result, i, to.length);
                i += from.length - 1;
            }
        }
        state(result);
        return result;
    }

    private static int count(byte[] data, byte[] needle) {
        int count = 0;
        for (int i = 0; i <= data.length - needle.length; i++) {
            if (matches(data, i, needle)) { count++; i += needle.length - 1; }
        }
        return count;
    }
    private static boolean matches(byte[] data, int offset, byte[] needle) {
        for (int j = 0; j < needle.length; j++) if (data[offset + j] != needle[j]) return false;
        return true;
    }
}
