"""Production display launch JVM checks; preference roundtrips run with Android in PassengerPreferencesIntegrationTest."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

class PassengerPanelTest(unittest.TestCase):

    def test_display_launch_never_falls_back_to_driver(self):
        stubs = {
            'android/os/Bundle.java': 'package android.os; public class Bundle {public int display;}',
            'android/view/Display.java': 'package android.view; public class Display {public static final int DEFAULT_DISPLAY=0; public boolean isValid(){return true;}}',
            'android/hardware/display/DisplayManager.java': 'package android.hardware.display; public class DisplayManager { public boolean available=true; public android.view.Display getDisplay(int id){return available && id==3?new android.view.Display():null;} }',
            'android/app/ActivityOptions.java': 'package android.app; public class ActivityOptions {int id; public static ActivityOptions makeBasic(){return new ActivityOptions();} public ActivityOptions setLaunchDisplayId(int id){this.id=id;return this;} public android.os.Bundle toBundle(){android.os.Bundle b=new android.os.Bundle();b.display=id;return b;}}',
            'android/content/Intent.java': 'package android.content; public class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=1, FLAG_ACTIVITY_LAUNCH_ADJACENT=2; public Intent(){}public Intent(Intent other){}public Intent addFlags(int f){return this;}}',
            'android/content/Context.java': '''package android.content; public class Context {
             public int plain, targeted, last=-1; public boolean denied;
             public android.hardware.display.DisplayManager manager=new android.hardware.display.DisplayManager();
             public <T>T getSystemService(Class<T> c){return c.cast(manager);}
             public void startActivity(Intent i){plain++;}
             public void startActivity(Intent i,android.os.Bundle b){targeted++;last=b.display;if(denied)throw new SecurityException();}
            }''',
            'android/content/ContextWrapper.java': 'package android.content; public class ContextWrapper extends Context {public ContextWrapper(Context c){}}',
        }
        harness = '''package dezz.status.widget.driver;
        import android.content.*;
        public class LaunchReplay {
         static void check(boolean b){if(!b)throw new AssertionError();}
         public static void main(String[] args){
          Context c=new Context(); Intent i=new Intent();
          PanelDisplayLauncher.start(c,i,3);check(c.targeted==1 && c.plain==0 && c.last==3);
          c.manager.available=false;
          try{PanelDisplayLauncher.start(c,i,3);throw new AssertionError();}catch(IllegalStateException expected){}
          check(c.targeted==1 && c.plain==0);
          c.manager.available=true;c.denied=true;
          try{PanelDisplayLauncher.start(c,i,3);throw new AssertionError();}catch(SecurityException expected){}
          check(c.plain==0);
          c.denied=false;PanelDisplayLauncher.scoped(c,3).startActivity(i);check(c.targeted==3 && c.plain==0);
          PanelDisplayLauncher.start(c,i,0);check(c.plain==1);
         }
        }'''
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for name,content in stubs.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
            p=root/'LaunchReplay.java';p.write_text(harness)
            production=ROOT/'app/src/main/java/dezz/status/widget/driver/PanelDisplayLauncher.java'
            run=subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java')),str(production)],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)
            run=subprocess.run(['java','-cp',tmp,'dezz.status.widget.driver.LaunchReplay'],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)
