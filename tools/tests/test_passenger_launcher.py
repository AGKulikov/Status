"""Production JVM checks; Android doubles do not establish ECARX task placement."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/dezz/status/widget"


class PassengerLauncherTest(unittest.TestCase):
    def replay(self, stubs, sources, harness, main, jar=""):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, content in stubs.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            (root / "Replay.java").write_text(harness)
            compiled = subprocess.run([
                "java", "com.sun.tools.javac.Main", "-encoding", "UTF-8", "-cp", jar or tmp,
                "-d", tmp, *map(str, root.rglob("*.java")),
                *[str(JAVA / source) for source in sources]], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(["java", "-cp", tmp + os.pathsep + jar, main],
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


    def test_explicit_passenger_task_and_no_fallback(self):
        stubs = {
            "android/os/Bundle.java": "package android.os; public class Bundle {public int display; public String custom; public Bundle(){} public Bundle(Bundle b){display=b.display;custom=b.custom;} public void putAll(Bundle b){display=b.display;}}",
            "android/view/Display.java": "package android.view; public class Display {public static final int STATE_OFF=1; public int state=2; public boolean valid=true; public boolean isValid(){return valid;} public int getState(){return state;}}",
            "android/hardware/display/DisplayManager.java": "package android.hardware.display; public class DisplayManager {public android.view.Display display=new android.view.Display(); public android.view.Display getDisplay(int id){if(id!=3)throw new AssertionError();return display;}}",
            "android/app/ActivityOptions.java": "package android.app; public class ActivityOptions {int id;public static ActivityOptions makeBasic(){return new ActivityOptions();}public ActivityOptions setLaunchDisplayId(int v){id=v;return this;}public android.os.Bundle toBundle(){android.os.Bundle b=new android.os.Bundle();b.display=id;return b;}}",
            "android/content/Intent.java": "package android.content; public class Intent {public static final String ACTION_MAIN=\"MAIN\";public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2,FLAG_ACTIVITY_SINGLE_TOP=4;public Class<?> target;public int flags;public Intent(Context c,Class<?> k){target=k;}public Intent setAction(String s){return this;}public Intent addFlags(int f){flags|=f;return this;}}",
            "android/content/Context.java": """package android.content; public class Context {
                public android.hardware.display.DisplayManager manager=new android.hardware.display.DisplayManager();
                public int calls;public boolean denied;public Intent last;
                public Context getApplicationContext(){return this;}
                public <T>T getSystemService(Class<T> c){return c.cast(manager);}
                public void startActivity(Intent i){throw new AssertionError("untargeted fallback");}
                public void startActivity(Intent i,android.os.Bundle b){if(b.display!=3)throw new AssertionError();calls++;last=i;if(denied)throw new SecurityException();}
            }""",
            "android/os/Looper.java": "package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}",
            "android/os/Handler.java": "package android.os; public class Handler {public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}}",
            "android/widget/Toast.java": "package android.widget;public class Toast {public static final int LENGTH_LONG=1; public static Toast makeText(android.content.Context c,String s,int d){return new Toast();}public void show(){}}",
            "dezz/status/widget/PassengerLauncherActivity.java": "package dezz.status.widget;public class PassengerLauncherActivity {}",
            "dezz/status/widget/diagnostics/DiagnosticJournal.java": "package dezz.status.widget.diagnostics;public class DiagnosticJournal {public static void infoAsync(String a,String b){}public static void warn(String a,String b){}}",
        }
        self.replay(stubs, ["launcher/PassengerHomeLauncher.java"], r'''package dezz.status.widget;
import android.content.*;import android.os.Bundle;import android.view.Display;
import dezz.status.widget.launcher.PassengerHomeLauncher;
public class Replay {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args) {
  Context c=new Context();
  check(PassengerHomeLauncher.open(c));
  check(c.calls==1 && c.last.target==PassengerLauncherActivity.class && c.last.flags==7);
  Bundle original=new Bundle();original.display=0;original.custom="transition";
  Bundle options=PassengerHomeLauncher.options(c,original);
  check(options.display==3 && original.display==0 && options.custom.equals("transition"));
  c.manager.display.state=Display.STATE_OFF;
  check(!PassengerHomeLauncher.open(c) && c.calls==1);
  c.manager.display.state=2;c.manager.display.valid=false;
  check(!PassengerHomeLauncher.open(c) && c.calls==1);
  c.manager.display=null;
  check(!PassengerHomeLauncher.open(c) && c.calls==1);
  c.manager.display=new Display();c.denied=true;
  check(!PassengerHomeLauncher.open(c) && c.calls==2);
  c.denied=false;check(PassengerHomeLauncher.open(c) && c.calls==3);
 }
}''', "dezz.status.widget.Replay")
