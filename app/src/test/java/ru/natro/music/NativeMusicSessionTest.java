/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.music;

import android.app.Application;
import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeMusicSessionTest {
    Context context; int ready, failure;
    @Before public void setup() throws Exception { context=RuntimeEnvironment.getApplication(); fixture("clear"); }
    @After public void cleanup() throws Exception { fixture("clear"); }
    void fixture(String method) throws Exception { Class.forName("d4j").getMethod(method).invoke(null); }
    void install() throws Exception {
        Class.forName("d4j").getMethod("install",Context.class,Handler.class)
                .invoke(null,context,new Handler(Looper.getMainLooper()));
    }
    BootstrapProvider.Connection connection() {
        Context wrapper=new ContextWrapper(context) {
            @Override public boolean bindService(Intent i,ServiceConnection c,int flags) {
                c.onServiceConnected(i.getComponent(),new Binder()); return true;
            }
            @Override public void unbindService(ServiceConnection c) {}
        };
        BootstrapProvider.Connection connection=new BootstrapProvider().new Connection(wrapper,"test",SystemClock.elapsedRealtime()+1000,
                new ResultReceiver(null) { @Override protected void onReceiveResult(int code,Bundle data) {
                    if(code==34201) { ready++; assertNotNull(data.getParcelable("token")); }
                    if(code==34202) failure++;
                }});
        connection.nativeComponent=new ComponentName("ru.yandex.music","fixture.Service");
        return connection;
    }
    @Test public void absentSessionIsNotReadiness() throws Exception {
        assertNull(NativeMusicSession.find(context));
        BootstrapProvider.Connection c=connection(); c.probeNative(0);
        assertFalse(c.delivered); assertEquals("native_session_wait",c.nativeStage);
        c.close("test_cleanup");
    }
    @Test public void tokenWaitsForPlayerLooperAcknowledgement() throws Exception {
        install(); BootstrapProvider.Connection c=connection(); c.probeNative(0);
        assertNotNull(NativeMusicSession.find(context)); assertFalse(c.delivered); assertEquals(0,ready);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(c.delivered); assertEquals(1,ready); c.close("test_cleanup");
    }
    @Test public void cancellationBeforeAckNeverDelivers() throws Exception {
        install(); BootstrapProvider.Connection c=connection(); c.probeNative(0); c.close("superseded");
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0,ready);
    }
    @Test public void expiredAckNeverDelivers() throws Exception {
        install(); BootstrapProvider.Connection c=connection(); c.probeNative(0);
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1001));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0,ready); assertTrue(c.closed);
    }
    @Test public void releasedSessionCannotBeReturnedByLateAck() throws Exception {
        install(); NativeMusicSession s=NativeMusicSession.find(context); assertTrue(s.current());
        BootstrapProvider.Connection c=connection(); c.probeNative(0); fixture("release");
        assertFalse(s.current()); assertNull(NativeMusicSession.find(context));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0,ready); c.close("test_cleanup");
    }
    @Test public void nativeBindKeepsServiceWhileInitializationFinishes() throws Exception {
        final Intent[] bound={null}; final ServiceConnection[] callback={null}; final int[] unbound={0};
        Context wrapper=new ContextWrapper(context) {
            @Override public boolean bindService(Intent i,ServiceConnection c,int flags) {
                bound[0]=i; callback[0]=c; assertEquals(Context.BIND_AUTO_CREATE,flags); return true;
            }
            @Override public void unbindService(ServiceConnection c) { unbound[0]++; }
        };
        BootstrapProvider.Connection c=new BootstrapProvider().new Connection(wrapper,"test",
                SystemClock.elapsedRealtime()+1000,new ResultReceiver(null));
        ComponentName service=new ComponentName("ru.yandex.music",
                "com.yandex.music.shared.media.session.session.Media3LibraryService");
        c.bindNative(service,0);
        assertEquals("androidx.media3.session.MediaSessionService",bound[0].getAction());
        assertNull(c.browser); callback[0].onServiceConnected(service,new Binder());
        assertFalse(c.delivered); assertEquals(0,unbound[0]);
        install(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        assertFalse(c.delivered); assertEquals(0,unbound[0]);
        assertEquals("android.media.browse.MediaBrowserService",bound[0].getAction());
        assertNull(c.browser); callback[0].onServiceConnected(service,new Binder());
        assertTrue(c.delivered);
        c.close("test_cleanup"); c.close("duplicate"); assertEquals(2,unbound[0]);
    }
}
