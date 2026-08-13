/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/** Pure title/rating policy reproduced from mSaver 2.6's media action path. */
public final class MediaLikeActionPolicy {
    private static final Pattern LIKE_TITLE = Pattern.compile(
            "\\b(лайк|like|нравится|heart|addlike|палец вверх|вверх|"
                    + "добавить в избранное|удалить из избранного|remove from favorites|"
                    + "add to favorites|выбрано|is_like|is_unlike)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DISLIKE_TITLE = Pattern.compile(
            "\\b(дизлайк|не нравится|палец вниз|вниз|dislike|adddislike|"
                    + "is_dislike|is_undislike)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private MediaLikeActionPolicy() {}

    public static boolean matchesNotificationAction(@Nullable CharSequence title) {
        if (title == null || title.length() == 0 || title.length() > 256) return false;
        // mSaver gives the dislike matcher precedence because "не нравится" also contains
        // the positive token "нравится".
        return !DISLIKE_TITLE.matcher(title).find() && LIKE_TITLE.matcher(title).find();
    }

    /** A missing/non-heart rating is treated as not liked, exactly as mSaver does. */
    public static boolean nextHeart(boolean heartRating, boolean hasHeart) {
        return !heartRating || !hasHeart;
    }
}
