"""Compile the complete production observer against inert Android boundary doubles.

No Android routing/GPU/radio is emulated. Tests execute the actual observer, correlation,
UTF-8 tail and dump formatter, with every command-producing boundary forbidden.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/dezz/status/widget"

STUBS = {
    "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static long now=1000; public static long uptimeMillis(){return now;} }""",
    "android/os/Build.java": """package android.os;
public class Build { public static class VERSION {public static int SDK_INT=28;} }""",
    "android/os/Process.java": """package android.os;
public class Process {public static final int THREAD_PRIORITY_BACKGROUND=10;}""",
    "android/os/Looper.java": """package android.os;
public class Looper {public final String name; Looper(String n){name=n;}}""",
    "android/os/HandlerThread.java": """package android.os;
public class HandlerThread {final String name; public HandlerThread(String n,int p){name=n;}
public void start(){} public Looper getLooper(){return new Looper(name);}}""",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 public final Looper looper;
 static final Map<String,Deque<Runnable>> queues=new HashMap<>();
 public Handler(Looper l){looper=l;queues.putIfAbsent(l.name,new ArrayDeque<>());}
 public boolean post(Runnable r){queues.get(looper.name).add(r);return true;}
 public boolean postDelayed(Runnable r,long ms){return true;}
 public void removeCallbacks(Runnable r){queues.get(looper.name).removeIf(v->v==r);}
 public static void drain(String name){int n=0;Deque<Runnable> q=queues.get(name);
  while(q!=null&&!q.isEmpty()){if(++n>100)throw new AssertionError("unbounded work");q.remove().run();}}
}""",
    "android/content/ComponentName.java": """package android.content;
public class ComponentName {public ComponentName(Context c,Class<?> t){}}""",
    "android/content/IntentFilter.java": """package android.content;import java.util.*;
public class IntentFilter {public int priority;public final List<String> actions=new ArrayList<>();
 public void addAction(String s){actions.add(s);}public void setPriority(int n){priority=n;}}""",
    "android/content/Intent.java": """package android.content;
public class Intent {public static final String ACTION_MEDIA_BUTTON="android.intent.action.MEDIA_BUTTON";
 public static final String EXTRA_KEY_EVENT="android.intent.extra.KEY_EVENT";
 final String action;final Object event;public Intent(String a,Object e){action=a;event=e;}
 public String getAction(){return action;}
 @SuppressWarnings("unchecked") public <T>T getParcelableExtra(String name){return (T)event;}}
""",
    "android/content/BroadcastReceiver.java": """package android.content;
public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);
 public boolean isOrderedBroadcast(){return true;}
 public void abortBroadcast(){throw new AssertionError("must not consume");}
 public Object goAsync(){throw new AssertionError("must not hold broadcast");}}
""",
    "android/content/Context.java": """package android.content;
import android.os.Handler;import android.media.session.MediaSessionManager;
public class Context {
 public static final String MEDIA_SESSION_SERVICE="media_session";
 public static final int RECEIVER_EXPORTED=2;
 public final MediaSessionManager manager=new MediaSessionManager();
 public BroadcastReceiver receiver;public Handler handler;public IntentFilter filter;public int flags;
 public Context getApplicationContext(){return this;}
 public Object getSystemService(String name){return manager;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h){
  receiver=r;filter=f;handler=h;return null;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h,int flags){
  this.flags=flags;return registerReceiver(r,f,p,h);}
 public void unregisterReceiver(BroadcastReceiver r){if(receiver==r)receiver=null;}
 public void sendBroadcast(Intent i){throw new AssertionError("must not forward");}
 public void sendOrderedBroadcast(Intent i,String p){throw new AssertionError("must not forward");}
}
""",
    "android/view/KeyEvent.java": """package android.view;
