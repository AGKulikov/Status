"""Real presentation and persistence owners against deterministic Android API doubles."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import JAVA

STUBS = {
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/HandlerThread.java': 'package android.os; public class HandlerThread {public HandlerThread(String s){}public void start(){}public Looper getLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long uptimeMillis(){return Handler.now;}}',
    'android/os/Handler.java': '''package android.os;import java.util.*;
public class Handler {
 public static volatile long now;static long seq;
 static class Job {long due,id;Runnable r;Job(long due,Runnable r){this.due=due;this.r=r;id=seq++;}}
 static PriorityQueue<Job> q=new PriorityQueue<>(Comparator.<Job>comparingLong(j->j.due).thenComparingLong(j->j.id));
 public Handler(Looper l){}public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long d){synchronized(q){q.add(new Job(now+d,r));}return true;}
 public void removeCallbacks(Runnable r){synchronized(q){q.removeIf(j->j.r==r);}}
 public static void advance(long t){while(true){Job j;synchronized(q){if(q.isEmpty()||q.peek().due>t){now=t;return;}j=q.remove();now=j.due;}j.r.run();}}
}''',
    'android/view/Surface.java': 'package android.view;public class Surface {public boolean valid=true;public boolean isValid(){return valid;}}',
    'android/graphics/Bitmap.java': '''package android.graphics;public class Bitmap {
 public enum Config{ARGB_8888}public int color;
 public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap();}
 public void getPixels(int[] p,int a,int b,int c,int d,int w,int h){java.util.Arrays.fill(p,color);}
 public void recycle(){}
}''',
    'android/view/PixelCopy.java': '''package android.view;import android.graphics.Bitmap;import android.os.Handler;
public class PixelCopy {
 public static final int SUCCESS=0;public static String mode="transparent";
 public static volatile int calls;public static volatile Runnable late;
 public interface Listener{void onPixelCopyFinished(int status);}
 public static void request(Surface s,Bitmap b,Listener l,Handler h){
  b.color=mode.equals("white")?0xffffffff:0;
  if(mode.equals("blocked")){calls++;return;}
  if(mode.equals("late")){late=()->l.onPixelCopyFinished(0);calls++;return;}
  l.onPixelCopyFinished(mode.equals("failed")?1:0);calls++;
 }
}''',
    'android/content/SharedPreferences.java': '''package android.content;public interface SharedPreferences {
 Editor edit();interface Editor {Editor remove(String k);Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putString(String k,String v);boolean commit();}
}''',
    'dezz/status/widget/diagnostics/DiagnosticJournal.java': 'package dezz.status.widget.diagnostics;public class DiagnosticJournal {public static void infoAsync(String t,String s){}}',
    'OwnersReplay.java': r'''
import android.os.Handler;
import android.view.*;
import android.content.SharedPreferences;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dezz.status.widget.navigation.*;
import dezz.status.widget.media.RuntimePreferenceWriter;
public class OwnersReplay {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 static void waitCopy()throws Exception {long stop=System.nanoTime()+2_000_000_000L;while(PixelCopy.calls==0&&System.nanoTime()<stop)Thread.sleep(1);check(PixelCopy.calls>0,"copy worker ran");Handler.advance(Handler.now);}
 static class Prefs implements SharedPreferences {
  Map<String,Object> data=new HashMap<>();int commits,failures;
  public Editor edit(){return new Editor(){Map<String,Object> changes=new HashMap<>();
   public Editor remove(String k){changes.put(k,null);return this;}
   public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
   public Editor putInt(String k,int v){changes.put(k,v);return this;}
   public Editor putLong(String k,long v){changes.put(k,v);return this;}
   public Editor putString(String k,String v){changes.put(k,v);return this;}
   public boolean commit(){commits++;if(failures-->0)return false;data.putAll(changes);return true;}
  };}
 }
 public static void main(String[] args)throws Exception {
  String test=args[0];
  if(test.equals("coalesced_writes")){
   Prefs p=new Prefs();for(int i=0;i<100;i++)RuntimePreferenceWriter.put(p,"selected",i);
   RuntimePreferenceWriter.put(p,"other",true);check(p.commits==0,"no synchronous write on input");
   Handler.advance(200);check(p.commits==1&&p.data.get("selected").equals(99)&&p.data.get("other").equals(true),"coalesce without losing other keys");return;
  }
  if(test.equals("failed_write_retries")){
   Prefs p=new Prefs();p.failures=1;RuntimePreferenceWriter.put(p,"selected",1);Handler.advance(200);
   RuntimePreferenceWriter.put(p,"selected",2);Handler.advance(700);
   check(p.commits==2&&p.data.get("selected").equals(2),"newer value wins over failed old write");return;
  }
  if(test.equals("white_classifier")){
   check(MapBootstrapPolicy.opaqueWhite(new int[]{-1,-1,-1}),"opaque white rejected");
   for(int c:new int[]{0,0x00ffffff,0xff101010,0xff808080,0xffe0e0e0,0xff0099ff})check(!MapBootstrapPolicy.opaqueWhite(new int[]{c,c,c}),"transparent, dark and normal maps allowed");return;
  }
  PixelCopy.mode=test.equals("white_timeout")?"white":test.equals("copy_failed")?"failed":test.equals("copy_blocked")?"blocked":test.equals("revoked_callback")?"late":"transparent";
  MapStartupPresentation owner=new MapStartupPresentation();Surface surface=new Surface();AtomicInteger shown=new AtomicInteger();
  owner.onFrame(surface,10,shown::incrementAndGet);
  if(!test.equals("single_initial_buffer"))owner.onFrame(surface,11,shown::incrementAndGet);
  Handler.advance(199);check(shown.get()==0,"settle period protects initial callback");
  Handler.advance(200);waitCopy();
  if(test.equals("transparent_map")){check(shown.get()==1,"transparent roads-only must not need content heuristic");Handler.advance(1000);check(shown.get()==1,"show once");return;}
  if(test.equals("revoked_callback")){
   owner.reset();surface.valid=false;PixelCopy.late.run();Handler.advance(1000);check(shown.get()==0,"old result cannot show revoked surface");
   PixelCopy.mode="blocked";owner.onFrame(new Surface(),20,shown::incrementAndGet);Handler.advance(1800);check(shown.get()==1,"replacement keeps independent fallback");return;
  }
  check(shown.get()==0,"bootstrap stays hidden before deadline");Handler.advance(800);
  check(shown.get()==1,"hard deadline guarantees availability even with no successful copy");
 }
}''',
}


class BoundedOwnersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = Path(cls.tmp.name)
        paths = []
        for name, source in STUBS.items():
            p = cls.out / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(source)
            paths.append(str(p))
        paths += [str(JAVA / n) for n in ['navigation/MapBootstrapPolicy.java',
                   'navigation/MapStartupPresentation.java', 'media/RuntimePreferenceWriter.java']]
        r = subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(cls.out), *paths], capture_output=True, text=True)
        if r.returncode:
            raise AssertionError(r.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_bounded_presentation_and_persistence(self):
        for name in ['coalesced_writes', 'failed_write_retries', 'white_classifier',
                     'white_timeout', 'copy_failed', 'copy_blocked', 'transparent_map',
                     'single_initial_buffer', 'revoked_callback']:
            with self.subTest(name=name):
                r = subprocess.run(['java', '-cp', str(self.out), 'OwnersReplay', name], capture_output=True, text=True, timeout=8)
                self.assertEqual(r.returncode, 0, r.stderr)
