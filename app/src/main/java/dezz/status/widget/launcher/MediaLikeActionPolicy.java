/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
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
    private static final String[] LIKE_INACTIVE_TITLES = {
            "not selected like", "like not selected", "не выбрано нравится",
            "нравится не выбрано", "не выбрано", "добавить в избранное",
            "add to favorites", "is_unlike"
    };
    private static final String[] LIKE_ACTIVE_TITLES = {
            "selected like", "like selected", "выбрано нравится", "нравится выбрано",
            "выбрано", "is_like", "удалить из избранного", "remove from favorites"
    };

    public enum ActionKind { NONE, LIKE, DISLIKE }

    private MediaLikeActionPolicy() {}

    public static boolean matchesNotificationAction(@Nullable CharSequence title) {
        return classifyNotificationAction(title) == ActionKind.LIKE;
    }

    /** Dislike wins because its phrases can contain the positive token, for example "не нравится". */
    @NonNull
    public static ActionKind classifyNotificationAction(@Nullable CharSequence title) {
        if (title == null || title.length() == 0 || title.length() > 256) {
            return ActionKind.NONE;
        }
        String normalized = title.toString().toLowerCase(Locale.ROOT);
        if (containsToken(normalized, DISLIKE_TITLES)) return ActionKind.DISLIKE;
        return containsToken(normalized, LIKE_TITLES) ? ActionKind.LIKE : ActionKind.NONE;
    }

    /**
     * Title fallback used by mSaver only after the notification action was classified as Like.
     * Null deliberately means that the action exists but its selected state is not observable.
     */
    @Nullable
    public static Boolean activeFromTitle(@Nullable CharSequence title) {
        if (classifyNotificationAction(title) != ActionKind.LIKE) return null;
        String normalized = title.toString().toLowerCase(Locale.ROOT);
        if (containsPhrase(normalized, LIKE_INACTIVE_TITLES)) return Boolean.FALSE;
        if (containsPhrase(normalized, LIKE_ACTIVE_TITLES)) return Boolean.TRUE;
        return null;
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

    private static boolean containsPhrase(String value, String[] phrases) {
        for (String phrase : phrases) {
            if (value.contains(phrase)) return true;
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

    /** USER_RATING overrides RATING for display, matching mSaver's notification parser. */
    @Nullable
    public static Boolean displayHeart(@Nullable Boolean ratingHeart,
                                       @Nullable Boolean userRatingHeart) {
        return userRatingHeart != null ? userRatingHeart : ratingHeart;
    }

    /** A pending optimistic target is the effective state for a rapid repeated tap. */
    public static boolean nextHeartTarget(@Nullable Boolean authoritative,
                                          @Nullable Boolean pendingTarget) {
        Boolean current = pendingTarget != null ? pendingTarget : authoritative;
        return !Boolean.TRUE.equals(current);
    }

    /**
     * Hold the optimistic target through the first stale callbacks, then accept an acknowledgement
     * or time out at the final bounded reconciliation.
     */
    public static boolean keepPending(boolean target, @Nullable Boolean authoritative,
                                      long elapsedMs, long minimumHoldMs, long timeoutMs) {
        if (elapsedMs < minimumHoldMs) return true;
        if (elapsedMs >= timeoutMs) return false;
        return authoritative == null || authoritative.booleanValue() != target;
    }
}