public class KeyEvent {
 public static final int KEYCODE_HEADSETHOOK=79,KEYCODE_MEDIA_PLAY_PAUSE=85,KEYCODE_MEDIA_STOP=86,
 KEYCODE_MEDIA_NEXT=87,KEYCODE_MEDIA_PREVIOUS=88,KEYCODE_MEDIA_REWIND=89,
 KEYCODE_MEDIA_FAST_FORWARD=90,KEYCODE_MEDIA_PLAY=126,KEYCODE_MEDIA_PAUSE=127,
 KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25,KEYCODE_VOLUME_MUTE=164,KEYCODE_MUTE=91;
 final int key,action,repeat,device;final long down,event;
 public KeyEvent(int k,int a,int r,int d,long dt,long et){key=k;action=a;repeat=r;device=d;down=dt;event=et;}
 public int getKeyCode(){return key;}public int getAction(){return action;}
 public int getRepeatCount(){return repeat;}public int getDeviceId(){return device;}
 public long getDownTime(){return down;}public long getEventTime(){return event;}
 public int getSource(){return 257;}public int getFlags(){return 0;}public int getScanCode(){return 0;}
}
""",
    "android/media/MediaMetadata.java": """package android.media;public class MediaMetadata {}""",
    "android/media/session/PlaybackState.java": """package android.media.session;
public class PlaybackState {public int getState(){return 3;}public long getLastPositionUpdateTime(){return 1000;}}""",
    "android/media/session/MediaController.java": """package android.media.session;
import android.media.MediaMetadata;import android.os.Handler;
public class MediaController {
 public final String pkg;public Callback callback;public Handler handler;
 public MediaController(String p){pkg=p;}public Object getSessionToken(){return this;}
 public String getPackageName(){return pkg;}
 public void registerCallback(Callback c,Handler h){callback=c;handler=h;}
 public void unregisterCallback(Callback c){if(callback==c)callback=null;}
 public PlaybackState getPlaybackState(){return new PlaybackState();}
 public Object getTransportControls(){throw new AssertionError("must not control player");}
 public static class Callback {
  public void onPlaybackStateChanged(PlaybackState s){} public void onMetadataChanged(MediaMetadata m){}
  public void onSessionDestroyed(){}
 }
}
""",
    "android/media/session/MediaSessionManager.java": """package android.media.session;
import java.util.*;import android.content.ComponentName;import android.os.Handler;
public class MediaSessionManager {
 public final List<MediaController> controllers=new ArrayList<>();
 public boolean denied;public int queries;public OnActiveSessionsChangedListener listener;
 public interface OnActiveSessionsChangedListener{void onActiveSessionsChanged(List<MediaController> c);}
 public void addOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l,ComponentName c,Handler h){
  if(denied)throw new SecurityException();listener=l;}
 public void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener l){listener=null;}
 public List<MediaController> getActiveSessions(ComponentName c){queries++;if(denied)throw new SecurityException();return controllers;}
}
""",
    "dezz/status/widget/MediaNotificationListener.java": """package dezz.status.widget;
public class MediaNotificationListener {}""",
    "dezz/status/widget/diagnostics/DiagnosticJournal.java": """package dezz.status.widget.diagnostics;
import java.util.*;
public class DiagnosticJournal {public static final List<String> lines=new ArrayList<>();
 public static boolean isEnabled(){return true;}
 public static void infoAsync(String tag,String s){lines.add(s);}}
""",
    "dezz/status/widget/diagnostics/ActionRecorder.java": """package dezz.status.widget.diagnostics;
