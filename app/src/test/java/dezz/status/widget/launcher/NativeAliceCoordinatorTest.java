package dezz.status.widget.launcher;
import android.app.Application;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class,shadows=NavigatorAliceActionTest.RealResultReceiver.class)
public class NativeAliceCoordinatorTest {
 ResultReceiver events; String id="native-voice"; long seq;
 @Before public void reset(){NativeAliceCoordinator.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 void prepare(){
  NativeAliceCoordinator.rememberLaunch("origin",null,()->true,SystemClock.elapsedRealtime()+10000);
  Bundle b=new Bundle();b.putString("origin","origin");b.putLong("deadline",SystemClock.elapsedRealtime()+300);
  b.putParcelable("reply",new ResultReceiver(new Handler(Looper.getMainLooper())) {
   protected void onReceiveResult(int code,Bundle answer){assertEquals(34101,code);events=answer.getParcelable("events");}
  });
  NativeAliceCoordinator.prepare(RuntimeEnvironment.getApplication(),id,b);
  Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(events);assertTrue(NativeAliceCoordinator.active());
 }
 void event(int code,String identity,long elapsed){
  Bundle b=new Bundle();b.putString("id",identity);b.putLong("sequence",++seq);b.putLong("elapsed",elapsed);
  events.send(code,b);Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
 @Test public void frameworkCallbackHandlesNativeVoiceAndTwoSecondEnd(){
  prepare();event(33804,id,SystemClock.elapsedRealtime());event(33805,id,SystemClock.elapsedRealtime());
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1999));assertTrue(NativeAliceCoordinator.active());
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertFalse(NativeAliceCoordinator.active());
 }
 @Test public void oldSessionCannotFinishCurrentConversation(){
  prepare();event(33804,id,SystemClock.elapsedRealtime());event(33805,"old-session",SystemClock.elapsedRealtime());
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertTrue(NativeAliceCoordinator.active());
 }
 @Test public void futureOrDelayedEngineEventsRevokePermission(){
  prepare();event(33804,id,SystemClock.elapsedRealtime()+1);assertFalse(NativeAliceCoordinator.active());
 }
 @Test public void noNativeEventsAfterPrepareExpiresWithoutPlay(){
  prepare();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertFalse(NativeAliceCoordinator.active());
 }
 @Test public void manualCommandInvalidatesNativeContinuation(){
  prepare();event(33804,id,SystemClock.elapsedRealtime());NavigatorAliceAction.onManualTransportControl();
  Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(NativeAliceCoordinator.active());
 }
}
