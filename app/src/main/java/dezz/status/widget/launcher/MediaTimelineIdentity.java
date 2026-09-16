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
        String a = normalize(artist), b = normalize(otherArtist);
        return a.isEmpty() || b.isEmpty() || a.equals(b);
    }

    static boolean mayRetainDuration(boolean sameOwner, String oldId, String newId,
                                     String title, String artist, String newTitle, String newArtist) {
        if (!sameOwner) return false;
        if (!oldId.isEmpty() && !newId.isEmpty() && !oldId.equals(newId)) return false;
        return matches(title, artist, newTitle, newArtist);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
