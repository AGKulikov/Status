"""Behavioral replay of APK-verified physical-button timing, routing and typed parameters."""
import pathlib
import shutil
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
    // Every supported code and every offered multiplicity, including both SRC variants.
    for(int code:new int[]{210004,200400,119,200231,300001,300002,26}) {
      String group=VehicleButton.bindingGroup(code);
      for(int count=1;count<=3;count++) {
        Fixture all=new Fixture();for(int gesture=1;gesture<=3;gesture++)all.bind(group,""+gesture);
        for(int n=0;n<count;n++){all.click(code);all.clock.tick(80);}
        all.clock.tick(600);all.expect(group+count);
        all.engine.input(code,false);all.clock.tick(2000);all.expect(group+count);
        Fixture disabled=new Fixture();disabled.bind(group,"1");
        disabled.enabled.remove(VehicleButton.fromCode(code));disabled.click(code);disabled.clock.tick(600);disabled.expect();
      }
      if(VehicleButton.fromCode(code).longPress) {
        Fixture held=new Fixture();held.bind(group,"1");held.bind(group,"long");held.engine.input(code,true);
        held.clock.tick(500);held.engine.input(code,true);held.clock.tick(500);held.expect(group+"long");
        held.engine.input(code,false);held.clock.tick(10000);held.expect(group+"long");
      }
    }
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
    Set<String> selectorActions=new HashSet<>();
    Set<Integer> selectorSteps=new HashSet<>();
    check(DriveSelectorButtonPreset.values().length==7,"seven selector commands");
    for(DriveSelectorButtonPreset preset:DriveSelectorButtonPreset.values()) {
      ButtonBinding old=new ButtonBinding(4,"keep app","old command","old target","keep shortcut");
      ButtonBinding binding=preset.referenceBinding(old);
      check(binding.validationError().isEmpty(),"preset valid without text entry");
      check(binding.application.equals("keep app") && binding.shortcutJson.equals("keep shortcut"),"unrelated fields preserved");
      check(old.command.equals("old command") && old.packageName.equals("old target"),"input not mutated");
      check(DriveSelectorButtonPreset.fromBinding(binding)==preset,"preset round trip");
      ButtonBinding nativeBinding=new ButtonBinding(preset.action.id,"","","","");
      check(nativeBinding.validationError().isEmpty(),"native selector needs no intent parameters");
      check(DriveSelectorButtonPreset.fromAction(nativeBinding.action)==preset,"native dispatch");
      check(DriveSelectorButtonPreset.fromBinding(nativeBinding)==preset,"native label after reopen");
      check(selectorActions.add(binding.command),"unique command");
      check(selectorSteps.add(preset.steps),"unique step count");
      check(binding.packageName.equals("dezz.monjaro.drive_modes"),"explicit destination");
      check(!binding.command.endsWith("ISSHOWING"),"feedback not an action");
      check(DriveSelectorButtonPreset.fromBinding(new ButtonBinding(4,"",binding.command+",ei:x:1",binding.packageName,""))==null,"manual extras not erased");
      check(DriveSelectorButtonPreset.fromBinding(new ButtonBinding(4,"",binding.command,"another.app",""))==null,"other target not reinterpreted");
      check(DriveSelectorButtonPreset.fromBinding(new ButtonBinding(31,"",binding.command,binding.packageName,""))==null,"activity not reinterpreted");
    }
    check(DriveSelectorButtonPreset.SHOW.steps==0,"SHOW never steps");
    for(int step=-3;step<=3;step++)check(selectorSteps.contains(step),"missing selector step "+step);
    List<Integer> order=Arrays.asList(10,20,30,40,50);
    check(DriveSelectorStepPolicy.target(order,30,0)==null,"SHOW is read-only");
    check(DriveSelectorStepPolicy.target(order,null,1)==null,"unknown actual never writes");
    check(DriveSelectorStepPolicy.target(Collections.emptyList(),30,1)==null,"empty list");
    check(DriveSelectorStepPolicy.target(Collections.singletonList(10),30,1)==null,"single mode");
    check(DriveSelectorStepPolicy.target(order,30,1)==40,"next one");
    check(DriveSelectorStepPolicy.target(order,30,2)==50,"next two");
    check(DriveSelectorStepPolicy.target(order,30,3)==10,"next three wraps");
    check(DriveSelectorStepPolicy.target(order,30,-1)==20,"previous one");
    check(DriveSelectorStepPolicy.target(order,30,-2)==10,"previous two");
    check(DriveSelectorStepPolicy.target(order,30,-3)==50,"previous three wraps");
    check(DriveSelectorStepPolicy.target(order,99,1)==10,"known excluded next starts at first");
    check(DriveSelectorStepPolicy.target(order,99,-1)==50,"known excluded previous starts at last");
    check(DriveSelectorStepPolicy.target(Arrays.asList(10,20,30),10,3)==null,"full circle does not rewrite");
    try { DriveSelectorStepPolicy.target(order,30,4);throw new AssertionError("unbounded steps"); } catch(IllegalArgumentException good){}
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
    byte[] combined=bytes;
    for(VehicleButton b:new VehicleButton[]{VehicleButton.SRC,VehicleButton.VA,VehicleButton.DM,VehicleButton.POWER})
      combined=ButtonInputPatch.apply(combined,b,true);
    for(VehicleButton b:new VehicleButton[]{VehicleButton.SRC,VehicleButton.VA,VehicleButton.DM,VehicleButton.POWER})
      check(ButtonInputPatch.state(combined,b),"all default paths coexist "+b);
    for(VehicleButton b:new VehicleButton[]{VehicleButton.SRC,VehicleButton.VA,VehicleButton.DM,VehicleButton.POWER})
      combined=ButtonInputPatch.apply(combined,b,false);
    check(Arrays.equals(bytes,combined),"all restored independently");
    for(String route:new String[]{"handle_voice_action","yandexnavi://ask_alice","handle_we_chat_action","ecarx.intent.action.ECARX_KEY_RSRC_EVENT"}) {
      VehicleButton b=route.contains("voice")||route.contains("alice")?VehicleButton.VA:VehicleButton.SRC;
      byte[] one=("vdex000000000000"+route+"\0END").getBytes(StandardCharsets.US_ASCII);
      byte[] on=ButtonInputPatch.apply(one,b,true);
      check(ButtonInputPatch.state(on,b)&&ButtonInputPatch.coverage(on,b).equals(b==VehicleButton.VA?"1/5":"1/2"),"single firmware variant "+route);
      check(Arrays.equals(one,ButtonInputPatch.apply(on,b,false)),"single route restores");
    }
    byte[] partial=("vdex000000000000handle_ioice_action\0yandexnavi://ask_alice\0").getBytes(StandardCharsets.US_ASCII);
    try {ButtonInputPatch.state(partial,VehicleButton.VA);throw new AssertionError("mixed state must be reported");}catch(IllegalArgumentException good){}
    check(ButtonInputPatch.state(ButtonInputPatch.apply(partial,VehicleButton.VA,true),VehicleButton.VA),"repair interrupted disable");
    check(!ButtonInputPatch.state(ButtonInputPatch.apply(partial,VehicleButton.VA,false),VehicleButton.VA),"repair interrupted restore");
    String nativeVa="Lecarx/xsf/inputservice/key/ECarXRVoiceAssistAction;\0"
      +"ecarx.intent.action.vr_pressed\0ecarx.intent.action.vr_released\0"
      +"ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT\0";
    byte[] nativeBytes=("vdex000000000000"+nativeVa+"com.ecarx.screensaver\0").getBytes(StandardCharsets.US_ASCII);
    byte[] nativeOn=ButtonInputPatch.apply(nativeBytes,VehicleButton.VA,true);
    check(ButtonInputPatch.state(nativeOn,VehicleButton.VA),"native broadcast VA disabled");
    check(new String(nativeOn,StandardCharsets.US_ASCII).contains("ecarx.intent.action.nr_pressed"),"DOWN rerouted");
    check(new String(nativeOn,StandardCharsets.US_ASCII).contains("ecarx.intent.action.nr_released"),"UP rerouted");
    check(new String(nativeOn,StandardCharsets.US_ASCII).contains("ECARX_KEY_NVOICEASSIST_EVENT"),"short and long rerouted");
    check(!ButtonInputPatch.state(nativeOn,VehicleButton.POWER),"unrelated power route preserved");
    check(Arrays.equals(nativeBytes,ButtonInputPatch.apply(nativeOn,VehicleButton.VA,false)),"native VA exact roundtrip");
    for(String bad:new String[]{nativeVa.replace("ecarx.intent.action.vr_released", "missing"),nativeVa.replace("ECarXRVoiceAssistAction", "UnknownOwner")}) {
      try {ButtonInputPatch.apply(("vdex000000000000"+bad).getBytes(StandardCharsets.US_ASCII),VehicleButton.VA,true);throw new AssertionError("incomplete native VA accepted");}
      catch(IllegalArgumentException expected){}
    }
    try {ButtonInputPatch.apply("vdex000000000000UNKNOWN".getBytes(),VehicleButton.VA,true);throw new AssertionError("absent hook accepted");}catch(IllegalArgumentException good){}
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
                "ButtonIntentSpec", "ButtonGestureEngine", "ButtonInputPatch", "DriveSelectorButtonPreset", "DriveSelectorStepPolicy")]
            compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
            subprocess.run([*compiler, "-encoding", "UTF-8", "-d", str(target),
                            *sources, str(target / "ButtonReplay.java")], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", str(target), "ButtonReplay"], check=True,
                                    capture_output=True, text=True)
            self.assertIn("PASS", result.stdout)

if __name__ == "__main__":
    unittest.main()
