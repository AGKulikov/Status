package dezz.status.widget.launcher;
import org.junit.Test;
import static org.junit.Assert.*;
public class YandexColdSessionPolicyTest {
    @Test public void activeSessionCannotConsumePlayBeforeBrowserToken() {
        assertTrue(YandexColdSessionPolicy.mayPlayUnready(true,
                YandexColdSessionPolicy.unreadyPlayReserved(false, false)));
        assertFalse(YandexColdSessionPolicy.mayPlayUnready(true,
                YandexColdSessionPolicy.unreadyPlayReserved(false, true)));
        assertFalse(YandexColdSessionPolicy.mayPlayUnready(true,
                YandexColdSessionPolicy.unreadyPlayReserved(true, false)));
    }
}
