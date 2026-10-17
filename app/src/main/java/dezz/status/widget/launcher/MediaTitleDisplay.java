/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** Presentation only: raw metadata stays available to identity, timeline and command routing. */
public final class MediaTitleDisplay {
    private MediaTitleDisplay() {}

    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        int index = spacingEnd(raw, 0);
        boolean removed = false;
        while (index < raw.length()) {
            char symbol = raw.charAt(index);
            if (symbol != '\u24d8' && symbol != '\u2139') break;
            removed = true;
            index++;
            if (index < raw.length()
                    && (raw.charAt(index) == '\ufe0f' || raw.charAt(index) == '\ufe0e')) index++;
            index = spacingEnd(raw, index);
        }
        // Preserve ordinary titles byte-for-byte, including a real letter i or embedded notice.
        return removed ? raw.substring(index) : raw;
    }

    private static int spacingEnd(String text, int index) {
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) break;
            index += Character.charCount(cp);
        }
        return index;
    }
}
