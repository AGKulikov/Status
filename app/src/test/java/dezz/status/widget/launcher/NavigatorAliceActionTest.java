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
 @Test public void manualChangeBeforeCaptureReturnsRevokesFutureResume() {
  java.util.function.BooleanSupplier captured=NavigatorAliceAction.musicResumePermit();
  assertTrue(captured.getAsBoolean());
  NavigatorAliceAction.onManualTransportControl();
  assertFalse(captured.getAsBoolean());
  assertTrue(NavigatorAliceAction.musicResumePermit().getAsBoolean());
 }
 @Test public void commandUsesExactActionPackageAndDeadlineWithoutActivityFlags() {
  android.content.Intent i=NavigatorAliceAction.command("id",1234,true);
  assertEquals("dd.call_alice",i.getAction());assertEquals("ru.yandex.yandexnavi",i.getPackage());
  assertEquals(0,i.getFlags());assertNull(i.getData());assertFalse(i.hasExtra("ddnavforcewinfull"));
  assertTrue(i.getBooleanExtra("natro_alice_probe",false));assertEquals(1234,i.getLongExtra("natro_alice_deadline",0));
 }
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
