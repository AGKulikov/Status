/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;
import android.app.Application;
import android.content.*;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.Executor;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class HwgpsSnapshotTest {
 static class Lane implements Executor { final Queue<Runnable> tasks=new ArrayDeque<>(); public void execute(Runnable r){tasks.add(r);} void next(){tasks.remove().run(); idle();} }
 static class Source extends ContextWrapper {
  int requests;
  Source(){super(RuntimeEnvironment.getApplication());}
  public Context getApplicationContext(){return this;}
  public void sendBroadcast(Intent i){requests++;assertEquals(HwgpsIntegration.STATE_REQUEST_RECEIVER,i.getComponent().getClassName());}
 }
 static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
 static void after(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
 @Test public void slowBinderDoesNotRunOnUiOrPileUpRetries(){
  Source source=new Source();Lane lane=new Lane();
  HwgpsIntegration.DrStateSubscription s=new HwgpsIntegration.DrStateSubscription(source,state->{},lane);
  try{s.start();assertEquals(0,source.requests);assertEquals(1,lane.tasks.size());after(60000);assertEquals(1,lane.tasks.size());
   lane.next();assertEquals(1,source.requests);after(1499);assertTrue(lane.tasks.isEmpty());after(1);assertEquals(1,lane.tasks.size());
   lane.next();after(3000);lane.next();after(6000);assertEquals(3,source.requests);assertTrue(lane.tasks.isEmpty());
  }finally{s.stop();}
 }
 @Test public void stopAndRestartFenceQueuedOldRequest(){
  Source source=new Source();Lane lane=new Lane();
  HwgpsIntegration.DrStateSubscription s=new HwgpsIntegration.DrStateSubscription(source,state->{},lane);
  try{s.start();s.stop();s.start();lane.next();assertEquals(0,source.requests);lane.next();assertEquals(1,source.requests);}finally{s.stop();}
 }
 @Test public void responseBeforeWorkerCompletionCancelsRetry(){
  Source source=new Source();Lane lane=new Lane();
  HwgpsIntegration.DrStateSubscription s=new HwgpsIntegration.DrStateSubscription(source,state->{},lane);
  try{s.start();RuntimeEnvironment.getApplication().sendBroadcast(new Intent(HwgpsIntegration.ACTION_FIX_STATE).putExtra(HwgpsIntegration.EXTRA_FIX,"fixed"));idle();
   lane.next();after(60000);assertEquals(1,source.requests);assertTrue(lane.tasks.isEmpty());}finally{s.stop();}
 }
}
