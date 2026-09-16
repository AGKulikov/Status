"""Actual OEM/dispatch method replays and pure batch policies; never connects to a car."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, ROOT, JAVA


class ColdStartRuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = Path(cls.tmp.name)
        oem = (JAVA / 'instrument/InstrumentOemController.java').read_text()
        selector = (JAVA / 'media/DriveSelectorController.java').read_text()
        source = r'''
import java.util.*;
import java.util.function.*;
import dezz.status.widget.instrument.InstrumentOemPolicy;
import dezz.status.widget.media.*;
public class ColdStartReplay {
 static long now,sequence;
 static class Job {long due,seq;Runnable r;Job(long d,Runnable r){due=d;this.r=r;seq=sequence++;}}
 static PriorityQueue<Job> jobs=new PriorityQueue<>(Comparator.<Job>comparingLong(j->j.due).thenComparingLong(j->j.seq));
 static void advance(long target){while(!jobs.isEmpty()&&jobs.peek().due<=target){Job j=jobs.remove();now=j.due;j.r.run();}now=target;android.os.SystemClock.now=now;}
 static class Handler {Handler(){}Handler(Object o){}void post(Runnable r){postDelayed(r,0);}void postDelayed(Runnable r,long ms){jobs.add(new Job(now+ms,r));}void removeCallbacks(Runnable r){jobs.removeIf(j->j.r==r);}}
 static class Looper {static Object getMainLooper(){return null;}}
 static class Context {static int starts;Context getApplicationContext(){return this;}Object getContentResolver(){return this;}void startForegroundService(Intent i){starts++;}}
 static class Settings {static boolean canDrawOverlays(Context c){return true;}static class Global {static int mode=3;static int getInt(Object c,String key,int fallback){return mode;}}}
 static class InstrumentDisplayLauncher {static Integer actual=3;static Integer readDimMode(Context c){return actual;}}
 static class InstrumentPanelActivity {static Object windowState(){return "visible";}}
 static class DiagnosticJournal {static void infoAsync(String t,String s){}}
 interface Result {void complete(boolean ok,String s);}
 interface Callback {void accept(String result,String error);}
 static String op="allow";
 static class Command {Supplier<String> command;Callback callback;Command(Supplier<String> c,Callback r){command=c;callback=r;}}
 static class PrivilegedShell {
  static PrivilegedShell instance=new PrivilegedShell();static ArrayDeque<Command> pending=new ArrayDeque<>();static List<String> executed=new ArrayList<>();
  static PrivilegedShell get(Context c){return instance;}
  void runCommand(Supplier<String> c,Callback r){pending.add(new Command(c,r));}
  static void complete(){Command q=pending.remove();String c=q.command.get();executed.add(c);if(c.startsWith("appops set"))op=c.contains("WINDOW deny")?"deny":"allow";String result="SYSTEM_ALERT_WINDOW: "+op;new Handler().post(()->q.callback.accept(result,null));}
 }
 static class White {
  Context context=new Context();Object preferences=new Object();Handler main=new Handler();boolean whiteBarEnabled=true,whiteOwned,whiteScheduled,shellBusy,forceWhiteApply=true;
  Boolean lastWhiteDeny;long whiteGeneration;int whiteAttempt;String whiteStatus;Result whitePending;
  final Runnable applyWhiteBar=this::applyWhiteBarNow;
  final Runnable verifyWhiteBar=()->{if(whiteBarEnabled&&!shellBusy&&!whiteScheduled){whiteAttempt=0;applyWhiteBarNow();}};
  SCHEDULE
  APPLY
 }
 static class Intent {long deadline;Intent(Context c,Class<?> t){}void setAction(String s){}void putExtra(String k,long v){deadline=v;}void putExtra(String k,int v){}void putExtra(String k,String v){}}
 static class Toast {static int LENGTH_LONG=1;static Toast makeText(Context c,String s,int t){return new Toast();}void show(){}}
 static class KnobReceiver {static String EXTRA_STEPS="s",EXTRA_DIRECTION="d",DIRECTION_PREV="p",DIRECTION_NEXT="n";}
 static class DriveModeOverlayService {static String EXTRA_ACTION_DEADLINE="deadline",ACTION_SHOW_PREVIEW="p",ACTION_KNOB_STEP="s";}
 static class DriveSelectorController {REQUEST}
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 static byte[] cache(String s){return ("vdex000000000000 "+s).getBytes(java.nio.charset.StandardCharsets.US_ASCII);}
 public static void main(String[] args){switch(args[0]){
  case "notification_burst":{
   White w=new White();for(int i=0;i<=15;i++){advance(i*100);w.scheduleWhiteBar();}
   check(PrivilegedShell.pending.size()==1,"apply deadline must not slide with every notification");break;
  }
  case "mode_changes_in_queue":{
   White w=new White();Settings.Global.mode=1;InstrumentDisplayLauncher.actual=1;w.scheduleWhiteBar();advance(1500);
   Settings.Global.mode=3;InstrumentDisplayLauncher.actual=3;w.scheduleWhiteBar();PrivilegedShell.complete();
   check(op.equals("deny"),"queued factory must read newest mode");break;
  }
  case "native_mode_wins":{
   White w=new White();Settings.Global.mode=1;InstrumentDisplayLauncher.actual=3;w.scheduleWhiteBar();advance(1500);PrivilegedShell.complete();advance(now);
   check(op.equals("deny"),"stale global mode must not undo native mode3");
   Settings.Global.mode=3;InstrumentDisplayLauncher.actual=1;w.scheduleWhiteBar();advance(now+1500);PrivilegedShell.complete();advance(now);
   check(op.equals("allow"),"real native exit must restore overlays");break;
  }
  case "external_appop_reset":{
   White w=new White();w.scheduleWhiteBar();advance(1500);PrivilegedShell.complete();advance(now);check(op.equals("deny"),"initial state");
   op="allow";advance(6500);PrivilegedShell.complete();advance(now);advance(6600);PrivilegedShell.complete();advance(now);
   check(op.equals("deny"),"periodic readback must repair external reset");
   check(PrivilegedShell.executed.get(1).equals("appops get com.ecarx.dimmenu SYSTEM_ALERT_WINDOW"),"healthy verification must not rewrite appop");break;
  }
  case "off_while_queued":{
   White w=new White();w.scheduleWhiteBar();advance(1500);w.whiteBarEnabled=false;w.scheduleWhiteBar();PrivilegedShell.complete();advance(now);
   check(op.equals("allow"),"OFF supersedes queued ON");break;
  }
  case "expired_selector":{
   Context c=new Context();DriveSelectorController.request(c,1);android.os.SystemClock.now=7510;now=7510;
   while(!jobs.isEmpty())jobs.remove().r.run();check(Context.starts==0,"late MAIN must not replay drive commands");break;
  }
  case "deadline_preserved":{
   Context c=new Context();ButtonActionDeadline.run(100,()->true,()->DriveSelectorController.request(c,1));
   android.os.SystemClock.now=101;while(!jobs.isEmpty())jobs.remove().r.run();check(Context.starts==0,"must not grant a new750ms at each hop");break;
  }
  case "fresh_selector":{
   DriveSelectorController.request(new Context(),1);advance(0);check(Context.starts==1,"fresh action works");break;
  }
  case "partial_restore_batch":{
   byte[] original=cache(MediaKeyPolicy.STOCK+" ecarx.settings.service.DrivingModeService com.ecarx.screensaver");
   ButtonRouteBatch batch=new ButtonRouteBatch(original,new VehicleButton[]{VehicleButton.MEDIA,VehicleButton.VA,VehicleButton.DM,VehicleButton.POWER});
   check(batch.errors.containsKey(VehicleButton.VA)&&batch.states.size()==3,"missing VA cannot cancel other routes");
   check(MediaInputPatch.state(batch.bytes).equals(MediaKeyPolicy.NATRO),"media restored");
   check(ButtonInputPatch.state(batch.bytes,VehicleButton.DM)&&ButtonInputPatch.state(batch.bytes,VehicleButton.POWER),"dm and power restored");
   check(MediaInputPatch.state(original).equals(MediaKeyPolicy.STOCK),"source bytes unchanged");break;
  }
  default:throw new AssertionError(args[0]);
 }}
}
'''
        source = source.replace('SCHEDULE', method(oem, 'private void scheduleWhiteBar()'))
        source = source.replace('APPLY', method(oem, 'private void applyWhiteBarNow()'))
        source = source.replace('REQUEST', method(selector, 'public static void request('))
        files = {
            'ColdStartReplay.java': source,
            'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long now; public static long uptimeMillis(){return now;}}',
            'dezz/status/widget/media/RuntimePreferenceWriter.java': 'package dezz.status.widget.media;public class RuntimePreferenceWriter {public static void put(Object p,Object... pairs){}}',
        }
        paths = []
        for name, content in files.items():
            p = cls.out / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            paths.append(str(p))
        for name in ['instrument/InstrumentOemPolicy.java', 'media/ButtonActionDeadline.java',
                     'media/ButtonRouteBatch.java', 'media/ButtonInputPatch.java',
                     'media/MediaInputPatch.java', 'media/MediaKeyPolicy.java', 'media/VehicleButton.java']:
            paths.append(str(JAVA / name))
        r = subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(cls.out), *paths], capture_output=True, text=True)
        if r.returncode:
            raise AssertionError(r.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_runtime_scenarios(self):
        for name in ['notification_burst', 'mode_changes_in_queue', 'native_mode_wins',
                     'external_appop_reset', 'off_while_queued', 'expired_selector',
                     'deadline_preserved', 'fresh_selector', 'partial_restore_batch']:
            with self.subTest(name=name):
                r = subprocess.run(['java', '-cp', str(self.out), 'ColdStartReplay', name], capture_output=True, text=True, timeout=8)
                self.assertEqual(r.returncode, 0, r.stderr)
