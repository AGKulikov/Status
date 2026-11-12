/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

/** One transient watcher, installed only after the user's startup delay expires. */
final class MediaAutoResumeWifi {
    private static ConnectivityManager manager;
    private static ConnectivityManager.NetworkCallback callback;
    private static Runnable ready;

    /** Diagnostic snapshot only. It does not replace the user's connected-Wi-Fi gate. */
    static String snapshot(Context context) {
        try {
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
            Network active=cm==null?null:cm.getActiveNetwork();
            NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
            return "wifi_connected="+connected(context)+", active_network="+(active!=null)
                    +", active_wifi="+(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    +", active_ethernet="+(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                    +", internet_capability="+(caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    +", validated="+(caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        } catch(RuntimeException error) { return "network_snapshot_error="+error.getClass().getSimpleName(); }
    }

    static boolean connected(Context context) {
        try {
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
            if(cm==null)return false;
            for(Network network:cm.getAllNetworks()) {
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);
                android.net.NetworkInfo info=cm.getNetworkInfo(network);
                if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && info!=null && info.isConnected())return true;
            }
        } catch(RuntimeException ignored) { }
        return false;
    }

    static synchronized void await(Context context,Runnable action) {
        ready=action;
        if(callback==null) {
            try {
                manager=context.getSystemService(ConnectivityManager.class);
                if(manager==null)return;
                ConnectivityManager.NetworkCallback next=new ConnectivityManager.NetworkCallback() {
                    private void changed(){
                        Runnable run;
                        synchronized(MediaAutoResumeWifi.class){
                            if(callback!=this || !connected(context))return;
                            run=ready;
                        }
                        if(run!=null)run.run();
                    }
                    @Override public void onAvailable(Network network){changed();}
                    @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){changed();}
                };
                callback=next;
                manager.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),next);
            } catch(RuntimeException ignored) {stop();return;}
        }
        // Close the check/register race. The caller serializes execution with boot cancellation.
        if(connected(context))action.run();
    }

    static synchronized void stop() {
        ConnectivityManager cm=manager;
        ConnectivityManager.NetworkCallback old=callback;
        callback=null;manager=null;ready=null;
        if(cm!=null && old!=null)try{cm.unregisterNetworkCallback(old);}catch(RuntimeException ignored){}
    }
}
