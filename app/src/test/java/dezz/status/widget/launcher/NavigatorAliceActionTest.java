package dezz.status.widget.launcher;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class NavigatorAliceActionTest {
 @Test public void runningIncludesBackgroundAndSecondaryProcesses() {
  assertEquals(1,NavigatorAliceAction.processState("NAME\nru.yandex.yandexnavi\nNATRO_ALICE_PS_OK"));
  assertEquals(1,NavigatorAliceAction.processState("NAME\nru.yandex.yandexnavi:service\nNATRO_ALICE_PS_OK"));
 }
 @Test public void absenceRequiresCompleteSystemInventory() {
  assertEquals(0,NavigatorAliceAction.processState("NAME\nru.yandex.yandexnavi.other\nNATRO_ALICE_PS_OK"));
  assertEquals(-1,NavigatorAliceAction.processState(null));
  assertEquals(-1,NavigatorAliceAction.processState("NAME\n"));
  assertEquals(-1,NavigatorAliceAction.processState("permission denied\nNATRO_ALICE_PS_OK"));
 }
}
