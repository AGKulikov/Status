"""Behavioral replay of APK-verified physical-button timing, routing and typed parameters."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/dezz/status/widget/media"

HARNESS = r'''
import dezz.status.widget.media.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class ButtonReplay {
  static class Timer implements ButtonGestureEngine.Scheduler {
    long time; int order;
    class Job { long due; int order; Runnable run; boolean cancelled; }
    List<Job> jobs = new ArrayList<>();
    public Object after(long delay, Runnable r) { Job j = new Job(); j.due=time+delay; j.order=order++; j.run=r; jobs.add(j); return j; }
    public void cancel(Object j) { ((Job)j).cancelled=true; }
    void tick(long delta) {
      long until=time+delta;
      while(true) {
        Job next=null;
        for(Job j:jobs) if(!j.cancelled && j.due<=until && (next==null || j.due<next.due || j.due==next.due && j.order<next.order)) next=j;
        if(next==null) break;
        jobs.remove(next); time=next.due; next.run.run();
      } time=until;
    }
  }
  static class Fixture implements ButtonGestureEngine.Bindings, ButtonGestureEngine.Output {
    Timer clock=new Timer(); Map<String,Integer> assignments=new HashMap<>(); List<String> out=new ArrayList<>();
    Set<VehicleButton> enabled=EnumSet.allOf(VehicleButton.class); boolean volume, music, menu, stock;
    ButtonGestureEngine engine=new ButtonGestureEngine(clock,this,this);
    public boolean enabled(VehicleButton b){return enabled.contains(b);}
    public int actionId(String g,String n){return assignments.getOrDefault(g+n,0);}
    public boolean knobVolume(){return volume;} public boolean musicActive(){return music;}
    public boolean driveMenuShowing(){return menu;} public boolean stockMediaSource(){return stock;}
    public void action(String g,String n){out.add(g+n);} public void stockSrc(){out.add("stock");}
    public void volume(int d){out.add("vol"+d);} public void driveMenu(int d){out.add("menu"+d);}
    public void starHeld(){out.add("star10");}
    void bind(String g,String n){assignments.put(g+n,1);}
    void click(int code){engine.input(code,true);clock.tick(20);engine.input(code,false);}
    void expect(String... values) { check(out.equals(Arrays.asList(values)),out+" != "+Arrays.toString(values)); }
  }
  static void check(boolean ok,Object why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args){
    Fixture f=new Fixture(); f.bind("src","1"); f.click(210004); f.expect("src1");
    f=new Fixture(); f.bind("src","1");f.bind("src","2");f.click(210004);f.clock.tick(599);f.expect();f.clock.tick(1);f.expect("src1");
    f=new Fixture();f.bind("src","1");f.bind("src","2");f.click(210004);f.clock.tick(300);f.click(210004);f.expect("src2");f.clock.tick(1000);f.expect("src2");
    f=new Fixture();f.bind("va","1");f.bind("va","3");f.click(200231);f.clock.tick(100);f.click(200231);f.expect();f.clock.tick(100);f.click(200231);f.expect("va3");f.clock.tick(1000);f.expect("va3");
    f=new Fixture();f.bind("va","1");f.bind("va","long");f.engine.input(200231,true);f.clock.tick(999);f.expect();f.clock.tick(1);f.expect("valong");f.engine.input(200231,false);f.expect("valong");
    f=new Fixture();f.bind("asterisk","1");f.engine.input(119,true);f.clock.tick(1001);f.engine.input(119,false);f.clock.tick(10000);f.expect();
    f=new Fixture();f.bind("power","1");f.engine.input(26,true);f.clock.tick(12000);f.expect();f.engine.input(26,false);f.expect("power1");
    f=new Fixture();f.engine.input(119,true);f.clock.tick(9999);f.expect();f.clock.tick(1);f.expect("star10");f.clock.tick(10000);f.expect("star10");
    f=new Fixture();f.engine.input(119,true);f.clock.tick(500);f.engine.reset();f.clock.tick(12000);f.expect();
    f=new Fixture();f.bind("knobLeft","1");f.volume=true;f.music=true;f.click(300001);f.expect("vol-1");
    f=new Fixture();f.bind("knobLeft","1");f.volume=true;f.click(300001);f.expect("knobLeft1");
    f=new Fixture();f.volume=true;f.enabled.remove(VehicleButton.DM);f.click(300002);f.expect("vol1");
    f=new Fixture();f.volume=true;f.music=true;f.menu=true;f.click(300001);f.expect("menu-1");
    f=new Fixture();f.bind("src","1");f.stock=true;f.click(200400);f.expect("stock");
    f=new Fixture();f.bind("src","2");f.stock=true;f.click(200400);f.click(200400);f.expect("src2");
    f=new Fixture();f.assignments.put("src1",20);f.stock=true;f.click(210004);f.expect("src1");
    f=new Fixture();f.bind("src","1");f.bind("src","2");f.click(200400);f.click(210004);f.expect();f.clock.tick(600);f.expect("src1","src1");
    f=new Fixture();f.bind("va","1");f.enabled.remove(VehicleButton.VA);f.click(200231);f.expect();
    f=new Fixture();f.bind("knobLeft","2");f.bind("knobRight","1");f.click(300001);f.click(300002);f.expect("knobRight1");f.clock.tick(600);f.expect("knobRight1");
    ButtonIntentSpec p=ButtonIntentSpec.parse("sh.gate,es:url:https://a/b:c,ei:n:-7,ez:flag:TRUE,ef:flags:4294967295","ru.natro.statuswidget",false);
    check(p.flags==-1,p.flags);check(p.extras.get("url").equals("https://a/b:c"),p.extras);check(p.extras.get("n").equals(-7),p.extras);check(p.extras.get("flag").equals(true),p.extras);
    check(ButtonIntentSpec.parse("act,ei:x:bad,ef:f:bad","","".equals("x")).extras.isEmpty(),"bad number");
    check(ButtonIntentSpec.parse("act","",false).flags==0x11000000,"broadcast flags");
    check(ButtonIntentSpec.parse("pkg.Activity","pkg",true).flags==0x10000000,"activity flags");
    check(new ButtonBinding(1,"Player [pkg / pkg.Main]","","","").validationError().isEmpty(),"valid app");
    check(!new ButtonBinding(1,"pkg","","","").validationError().isEmpty(),"missing activity");
    check(!new ButtonBinding(31,"","pkg.Main","","").validationError().isEmpty(),"activity needs package");
    check(new ButtonBinding(4,"","custom.ACTION,ei:x:invalid","","").validationError().isEmpty(),"reference skips invalid extra");
    check(!new ButtonBinding(100,"","","","").validationError().isEmpty(),"missing driver action");
    check(!new ButtonBinding(34,"","  ","","").validationError().isEmpty(),"missing phone number");
    Set<Integer> ids=new HashSet<>();for(ButtonAction a:ButtonAction.values())check(ids.add(a.id),"duplicate action");for(int i=0;i<=34;i++)check(ids.contains(i),"missing "+i);
    byte[] bytes=("vdex000000000000"+
      "ecarx.intent.action.ECARX_KEY_RSRC_EVENT\0handle_we_chat_action\0"+
      "handle_voice_action\0yandexnavi://ask_alice\0"+
      "ecarx.settings.service.DrivingModeService\0com.ecarx.screensaver\0END").getBytes(StandardCharsets.US_ASCII);
    byte[] src=ButtonInputPatch.apply(bytes,VehicleButton.SRC,true);check(src.length==bytes.length,"size");
    check(ButtonInputPatch.state(src,VehicleButton.SRC),"SRC off");check(!ButtonInputPatch.state(src,VehicleButton.VA),"VA preserved");
    check(Arrays.equals(src,ButtonInputPatch.apply(src,VehicleButton.SRC,true)),"idempotence");
    for(VehicleButton b:new VehicleButton[]{VehicleButton.SRC,VehicleButton.VA,VehicleButton.DM,VehicleButton.POWER}){
      byte[] patched=ButtonInputPatch.apply(bytes,b,true);
      check(Arrays.equals(bytes,ButtonInputPatch.apply(patched,b,false)),"roundtrip "+b);
    }
    try { ButtonInputPatch.apply("not a cache of adequate size".getBytes(),VehicleButton.SRC,true);throw new AssertionError("bad cache accepted"); } catch(IllegalArgumentException good){}
    System.out.println("button timing, routes, action grammar, VDEX round trips: PASS");
  }
}
'''

class ButtonModelReplay(unittest.TestCase):
    def test_physical_button_contract(self):
        with tempfile.TemporaryDirectory(prefix="button-model-") as tmp:
            target = pathlib.Path(tmp)
            (target / "ButtonReplay.java").write_text(HARNESS)
            sources = [str(JAVA / (name + ".java")) for name in (
                "VehicleButton", "ButtonAction", "ButtonBinding",
                "ButtonIntentSpec", "ButtonGestureEngine", "ButtonInputPatch")]
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(target),
                            *sources, str(target / "ButtonReplay.java")], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", str(target), "ButtonReplay"], check=True,
                                    capture_output=True, text=True)
            self.assertIn("PASS", result.stdout)

if __name__ == "__main__":
    unittest.main()
