/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Optional DIM subscription. Vendor Binder calls never occupy the button/UI Looper. */
final class DimMenuVendorBridge {
    interface Listener {
        void onPrevious();
        void onNext();
        void onConfirm();
        void onVendorStateChanged();
    }
    interface Events { void state(String method,Object[] args); }
    interface Connection {
        void check() throws Exception;
        void close();
    }
    interface Connector { Connection connect(Events events) throws Exception; }

    static final String ACTION_SCROLL_UP="ecarx.intent.action.ECARX_KEY_DIMSCROLLUP_EVENT";
    static final String ACTION_SCROLL_DOWN="ecarx.intent.action.ECARX_KEY_DIMSCROLLDOWN_EVENT";
    static final String ACTION_CONFIRM="ecarx.intent.action.ECARX_KEY_DIMCONFIRM_EVENT";
    private static final String TAG="DimMenuVendor";
    private static final long[] RETRY_DELAYS_MS={2_000L,5_000L,10_000L,30_000L,60_000L};
    private static final long HEALTH_MS=15_000L;
    private static final Executor VENDOR_LANE=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"natro-dim-subscription");thread.setDaemon(true);return thread;
    });
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Connector connector;
    private final Executor worker;
    private final Map<String,Object[]> initialStates=new LinkedHashMap<>();
    private boolean started,receiverRegistered,connected,connecting;
    private boolean engineOn=true;
    private int currentTab=-1,controlCenterState,retryIndex;
    private long generation;
    private Connection connection;

    private final BroadcastReceiver steeringReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){
            if(!started||intent==null)return;
            String action=intent.getAction();
            if(ACTION_SCROLL_UP.equals(action))listener.onPrevious();
            else if(ACTION_SCROLL_DOWN.equals(action))listener.onNext();
            else if(ACTION_CONFIRM.equals(action))listener.onConfirm();
        }
    };
    DimMenuVendorBridge(Context context,Listener listener){this(context,listener,null,VENDOR_LANE);}
    DimMenuVendorBridge(Context context,Listener listener,Connector connector,Executor worker){
        Context app=context.getApplicationContext();this.context=app==null?context:app;
        this.listener=listener;this.worker=worker;
        this.connector=connector==null?events->ReflectionConnection.open(this.context,events):connector;
    }
    void start(){
        if(started)return;
        started=true;retryIndex=0;
        IntentFilter filter=new IntentFilter();filter.addAction(ACTION_SCROLL_UP);
        filter.addAction(ACTION_SCROLL_DOWN);filter.addAction(ACTION_CONFIRM);
        try {ContextCompat.registerReceiver(context,steeringReceiver,filter,ContextCompat.RECEIVER_EXPORTED);receiverRegistered=true;}
        catch(RuntimeException failure){Log.w(TAG,"Could not register steering receiver",failure);}
        connect();
    }
    void stop(){
        started=false;generation++;connecting=false;
        main.removeCallbacksAndMessages(this);
        if(receiverRegistered){receiverRegistered=false;try{context.unregisterReceiver(steeringReceiver);}catch(RuntimeException ignored){}}
        release();
    }
    boolean isConnected(){return connected;}
    boolean isEngineOn(){return engineOn;}
    int currentTab(){return currentTab;}
    int controlCenterState(){return controlCenterState;}

    private void connect(){
        if(!started||connected||connecting)return;
        connecting=true;long epoch=++generation;initialStates.clear();
        Events events=(method,args)->{
            Object[] snapshot=args==null?null:args.clone();
            post(()->{
                if(!started||generation!=epoch)return;
                if(connected)handleCallback(method,snapshot);
                else initialStates.put(method,snapshot);
            },0);
        };
        worker.execute(()->{
            try {
                Connection candidate=connector.connect(events);
                // Always deliver completion, including after stop: a late registration must be removed.
                main.post(()->{
                    if(!started||epoch!=generation){worker.execute(candidate::close);return;}
                    connecting=false;connection=candidate;connected=true;retryIndex=0;
                    for(Map.Entry<String,Object[]> state:initialStates.entrySet())handleCallback(state.getKey(),state.getValue());
                    initialStates.clear();listener.onVendorStateChanged();
                    DiagnosticJournal.infoAsync("dim-subscription","connected generation="+epoch);
                    scheduleHealth(epoch,candidate);
                });
            }catch(Exception|LinkageError failure){main.post(()->lost(epoch,"connect_"+failure.getClass().getSimpleName()));}
        });
    }
    private void scheduleHealth(long epoch,Connection current){
        post(()->{
            if(!started||generation!=epoch||current!=connection)return;
            worker.execute(()->{
                try {current.check();main.post(()->{if(started&&generation==epoch&&current==connection)scheduleHealth(epoch,current);});}
                catch(Exception|LinkageError failure){main.post(()->lost(epoch,"read_"+failure.getClass().getSimpleName()));}
            });
        },HEALTH_MS);
    }
    private void lost(long epoch,String reason){
        if(!started||generation!=epoch)return;
        generation++;connecting=false;main.removeCallbacksAndMessages(this);release();
        listener.onVendorStateChanged();
        DiagnosticJournal.warn("dim-subscription","disconnected reason="+reason+"; physical_cause=unproven");
        int index=Math.min(retryIndex,RETRY_DELAYS_MS.length-1);
        retryIndex=Math.min(RETRY_DELAYS_MS.length-1,retryIndex+1);
        post(this::connect,RETRY_DELAYS_MS[index]);
    }
    private void release(){
        Connection previous=connection;connection=null;connected=false;initialStates.clear();
        engineOn=true;currentTab=-1;controlCenterState=0;
        if(previous!=null)worker.execute(previous::close);
    }
    private void post(Runnable task,long delay){main.postAtTime(task,this,SystemClock.uptimeMillis()+delay);}
    private void handleCallback(String method,Object[] args){
        if("onEngineStatusChanged".equals(method))engineOn=booleanArg(args,true);
        else if("onTabChanged".equals(method))currentTab=intArg(args,-1);
        else if("onControlCenterStateChanged".equals(method))controlCenterState=intArg(args,0);
        listener.onVendorStateChanged();
    }
    private static int intArg(Object[] args,int fallback){return args!=null&&args.length>0&&args[0] instanceof Number?((Number)args[0]).intValue():fallback;}
    private static boolean booleanArg(Object[] args,boolean fallback){return args!=null&&args.length>0&&args[0] instanceof Boolean?(Boolean)args[0]:fallback;}

    /** Uses only the already supported AdaptAPI menu. Never disconnects its shared owner. */
    static final class ReflectionConnection implements Connection {
        private Object menu,callback;
        private Method unregister,read;
        static Connection open(Context context,Events events)throws Exception{
            Class<?> type=Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction");
            Object interaction=type.getMethod("create",Context.class).invoke(null,context);
            if(interaction==null)throw new IllegalStateException("DIM interaction missing");
            Object menu=type.getMethod("getDimMenuInteraction").invoke(interaction);
            if(menu==null)throw new IllegalStateException("DIM menu missing");
            Class<?> callbackClass=Class.forName("com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction$IDimMenuInteractionCallback");
            return open(menu,callbackClass,events);
        }
        static Connection open(Object menu,Class<?> callbackClass,Events events)throws Exception{
            ReflectionConnection result=new ReflectionConnection();result.menu=menu;
            try{
                result.callback=Proxy.newProxyInstance(callbackClass.getClassLoader(),new Class<?>[]{callbackClass},(self,method,args)->{
                    if(method.getDeclaringClass()==Object.class){
                        if("hashCode".equals(method.getName()))return System.identityHashCode(self);
                        if("equals".equals(method.getName()))return args!=null&&args.length==1&&self==args[0];
                        if("toString".equals(method.getName()))return "NatroDimCallback";
                    }
                    events.state(method.getName(),args);return null;
                });
                result.unregister=menu.getClass().getMethod("unregisterDimMenuInteractionCallback",callbackClass);
                result.read=menu.getClass().getMethod("getNaviMode");
                menu.getClass().getMethod("registerDimMenuInteractionCallback",callbackClass).invoke(menu,result.callback);
                try{menu.getClass().getMethod("notifyIHUReady").invoke(menu);}
                catch(ReflectiveOperationException optional){Log.i(TAG,"Optional notifyIHUReady unavailable");}
                result.check();return result;
            }catch(Exception|LinkageError failure){result.close();throw failure;}
        }
        @Override public void check()throws Exception{
            if(!(read.invoke(menu) instanceof Number))throw new IllegalStateException("DIM mode read unavailable");
        }
        @Override public void close(){
            if(unregister!=null&&callback!=null)try{unregister.invoke(menu,callback);}catch(Exception ignored){}
            unregister=null;callback=null;
        }
    }
}
