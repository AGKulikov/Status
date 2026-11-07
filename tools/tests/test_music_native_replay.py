"""Compile the real Music provider/adapter and replay native startup lifecycle boundaries.

Android stubs model only bind callbacks, independent loopers and monotonic time.
This complements Android/Robolectric tests; it does not establish device readiness.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class NativeMusicReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        sources = {
            'android/os/SystemClock.java': '''package android.os; public class SystemClock {
                public static long now; public static long elapsedRealtime(){return now;}}''',
            'android/os/Looper.java': '''package android.os; import java.util.*;
                public class Looper {
                  private static final Looper MAIN=new Looper();
                  public static Looper getMainLooper(){return MAIN;}
                  static long sequence;
                  static class Task implements Comparable<Task> {
                    final long when,order; final Runnable run;
                    Task(Runnable r,long d){run=r;when=SystemClock.now+d;order=sequence++;}
                    public int compareTo(Task o){int n=Long.compare(when,o.when);return n!=0?n:Long.compare(order,o.order);}
                  }
                  final PriorityQueue<Task> queue=new PriorityQueue<>();
                  public void one(){if(!queue.isEmpty() && queue.peek().when<=SystemClock.now)queue.remove().run.run();}
                  public void idle(){int n=0;while(!queue.isEmpty() && queue.peek().when<=SystemClock.now){
                    if(++n>1000)throw new AssertionError("unbounded loop");one();}}
                }''',
            'android/os/Handler.java': '''package android.os; public class Handler {
                final Looper looper; public boolean running=true;
                public Handler(Looper l){looper=l;}
                public boolean post(Runnable r){return postDelayed(r,0);}
                public boolean postDelayed(Runnable r,long d){if(!running)return false;
                  looper.queue.add(new Looper.Task(r,d));return true;}}''',
            'android/os/Bundle.java': '''package android.os; public class Bundle extends java.util.HashMap<String,Object> {
                public void putLong(String k,long v){put(k,v);} public long getLong(String k){Object v=get(k);return v==null?0:(Long)v;}
                public void putInt(String k,int v){put(k,v);} public void putString(String k,String v){put(k,v);}
                public String getString(String k){return (String)get(k);}
                public void putBoolean(String k,boolean v){put(k,v);} public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}
                public void putParcelable(String k,Object v){put(k,v);} public <T>T getParcelable(String k){return (T)get(k);}}''',
            'android/os/ResultReceiver.java': '''package android.os; public class ResultReceiver {
                public ResultReceiver(Handler h){} public void send(int code,Bundle data){onReceiveResult(code,data);}
                protected void onReceiveResult(int code,Bundle data) {}}''',
            'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
            'android/os/Binder.java': 'package android.os; public class Binder implements IBinder {public static int getCallingUid(){return 1;}}',
            'android/net/Uri.java': 'package android.net; public class Uri {}',
            'android/database/Cursor.java': 'package android.database; public interface Cursor {}',
            'android/content/ContentValues.java': 'package android.content; public class ContentValues {}',
            'android/content/ComponentName.java': '''package android.content; public class ComponentName {
                final String pkg,name; public ComponentName(String p,String n){pkg=p;name=n;}
                public String getClassName(){return name;} public String getPackageName(){return pkg;}
                public String toString(){return pkg+"/"+name;}
                public boolean equals(Object o){return o instanceof ComponentName && toString().equals(o.toString());}
                public int hashCode(){return toString().hashCode();}}''',
            'android/content/Intent.java': '''package android.content; public class Intent {
                final String action; String pkg; ComponentName component; public Intent(String s){action=s;}
                public Intent setPackage(String s){pkg=s;return this;} public Intent setComponent(ComponentName c){component=c;return this;}
                public String getAction(){return action;} public ComponentName getComponent(){return component;}}''',
            'android/content/ServiceConnection.java': '''package android.content; import android.os.*;
                public interface ServiceConnection {void onServiceConnected(ComponentName n,IBinder b);
                  void onServiceDisconnected(ComponentName n); default void onNullBinding(ComponentName n){}
                  default void onBindingDied(ComponentName n){}}''',
            'android/content/pm/ServiceInfo.java': '''package android.content.pm; public class ServiceInfo {
                public boolean enabled=true; public String packageName,name;}''',
            'android/content/pm/ResolveInfo.java': 'package android.content.pm; public class ResolveInfo {public ServiceInfo serviceInfo;}',
            'android/content/pm/PackageManager.java': '''package android.content.pm; import android.content.*; import java.util.*;
                public class PackageManager {
                  public static final int SIGNATURE_MATCH=0,COMPONENT_ENABLED_STATE_DEFAULT=0,COMPONENT_ENABLED_STATE_ENABLED=1;
                  public static class NameNotFoundException extends Exception {}
                  public final HashMap<ComponentName,ServiceInfo> services=new HashMap<>();
                  public String[] getPackagesForUid(int u){return new String[]{"ru.natro.statuswidget"};}
                  public int checkSignatures(String a,String b){return SIGNATURE_MATCH;}
                  public int getComponentEnabledSetting(ComponentName c){return COMPONENT_ENABLED_STATE_DEFAULT;}
                  public ServiceInfo getServiceInfo(ComponentName c,int f)throws NameNotFoundException {
                    if(!services.containsKey(c))throw new NameNotFoundException(); return services.get(c);}
                  public List<ResolveInfo> queryIntentServices(Intent i,int f){return Collections.emptyList();}
                }''',
            'android/content/Context.java': '''package android.content; import android.content.pm.*; import java.util.*;
                public class Context {
                  public static final int BIND_AUTO_CREATE=1;
                  public final PackageManager pm=new PackageManager();
                  public final List<Intent> intents=new ArrayList<>();
                  public final List<ServiceConnection> connections=new ArrayList<>();
                  public final Set<ServiceConnection> released=new HashSet<>();
                  public boolean accept=true;
                  public String getPackageName(){return "ru.yandex.music";} public ClassLoader getClassLoader(){return getClass().getClassLoader();}
                  public PackageManager getPackageManager(){return pm;}
                  public boolean bindService(Intent i,ServiceConnection c,int f){
                    if(f!=BIND_AUTO_CREATE)throw new AssertionError("bind flags");
                    intents.add(i);connections.add(c);return accept;}
                  public void unbindService(ServiceConnection c){if(!released.add(c))throw new AssertionError("double unbind");}
                  public void connect(int index){connections.get(index).onServiceConnected(intents.get(index).getComponent(),new android.os.Binder());}
                }''',
            'android/content/ContentProvider.java': '''package android.content; import android.os.*; import android.net.*; import android.database.*;
                public abstract class ContentProvider {
                  public Context getContext(){return new Context();} public abstract boolean onCreate();
                  public Bundle call(String m,String id,Bundle b){return null;}
                  public abstract Cursor query(Uri u,String[] p,String s,String[] a,String o);
                  public abstract String getType(Uri u); public abstract Uri insert(Uri u,ContentValues v);
                  public abstract int delete(Uri u,String s,String[] a); public abstract int update(Uri u,ContentValues v,String s,String[] a);
                }''',
            'android/media/session/MediaSession.java': '''package android.media.session; import android.content.Context;
                public class MediaSession {public static class Token{} private final Token token=new Token();
                  public MediaSession(Context c,String tag){} public Token getSessionToken(){return token;} public void release(){}}''',
            'android/media/browse/MediaBrowser.java': '''package android.media.browse; import android.content.*; import android.os.*;
                public class MediaBrowser {
                  public static int connects;
                  public static class ConnectionCallback{public void onConnected(){} public void onConnectionFailed(){} public void onConnectionSuspended(){}}
                  public MediaBrowser(Context c,ComponentName n,ConnectionCallback b,Bundle h){}
                  public void connect(){connects++;throw new AssertionError("modern bootstrap must not request legacy browser root");}
                  public void disconnect(){} public android.media.session.MediaSession.Token getSessionToken(){return null;}
                }''',
            'ru/natro/music/NativeReplay.java': r'''package ru.natro.music;
import android.content.*;import android.content.pm.*;import android.os.*;import android.media.session.*;import java.util.*;
public class NativeReplay {
 static Context context=new Context(); static Looper player=new Looper(); static Handler playerHandler=new Handler(player);
 static int ready,closed; static MediaSession.Token token;
 static List<String> stages=new ArrayList<>(); static BootstrapProvider.Connection connection;
 static ComponentName service=new ComponentName("ru.yandex.music","com.yandex.music.shared.media.session.session.Media3LibraryService");
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void mainIdle(){Looper.getMainLooper().idle();}
 static void advance(long ms){SystemClock.now+=ms;mainIdle();}
 static void install()throws Exception{Class.forName("d4j").getMethod("install",Context.class,Handler.class).invoke(null,context,playerHandler);}
 static void fixture(String name)throws Exception{Class.forName("d4j").getMethod(name).invoke(null);}
 static BootstrapProvider.Connection begin(){
   ServiceInfo info=new ServiceInfo();info.packageName=service.getPackageName();info.name=service.getClassName();context.pm.services.put(service,info);
   BootstrapProvider provider=new BootstrapProvider();
   connection=provider.new Connection(context,"test",15000,new ResultReceiver(null){
     protected void onReceiveResult(int code,Bundle data){
       check("test".equals(data.getString("id")),"request identity");
       if(code==34200)stages.add(data.getString("stage"));
       if(code==34201){ready++;token=data.getParcelable("token");check(token!=null,"null token");}
       if(code==34202)closed++;
     }});
   connection.progressEnabled=true;connection.start();
   check(context.intents.size()==1,"one initial bind");
   check("androidx.media3.session.MediaSessionService".equals(context.intents.get(0).getAction()),"native bind action");
   context.connect(0);return connection;
 }
 static void ack(){player.idle();mainIdle();}
 static void attach(){
   check(context.intents.size()==2,"attach follows player acknowledgement");
   check(ready==0,"raw binding alone is not readiness");
   check("android.media.browse.MediaBrowserService".equals(context.intents.get(1).getAction()),"attach existing native session");
   check(connection.browser==null,"no MediaBrowser object");context.connect(1);
 }
 public static void main(String[] args)throws Exception {
   switch(args[0]){
     case "deferred":
       begin();check("native_session_wait".equals(connection.nativeStage),"await async initialization");
       install();advance(100);check(ready==0 && connection.nativeProbePending,"wait for native looper");
       ack();attach();check(ready==1,"ready exactly once");
       context.connect(1);check(ready==1,"duplicate binder callback");
       advance(59999);check(closed==0,"hold playback service lease");advance(1);
       check(closed==1 && context.released.size()==2,"lease releases both binds once");
       check(stages.contains("native_looper_ready"),"readiness diagnostic");break;
     case "absent":
       begin();advance(15000);check(ready==0 && closed==1,"no session must time out");
       check(context.released.size()==1,"release initialization bind");break;
     case "blocked_looper":
       install();begin();check(connection.nativeProbePending,"probe queued");advance(15000);ack();
       check(ready==0 && closed==1 && context.intents.size()==1,"late native acknowledgement cannot attach");break;
     case "cancel":
       install();begin();connection.close("superseded");ack();
       check(ready==0 && closed==1 && context.intents.size()==1,"cancelled probe cannot attach");break;
     case "released":
       install();begin();fixture("release");ack();
       check(ready==0 && context.intents.size()==1,"released session cannot attach");advance(15000);
       check(closed==1,"released session bounded wait");break;
     case "replaced":
       install();begin();MediaSession.Token original=NativeMusicSession.find(context).token;
       install();ack();check(ready==0 && context.intents.size()==1,"old session acknowledgement discarded");
       ack();attach();check(ready==1 && token!=original,"only current session token");break;
     case "generation":
       install();begin();connection.retryBeforeToken("test_null_binding");advance(500);context.connect(1);
       check(connection.nativeProbePending,"new generation has pending probe");
       player.one();mainIdle();check(connection.nativeProbePending,"stale acknowledgement cannot clear new pending state");
       check(context.intents.size()==2 && ready==0,"stale generation cannot attach");
       ack();check(context.intents.size()==3,"current generation attaches once");context.connect(2);
       check(ready==1,"current generation ready");connection.close("cleanup");
       check(context.released.size()==3,"all successful binds released once");break;
     case "attach_timeout":
       install();begin();ack();advance(15000);context.connect(1);
       check(ready==0 && closed==1 && context.released.size()==2,"expired attach cannot return token");break;
     case "attach_replaced":
       install();begin();ack();install();context.connect(1);
       check(ready==0 && closed==1 && context.released.size()==2,"replacement during attach rejected");break;
     case "stopped_looper":
       install();playerHandler.running=false;begin();
       check(ready==0 && closed==1 && context.intents.size()==1,"stopped player cannot be ready");break;
     case "attach_rejected":
       install();begin();context.accept=false;ack();
       check(ready==0 && closed==1 && context.released.size()==1,"failed bind not unbound as accepted");break;
     case "ambiguous":
       install();java.util.Map sessions=(java.util.Map)Class.forName("d4j").getField("c").get(null);
       Object first=sessions.get("fixture");install();sessions.put("another",first);begin();
       check(NativeMusicSession.find(context)==null,"do not select arbitrary player");advance(15000);
       check(ready==0 && closed==1,"ambiguous player cannot start");break;
     default:throw new AssertionError(args[0]);
   }
   check(android.media.browse.MediaBrowser.connects==0,"no legacy catalog browsing");
   connection.close("cleanup");check(closed==1,"single terminal callback");
 }
}''',
        }
        for name in ['BootstrapProvider', 'NativeMusicSession']:
            sources[f'ru/natro/music/{name}.java'] = (
                ROOT / 'music-mod/src/main/java/ru/natro/music' / f'{name}.java'
            ).read_text()
        sources['d4j.java'] = (ROOT / 'app/src/test/java/d4j.java').read_text()
        for name, data in sources.items():
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(data)
        result = subprocess.run(
            ['java', 'com.sun.tools.javac.Main', '-source', '8', '-target', '8', '-d', str(cls.root),
             *[str(cls.root / name) for name in sources]], capture_output=True, text=True,
        )
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_native_lifecycle(self):
        cases = ['deferred', 'absent', 'blocked_looper', 'cancel', 'released',
                 'replaced', 'generation', 'attach_timeout', 'attach_replaced',
                 'stopped_looper', 'attach_rejected', 'ambiguous']
        for case in cases:
            with self.subTest(case=case):
                result = subprocess.run(
                    ['java', '-cp', str(self.root), 'ru.natro.music.NativeReplay', case],
                    capture_output=True, text=True, timeout=8,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
