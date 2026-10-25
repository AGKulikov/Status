package dezz.status.widget.launcher;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,shadows=NavigatorAliceActionTest.RealResultReceiver.class)
public class NavigatorAliceActionTest {
 // Robolectric's default ShadowResultReceiver.send calls onReceiveResult directly on
 // the proxy and bypasses its Binder. Exercise the real Android implementation.
 @org.robolectric.annotation.Implements(value=android.os.ResultReceiver.class,callThroughByDefault=true)
 public static class RealResultReceiver {}

 // A Navigator process can load Android classes, but cannot load Natro classes.
 private ClassLoader foreignLoader() {
  return new ClassLoader(getClass().getClassLoader()) {
   @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.startsWith("dezz.status.widget.")) throw new ClassNotFoundException(name);
    return super.loadClass(name, resolve);
   }
  };
 }
 private android.content.Intent crossProcess(android.content.Intent outgoing) {
  android.os.Parcel parcel=android.os.Parcel.obtain();
  try {
   outgoing.writeToParcel(parcel,0);parcel.setDataPosition(0);
   android.content.Intent incoming=android.content.Intent.CREATOR.createFromParcel(parcel);
   incoming.setExtrasClassLoader(foreignLoader());return incoming;
  } finally {parcel.recycle();}
 }
 @Test public void oldSubclassCallbackCannotBeDecodedByAnotherApplication() {
  android.os.ResultReceiver local=new android.os.ResultReceiver(null) {};
  android.content.Intent incoming=crossProcess(NavigatorAliceAction.command("old",10000,false)
    .putExtra("natro_alice_callback",local));
  try { incoming.getParcelableExtra("natro_alice_callback");fail("Foreign app must not load Natro subclass"); }
  catch (android.os.BadParcelableException expected) {assertTrue(expected.getMessage().contains("ClassNotFoundException"));}
 }
 @Test public void commandSurvivesForeignLoaderAndDeliversDialogEventsToOriginalCallback() {
  java.util.List<Integer> results=new java.util.ArrayList<>();
  android.os.ResultReceiver local=new android.os.ResultReceiver(new android.os.Handler(android.os.Looper.getMainLooper())) {
   @Override protected void onReceiveResult(int code,android.os.Bundle data){results.add(code);}
  };
  android.content.Intent outgoing=NavigatorAliceAction.command("request",10000,local);
  assertEquals(android.os.ResultReceiver.class, outgoing.getParcelableExtra("natro_alice_callback").getClass());
  android.content.Intent incoming=crossProcess(outgoing);
  assertEquals("request",incoming.getStringExtra("natro_alice_id"));
  assertEquals(10000,incoming.getLongExtra("natro_alice_deadline",0));
  assertEquals(0,incoming.getFlags());
  android.os.ResultReceiver callback=incoming.getParcelableExtra("natro_alice_callback");
  callback.send(33804,null);callback.send(33805,null);
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
  assertEquals(java.util.Arrays.asList(33804,33805),results);
 }
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
