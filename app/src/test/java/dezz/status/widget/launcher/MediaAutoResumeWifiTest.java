package dezz.status.widget.launcher;

import android.app.Application;
import android.content.Context;
import android.net.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class MediaAutoResumeWifiTest {
    Context context; ConnectivityManager manager; ShadowConnectivityManager shadow;
    Network wifi=new Network(41); int starts;
    @Before public void setup(){
        MediaAutoResumeWifi.stop();context=RuntimeEnvironment.getApplication();
        manager=context.getSystemService(ConnectivityManager.class);shadow=Shadows.shadowOf(manager);
        shadow.clearAllNetworks();
    }
    @After public void cleanup(){MediaAutoResumeWifi.stop();}
    void add(Network n,int type,int transport,boolean connected){
        NetworkInfo info=new NetworkInfo(type,0,"test","");
        info.setDetailedState(connected?NetworkInfo.DetailedState.CONNECTED:NetworkInfo.DetailedState.DISCONNECTED,null,null);
        shadow.addNetwork(n,info);
        shadow.setNetworkCapabilities(n,new NetworkCapabilities().addTransportType(transport));
    }
    @Test public void ethernetAndDisconnectedWifiDoNotOpenGate(){
        add(new Network(42),ConnectivityManager.TYPE_ETHERNET,NetworkCapabilities.TRANSPORT_ETHERNET,true);
        add(wifi,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI,false);
        assertFalse(MediaAutoResumeWifi.connected(context));
    }
    @Test public void connectedWifiNeedsNoValidatedInternet(){
        add(wifi,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI,true);
        assertTrue(MediaAutoResumeWifi.connected(context));
        MediaAutoResumeWifi.await(context,()->starts++);assertEquals(1,starts);
    }
    @Test public void lateConnectionInvokesWithoutAnotherTimerAndStopRejectsStaleCallback(){
        MediaAutoResumeWifi.await(context,()->starts++);assertEquals(0,starts);
        ConnectivityManager.NetworkCallback cb=shadow.getNetworkCallbacks().iterator().next();
        cb.onAvailable(wifi);assertEquals(0,starts);
        add(wifi,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI,true);
        cb.onCapabilitiesChanged(wifi,manager.getNetworkCapabilities(wifi));assertEquals(1,starts);
        MediaAutoResumeWifi.stop();cb.onAvailable(wifi);assertEquals(1,starts);
        assertTrue(shadow.getNetworkCallbacks().isEmpty());
    }
    @Test public void repeatedWaitKeepsOneSubscriptionAndUsesCurrentPlan(){
        MediaAutoResumeWifi.await(context,()->starts+=100);
        MediaAutoResumeWifi.await(context,()->starts++);
        assertEquals(1,shadow.getNetworkCallbacks().size());
        add(wifi,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI,true);
        shadow.getNetworkCallbacks().iterator().next().onAvailable(wifi);assertEquals(1,starts);
    }
}
