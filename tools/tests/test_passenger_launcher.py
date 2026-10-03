"""Production JVM checks; Android doubles do not establish ECARX task placement."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from test_passenger_panel import STUBS

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

    def test_independent_preferences_future_keys_migration_and_restore(self):
        jar = os.environ.get("NATRO_ANDROID_JSON_JAR")
        if not jar:
            self.skipTest("Set NATRO_ANDROID_JSON_JAR")
        self.replay(STUBS, ["Preferences.java", "BrickType.java",
            "launcher/PassengerLauncherProfile.java", "launcher/LauncherSettingsMigrationRegistry.java",
            "launcher/LauncherGlobalElementLayoutStore.java",
            "phone/PhoneNotificationDeferralPolicy.java", "phone/transport/v2/IphoneLeEnrollmentRecordV2.java",
            "media/ButtonAction.java"], r'''package dezz.status.widget;
import android.content.*;
import dezz.status.widget.media.ButtonAction;
import dezz.status.widget.launcher.LauncherGlobalElementLayoutStore;
import java.util.*;
public class Replay {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  Context c=new Context(); Preferences driver=new Preferences(c);
  driver.launcherLayoutJson.set("driver layout");
  driver.launcherClimateConfigJson.set("driver climate");
  driver.launcherAllAppsColumns.set(4);
  driver.launcherImmersive.set(false);
  Preferences passenger=Preferences.forPassengerLauncher(c);
  check(passenger.launcherLayoutJson.get().isEmpty(),"independent initial layout");
  passenger.launcherLayoutJson.set("passenger layout");
  passenger.launcherClimateConfigJson.set("passenger climate");
  passenger.launcherAllAppsColumns.set(8);
  passenger.launcherImmersive.set(true);
  passenger.launcherAllAppsHiddenComponents.set(new HashSet<>(Arrays.asList("p/a")));
  check(driver.launcherLayoutJson.get().equals("driver layout"),"layout isolation");
  check(driver.launcherClimateConfigJson.get().equals("driver climate"),"climate isolation");
  check(driver.launcherAllAppsColumns.get()==4 && !driver.launcherImmersive.get(),"scalar isolation");
  check(driver.launcherAllAppsHiddenComponents.get().isEmpty(),"set isolation");
  for(java.lang.reflect.Field f:Preferences.class.getFields()) {
   if(!f.getName().startsWith("launcher") || !Preferences.Preference.class.isAssignableFrom(f.getType()))continue;
   String main=((Preferences.Preference)f.get(driver)).key;
   String other=((Preferences.Preference)f.get(passenger)).key;
   if(f.getName().startsWith("launcherMediaAutoResume") || f.getName().startsWith("launcherMediaFixedPlayer")
     || f.getName().equals("launcherHideSystemStatusBar") || f.getName().equals("launcherSystemStatusBarOriginalPolicy"))
    check(main.equals(other),"device policy must stay shared: "+main);
   else check(!main.equals(other),"profile key collision: "+main);
  }
  new Preferences.Str(passenger,"launcherFutureDocument","").set("future value");
  check(new Preferences.Str(driver,"launcherFutureDocument","").get().isEmpty(),"future key isolation");
  passenger.launcherMediaFixedPlayerPackage.set("player");
  check(driver.launcherMediaFixedPlayerPackage.get().equals("player"),"global media policy");
  LauncherGlobalElementLayoutStore widgets=new LauncherGlobalElementLayoutStore(passenger);
  widgets.load(1920,720);
  widgets.put("clock",new LauncherGlobalElementLayoutStore.Geometry(10,10,100,100));
  LauncherGlobalElementLayoutStore.Appearance appearance=widgets.getAppearance("clock");
  appearance.tapAction=LauncherGlobalElementLayoutStore.TapAction.PASSENGER_HOME;
  widgets.putAppearance("clock",appearance);
  Context restored=new Context(); Preferences restoredDriver=new Preferences(restored);
  restoredDriver.importFromJson(driver.exportToJson());
  Preferences restoredPassenger=Preferences.forPassengerLauncher(restored);
  check(restoredDriver.launcherLayoutJson.get().equals("driver layout"),"restore driver");
  check(restoredPassenger.launcherLayoutJson.get().equals("passenger layout"),"restore passenger");
  check(restoredPassenger.launcherAllAppsHiddenComponents.get().contains("p/a"),"restore set");
  check(new Preferences.Str(restoredPassenger,"launcherFutureDocument","").get().equals("future value"),"restore future key");
  LauncherGlobalElementLayoutStore restoredWidgets=new LauncherGlobalElementLayoutStore(restoredPassenger);
  restoredWidgets.load(1920,720);
  check(restoredWidgets.getAppearance("clock").tapAction==LauncherGlobalElementLayoutStore.TapAction.PASSENGER_HOME,"restore widget action");
  Context legacy=new Context();
  SharedPreferences raw=legacy.getSharedPreferences("ru.natro.statuswidget_preferences",0);
  raw.edit().putString("launcherClimateConfigJson","old driver climate").commit();
  Preferences.forPassengerLauncher(legacy);
  check(!raw.contains("floatingClimateConfigJson"),"passenger must not consume main migration");
  Preferences migrated=new Preferences(legacy);
  check(migrated.floatingClimateConfigJson.get().equals("old driver climate"),"main migration preserved");
  check(ButtonAction.fromId(10)==ButtonAction.PASSENGER_HOME,"PHOME compatibility");
  check(ButtonAction.fromId(108)==ButtonAction.NATRO_PASSENGER_HOME,"new physical action");
  Set<Integer> ids=new HashSet<>();
  for(ButtonAction a:ButtonAction.values())check(ids.add(a.id),"duplicate button id");
 }
}''', "dezz.status.widget.Replay", jar)

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
