package dezz.status.widget.launcher;
import org.junit.Test;
import static org.junit.Assert.*;
/** State-level tests deliberately contain no steering-wheel, button or intent assumptions. */
public class AliceVoiceContinuationTest {
 @Test public void anotherUtteranceInsideDelayInvalidatesOldTimerAndWaitsForItsOwnEnd() {
  AliceMusicResumeTest.Clock c=new AliceMusicResumeTest.Clock(); AliceMusicResume s=c.create(true);
  s.event(33804); s.event(33805); c.advance(1500); s.event(33804);
  c.advance(1000); assertEquals(0,c.plays);
  s.event(33805); c.advance(1999); assertEquals(0,c.plays); c.advance(1); assertEquals(1,c.plays);
 }
 @Test public void repeatedIdleHeartbeatsDoNotPostponeTheTwoSecondDeadline() {
  AliceMusicResumeTest.Clock c=new AliceMusicResumeTest.Clock(); AliceMusicResume s=c.create(true);
  s.event(33804); s.event(33805);
  for(int i=0;i<7;i++){c.advance(250);s.event(33805);}
  c.advance(250); assertEquals(1,c.plays);
 }
 @Test public void restartedVoiceCancelsPlayAlreadyQueuedToBinderWorker() {
  AliceMusicResumeTest.Clock c=new AliceMusicResumeTest.Clock(); AliceMusicResume s=c.create(true);
  s.event(33804);s.event(33805);c.advance(2000);assertTrue(s.mayResume());
  s.event(33804);assertFalse(s.mayResume());
 }
 @Test public void engineLossDuringDelayNeverResumes() {
  AliceMusicResumeTest.Clock c=new AliceMusicResumeTest.Clock(); AliceMusicResume s=c.create(true);
  s.event(33804);s.event(33805);c.advance(1500);s.event(33806);c.advance(2000);
  assertEquals(0,c.plays);
 }
 @Test public void asynchronousPlayerReadCannotResumeAcrossNewSpeechOrCancelNewTimer() {
  AliceMusicResumeTest.Clock c=new AliceMusicResumeTest.Clock();
  AliceMusicResume s=new AliceMusicResume(c,()->c.plays++,true);
  s.event(33804);s.event(33805);c.advance(2000);long old=s.revision();
  assertTrue(s.active());assertTrue(s.mayResume());
  s.event(33804);assertFalse(s.mayResume());
  s.event(33805);s.complete(old,false);c.advance(1999);assertEquals(1,c.plays);
  c.advance(1);assertEquals(2,c.plays);assertTrue(s.mayResume());
  s.complete(s.revision(),true);assertFalse(s.active());
 }
}
