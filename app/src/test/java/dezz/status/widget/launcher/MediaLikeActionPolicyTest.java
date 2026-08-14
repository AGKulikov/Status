/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaLikeActionPolicyTest {
    @Test public void matchesEvidencedMsaverLikeTitles() {
        for (String title : new String[] {"Like", "LIKE", "AddLike", "Heart", "ЛАЙК",
                "♥ Лайк ♥", "Добавить в избранное", "Поставить лайк!", "Remove from favorites",
                "is_unlike"}) {
            assertTrue(title, MediaLikeActionPolicy.matchesNotificationAction(title));
        }
    }

    @Test public void rejectsTransportAndDislikeActions() {
        for (String title : new String[] {"Play", "Next", "AddDislike", "Не нравится",
                "Unlike", "Likely", "суперлайк", "лайки", "xлайк", "лайкx", "xis_like",
                "is_like2", "Ⅻlike", "likeⅫ", "superлайкер", ""}) {
            assertFalse(title, MediaLikeActionPolicy.matchesNotificationAction(title));
        }
    }

    @Test public void dislikeClassificationWinsOverEmbeddedLikeText() {
        assertEquals(MediaLikeActionPolicy.ActionKind.DISLIKE,
                MediaLikeActionPolicy.classifyNotificationAction("Не нравится"));
        assertEquals(MediaLikeActionPolicy.ActionKind.DISLIKE,
                MediaLikeActionPolicy.classifyNotificationAction("AddDislike"));
        assertEquals(MediaLikeActionPolicy.ActionKind.LIKE,
                MediaLikeActionPolicy.classifyNotificationAction("Like selected"));
        assertEquals(MediaLikeActionPolicy.ActionKind.NONE,
                MediaLikeActionPolicy.classifyNotificationAction(null));
    }

    @Test public void notificationTitleFallbackIsTriState() {
        assertEquals(Boolean.TRUE, MediaLikeActionPolicy.activeFromTitle("Selected like"));
        assertEquals(Boolean.TRUE,
                MediaLikeActionPolicy.activeFromTitle("Удалить из избранного"));
        assertEquals(Boolean.FALSE,
                MediaLikeActionPolicy.activeFromTitle("Не выбрано нравится"));
        assertEquals(Boolean.FALSE, MediaLikeActionPolicy.activeFromTitle("is_unlike"));
        assertNull(MediaLikeActionPolicy.activeFromTitle("Like"));
        assertNull(MediaLikeActionPolicy.activeFromTitle("Dislike"));
    }

    @Test public void userRatingOverridesRatingForDisplay() {
        assertEquals(Boolean.TRUE, MediaLikeActionPolicy.displayHeart(Boolean.TRUE, null));
        assertEquals(Boolean.FALSE,
                MediaLikeActionPolicy.displayHeart(Boolean.TRUE, Boolean.FALSE));
        assertEquals(Boolean.TRUE,
                MediaLikeActionPolicy.displayHeart(Boolean.FALSE, Boolean.TRUE));
        assertNull(MediaLikeActionPolicy.displayHeart(null, null));
    }

    @Test public void heartToggleMatchesMsaver() {
        assertTrue(MediaLikeActionPolicy.nextHeart(false, false));
        assertTrue(MediaLikeActionPolicy.nextHeart(true, false));
        assertFalse(MediaLikeActionPolicy.nextHeart(true, true));
    }

    @Test public void rapidSecondTapInvertsPendingTargetInsteadOfStaleSnapshot() {
        boolean firstTarget = MediaLikeActionPolicy.nextHeartTarget(Boolean.FALSE, null);
        assertTrue(firstTarget);
        assertFalse(MediaLikeActionPolicy.nextHeartTarget(Boolean.FALSE, firstTarget));
        assertTrue(MediaLikeActionPolicy.nextHeartTarget(Boolean.TRUE, Boolean.FALSE));
    }

    @Test public void commandInvertsTheSameUserRatingOverrideThatTheHeartDisplays() {
        Boolean displayedLiked = MediaLikeActionPolicy.displayHeart(
                Boolean.FALSE, Boolean.TRUE);
        assertEquals(Boolean.TRUE, displayedLiked);
        assertFalse(MediaLikeActionPolicy.nextHeartTarget(displayedLiked, null));

        Boolean displayedUnliked = MediaLikeActionPolicy.displayHeart(
                Boolean.TRUE, Boolean.FALSE);
        assertEquals(Boolean.FALSE, displayedUnliked);
        assertTrue(MediaLikeActionPolicy.nextHeartTarget(displayedUnliked, null));
    }

    @Test public void notificationStateDrivesCommandWhenSessionRatingsAreUnknown() {
        Boolean sessionState = MediaLikeActionPolicy.displayHeart(null, null);
        Boolean notificationState = Boolean.TRUE;
        Boolean mergedState = sessionState != null ? sessionState : notificationState;
        assertFalse(MediaLikeActionPolicy.nextHeartTarget(mergedState, null));
    }

    @Test public void pendingStateIgnoresEarlyStaleCallbacksThenAcknowledgesOrExpires() {
        assertTrue(MediaLikeActionPolicy.keepPending(true, Boolean.FALSE, 140L, 720L, 2_400L));
        assertTrue(MediaLikeActionPolicy.keepPending(true, Boolean.TRUE, 719L, 720L, 2_400L));
        assertFalse(MediaLikeActionPolicy.keepPending(true, Boolean.TRUE, 720L, 720L, 2_400L));
        assertTrue(MediaLikeActionPolicy.keepPending(true, Boolean.FALSE, 2_399L, 720L, 2_400L));
        assertFalse(MediaLikeActionPolicy.keepPending(true, null, 2_400L, 720L, 2_400L));
    }
}
