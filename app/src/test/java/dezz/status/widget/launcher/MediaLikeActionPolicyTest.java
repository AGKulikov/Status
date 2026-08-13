/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaLikeActionPolicyTest {
    @Test public void matchesEvidencedMsaverLikeTitles() {
        for (String title : new String[] {"Like", "AddLike", "Heart",
                "Добавить в избранное", "Remove from favorites", "is_unlike"}) {
            assertTrue(title, MediaLikeActionPolicy.matchesNotificationAction(title));
        }
    }

    @Test public void rejectsTransportAndDislikeActions() {
        for (String title : new String[] {"Play", "Next", "AddDislike", "Не нравится", ""}) {
            assertFalse(title, MediaLikeActionPolicy.matchesNotificationAction(title));
        }
    }

    @Test public void heartToggleMatchesMsaver() {
        assertTrue(MediaLikeActionPolicy.nextHeart(false, false));
        assertTrue(MediaLikeActionPolicy.nextHeart(true, false));
        assertFalse(MediaLikeActionPolicy.nextHeart(true, true));
    }
}
