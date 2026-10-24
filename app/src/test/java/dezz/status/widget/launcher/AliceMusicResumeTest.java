package dezz.status.widget.launcher;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class AliceMusicResumeTest {
 static class Clock implements AliceMusicResume.Scheduler {
  long now; int plays; List<Runnable> tasks=new ArrayList<>(); List<Long> times=new ArrayList<>();
  public void later(long delay,Runnable task){tasks.add(task);times.add(now+delay);}
  void advance(long ms){now+=ms;for(int i=tasks.size()-1;i>=0;i--)if(times.get(i)<=now){times.remove(i);tasks.remove(i).run();}}
  AliceMusicResume create(boolean wasPlaying){return new AliceMusicResume(this,wasPlaying?()->plays++:null);}
 }
 @Test public void waitsTwoSecondsAfterActualEndNotAfterLaunchOrSilence(){
  Clock c=new Clock();AliceMusicResume s=c.create(true);s.event(33804);c.advance(60000);assertEquals(0,c.plays);
  s.event(33805);c.advance(1999);assertEquals(0,c.plays);c.advance(1);assertEquals(1,c.plays);
  s.event(33805);c.advance(10000);assertEquals(1,c.plays);
 }
 @Test public void musicPausedBeforeAliceIsNeverStarted(){
  Clock c=new Clock();AliceMusicResume s=c.create(false);s.event(33804);s.event(33805);c.advance(3000);assertEquals(0,c.plays);
 }
 @Test public void finishWithoutStartAndAbortedDialogDoNotPlay(){
  Clock c=new Clock();AliceMusicResume s=c.create(true);s.event(33805);c.advance(10000);assertEquals(0,c.plays);
  s.event(33804);s.event(33806);s.event(33805);c.advance(3000);assertEquals(0,c.plays);
 }
 @Test public void manualTransportDuringDelayCancelsContinuation(){
  Clock c=new Clock();AliceMusicResume s=c.create(true);s.event(33804);s.event(33805);c.advance(1000);s.cancel();c.advance(3000);
  assertEquals(0,c.plays);assertFalse(s.mayResume());
 }
}
