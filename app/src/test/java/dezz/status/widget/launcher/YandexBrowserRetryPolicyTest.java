package dezz.status.widget.launcher;
import org.junit.Test;
import static org.junit.Assert.*;
import static dezz.status.widget.launcher.YandexBrowserRetryPolicy.Retry.*;
public class YandexBrowserRetryPolicyTest {
    @Test public void dispatchedUnreadyPlayCanOnlyWarmUpService() {
        assertEquals(WARMUP,YandexBrowserRetryPolicy.next(false,true,0,true));
        assertEquals(WARMUP,YandexBrowserRetryPolicy.next(true,true,1,true));
        assertEquals(PLAY,YandexBrowserRetryPolicy.next(true,false,0,true));
        assertEquals(NONE,YandexBrowserRetryPolicy.next(false,false,0,true));
    }
    @Test public void cancellationAndBoundAlwaysWin() {
        for(boolean missing:new boolean[]{false,true}) for(boolean unready:new boolean[]{false,true}) {
            assertEquals(NONE,YandexBrowserRetryPolicy.next(missing,unready,0,false));
            assertEquals(NONE,YandexBrowserRetryPolicy.next(missing,unready,2,true));
        }
    }
}
