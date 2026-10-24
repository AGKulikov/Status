"""Replay the production shared recognizer and XSF qualification gate together."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MediaGesturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-media-gestures-")
        cls.out = Path(cls.temp.name)
        source = cls.out / "MediaGesturesReplay.java"
        source.write_text(r'''
import java.util.*;
import dezz.status.widget.media.*;
public final class MediaGesturesReplay {
  static void check(boolean value, Object why) { if (!value) throw new AssertionError(why); }
  static class Clock implements ButtonGestureEngine.Scheduler {
    long now=10000, sequence;
    class Task {long at,id;Runnable run;Task(long ms,Runnable r){at=now+ms;id=sequence++;run=r;}}
    List<Task> tasks=new ArrayList<>();
    public Object after(long ms,Runnable r){Task t=new Task(ms,r);tasks.add(t);return t;}
    public void cancel(Object token){tasks.remove(token);}
    void tick(long ms){long end=now+ms;while(true){Task next=null;for(Task t:tasks)
      if(t.at<=end&&(next==null||t.at<next.at||(t.at==next.at&&t.id<next.id)))next=t;
      if(next==null)break;tasks.remove(next);now=next.at;next.run.run();}now=end;}
  }
  static class F implements ButtonGestureEngine.Bindings, ButtonGestureEngine.Output, MediaGestureGate.Output {
    Clock clock=new Clock(); Map<String,Integer> assignments=new HashMap<>();List<String> out=new ArrayList<>();
    MediaGestureGate gate=new MediaGestureGate(clock,this);ButtonGestureEngine engine=new ButtonGestureEngine(clock,this,this);
    public boolean enabled(VehicleButton b){return true;}
    public int actionId(String g,String n){return assignments.getOrDefault(g+n,VehicleButton.defaultAction(g,n));}
    public boolean knobVolume(){return false;} public boolean musicActive(){return false;}
    public boolean driveMenuShowing(){return false;} public boolean stockMediaSource(){return false;}
    public void stockSrc(){} public void volume(int d){} public void driveMenu(int d){} public void starHeld(){}
    public void action(String g,String n){gate.recognized(g,n);}
    public void confirmed(String g,String n){out.add(g+":"+n);}
    public void diagnostic(String g,String outcome){}
    public void diagnostic(int c,String n,String o){if(o.equals("no_assignment"))gate.discard(c);}
    void bind(int key,String gesture){assignments.put(VehicleButton.bindingGroup(key)+gesture,5);}
    void edge(int c,boolean down,long source){
      long began=gate.downAt(c),release=gate.lastRelease(c);
      if(down&&release>0&&source-release>600)engine.flushClicks(c);
      if(gate.physical(c,down,source,clock.now))engine.input(c,down,down?clock.now-source:source-began);
    }
    void down(int c){edge(c,true,clock.now);}void up(int c){edge(c,false,clock.now);}
    void nativeKey(int code){gate.nativeMedia(code,clock.now,clock.now);}
    void click(int c){down(c);clock.tick(40);up(c);nativeKey(VehicleButton.mediaCode(VehicleButton.bindingGroup(c)));}
    void expect(String... expected){check(out.equals(Arrays.asList(expected)),out+" != "+Arrays.toString(expected));}
    void reset(){engine.reset();gate.reset();}
  }
  static void ordinaryAndDuplicates(){for(int key:new int[]{200085,200087,200088}){
    F f=new F();f.down(key);f.down(key);f.clock.tick(40);f.up(key);f.expect();
    int android=VehicleButton.mediaCode(VehicleButton.bindingGroup(key));f.nativeKey(android);
    f.nativeKey(android);f.up(key);f.clock.tick(2000);f.expect(VehicleButton.bindingGroup(key)+":1");
  }}
  static void multiplicity(){for(int key:new int[]{200085,200087,200088})for(int n=1;n<=3;n++){
    F f=new F();f.bind(key,"2");f.bind(key,"3");
    for(int i=0;i<n;i++){f.click(key);f.clock.tick(80);}f.clock.tick(600);
    f.expect(VehicleButton.bindingGroup(key)+":"+n);
  }}
  static void hold(){for(int key:new int[]{200085,200087,200088}){
    F f=new F();f.bind(key,"long");f.down(key);f.clock.tick(999);f.expect();
    f.clock.tick(1);f.expect(); // physical hold alone is not evidence of media context
    f.clock.tick(500);f.nativeKey(key==200087?90:key==200088?89:85);
    f.expect(VehicleButton.bindingGroup(key)+":long");
    for(int i=0;i<10;i++){f.clock.tick(50);f.nativeKey(key==200087?90:key==200088?89:85);}
    f.up(key);f.clock.tick(2000);f.expect(VehicleButton.bindingGroup(key)+":long");
  }}
  static void clickThenHold(){F f=new F();f.bind(200087,"2");f.bind(200087,"long");
    f.click(200087);f.clock.tick(100);f.down(200087);f.clock.tick(1000);f.expect();
    f.nativeKey(90);f.up(200087);f.clock.tick(2000);f.expect("mediaNext:long");}
  static void nativeBeforeLog(){F f=new F();long down=f.clock.now;f.clock.tick(40);f.nativeKey(87);
    f.clock.tick(20);f.edge(200087,true,down);f.edge(200087,false,down+40);f.expect("mediaNext:1");}
  static void missingAndUnrelated(){F f=new F();f.bind(200087,"long");f.down(200087);
    f.clock.tick(40);f.nativeKey(87); // short native event with lost physical UP
    f.clock.tick(2500);f.expect();f.reset();
    f.down(200087);f.clock.tick(1500);f.nativeKey(85);f.up(200087);f.clock.tick(2000);f.expect();
    f.click(200087);f.expect("mediaNext:1");
    f=new F();f.click(200088);f.down(200087);f.clock.tick(40);f.up(200087);
    f.clock.tick(1000);f.expect("mediaPrevious:1"); // call/DIM edge lacks a native media event
  }
  static void resetAndStale(){F f=new F();f.bind(200087,"2");f.click(200087);f.reset();f.clock.tick(3000);f.expect();
    f.down(200087);f.clock.tick(30);f.up(200087);f.reset();f.nativeKey(87);f.clock.tick(2000);f.expect();
    check(!f.gate.physical(200087,true,f.clock.now-751,f.clock.now),"stale edge");
    f.gate.nativeMedia(87,f.clock.now-751,f.clock.now);f.clock.tick(2000);f.expect();}
  static void independentAndGap(){F f=new F();f.bind(200087,"2");f.bind(200088,"2");
    f.click(200087);f.click(200088);f.click(200087);f.clock.tick(600);f.expect("mediaNext:2","mediaPrevious:1");
    f=new F();f.bind(200087,"2");f.click(200087);f.clock.tick(601);f.click(200087);f.clock.tick(600);
    f.expect("mediaNext:1","mediaNext:1");}
  static void deliveredUpAtThreshold(){F f=new F();f.bind(200085,"long");long start=f.clock.now;
    f.clock.tick(500);f.edge(200085,true,start);f.clock.tick(500);f.nativeKey(85);
    f.up(200085);f.clock.tick(1000);f.expect("mediaPlayPause:long");}
  static void noneAndBounded(){F f=new F();f.bind(200087,"2");f.bind(200087,"long");
    f.assignments.put("mediaNext1",0);f.click(200087);f.clock.tick(600);f.expect();
    for(int i=0;i<1000;i++){f.nativeKey(85);f.clock.tick(1);}f.reset();f.clock.tick(3000);f.expect();}
  public static void main(String[] args)throws Exception{MediaGesturesReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}
''', encoding="utf-8")
        base = ROOT / "app/src/main/java/dezz/status/widget/media"
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.out), str(source),
            *[str(base / (n + ".java")) for n in ["VehicleButton", "ButtonAction", "ButtonGestureEngine", "MediaGestureGate"]]],
            capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, name):
        result = subprocess.run(["java", "-cp", str(self.out), "MediaGesturesReplay", name], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_ordinary_and_duplicates(self): self.run_case("ordinaryAndDuplicates")
    def test_multiplicity(self): self.run_case("multiplicity")
    def test_confirmed_hold_and_native_repeats(self): self.run_case("hold")
    def test_hold_cancels_pending_click(self): self.run_case("clickThenHold")
    def test_native_before_log(self): self.run_case("nativeBeforeLog")
    def test_missing_edges_and_nonmedia_context(self): self.run_case("missingAndUnrelated")
    def test_reset_and_stale(self): self.run_case("resetAndStale")
    def test_independent_buttons_and_click_window(self): self.run_case("independentAndGap")
    def test_delayed_physical_edge_uses_source_duration(self): self.run_case("deliveredUpAtThreshold")
    def test_explicit_none_and_bounded_input(self): self.run_case("noneAndBounded")
