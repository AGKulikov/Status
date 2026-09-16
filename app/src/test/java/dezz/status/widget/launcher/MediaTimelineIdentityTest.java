package dezz.status.widget.launcher;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaTimelineIdentityTest {
    @Test public void stableMediaIdRetainsDurationThroughPartialMetadata() {
        assertTrue(MediaTimelineIdentity.mayRetainDuration(true,"123","123","Song","Artist","",""));
        assertFalse(MediaTimelineIdentity.mayRetainDuration(true,"123","456","Song","Artist","Song","Artist"));
        assertFalse(MediaTimelineIdentity.mayRetainDuration(false,"123","123","Song","Artist","",""));
        assertFalse(MediaTimelineIdentity.mayRetainDuration(true,"123","","Song","Artist","",""));
    }
    @Test public void fullCreditsCanDifferInFormattingButNotInPerformers() {
        assertTrue(MediaTimelineIdentity.matches("Два стука", "Isupov, Morzza", "Два стука", "Isupov & Morzza"));
        assertFalse(MediaTimelineIdentity.matches("Song", "Artist", "Song live", "Artist"));
        assertFalse(MediaTimelineIdentity.matches("Song", "Artist", "Song", "Artist, Other"));
    }
    @Test public void tokenAloneNeverSelectsAnAdjacentSongsTimeline() {
        assertEquals("track_text_mismatch", evidence(true,"","","Song B","Song A",false));
        assertEquals("media_id_mismatch", evidence(true,"B","A","Song","Song",false));
        assertEquals("owner_mismatch", evidence(false,"A","A","Song","Song",false));
        assertEquals("media_id", evidence(true,"A","A","Display song","Song",false));
        assertEquals("session_text", evidence(true,"","","","Song",true));
        assertEquals("missing_track_evidence", evidence(true,"","","","Song",false));
    }
    @Test public void noticeIsDisplayDecorationNotAnotherTrack() {
        for (String prefix : new String[]{"ⓘ ", "ℹ️ ", "⚠\u00a0"}) {
            String result = evidence(true,"","",prefix + "Лапами вверх","Лапами вверх",false);
            assertEquals("track_text_notice", result);
            assertTrue(MediaTimelineIdentity.accepts(result));
        }
        assertEquals("track_text_mismatch", evidence(true,"","","i Лапами вверх","Лапами вверх",false));
        assertEquals("track_text_mismatch", evidence(true,"","","Лапами ⓘ вверх","Лапами вверх",false));
        assertEquals("track_text_mismatch", evidence(true,"","","ⓘSong","Song",false));
        assertEquals("track_text_mismatch", evidence(true,"","","ⓘ Song B","Song A",false));
        assertEquals("media_id_mismatch", evidence(true,"B","A","ⓘ Song","Song",false));
        assertEquals("owner_mismatch", evidence(false,"","","ⓘ Song","Song",false));
        assertEquals("track_text_mismatch", MediaTimelineIdentity.notificationEvidence(true,"","",
                "ⓘ Song","Other artist","Song","Artist",false));
    }
    private String evidence(boolean owner, String a, String b, String t, String u, boolean ownText) {
        return MediaTimelineIdentity.notificationEvidence(owner,a,b,t,"Artist",u,"Artist",ownText);
    }
}
