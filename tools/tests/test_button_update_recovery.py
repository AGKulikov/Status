import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/dezz/status/widget/media'
class ButtonUpdateRecoveryTest(unittest.TestCase):
    def test_gate_retries_and_package_update_and_source_age(self):
        replay = r'''
import dezz.status.widget.media.*;
public class RecoveryReplay {
 static void yes(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] ignored){
  ButtonRestoreGate g=new ButtonRestoreGate();
  yes(g.begin(false)); yes(!g.begin(false));
  yes(g.finish(false)==ButtonRestoreGate.Next.RETRY);
  yes(g.begin(false)); yes(g.finish(true)==ButtonRestoreGate.Next.NONE); yes(!g.begin(false));
  yes(g.begin(true)); yes(!g.begin(true)); yes(!g.begin(true));
  yes(g.finish(true)==ButtonRestoreGate.Next.FORCE);
  yes(g.begin(true)); yes(g.finish(true)==ButtonRestoreGate.Next.NONE); yes(!g.begin(false));
  g=new ButtonRestoreGate();
  for(int i=1;i<=3;i++){yes(g.begin(false));yes(g.attempts()==i);yes(g.finish(false)==(i<3?ButtonRestoreGate.Next.RETRY:ButtonRestoreGate.Next.NONE));}
  yes(!g.begin(false)); yes(g.begin(true));
  yes(MediaKeyPolicy.freshAt(1000,1000));yes(MediaKeyPolicy.freshAt(1000,1750));
  yes(!MediaKeyPolicy.freshAt(1000,1751));yes(!MediaKeyPolicy.freshAt(1000,48312));
  yes(!MediaKeyPolicy.freshAt(0,1000));yes(!MediaKeyPolicy.freshAt(2000,1000));
 }
}
'''
        with tempfile.TemporaryDirectory() as temp:
            f=Path(temp)/'RecoveryReplay.java'; f.write_text(replay)
            subprocess.run(['java','com.sun.tools.javac.Main','-d',temp,str(JAVA/'ButtonRestoreGate.java'),str(JAVA/'MediaKeyPolicy.java'),str(f)],check=True)
            subprocess.run(['java','-cp',temp,'RecoveryReplay'],check=True)

    def test_manual_media_control_never_loads_preferences_on_command_lane(self):
        source=(ROOT/'app/src/main/java/dezz/status/widget/launcher/MediaAutoResumeController.java').read_text()
        start=source.index('public static void onManualTransportControl(')
        brace=source.index('{',start); end=brace+1; depth=1
        while depth:
            depth+=(source[end]=='{')-(source[end]=='}');end+=1
        method=source[start:end].replace('@NonNull ', '')
        replay=r"""
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public class ManualReplay {
 static class Context {}
 static Context applicationContext(Context c){return c;}
 static AtomicLong MANUAL_GENERATION=new AtomicLong(); static long captureManualGeneration;
 static List<Runnable> jobs=new ArrayList<>(); static java.util.concurrent.Executor EXACT_TIMER=jobs::add;
 static String KEY_COMPLETED="completed"; static int reads,completed;
 static class State {boolean getBoolean(String k,boolean d){return false;}}
 static State state(Context c){reads++;return new State();}
 static void complete(Context c,String reason){completed++;}
 METHOD
 public static void main(String[] args){
  Context c=new Context();onManualTransportControl(c);
  if(reads!=0 || MANUAL_GENERATION.get()!=1)throw new AssertionError("I/O on command lane");
  jobs.remove(0).run(); if(completed!=1)throw new AssertionError("did not stop old autoresume");
  onManualTransportControl(c);captureManualGeneration=MANUAL_GENERATION.get();
  jobs.remove(0).run();if(completed!=1)throw new AssertionError("cancelled new boot capture");
  onManualTransportControl(c);onManualTransportControl(c);jobs.remove(0).run();
  if(completed!=1)throw new AssertionError("old callback acted");
  jobs.remove(0).run();if(completed!=2)throw new AssertionError("latest callback lost");
 }
}
""".replace('METHOD',method)
        with tempfile.TemporaryDirectory() as temp:
            f=Path(temp)/'ManualReplay.java';f.write_text(replay)
            subprocess.run(['java','com.sun.tools.javac.Main','-d',temp,str(f)],check=True)
            subprocess.run(['java','-cp',temp,'ManualReplay'],check=True)
