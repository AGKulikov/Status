"""Execute the unchanged startup controller with inert Android ports; not KX11 acceptance."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PassengerStartupReplay(unittest.TestCase):
    def test_startup_is_opt_in_unlocked_main_process_and_one_shot(self):
        fixtures = {
            'android/content/Context.java': '''package android.content;
public class Context { public Context getApplicationContext(){return this;} }''',
            'dezz/status/widget/Preferences.java': '''package dezz.status.widget;
import android.content.Context;
public class Preferences {
 static boolean enabled; public final Flag passengerLauncherAutoStart=new Flag();
 Preferences(Context c,boolean migrate){if(migrate)throw new AssertionError("startup migration");}
 public static class Flag {public boolean get(){return enabled;}}
}''',
            'dezz/status/widget/StartupWorkCoordinator.java': '''package dezz.status.widget;
import android.content.Context;
class StartupWorkCoordinator { static boolean unlocked;
 static boolean isUserUnlocked(Context c){return unlocked;} }''',
            'dezz/status/widget/launcher/PassengerHomeLauncher.java': '''package dezz.status.widget.launcher;
import android.content.Context;
public class PassengerHomeLauncher {public static int starts;
 public static boolean open(Context c){starts++;return true;} }''',
            'dezz/status/widget/Replay.java': '''package dezz.status.widget;
import android.content.Context;
import dezz.status.widget.launcher.PassengerHomeLauncher;
public class Replay {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Context app=new Context();
  Context editor=new Context(){public Context getApplicationContext(){return app;}};
  PassengerHomeStartup first=new PassengerHomeStartup();
  Preferences.enabled=true;
  first.onUnlockedStart(editor,false);check(PassengerHomeLauncher.starts==0);
  StartupWorkCoordinator.unlocked=true;
  first.onUnlockedStart(editor,true);check(PassengerHomeLauncher.starts==0);
  first.onUnlockedStart(editor,false);check(PassengerHomeLauncher.starts==1);
  for(int i=0;i<20;i++)first.onUnlockedStart(editor,false);
  check(PassengerHomeLauncher.starts==1);
  PassengerHomeStartup second=new PassengerHomeStartup();Preferences.enabled=false;
  second.onUnlockedStart(editor,false);Preferences.enabled=true;
  second.onUnlockedStart(editor,false);check(PassengerHomeLauncher.starts==1);
  new PassengerHomeStartup().onUnlockedStart(editor,false);
  check(PassengerHomeLauncher.starts==2);
 }
}'''
        }
        with tempfile.TemporaryDirectory(prefix='natro-passenger-start-') as tmp:
            sources = []
            for path, code in fixtures.items():
                file = Path(tmp) / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(code)
                sources.append(str(file))
            sources.append(str(ROOT / 'app/src/main/java/dezz/status/widget/PassengerHomeStartup.java'))
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', tmp, *sources], check=True, capture_output=True)
            subprocess.run(['java', '-cp', tmp, 'dezz.status.widget.Replay'], check=True, capture_output=True)

    def test_hub_opts_out_but_editors_keep_grouping(self):
        root = ROOT / 'app/src/main/java/dezz/status/widget'
        self.assertIn('boolean usesEditorSections() { return false; }', (root / 'SettingsHubActivity.java').read_text())
        base = (root / 'settings/SettingsActivity.java').read_text()
        self.assertIn('boolean usesEditorSections() { return true; }', base)
        self.assertIn('if (usesEditorSections())\n            SettingsEditorLayout.install(', base)

    def test_retired_reader_does_not_report_new_failure(self):
        source = (ROOT / 'app/src/main/java/dezz/status/widget/media/VehicleButtonController.java').read_text()
        catch = source[source.index('} catch (Exception failed) {'):]
        catch = catch[:catch.index('} finally {')]
        self.assertIn('if (owner == logGeneration) {', catch)
        current, retired = catch.split('} else {', 1)
        self.assertIn('CausalDiagnostics.capture("input_reader_failed", false)', current)
        self.assertNotIn('CausalDiagnostics.capture', retired)
        self.assertIn('retired_reader_closed', retired)


if __name__ == '__main__':
    unittest.main()
