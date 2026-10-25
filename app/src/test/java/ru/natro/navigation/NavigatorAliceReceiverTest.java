package ru.natro.navigation;
import android.app.Application;
import android.content.Intent;
import android.os.ResultReceiver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class NavigatorAliceReceiverTest {
 static class Target implements NavigatorAliceReceiver.Target {
  int calls;boolean ready=true,accepted=true;
  public boolean ready(){return ready;} public int state(){return 0;}
  public boolean start(){calls++;return accepted;}
 }
 private Intent request(boolean probe){return new Intent("dd.call_alice")
  .putExtra("natro_alice_id","test-request").putExtra("natro_alice_deadline",10000L)
  .putExtra("natro_alice_probe",probe).putExtra("natro_alice_callback",new ResultReceiver(null));}
 @Test public void malformedLazyExtrasCannotCrashReceiverOrStartAlice(){
  Target target=new Target();NavigatorAliceReceiver receiver=new NavigatorAliceReceiver(target);
  Intent malformed=new Intent("dd.call_alice") {
   @Override public long getLongExtra(String name,long fallback) {
    throw new android.os.BadParcelableException("ClassNotFoundException when unmarshalling callback");
   }
  };
  assertEquals(33803,receiver.receive(malformed,1000));assertEquals(0,target.calls);
  assertEquals(33802,receiver.receive(request(false),1000));assertEquals(1,target.calls);
 }
 @Test public void wrongParcelableTypeIsRejectedWithoutLaunching(){
  Target target=new Target();NavigatorAliceReceiver receiver=new NavigatorAliceReceiver(target);
  Intent wrong=request(false).putExtra("natro_alice_callback",new android.os.Bundle());
  assertEquals(33803,receiver.receive(wrong,1000));assertEquals(0,target.calls);
 }
 @Test public void receivesOneAddressedCommandWithoutStartingAnActivity(){
  Target target=new Target();NavigatorAliceReceiver receiver=new NavigatorAliceReceiver(target);
  assertEquals(33801,receiver.receive(request(true),1000));assertEquals(0,target.calls);
  assertEquals(33802,receiver.receive(request(false),1000));assertEquals(33802,receiver.receive(request(false),1100));
  assertEquals(1,target.calls);assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
 }
 @Test public void staleWrongOrUnreadyRequestsDoNotDispatch(){
  Target target=new Target();NavigatorAliceReceiver receiver=new NavigatorAliceReceiver(target);
  assertEquals(33803,receiver.receive(request(false),10000));
  assertEquals(33803,receiver.receive(request(false).setAction("other"),1000));
  target.ready=false;assertEquals(33803,receiver.receive(request(false),1000));assertEquals(0,target.calls);
 }
 @Test public void failedCommandIsNeverReplayedOrAcknowledgedAsSuccess(){
  Target target=new Target();target.accepted=false;NavigatorAliceReceiver receiver=new NavigatorAliceReceiver(target);
  assertEquals(33803,receiver.receive(request(false),1000));assertEquals(33803,receiver.receive(request(false),1100));assertEquals(1,target.calls);
 }
 @Test public void completionChannelRequiredBeforeDispatch(){
  Target target=new Target();NavigatorAliceReceiver receiver=new NavigatorAliceReceiver(target);
  Intent i=request(false);i.removeExtra("natro_alice_callback");assertEquals(33803,receiver.receive(i,1000));assertEquals(0,target.calls);
 }
}