public class ActionRecorder {public static final String SOURCE_STEERING_KEY="steering_key";
 public interface RecordingListener{void onRecordingChanged(boolean b);}
 public static void addRecordingListener(RecordingListener l){l.onRecordingChanged(false);}
 public static Object object(Object... values){return values;}
 public static void recordAsync(String s,String e,Object d){}
}
""",
}

REPLAY = r'''package dezz.status.widget.diagnostics;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
import android.content.*;import android.os.*;import android.view.*;import android.media.session.*;
public class PassiveMediaReplay {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 static String logs(){return String.join("\n",DiagnosticJournal.lines);}
 static Context boot(){Context c=new Context();c.manager.controllers.add(new MediaController("player.one"));
  c.manager.controllers.add(new MediaController("player.two"));SteeringKeyDiagnostics.initialize(c);
  Handler.drain("media-key-observer");Handler.drain("media-session-observer");return c;}
 static void key(Context c,String action,int code,int type,int repeat,long down,long event){
  c.receiver.onReceive(c,new Intent(action,new KeyEvent(code,type,repeat,7,down,event)));}
 static void passiveForwardingAndAllPlayers(){
  Context c=boot();check(c.filter.priority<0);check(c.filter.actions.size()==2);
  check(c.handler.looper.name.equals("media-key-observer"));
  check(c.manager.controllers.get(0).callback!=null&&c.manager.controllers.get(1).callback!=null);
  int queries=c.manager.queries;DiagnosticJournal.lines.clear();
  key(c,SteeringKeyDiagnostics.IEDIA_BUTTON,87,0,0,900,900);
  SystemClock.now=1020;key(c,Intent.ACTION_MEDIA_BUTTON,87,0,0,900,900);
  SystemClock.now=1040;key(c,Intent.ACTION_MEDIA_BUTTON,87,1,0,900,1010);
  check(c.manager.queries==queries,"input performed Binder query");
  check(DiagnosticJournal.lines.size()==3);
  for(String s:DiagnosticJournal.lines)check(s.contains("input_sequence=1,"));
  c.manager.controllers.get(1).callback.onPlaybackStateChanged(new PlaybackState());
  check(logs().contains("package=player.two"));check(logs().contains("after_input_sequence=1"));
  check(logs().contains("association=temporal_only"));check(logs().contains("commands_sent=0"));
 }
 static void disabledCaptureUnregistersAndRejectsLateCallbacks(){
  Context c=boot();MediaController.Callback old=c.manager.controllers.get(0).callback;
  android.media.session.MediaSessionManager.OnActiveSessionsChangedListener oldListener=c.manager.listener;
  SteeringKeyDiagnostics.debugChanged(false);Handler.drain("media-key-observer");
  Handler.drain("media-session-observer");check(c.receiver==null);check(c.manager.listener==null);
  int before=DiagnosticJournal.lines.size();old.onPlaybackStateChanged(new PlaybackState());
  check(DiagnosticJournal.lines.size()==before);
  SteeringKeyDiagnostics.debugChanged(true);Handler.drain("media-key-observer");
  Handler.drain("media-session-observer");before=DiagnosticJournal.lines.size();
  old.onPlaybackStateChanged(new PlaybackState());check(DiagnosticJournal.lines.size()==before);
  oldListener.onActiveSessionsChanged(java.util.Collections.emptyList());
  check(DiagnosticJournal.lines.size()==before&&c.manager.controllers.get(0).callback!=null);
  old.onSessionDestroyed();Handler.drain("media-session-observer");
  check(DiagnosticJournal.lines.size()==before);
 }
 static void missingPermissionDoesNotLoseBroadcastObservation(){
  Context c=new Context();c.manager.denied=true;SteeringKeyDiagnostics.initialize(c);
  Handler.drain("media-key-observer");Handler.drain("media-session-observer");
  check(logs().contains("notification_access_required"));
  key(c,SteeringKeyDiagnostics.IEDIA_BUTTON,88,0,0,900,900);
  check(logs().contains("stage=broadcast_observed"));
 }
 static void ordinaryTypingIsExcludedAndInputFloodIsBounded(){
  Context c=boot();DiagnosticJournal.lines.clear();
  key(c,Intent.ACTION_MEDIA_BUTTON,29,0,0,900,900);check(DiagnosticJournal.lines.isEmpty());
  for(int i=0;i<1000;i++)key(c,Intent.ACTION_MEDIA_BUTTON,87,0,0,900+i,900);
  check(DiagnosticJournal.lines.size()<=32);
 }
 static void modernReceiverIsExplicitlyExported(){Build.VERSION.SDK_INT=33;Context c=boot();
  check(c.flags==Context.RECEIVER_EXPORTED);}
 static void correlationHasNoFalseAckAndExpires(){
  MediaKeyObservation o=new MediaKeyObservation();
  MediaKeyObservation.Press first=o.received(87,1,800,800,0,0,1000);
  check(first.sequence==o.received(87,1,800,800,0,0,1010).sequence);
  check(first.sequence==o.received(87,1,800,900,1,0,1030).sequence);
  check(o.candidate(1040).count==1);
  o.received(88,1,950,950,0,0,1050);check(o.candidate(1060).count==2);
  check(o.candidate(17000).sequence==0);
  o.received(87,2,17500,17900,1,0,18000);check(o.candidate(18001).sequence==0);
  o.received(87,2,18000,19000,0,0,18001);check(o.candidate(18002).sequence==0);
  o.clear();for(int i=0;i<100;i++)o.received(87,1,20000+i,20000+i,0,0,20100+i);
  check(o.candidate(20200).count==32);
 }
 static void utf8TailIsBoundedAndKeepsLineBreaks()throws Exception{
  File f=File.createTempFile("natro-tail-", ".txt");
  try(RandomAccessFile out=new RandomAccessFile(f,"rw")){
   String small="Первая строка\nВторая 🌍\n";out.write(small.getBytes(StandardCharsets.UTF_8));
   check(BoundedUtf8Tail.read(f,1000).equals(small));
   out.setLength(8L*1024*1024*1024);out.seek(out.length());
   String suffix="Кнопка 🌍\n".repeat(400);out.write(suffix.getBytes(StandardCharsets.UTF_8));
   String tail=BoundedUtf8Tail.read(f,101);check(tail.length()<=101);check(tail.startsWith("…\n"));
   check(tail.endsWith("Кнопка 🌍\n"));check(!tail.contains("\ufffd"));
   out.write(new byte[]{(byte)0xf0,(byte)0x9f});
   check(!BoundedUtf8Tail.read(f,100).contains("\ufffd"));
  }finally{check(f.delete());}
 }
 static void dumpReservesQueuedWorkAndWriterEvenWithLongStacks(){
  Thread main=new Thread("main");Thread queued=new Thread("queued-work");
  Map<Thread,StackTraceElement[]> stacks=new LinkedHashMap<>();
  StackTraceElement[] normal={new StackTraceElement("Worker","idle","Worker.java",1)};
  for(int i=0;i<100;i++)stacks.put(new Thread("worker-"+i),normal);
  StackTraceElement[] huge=new StackTraceElement[100];
  Arrays.fill(huge,new StackTraceElement("Main"+"x".repeat(300),"blocked","Main.java",1));
  stacks.put(main,huge);stacks.put(queued,normal);
  stacks.put(new Thread("arbitrary-writer"),new StackTraceElement[]{
   new StackTraceElement("android.app.SharedPreferencesImpl","writeToFile","X.java",1)});
  String text=ThreadDumpFormatter.format(main,stacks);
  check(text.startsWith("THREAD main"));check(text.contains("THREAD queued-work"));
  check(text.contains("SharedPreferencesImpl.writeToFile"));check(text.length()<=14000);
 }
 public static void main(String[] args)throws Exception{PassiveMediaReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}
'''


class PassiveMediaDiagnosticsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-passive-media-")
        cls.folder = Path(cls.temp.name)
        sources = dict(STUBS)
        for name in ("SteeringKeyDiagnostics", "MediaKeyObservation", "BoundedUtf8Tail", "ThreadDumpFormatter"):
            sources[f"dezz/status/widget/diagnostics/{name}.java"] = (JAVA / f"diagnostics/{name}.java").read_text()
        sources["dezz/status/widget/diagnostics/PassiveMediaReplay.java"] = REPLAY
        paths = []
        for relative, value in sources.items():
            path = cls.folder / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)
            paths.append(str(path))
        compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        result = subprocess.run([*compiler, "-d", str(cls.folder), *paths], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, name):
        result = subprocess.run(["java", "-cp", str(self.folder),
                                 "dezz.status.widget.diagnostics.PassiveMediaReplay", name],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_real_observer_does_not_forward_and_watches_both_players(self):
        self.replay("passiveForwardingAndAllPlayers")

    def test_unregister_and_late_callback_generation(self):
        self.replay("disabledCaptureUnregistersAndRejectsLateCallbacks")

    def test_permission_gap_is_explicit_without_blocking_input(self):
        self.replay("missingPermissionDoesNotLoseBroadcastObservation")

    def test_key_privacy_and_rate_budget(self):
        self.replay("ordinaryTypingIsExcludedAndInputFloodIsBounded")

    def test_android_33_receiver_flags(self):
        self.replay("modernReceiverIsExplicitlyExported")

    def test_real_correlation_identity_ambiguity_expiry_and_cap(self):
        self.replay("correlationHasNoFalseAckAndExpires")

    def test_sparse_eight_gib_file_and_utf8_boundaries(self):
        self.replay("utf8TailIsBoundedAndKeepsLineBreaks")

    def test_queue_writer_dump_survives_caps(self):
        self.replay("dumpReservesQueuedWorkAndWriterEvenWithLongStacks")
