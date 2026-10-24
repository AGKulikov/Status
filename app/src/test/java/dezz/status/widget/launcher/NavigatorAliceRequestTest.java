package dezz.status.widget.launcher;
import java.util.*;
import java.util.function.IntConsumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigatorAliceRequestTest {
 static class Fake implements NavigatorAliceRequest.Backend {
  long time; int launches, commands, inspections, probes, readyAfter, state=1, commandReply=33802;
  String failure; List<Event> events=new ArrayList<>();
  static class Event { long at; Runnable run; Event(long a,Runnable r){at=a;run=r;} }
  public long now(){return time;}
  public void send(boolean probe,IntConsumer reply){
   if(probe) reply.accept(++probes>readyAfter?33801:33803);
   else {commands++;reply.accept(commandReply);}
  }
  public void inspect(IntConsumer reply){inspections++;reply.accept(state);}
  public boolean launch(){launches++;return true;}
  public void later(long delay,Runnable task){events.add(new Event(time+delay,task));}
  public void failure(String reason){failure=reason;}
  void advance(long until){
   while(true){Event next=events.stream().min(Comparator.comparingLong(e->e.at)).orElse(null);
    if(next==null||next.at>until)break;events.remove(next);time=next.at;next.run.run();}
   time=until;
  }
  NavigatorAliceRequest start(){NavigatorAliceRequest r=new NavigatorAliceRequest(this,10000);r.start();return r;}
 }
 @Test public void readyBackgroundOrWindowedReceiverNeverLaunchesActivity(){
  Fake b=new Fake();b.start();b.advance(11000);
  assertEquals(1,b.commands);assertEquals(0,b.launches);assertEquals(0,b.inspections);assertNull(b.failure);
 }
 @Test public void coldStartWaitsForReceiverRatherThanFixedSleep(){
  Fake b=new Fake();b.state=0;b.readyAfter=8;b.start();assertEquals(0,b.commands);
  b.advance(3000);assertEquals(1,b.launches);assertEquals(1,b.commands);assertNull(b.failure);
 }
 @Test public void runningButOldNavigatorIsNotBroughtForward(){
  Fake b=new Fake();b.readyAfter=100;b.start();
  assertEquals(0,b.launches);assertEquals(0,b.commands);assertEquals("receiver_unavailable",b.failure);
 }
 @Test public void unknownProcessStateNeverLaunches(){
  Fake b=new Fake();b.readyAfter=100;b.state=-1;b.start();
  assertEquals(0,b.launches);assertEquals("state_unknown",b.failure);
 }
 @Test public void cancelledStartupCannotExecuteLater(){
  Fake b=new Fake();b.readyAfter=4;b.state=0;NavigatorAliceRequest r=b.start();r.cancel();b.advance(15000);
  assertEquals(0,b.commands);assertNull(b.failure);
 }
 @Test public void receiverNotReadyTimesOutWithoutRepeatedLaunches(){
  Fake b=new Fake();b.readyAfter=1000;b.state=0;b.start();b.advance(15000);
  assertEquals(1,b.launches);assertEquals(0,b.commands);assertEquals("timeout",b.failure);
 }
 @Test public void failedCommandIsNeverRetriedOrReportedSuccessful(){
  Fake b=new Fake();b.commandReply=33803;b.start();b.advance(15000);
  assertEquals(1,b.commands);assertEquals(0,b.launches);assertEquals("not_accepted",b.failure);
 }
 @Test public void expiredProcessReplyCannotLaunch(){
  Fake b=new Fake(){@Override public void inspect(IntConsumer reply){later(11000,()->reply.accept(0));}};
  b.readyAfter=100;b.start();b.advance(12000);assertEquals(0,b.launches);assertEquals(0,b.commands);
 }
}
