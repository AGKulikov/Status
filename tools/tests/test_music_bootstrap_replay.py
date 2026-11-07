"""Compile the production coordinator and replay slow IPC, expiry and duplicate callbacks."""
import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

class MusicBootstrapReplayTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory(); cls.root=Path(cls.tmp.name)
  sources={
   'android/os/SystemClock.java':'package android.os; public class SystemClock {public static volatile long now; public static long elapsedRealtime(){return now;}}',
   'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
   'android/os/Handler.java':'package android.os; public class Handler {public Handler(Looper l){} public boolean postDelayed(Runnable r,long d){return true;}}',
   'android/os/RemoteException.java':'package android.os; public class RemoteException extends Exception {}',
   'android/os/Bundle.java':'''package android.os; public class Bundle extends java.util.HashMap<String,Object> {
    public void putLong(String k,long v){put(k,v);} public long getLong(String k){return (Long)get(k);}
    public void putParcelable(String k,Object v){put(k,v);} public <T>T getParcelable(String k){return (T)get(k);}
    public String getString(String k){return (String)get(k);} public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}}''',
   'android/os/Parcel.java':'package android.os; public class Parcel {Object value; public static Parcel obtain(){return new Parcel();} public void setDataPosition(int i){} public void recycle(){}}',
   'android/os/ResultReceiver.java':'''package android.os; public class ResultReceiver {
    public ResultReceiver(Handler h){} protected void onReceiveResult(int c,Bundle b){} public void send(int c,Bundle b){onReceiveResult(c,b);}
    public void writeToParcel(Parcel p,int f){p.value=this;} public static Creator CREATOR=new Creator();
    public static class Creator {public ResultReceiver createFromParcel(Parcel p){return (ResultReceiver)p.value;}}}''',
   'android/net/Uri.java':'package android.net; public class Uri {public static Uri parse(String s){return new Uri();}}',
   'android/media/session/MediaSession.java':'package android.media.session; public class MediaSession {public static class Token{}}',
   'android/content/pm/ProviderInfo.java':'package android.content.pm; public class ProviderInfo {public boolean enabled,exported;public String packageName;}',
   'android/content/pm/PackageManager.java':'package android.content.pm; public class PackageManager {public static int SIGNATURE_MATCH=0;public ProviderInfo resolveContentProvider(String s,int f){return null;} public int checkSignatures(String a,String b){return 0;}}',
   'android/content/Context.java':'''package android.content; public class Context {
    public ContentResolver resolver=new ContentResolver(); public Context getApplicationContext(){return this;}
    public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
    public String getPackageName(){return "ru.natro.statuswidget";} public ContentResolver getContentResolver(){return resolver;}}''',
   'android/content/ContentResolver.java':'''package android.content; public class ContentResolver {
    public Runnable acquire=()->{}; public ContentProviderClient client=new ContentProviderClient();
    public ContentProviderClient acquireUnstableContentProviderClient(android.net.Uri u){acquire.run();return client;}}''',
   'android/content/ContentProviderClient.java':'''package android.content; import android.os.*; public class ContentProviderClient {
    public volatile int calls;public volatile boolean closed;public Bundle args;public String id;
    public Bundle call(String m,String id,Bundle args)throws RemoteException {this.args=args;this.id=id;calls++;Bundle b=new Bundle();b.put("accepted",true);return b;}
    public void close(){closed=true;}}''',
   'dezz/status/widget/diagnostics/DiagnosticJournal.java':'package dezz.status.widget.diagnostics; public class DiagnosticJournal {public static void infoAsync(String c,String m){}}',
   'dezz/status/widget/launcher/MediaResumeCommand.java':'''package dezz.status.widget.launcher; public class MediaResumeCommand {
    static volatile int plays;static class DispatchTrace{String result="SESSION_COMMAND",detail="test";}
    static DispatchTrace playBrowserSession(android.content.Context c,android.media.session.MediaSession.Token t,String p){plays++;return new DispatchTrace();}}''',
   'dezz/status/widget/launcher/MediaAutoResumeController.java':'''package dezz.status.widget.launcher;public class MediaAutoResumeController {
    static MediaResumeCommand.DispatchTrace guardedBrowserDispatch(android.content.Context c,java.util.function.BooleanSupplier p,java.util.function.Supplier<MediaResumeCommand.DispatchTrace>d){return p.getAsBoolean()?d.get():null;}}''',
   'dezz/status/widget/launcher/Replay.java':r'''package dezz.status.widget.launcher;
import android.content.*;import android.os.*;import java.lang.reflect.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class Replay {
 static Object req;static Context context=new Context();static AtomicBoolean permit=new AtomicBoolean(true);
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static Object request(int attempt)throws Exception{
  Class<?> c=Class.forName("dezz.status.widget.launcher.NatroMusicBootstrap$Request");
  Constructor<?> ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);
  Object r=ctor.newInstance(context,(java.util.function.BooleanSupplier)permit::get,attempt);
  Field f=NatroMusicBootstrap.class.getDeclaredField("current");f.setAccessible(true);f.set(null,r);return r;
 }
 static void invoke(Object r,String name)throws Exception{Method m=r.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(r);}
 static void finish(Object r)throws Exception{Method m=r.getClass().getDeclaredMethod("finish",String.class);m.setAccessible(true);m.invoke(r,"test_failure");}
 static void callback(ContentProviderClient c)throws Exception{
  Bundle b=new Bundle();b.put("id",c.id);b.putParcelable("token",new android.media.session.MediaSession.Token());
  ((ResultReceiver)c.args.getParcelable("callback")).send(34201,b);
 }
 static void await(java.util.function.BooleanSupplier p)throws Exception{long end=System.nanoTime()+2_000_000_000L;while(!p.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);check(p.getAsBoolean(),"worker timeout");}
 public static void main(String[] a)throws Exception{
  try{
   switch(a[0]){
    case "cold":
     req=request(1);context.resolver.acquire=()->SystemClock.now=30000;invoke(req,"prepare");
     check(context.resolver.client.calls==1,"cold startup must still prepare");
     check(context.resolver.client.args.getLong("deadline")==45000,"fresh short budget, bounded overall");
     check(context.resolver.client.closed,"provider released");break;
    case "expired":
     req=request(1);context.resolver.acquire=()->SystemClock.now=45000;invoke(req,"prepare");
     check(context.resolver.client.calls==0,"expired acquisition cannot prepare");check(context.resolver.client.closed,"release late provider");break;
    case "manual":
     req=request(1);context.resolver.acquire=()->permit.set(false);invoke(req,"prepare");
     check(context.resolver.client.calls==0,"manual cancellation");break;
    case "late":
     req=request(1);invoke(req,"prepare");ContentProviderClient old=context.resolver.client;
     request(2);callback(old);Thread.sleep(30);check(MediaResumeCommand.plays==0,"old callback cannot play");break;
    case "duplicate":
     req=request(1);invoke(req,"prepare");callback(context.resolver.client);callback(context.resolver.client);
     await(()->MediaResumeCommand.plays==1);Thread.sleep(30);check(MediaResumeCommand.plays==1,"duplicate PLAY");break;
    case "retry":
     req=request(1);finish(req);check(NatroMusicBootstrap.request(context,permit::get).equals("bootstrap_scheduled"),"retry admitted");
     await(()->context.resolver.client.calls==1);break;
    case "limit":
     req=request(3);finish(req);check(NatroMusicBootstrap.request(context,permit::get).equals("attempt_limit"),"bounded retries");break;
    case "blocked":
     CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
     context.resolver.acquire=()->{entered.countDown();try{release.await();}catch(Exception e){throw new RuntimeException(e);}};
     check(NatroMusicBootstrap.request(context,permit::get).equals("bootstrap_scheduled"),"initial request");check(entered.await(2,TimeUnit.SECONDS),"entered acquire");
     SystemClock.now=45000;check(NatroMusicBootstrap.request(context,permit::get).equals("worker_busy"),"no queued requests behind hung IPC");
     release.countDown();await(()->context.resolver.client.closed);check(context.resolver.client.calls==0,"late worker cannot prepare");break;
    default:throw new AssertionError(a[0]);
   }
   System.exit(0);
  }catch(Throwable t){t.printStackTrace();System.exit(1);}
 }
}'''
  }
  sources['dezz/status/widget/launcher/NatroMusicBootstrap.java']=(ROOT/'app/src/main/java/dezz/status/widget/launcher/NatroMusicBootstrap.java').read_text()
  for name,data in sources.items():
   p=cls.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
  subprocess.run(['java','com.sun.tools.javac.Main','-d',str(cls.root),*[str(cls.root/x) for x in sources]],check=True,capture_output=True)
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def test_replay(self):
  for case in ['cold','expired','manual','late','duplicate','retry','limit','blocked']:
   with self.subTest(case=case):
    subprocess.run(['java','-cp',str(self.root),'dezz.status.widget.launcher.Replay',case],check=True,capture_output=True,timeout=8)
