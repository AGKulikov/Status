"""Run production diagnostic state/PixelCopy scheduling with a deterministic Android clock."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "app/src/main/java/dezz/status/widget/navigation"


class MapStartupDiagnosticsReplay(unittest.TestCase):
    def test_pixels_lifecycle_disabled_and_stuck_copy(self):
        fixtures = {
            "android/os/SystemClock.java": """package android.os;
public final class SystemClock { public static long now; public static long elapsedRealtime(){return now;} }""",
            "android/os/Process.java": "package android.os; public class Process {public static final int THREAD_PRIORITY_BACKGROUND=10;}",
            "android/os/Looper.java": """package android.os;
public class Looper { public final String name; public Looper(String n){name=n;} }""",
            "android/os/HandlerThread.java": """package android.os;
public class HandlerThread {final Looper l; public HandlerThread(String n,int p){l=new Looper(n);}
public void start(){} public Looper getLooper(){return l;} }""",
            "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 public static String executing="UI"; static long seq;
 static class Job {Handler h; Runnable r; long at,id;Job(Handler h,Runnable r,long at){this.h=h;this.r=r;this.at=at;id=seq++;}}
 static final PriorityQueue<Job> q=new PriorityQueue<>((a,b)->a.at==b.at?Long.compare(a.id,b.id):Long.compare(a.at,b.at));
 final Looper l; public Handler(Looper l){this.l=l;}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long d){q.add(new Job(this,r,SystemClock.now+d));return true;}
 public void removeCallbacks(Runnable r){q.removeIf(j->j.h==this&&j.r==r);}
 public static int queued(){return q.size();}
 public static void until(long t){int cap=0;while(!q.isEmpty()&&q.peek().at<=t){
  if(++cap>20000)throw new AssertionError("unbounded tasks");Job j=q.remove();SystemClock.now=j.at;
  executing=j.h.l.name;j.r.run();executing="UI";}SystemClock.now=t;}
 public static void drain(){until(SystemClock.now);}
} """,
            "android/graphics/Bitmap.java": """package android.graphics;
public class Bitmap {
 public enum Config{ARGB_8888} public static int live; boolean recycled;
 public int color=0xffffffff; public boolean patterned;
 public static Bitmap createBitmap(int w,int h,Config c){live++;return new Bitmap();}
 public void getPixels(int[] p,int o,int s,int x,int y,int w,int h){
  if(recycled)throw new AssertionError("use after recycle");for(int i=0;i<p.length;i++)p[i]=patterned&&i%2==0?0xff102030:color;}
 public boolean isRecycled(){return recycled;} public void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;live--;}
} """,
            "android/view/Surface.java": "package android.view; public class Surface {public boolean valid=true; public boolean isValid(){return valid;}}",
            "android/view/PixelCopy.java": """package android.view;
import android.os.Handler;import android.graphics.Bitmap;
public class PixelCopy {
 public static final int SUCCESS=0,ERROR_UNKNOWN=1,ERROR_TIMEOUT=2,ERROR_SOURCE_NO_DATA=3,ERROR_SOURCE_INVALID=4,ERROR_DESTINATION_INVALID=5;
 public interface OnPixelCopyFinishedListener{void onPixelCopyFinished(int result);}
 public static int requests, result=SUCCESS, color=0xffffffff; public static boolean hold,patterned;
 static Bitmap waitingBitmap; static Handler waitingHandler; static OnPixelCopyFinishedListener waiting;
 public static void request(Surface s,Bitmap b,OnPixelCopyFinishedListener l,Handler h){
  if(!Handler.executing.equals("NatroMapPixelCopy"))throw new AssertionError("copy on "+Handler.executing);
  requests++; if(waiting!=null)throw new AssertionError("parallel/queued copy");
  if(hold){waitingBitmap=b;waitingHandler=h;waiting=l;}else{b.color=color;b.patterned=patterned;h.post(()->l.onPixelCopyFinished(result));}}
 public static void complete(int r){Bitmap b=waitingBitmap;Handler h=waitingHandler;OnPixelCopyFinishedListener l=waiting;
  if(b.isRecycled())throw new AssertionError("recycled pending GPU target");waiting=null;waitingBitmap=null;
  b.color=color;b.patterned=patterned;h.post(()->l.onPixelCopyFinished(r));}
} """,
            "dezz/status/widget/diagnostics/DiagnosticJournal.java": """package dezz.status.widget.diagnostics;
import java.util.*; public class DiagnosticJournal {
 public static boolean enabled;public static final List<String> lines=new ArrayList<>();
 public static boolean isEnabled(){return enabled;}
 public static void infoAsync(String c,String m){if(enabled)lines.add(c+" "+m);}
} """,
            "dezz/status/widget/navigation/TraceReplay.java": r"""package dezz.status.widget.navigation;
import android.os.*;import android.graphics.Bitmap;import android.view.*;
import dezz.status.widget.diagnostics.DiagnosticJournal;import java.util.*;
public class TraceReplay {
 static void check(boolean b){if(!b)throw new AssertionError(DiagnosticJournal.lines.toString());}
 static boolean has(String a,String b){return DiagnosticJournal.lines.stream().anyMatch(s->s.contains(a)&&s.contains(b));}
 static void enabled(boolean value){DiagnosticJournal.enabled=value;MapStartupDiagnostics.journalChanged(value);Handler.drain();}
 static int[] fill(int color){int[] p=new int[576];Arrays.fill(p,color);return p;}
 public static void main(String[] args){
  MapStartupFrameStats stats=new MapStartupFrameStats();
  MapStartupFrameStats.Sample white=MapStartupFrameStats.classify(fill(0xfffdfcfe));
  check(white.kind.equals("white_candidate"));
  check(MapStartupFrameStats.classify(fill(0xff000000)).kind.equals("black_candidate"));
  check(MapStartupFrameStats.classify(fill(0)).kind.equals("transparent"));
  check(MapStartupFrameStats.classify(fill(0xff808080)).kind.equals("uniform"));
  int[] roads=fill(0);for(int i=0;i<roads.length;i+=13)roads[i]=0xff30cf10;
  check(MapStartupFrameStats.classify(roads).kind.equals("content_candidate"));
  int[] map=fill(0xffffffff);for(int i=0;i<map.length;i+=4)map[i]=0xff102030;
  MapStartupFrameStats.Sample content=MapStartupFrameStats.classify(map);
  check(content.kind.equals("content_candidate"));
  stats.accept(white,100);stats.accept(white,600);stats.gap();stats.accept(white,5600);
  stats.accept(content,6100);stats.accept(white,6600);
  check(stats.whiteEpisodes==3&&stats.whiteSampleSpanMs==500&&stats.firstContentMs==6100);

  // Disabled startup has no tasks/pixel allocation. Later enable observes even zero callbacks.
  Surface first=new Surface();MapStartupDiagnostics.begin(false,first,1,500,200,160);
  check(Handler.queued()==0&&PixelCopy.requests==0&&Bitmap.live==0);
  enabled(true);check(PixelCopy.requests==1&&has("generation=1,","first_update_ms=-1"));
  MapStartupDiagnostics.presentation(false,first,1f,0,0,true,true);
  MapStartupDiagnostics.dispatch(false,1,true);MapStartupDiagnostics.readiness(false,1,true);
  MapStartupDiagnostics.frame(false,first,17);Handler.drain();
  PixelCopy.patterned=true;Handler.until(1000);
  check(has("generation=1,","state=white_candidate")&&has("generation=1,","state=content_candidate"));
  check(has("generation=1,","mapkit_ready=true"));
  // Copy failures are not declared healthy/white and do not join white duration spans.
  PixelCopy.result=PixelCopy.ERROR_SOURCE_NO_DATA;Handler.until(1500);
  check(has("generation=1,","state=copy_source_no_data"));
  PixelCopy.result=PixelCopy.ERROR_TIMEOUT;Handler.until(2000);
  check(has("generation=1,","state=copy_timeout"));
  PixelCopy.result=PixelCopy.SUCCESS;

  // Stalled request: one global slot, no new allocations, heartbeat/timeout still run.
  PixelCopy.hold=true;Handler.until(2500);int stuckAt=PixelCopy.requests;
  MapStartupDiagnostics.begin(true,new Surface(),2,800,500,160);Handler.until(6000);
  check(PixelCopy.requests==stuckAt&&Bitmap.live==1);
  check(has("generation=1,","copy_pending_timeout")&&has("profile=cluster","copy_worker_busy"));
  // Superseded producer cannot label its replacement white or recycle a GPU-owned target early.
  MapStartupDiagnostics.begin(false,new Surface(),3,600,200,160);Handler.drain();
  PixelCopy.color=0xffffffff;PixelCopy.patterned=false;PixelCopy.complete(PixelCopy.SUCCESS);Handler.drain();
  check(!has("generation=3,","state=white_candidate")&&Bitmap.live==0);
  PixelCopy.hold=false;PixelCopy.patterned=true;Handler.until(7000);
  check(has("generation=3,","state=content_candidate")&&has("generation=2,","state=content_candidate"));

  // Journal off mid-copy: callback only frees its bitmap, cannot publish results or rearm work.
  PixelCopy.hold=true;Handler.until(7500);int requestsBeforeOff=PixelCopy.requests;
  enabled(false);int logsBeforeOff=DiagnosticJournal.lines.size();Handler.until(8000);
  PixelCopy.complete(PixelCopy.SUCCESS);Handler.drain();Handler.until(10000);
  check(PixelCopy.requests==requestsBeforeOff&&Bitmap.live==0&&DiagnosticJournal.lines.size()==logsBeforeOff);
  PixelCopy.hold=false;enabled(true);
  int restartedAt=PixelCopy.requests;Handler.until(131000);
  check(PixelCopy.requests-restartedAt<=128&&Bitmap.live==0);
  check(has("generation=3,","reason=observation_limit")&&has("generation=2,","physical_display_verified=false"));
  int endedAt=PixelCopy.requests;Handler.until(150000);check(PixelCopy.requests==endedAt);
  // A real bridge reconnect starts a fresh observation window even on the same Surface generation.
  MapStartupDiagnostics.dispatch(false,3,true);MapStartupDiagnostics.bridgeLost();
  MapStartupDiagnostics.dispatch(false,3,true);Handler.drain();
  check(PixelCopy.requests>endedAt&&has("generation=3,","disconnects=1"));
  MapStartupDiagnostics.end(false,3,"test_end");MapStartupDiagnostics.end(true,2,"test_end");Handler.drain();
  check(Handler.queued()==0);
 }
} """,
        }
        with tempfile.TemporaryDirectory(prefix="natro-map-trace-") as tmp:
            folder = Path(tmp)
            for relative, content in fixtures.items():
                path = folder / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            sources = [str(folder / name) for name in fixtures]
            sources += [str(PACKAGE / name) for name in ("MapStartupDiagnostics.java", "MapStartupFrameStats.java")]
            built = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", tmp, *sources], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run(["java", "-cp", tmp, "dezz.status.widget.navigation.TraceReplay"], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stderr)

    def test_observation_cannot_gate_presentation(self):
        source = (PACKAGE / "MapStartupDiagnostics.java").read_text()
        for forbidden in ("getBitmap(", "setAlpha(", "setVisibility(", ".await(", "Thread.sleep(", "getMainLooper("):
            self.assertNotIn(forbidden, source)
        endpoint = (PACKAGE / "NavigationHudEndpointService.java").read_text()
        self.assertIn("MapStartupDiagnostics.begin(true, recovered.surface", endpoint)
        for relative in ("hud/HudCompositeView.java", "instrument/InstrumentPanelView.java"):
            view = (PACKAGE.parent / relative).read_text()
            self.assertIn("MapStartupDiagnostics.frame(", view)
            self.assertIn("map shown", view)
            self.assertNotIn("MapFirstFrameDetector", view)


if __name__ == "__main__":
    unittest.main()
