/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class DimMenuVendorBridgeTest {
    static final class Lane implements Executor {
        final Queue<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.add(task);}
        void next(){assertFalse(tasks.isEmpty());tasks.remove().run();idle();}
    }
    static final class Listener implements DimMenuVendorBridge.Listener {
        int next,states;
        public void onPrevious(){}
        public void onNext(){next++;}
        public void onConfirm(){}
        public void onVendorStateChanged(){states++;}
    }
    static final class Session implements DimMenuVendorBridge.Connection {
        DimMenuVendorBridge.Events events;int closes,checks;boolean failed;
        Session(DimMenuVendorBridge.Events events){this.events=events;}
        public void check(){checks++;if(failed)throw new IllegalStateException("Binder unavailable");}
        public void close(){closes++;}
    }
    static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static void after(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis));}

    @Test public void registrationIsOffUiAndInitialCallbacksAreCopiedBeforePublication(){
        Lane lane=new Lane();Listener listener=new Listener();
        DimMenuVendorBridge bridge=new DimMenuVendorBridge(RuntimeEnvironment.getApplication(),listener,events->{
            Object[] reused={4};events.state("onTabChanged",reused);reused[0]=99;
            return new Session(events);
        },lane);
        try {
            bridge.start();bridge.start();assertEquals(1,lane.tasks.size());assertFalse(bridge.isConnected());
            RuntimeEnvironment.getApplication().sendBroadcast(new Intent(DimMenuVendorBridge.ACTION_SCROLL_DOWN));idle();
            assertEquals("Steering remains usable while vendor registration waits",1,listener.next);
            lane.next();assertTrue(bridge.isConnected());assertEquals(4,bridge.currentTab());
        } finally {bridge.stop();lane.next();}
    }
    @Test public void failedReadReconnectsAndOldCallbackCannotOverwriteNewSubscription(){
        Lane lane=new Lane();Listener listener=new Listener();List<Session> sessions=new ArrayList<>();
        DimMenuVendorBridge bridge=new DimMenuVendorBridge(RuntimeEnvironment.getApplication(),listener,events->{
            Session session=new Session(events);sessions.add(session);return session;
        },lane);
        bridge.start();lane.next();Session first=sessions.get(0);
        first.events.state("onTabChanged",new Object[]{2});idle();assertEquals(2,bridge.currentTab());
        first.failed=true;after(15000);assertTrue(bridge.isConnected());lane.next();
        assertFalse(bridge.isConnected());assertEquals(-1,bridge.currentTab());lane.next();assertEquals(1,first.closes);
        after(1999);assertTrue(lane.tasks.isEmpty());after(1);lane.next();
        assertTrue(bridge.isConnected());assertEquals(2,sessions.size());
        sessions.get(1).events.state("onTabChanged",new Object[]{3});idle();int count=listener.states;
        first.events.state("onTabChanged",new Object[]{7});idle();
        assertEquals(3,bridge.currentTab());assertEquals(count,listener.states);
        bridge.stop();lane.next();after(60000);assertTrue(lane.tasks.isEmpty());
    }
    @Test public void stoppedInFlightRegistrationIsUnregisteredAndCannotReanimateOwner(){
        Lane lane=new Lane();Listener listener=new Listener();List<Session> sessions=new ArrayList<>();
        DimMenuVendorBridge bridge=new DimMenuVendorBridge(RuntimeEnvironment.getApplication(),listener,events->{
            Session session=new Session(events);sessions.add(session);return session;
        },lane);
        bridge.start();bridge.stop();lane.next();assertFalse(bridge.isConnected());lane.next();
        assertEquals(1,sessions.get(0).closes);assertEquals(0,listener.states);
        sessions.get(0).events.state("onTabChanged",new Object[]{8});idle();assertEquals(-1,bridge.currentTab());
        bridge.start();lane.next();assertTrue(bridge.isConnected());
        bridge.stop();lane.next();assertEquals(1,sessions.get(1).closes);
    }
    public interface VendorCallback {void onTabChanged(int tab);}
    public static final class VendorMenu {
        final Set<VendorCallback> callbacks=new HashSet<>();boolean failRead;
        public void registerDimMenuInteractionCallback(VendorCallback callback){callbacks.add(callback);}
        public void unregisterDimMenuInteractionCallback(VendorCallback callback){callbacks.remove(callback);}
        public int getNaviMode(){if(failRead)throw new IllegalStateException("Binder unavailable");return 3;}
        public void notifyIHUReady(){}
    }
    @Test public void realReflectionProxyWorksInVendorHashRegistryAndPartialFailureCleansItUp()throws Exception{
        VendorMenu menu=new VendorMenu();List<String> events=new ArrayList<>();
        DimMenuVendorBridge.Connection connection=DimMenuVendorBridge.ReflectionConnection.open(menu,VendorCallback.class,
                (name,args)->events.add(name+":"+args[0]));
        VendorCallback callback=menu.callbacks.iterator().next();
        assertTrue(callback.equals(callback));assertFalse(callback.equals(new Object()));
        assertNotNull(callback.toString());assertTrue(events.isEmpty());
        callback.onTabChanged(6);assertEquals(Collections.singletonList("onTabChanged:6"),events);
        connection.close();connection.close();assertTrue(menu.callbacks.isEmpty());
        menu.failRead=true;
        assertThrows(Exception.class,()->DimMenuVendorBridge.ReflectionConnection.open(menu,VendorCallback.class,(name,args)->{}));
        assertTrue("A registration that fails validation must not remain registered",menu.callbacks.isEmpty());
    }
}
