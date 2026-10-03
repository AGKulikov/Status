"""JVM check of production passenger storage. Android preferences are an in-memory test double."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
STUBS = {'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}', 'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}', 'android/util/Log.java': 'package android.util; public final class Log {\n public static int w(String a,String b,Throwable t){return 0;}\n public static int e(String a,String b,Throwable t){return 0;}\n}', 'android/content/Context.java': 'package android.content; import java.util.*;\npublic final class Context {\n public static final int MODE_PRIVATE=0; public final Map<String,SharedPreferences> stores=new HashMap<>();\n public Context getApplicationContext(){return this;}\n public Context createDeviceProtectedStorageContext(){return this;}\n public String getPackageName(){return "ru.natro.statuswidget";}\n public SharedPreferences getSharedPreferences(String n,int mode){return stores.computeIfAbsent(n,k->new SharedPreferences());}\n}', 'android/content/SharedPreferences.java': 'package android.content; import java.util.*;\npublic final class SharedPreferences {\n public int failCommits=0; private Map<String,Object> ram=new LinkedHashMap<>(), disk=new LinkedHashMap<>();\n public Map<String,?> getAll(){return new LinkedHashMap<>(ram);}\n public Map<String,?> durable(){return new LinkedHashMap<>(disk);}\n public boolean contains(String k){return ram.containsKey(k);}\n public boolean getBoolean(String k,boolean d){return (Boolean)ram.getOrDefault(k,d);}\n public int getInt(String k,int d){return (Integer)ram.getOrDefault(k,d);}\n public long getLong(String k,long d){return (Long)ram.getOrDefault(k,d);}\n public float getFloat(String k,float d){return (Float)ram.getOrDefault(k,d);}\n public String getString(String k,String d){return (String)ram.getOrDefault(k,d);}\n @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)ram.getOrDefault(k,d);}\n public Editor edit(){return new Editor();}\n public final class Editor {\n  private final Map<String,Object> edits=new LinkedHashMap<>(); private boolean clear;\n  public Editor putBoolean(String k,boolean v){edits.put(k,v);return this;}\n  public Editor putInt(String k,int v){edits.put(k,v);return this;}\n  public Editor putLong(String k,long v){edits.put(k,v);return this;}\n  public Editor putFloat(String k,float v){edits.put(k,v);return this;}\n  public Editor putString(String k,String v){edits.put(k,v);return this;}\n  public Editor putStringSet(String k,Set<String> v){edits.put(k,new HashSet<>(v));return this;}\n  public Editor remove(String k){edits.put(k,null);return this;}\n  public Editor clear(){clear=true;return this;}\n  public boolean commit(){if(clear)ram.clear();for(Map.Entry<String,Object> e:edits.entrySet()){\n   if(e.getValue()==null)ram.remove(e.getKey());else ram.put(e.getKey(),e.getValue());}\n   if(failCommits>0){failCommits--;return false;}disk=new LinkedHashMap<>(ram);return true;}\n  public void apply(){commit();}\n }\n}', 'dezz/status/widget/AppProcessPolicy.java': 'package dezz.status.widget; public final class AppProcessPolicy { public static int preferenceMode(){return 0;} }', 'dezz/status/widget/Fonts.java': 'package dezz.status.widget; public final class Fonts { public static final String DEFAULT_KEY="roboto_condensed"; }', 'dezz/status/widget/SecretStore.java': 'package dezz.status.widget; import android.content.Context;\nfinal class SecretStore {static String encrypt(Context c,String p){return "stub:"+p;}\n static String decrypt(Context c,String p){return p.startsWith("stub:")?p.substring(5):p;} }', 'dezz/status/widget/StatusBarSurfaceContext.java': 'package dezz.status.widget;\npublic final class StatusBarSurfaceContext {\n public static final String NAVIGATOR_WINDOW="@surface/navigator_window";\n static boolean isYandexPackage(String v){if(v==null)return false;String p=v.trim();\n return p.equals("ru.yandex.yandexnavi")||p.equals("ru.yandex.yandexmaps")||p.equals("com.yandex.yango");}\n}', 'dezz/status/widget/automation/AutomationContract.java': 'package dezz.status.widget.automation; public final class AutomationContract { public static String requireSafeId(String raw) { String id=raw==null?"":raw.trim(); if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException(); return id;} }'}

class PassengerPanelTest(unittest.TestCase):
    def test_profiles_favorites_and_backup_roundtrip(self):
        jar = os.environ.get("NATRO_ANDROID_JSON_JAR")
        if not jar:
            self.skipTest("Set NATRO_ANDROID_JSON_JAR")
        harness = r'''package dezz.status.widget;
import android.content.Context;
import dezz.status.widget.driver.*;
import java.util.*;
public class PassengerReplay {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  Context c=new Context(); Preferences p=new Preferences(c);
  check(!p.passengerPanelEnabled.get());
  check(p.passengerPanel.side.get()==1 && p.activeDriverPanelProfile().side.get()==0);
  p.passengerPanelEnabled.set(true); p.passengerPanel.widthPx.set(230);
  p.passengerPanel.shortcutsJson.set("[{\"id\":\"passenger\"}]");
  check(p.activeDriverPanelProfile().widthPx.get()!=230);
  check(!p.activeDriverPanelProfile().shortcutsJson.get().contains("passenger"));
  DriverFavoritesPanelStore d=new DriverFavoritesPanelStore(p), s=new DriverFavoritesPanelStore(p,true);
  DriverFavoritesPanelConfig dc=d.create("Driver"), sc=s.create("Passenger");
  check(d.find(sc.id)==null && s.find(dc.id)==null);
  sc.columns=7;sc.autoCloseSeconds=19;s.upsert(sc);
  p.passengerFavoritesShortcuts(sc.id).set("passenger cells");
  p.driverFavoritesShortcuts(sc.id).set("driver cells");
  check(p.passengerFavoritesShortcuts(sc.id).get().equals("passenger cells"));
  s.remove(sc.id);check(p.passengerFavoritesShortcuts(sc.id).get().equals("passenger cells"));
  s.upsert(sc);
  p.passengerAllAppsColumns.set(8);
  p.passengerAllAppsHiddenComponents.set(new HashSet<>(Arrays.asList("pkg/activity")));
  check(p.launcherAllAppsColumns.get()==5 && p.launcherAllAppsHiddenComponents.get().isEmpty());
  Preferences q=new Preferences(new Context());q.importFromJson(p.exportToJson());
  check(q.passengerPanelEnabled.get() && q.passengerPanel.widthPx.get()==230);
  check(q.passengerPanel.shortcutsJson.get().equals(p.passengerPanel.shortcutsJson.get()));
  check(new DriverFavoritesPanelStore(q,true).find(sc.id).autoCloseSeconds==19);
  check(q.passengerFavoritesShortcuts(sc.id).get().equals("passenger cells"));
  check(q.driverFavoritesShortcuts(sc.id).get().equals("driver cells"));
  check(q.passengerAllAppsHiddenComponents.get().contains("pkg/activity"));
  check(q.passengerAllAppsColumns.get()==8);
  for(int width:new int[]{800,1280,1920,2560}) {
   check(PassengerPanelPlacement.x(width,150,false)==0);
   check(PassengerPanelPlacement.x(width,150,true)==width-150);
  }
  check(PassengerPanelPlacement.x(80,150,true)==0);
  try {p.passengerFavoritesShortcuts("../bad");throw new AssertionError();}catch(IllegalArgumentException expected){}
  System.out.println("Passenger profiles/favorites/catalog/JSON round-trip/geometry PASS");
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for name, content in STUBS.items():
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
            path=root/"dezz/status/widget/PassengerReplay.java";path.write_text(harness)
            sources=[ROOT/"app/src/main/java/dezz/status/widget"/name for name in
                ["Preferences.java", "launcher/PassengerLauncherProfile.java", "BrickType.java", "launcher/LauncherSettingsMigrationRegistry.java", "phone/PhoneNotificationDeferralPolicy.java", "phone/transport/v2/IphoneLeEnrollmentRecordV2.java", "driver/DriverFavoritesPanelStore.java", "driver/DriverFavoritesPanelConfig.java", "driver/PassengerPanelPlacement.java"]]
            run=subprocess.run(["java","com.sun.tools.javac.Main","-cp",jar,"-d",tmp,*map(str,root.rglob("*.java")),*map(str,sources)],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stderr)
            run=subprocess.run(["java","-cp",tmp+os.pathsep+jar,"dezz.status.widget.PassengerReplay"],capture_output=True,text=True)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

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
