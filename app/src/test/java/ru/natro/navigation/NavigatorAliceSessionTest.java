package ru.natro.navigation;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class NavigatorAliceSessionTest {
 static class Backend implements NavigatorAliceSession.Backend {
  long now; int state; List<Integer> events=new ArrayList<>(); Runnable next;
  public long now(){return now;} public int state(){return state;}
  public void later(long delay,Runnable task){next=task;}
  public void event(int code){events.add(code);}
  void step(int value){state=value;now+=100;Runnable task=next;next=null;if(task!=null)task.run();}
 }
 @Test public void nativeDialogMustOpenThenCloseBeforeFinish(){
  Backend b=new Backend();NavigatorAliceSession s=new NavigatorAliceSession(b);s.poll();
  b.step(0);assertTrue(b.events.isEmpty());b.step(1);assertEquals(Arrays.asList(33804),b.events);
  // Background/voice silence does not change the dedicated router state.
  for(int i=0;i<100;i++)b.step(1);assertEquals(Arrays.asList(33804),b.events);
  b.step(0);assertEquals(Arrays.asList(33804,33805),b.events);s.poll();assertEquals(2,b.events.size());
 }
 @Test public void missingActivityIsAbortNotEnd(){
  Backend b=new Backend();b.state=1;NavigatorAliceSession s=new NavigatorAliceSession(b);s.poll();b.step(-1);
  assertEquals(Arrays.asList(33804,33806),b.events);
 }
 @Test public void dispatchWithoutNativeDialogTimesOut(){
  Backend b=new Backend();NavigatorAliceSession s=new NavigatorAliceSession(b);s.poll();b.now=10000;b.step(0);
  assertEquals(Arrays.asList(33806),b.events);
 }
}
