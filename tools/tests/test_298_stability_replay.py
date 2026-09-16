"""Replay the production queues/identity/branch geometry without an Android device."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StabilityReplay(unittest.TestCase):
    def run_java(self, package, name, code, sources):
        with tempfile.TemporaryDirectory(prefix="natro-298-") as tmp:
            folder = Path(tmp)
            harness = folder / (name + ".java")
            harness.write_text("package " + package + ";\n" + code)
            built = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", tmp,
                                    str(harness), *[str(ROOT / p) for p in sources]], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run(["java", "-cp", tmp, package + "." + name], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stderr)

    def test_slow_watchdog_coalesces_and_cancel_wins(self):
        self.run_java("dezz.status.widget", "QueueReplay", r'''
import java.util.*;import java.util.concurrent.*;
public class QueueReplay {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  List<Runnable> work=new ArrayList<>();List<String> events=new ArrayList<>();
  LatestTaskQueue queue=new LatestTaskQueue(work::add);
  CompletableFuture<Void> first=queue.submit(()->{
   events.add("slow-arm");
   queue.submit(()->events.add("old-arm"));
   queue.submit(()->events.add("new-arm"));
   queue.submit(()->events.add("cancel"));
  });
  check(!first.isDone()&&work.size()==1);work.remove(0).run();
  check(events.equals(Arrays.asList("slow-arm","cancel"))&&first.isDone()&&work.isEmpty());
  CompletableFuture<Void> stale=queue.submit(()->events.add("stale"));
  CompletableFuture<Void> latest=queue.submit(()->events.add("new-start"));
  check(work.size()==1&&!stale.isDone());work.remove(0).run();
  check(stale.isDone()&&latest.isDone()&&!events.contains("stale"));
  queue.submit(()->{throw new IllegalStateException();});work.remove(0).run();
  queue.submit(()->events.add("recovered"));work.remove(0).run();
  check(events.get(events.size()-1).equals("recovered"));
 }
}''', ["app/src/main/java/dezz/status/widget/LatestTaskQueue.java"])

    def test_timeline_partial_metadata_and_boot_session(self):
        self.run_java("dezz.status.widget.launcher", "MediaReplay", r'''
public class MediaReplay {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  check(MediaTimelineIdentity.matches("Song\u00a0 A", "Artist", "song A", "ARTIST"));
  check(!MediaTimelineIdentity.matches("Song A", "Artist", "Song B", "Artist"));
  check(!MediaTimelineIdentity.matches("Song A", "Artist", "Song A", "Other artist"));
  check(!MediaTimelineIdentity.matches("", "Artist", "", "Artist"));
  check(MediaTimelineIdentity.mayRetainDuration(true,"123","123","Song A","Artist","Song A","Artist"));
  check(!MediaTimelineIdentity.mayRetainDuration(true,"123","456","Song A","Artist","Song A","Artist"));
  check(!MediaTimelineIdentity.mayRetainDuration(false,"123","123","Song A","Artist","Song A","Artist"));
  check(YandexColdSessionPolicy.mayPlayUnready(true,false));
  check(!YandexColdSessionPolicy.mayPlayUnready(false,false));
  check(!YandexColdSessionPolicy.mayPlayUnready(true,true));
 }
}''', ["app/src/main/java/dezz/status/widget/launcher/MediaTimelineIdentity.java",
       "app/src/main/java/dezz/status/widget/launcher/YandexColdSessionPolicy.java"])

    def test_branch_distance_bends_short_branch_rejoin_and_common_prefix(self):
        self.run_java("ru.natro.navigation", "BranchReplay", r'''
import java.util.*;
public class BranchReplay {
 static final double DEG=180d/(Math.PI*6371000d);
 static double[] p(double x,double y){return new double[]{y*DEG,x*DEG};}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  double[][] active={p(0,0),p(0,1000)};
  List<AlternativeBranchAnchors.Anchor> anchors=AlternativeBranchAnchors.candidates(
   new double[][]{p(0,0),p(200,0),p(200,400)},0,0,active,0,0);
  check(anchors.size()==5);AlternativeBranchAnchors.Anchor a=anchors.get(0);
  check(Math.abs(a.meters-300)<.1 && Math.abs(a.longitude/DEG-200)<.1 && Math.abs(a.latitude/DEG-100)<.1);
  anchors=AlternativeBranchAnchors.candidates(new double[][]{p(0,0),p(60,0),p(60,60),p(0,60),p(0,1000)},0,0,active,0,0);
  check(!anchors.isEmpty());
  for(AlternativeBranchAnchors.Anchor shortA:anchors)check(shortA.meters<180&&shortA.longitude/DEG>6);
  check(AlternativeBranchAnchors.candidates(active,0,0,active,0,0).isEmpty());
  double[][] prefix={p(0,-100),p(0,0),p(400,0)};
  anchors=AlternativeBranchAnchors.candidates(prefix,1,0,active,0,0);
  check(!anchors.isEmpty()&&anchors.get(0).longitude/DEG>299);
  check(AlternativeBranchAnchors.candidates(prefix,99,0,active,0,0).isEmpty());
  check(AlternativeBranchAnchors.candidates(prefix,1,Double.NaN,active,0,0).isEmpty());
  check(AlternativeBranchAnchors.candidates(new double[][]{p(0,0),p(40,0)},0,0,active,0,0).get(0).meters<40);
  // Fork inside a segment, repeated zero-length vertices and a different route are fenced.
  anchors=AlternativeBranchAnchors.candidates(new double[][]{p(-100,0),p(100,0),p(100,0),p(500,0)},0,.5,active,0,0);
  check(!anchors.isEmpty()&&Math.abs(anchors.get(0).longitude/DEG-300)<.1);
  check(AlternativeBranchAnchors.candidates(prefix,1,0,new double[][]{p(1000,0),p(1000,500)},0,0).isEmpty());
 }
}''', ["navigator-mod/src/main/java/ru/natro/navigation/AlternativeBranchAnchors.java"])

    def test_route_worker_and_hidden_markers(self):
        transport = (ROOT / "app/src/main/java/dezz/status/widget/phone/transport/v2/android/AndroidCentralTransportV2.java").read_text()
        self.assertIn('new HandlerThread("NatroAncsRouteA")', transport)
        self.assertNotIn("new Handler(Looper.getMainLooper())", transport)
        # An exception between native placemark allocation and setIcon must leave no default pin.
        events = (ROOT / "navigator-mod/src/main/java/ru/natro/navigation/RouteRoadEventMapLayer.java").read_text()
        hidden = events.index("hideUntilTextured(placemark)")
        textured = events.index('invoke(placemark, "setIcon"')
        visible = events.index('invoke(placemark, "setVisible"')
        self.assertLess(hidden, textured)
        self.assertLess(textured, visible)


if __name__ == "__main__":
    unittest.main()
