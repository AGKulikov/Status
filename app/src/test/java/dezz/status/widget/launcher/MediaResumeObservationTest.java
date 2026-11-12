package dezz.status.widget.launcher;

import android.app.Application;
import android.media.session.PlaybackState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class MediaResumeObservationTest {
    @Test public void roadSequenceWaitsForNoneAndBufferingThenConfirmsPlaying() {
        int[] states={PlaybackState.STATE_NONE, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_PLAYING};
        for (int state : states) {
            PlaybackState snapshot=new PlaybackState.Builder().setState(state,1200,1f,12345).build();
            MediaResumeCommand.DispatchTrace trace=MediaResumeCommand.observePlaybackState(snapshot);
            assertEquals(state==PlaybackState.STATE_PLAYING
                    ? MediaResumeCommand.Result.ALREADY_PLAYING
                    : MediaResumeCommand.Result.WAITING_FOR_SESSION, trace.result);
            assertTrue(trace.detail.contains("route=observe_only"));
            assertTrue(trace.detail.contains("wave_mode=unknown"));
        }
    }
    @Test public void missingSessionOrErrorDoesNotAuthorizeFallback() {
        assertEquals(MediaResumeCommand.Result.WAITING_FOR_SESSION,
                MediaResumeCommand.observePlaybackState(null).result);
        for(int state:new int[]{PlaybackState.STATE_ERROR,PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_STOPPED,PlaybackState.STATE_CONNECTING})
            assertEquals(MediaResumeCommand.Result.WAITING_FOR_SESSION,
                    MediaResumeCommand.observePlaybackState(new PlaybackState.Builder()
                            .setState(state,0,0).build()).result);
    }
}
