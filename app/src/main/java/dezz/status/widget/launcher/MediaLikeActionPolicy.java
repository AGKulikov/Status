/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Pure title/rating policy reproduced from mSaver 2.6's media action path. */
public final class MediaLikeActionPolicy {
    // Do not use Pattern.UNICODE_CHARACTER_CLASS here: Android 9 rejects flag 0x100 while
    // initializing the class. A small Unicode-aware token matcher behaves identically on the
    // KX11 runtime and the host JVM, including Cyrillic boundaries.
    private static final String[] LIKE_TITLES = {
            "лайк", "like", "нравится", "heart", "addlike", "палец вверх", "вверх",
            "добавить в избранное", "удалить из избранного", "remove from favorites",
            "add to favorites", "выбрано", "is_like", "is_unlike"
    };
    private static final String[] DISLIKE_TITLES = {
            "дизлайк", "не нравится", "палец вниз", "вниз", "dislike", "adddislike",
            "is_dislike", "is_undislike"
    };

    private MediaLikeActionPolicy() {}

    public static boolean matchesNotificationAction(@Nullable CharSequence title) {
        if (title == null || title.length() == 0 || title.length() > 256) return false;
        // mSaver gives the dislike matcher precedence because "не нравится" also contains
        // the positive token "нравится".
        String normalized = title.toString().toLowerCase(Locale.ROOT);
        return !containsToken(normalized, DISLIKE_TITLES)
                && containsToken(normalized, LIKE_TITLES);
    }

    private static boolean containsToken(String value, String[] tokens) {
        for (String token : tokens) {
            int from = 0;
            while (from <= value.length() - token.length()) {
                int at = value.indexOf(token, from);
                if (at < 0) break;
                int end = at + token.length();
                if ((at == 0 || !isWordCodePoint(value.codePointBefore(at)))
                        && (end == value.length() || !isWordCodePoint(value.codePointAt(end)))) {
                    return true;
                }
                from = at + 1;
            }
        }
        return false;
    }

    private static boolean isWordCodePoint(int value) {
        if (Character.isAlphabetic(value) || Character.isDigit(value)
                || value == 0x200C || value == 0x200D) return true;
        int type = Character.getType(value);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.CONNECTOR_PUNCTUATION;
    }

    /** A missing/non-heart rating is treated as not liked, exactly as mSaver does. */
    public static boolean nextHeart(boolean heartRating, boolean hasHeart) {
        return !heartRating || !hasHeart;
    }
}
