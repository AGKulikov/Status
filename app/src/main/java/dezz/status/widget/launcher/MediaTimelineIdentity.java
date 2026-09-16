/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import java.text.Normalizer;
import java.util.Locale;

/** Positive track evidence for timeline merging; optional notification album is not an ID. */
final class MediaTimelineIdentity {
    private MediaTimelineIdentity() {}

    static boolean matches(String title, String artist, String otherTitle, String otherArtist) {
        String left = normalize(title), right = normalize(otherTitle);
        if (left.isEmpty() || !left.equals(right)) return false;
        String a = credits(artist), b = credits(otherArtist);
        return a.isEmpty() || b.isEmpty() || a.equals(b);
    }

    static boolean mayRetainDuration(boolean sameOwner, String oldId, String newId,
                                     String title, String artist, String newTitle, String newArtist) {
        if (!sameOwner) return false;
        if (!empty(oldId) && !empty(newId)) return oldId.equals(newId);
        return matches(title, artist, newTitle, newArtist);
    }

    static String notificationEvidence(boolean sameOwner, String notificationId, String sessionId,
            String title, String artist, String sessionTitle, String sessionArtist,
            boolean sessionOwnsText) {
        if (!sameOwner) return "owner_mismatch";
        if (!empty(notificationId) && !empty(sessionId))
            return notificationId.equals(sessionId) ? "media_id" : "media_id_mismatch";
        if (sessionOwnsText && normalize(title).isEmpty()) return "session_text";
        if (matches(title, artist, sessionTitle, sessionArtist)) return "track_text";
        // The notification can prepend a content notice to the display title (ⓘ Song).
        // Strip only a leading, whitespace-delimited presentation glyph BEFORE NFKC:
        // NFKC turns U+24D8 into an ordinary 'i', losing the distinction. Never alter
        // session metadata, the displayed title, ordinary punctuation, or artist credits.
        String undecorated = withoutLeadingNotice(title);
        if (!undecorated.equals(title == null ? "" : title)
                && matches(undecorated, artist, sessionTitle, sessionArtist))
            return "track_text_notice";
        return normalize(title).isEmpty() ? "missing_track_evidence" : "track_text_mismatch";
    }

    static boolean accepts(String evidence) {
        return "media_id".equals(evidence) || "session_text".equals(evidence)
                || "track_text".equals(evidence) || "track_text_notice".equals(evidence);
    }

    private static String withoutLeadingNotice(String text) {
        if (text == null) return "";
        int index = 0;
        while (index < text.length() && spacing(text.charAt(index))) index++;
        if (index == text.length()) return text;
        char glyph = text.charAt(index++);
        if (glyph != '\u24d8' && glyph != '\u2139' && glyph != '\u26a0') return text;
        if (index < text.length() && (text.charAt(index) == '\ufe0f'
                || text.charAt(index) == '\ufe0e')) index++;
        if (index == text.length() || !spacing(text.charAt(index))) return text;
        while (index < text.length() && spacing(text.charAt(index))) index++;
        return text.substring(index);
    }

    private static boolean spacing(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }

    /** Formatting-equivalent full credits, not substring/fuzzy matching between artists. */
    private static String credits(String text) {
        String value = normalize(text);
        return value.replaceAll("\\s*(?:,|;| & | feat\\.? | ft\\.? | featuring )\\s*", "|");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
