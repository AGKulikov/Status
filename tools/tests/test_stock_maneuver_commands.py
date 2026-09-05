#!/usr/bin/env python3
"""Run the production observer against synthetic native values and an Android ownership tree.

Needs JSON_JAR (org.json 20240303). The fixture verifies command/owner lifecycles, not Android drawing.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
 'android/os/Looper.java': '''package android.os; public class Looper {
 static Looper main=new Looper();public static Looper myLooper(){return main;}public static Looper getMainLooper(){return main;}}
''',
 'android/content/res/Resources.java': '''package android.content.res; import java.util.*; public class Resources {
 static Map<String,Integer> ids=new HashMap<>(); public int getIdentifier(String name,String type,String pkg){
 if(!ids.containsKey(name))ids.put(name,ids.size()+1);return ids.get(name);} public String getResourceEntryName(int id){return "icon";}}
''',
 'android/content/Context.java': '''package android.content; public class Context {
 public String getPackageName(){return "ru.yandex.yandexnavi";}}
''',
 'android/view/ViewParent.java': 'package android.view; public interface ViewParent {}',
 'android/view/View.java': '''package android.view; import android.content.*;import android.content.res.*;
 public class View implements ViewParent {public static int VISIBLE=0;public int visibility,id;public ViewParent parent;
 public Resources getResources(){return new Resources();}public Context getContext(){return new Context();}
 public int getVisibility(){return visibility;}public float getAlpha(){return 1;}public ViewParent getParent(){return parent;}
 public View findViewById(int id){return this.id==id?this:null;}public ViewGroup.LayoutParams getLayoutParams(){return new ViewGroup.MarginLayoutParams();}}
''',
 'android/view/ViewGroup.java': '''package android.view; import java.util.*;public class ViewGroup extends View {
 public List<View> children=new ArrayList<>();public void add(View v){children.add(v);v.parent=this;}
 public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}
 public View findViewById(int id){if(this.id==id)return this;for(View v:children){View r=v.findViewById(id);if(r!=null)return r;}return null;}
 public static class LayoutParams {public int width=32,height=48;}public static class MarginLayoutParams extends LayoutParams{public int leftMargin,rightMargin;}}
''',
 'android/widget/TextView.java': '''package android.widget;public class TextView extends android.view.View {
 public String text=""; public CharSequence getText(){return text;}}
''',
 'android/view/Window.java': 'package android.view; public class Window {public ViewGroup root=new ViewGroup();public View getDecorView(){return root;}}',
 'android/app/Activity.java': '''package android.app;import android.view.*;public class Activity {
 public boolean destroyed;public Window window=new Window();public boolean isFinishing(){return false;}public boolean isDestroyed(){return destroyed;}
 public Window getWindow(){return window;}}
''',
 'ru/yandex/yandexnavi/ui/guidance/maneuver/ContextManeuverView.java': '''package ru.yandex.yandexnavi.ui.guidance.maneuver;
 import android.view.*;import android.widget.*;
 public class ContextManeuverView extends ViewGroup {
 public boolean isViewContentVisible=true,can=true,screenSaverMode;public String viewMode="MANEUVER";public float viewScale=1;
 public Object presenter=new Object();public Object getPresenter(){return presenter;}public boolean getCanBeVisible(){return can;}
 public String getStyle(){return "SINGLE_UPCOMING_ANNOTATION";}public int getMaxLines(){return 2;}
 public boolean getNextStreetCanBeLarge(){return true;}public boolean isDirectionSignRedisigned(){return false;}
 public void value(String name,String text){TextView v=new TextView();v.id=getResources().getIdentifier(name,"id","");v.text=text;add(v);}
 public ContextManeuverView(){value("text_maneuverballoon_distance","600");value("text_maneuverballoon_metrics","м");
 value("text_nextstreet","Еринское ш.");value("image_maneuverballoon_maneuver","");}}
''',
 'ru/natro/navigation/CommandsReplay.java': '''package ru.natro.navigation;
 import android.app.*;import org.json.*;import ru.yandex.yandexnavi.ui.guidance.maneuver.*;
 public class CommandsReplay {
 public static class Resource {public String getInternalId(){return "context_ra_turn_right";}}
 public static class Regular {public Resource getImageId(){return new Resource();}public String getNextRoadName(){return "Еринское ш.";}}
 public static class Description {public Regular getRegularManeuver(){return new Regular();}public Object getViaPointManeuver(){return null;}}
 static void check(boolean value){if(!value)throw new AssertionError();}
 static Activity activity;static ContextManeuverView owner;
 static void setup(){StockManeuverCommands.reset(3);activity=new Activity();owner=new ContextManeuverView();activity.window.root.add(owner);send(owner);}
 static void send(ContextManeuverView target){StockManeuverCommands.onManeuver(target,new Description(),"600","м",null);}
 static JSONObject read()throws Exception{return new JSONObject(StockManeuverCommands.snapshot(activity,3,true));}
 static void routeNeedsFreshCommand()throws Exception{setup();check(read().getBoolean("visible"));StockManeuverCommands.reset(4);
 check(!new JSONObject(StockManeuverCommands.snapshot(activity,4,true)).getBoolean("visible"));send(owner);
 check(new JSONObject(StockManeuverCommands.snapshot(activity,4,true)).getBoolean("visible"));check(!read().getBoolean("visible"));}
 static void backgroundWindowKeepsContentButNativeHideClears()throws Exception{setup();activity.window.root.visibility=8;
 check(read().getBoolean("visible"));owner.isViewContentVisible=false;StockManeuverCommands.onChanged(owner);check(!read().getBoolean("visible"));}
 static void oldOwnerCannotCrossActivityOrPresenter()throws Exception{setup();long first=read().getLong("generation");owner.presenter=new Object();
 StockManeuverCommands.onChanged(owner);check(!read().getBoolean("visible"));send(owner);check(read().getLong("generation")>first);
 activity=new Activity();check(!read().getBoolean("visible"));}
 static void ineligibleOrientationCannotHideActiveOwner()throws Exception{setup();ContextManeuverView alternate=new ContextManeuverView();
 alternate.can=false;activity.window.root.add(alternate);send(alternate);check(read().getBoolean("visible"));
 owner.parent=null;check(!read().getBoolean("visible"));}
 static void sourceWrappersAreNotRetainedAndFaultsDoNotEscape()throws Exception{setup();Object presenter=owner.presenter;
 StockManeuverCommands.onManeuver(owner,new Object(),"bad","",null);check(!read().getBoolean("visible"));check(presenter==owner.presenter);
 StockManeuverCommands.onManeuver(new Object(),null,null,null,null);}
 public static void main(String[] args)throws Exception{CommandsReplay.class.getDeclaredMethod(args[0]).invoke(null);}}
'''
}


class StockManeuverCommandsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        jar = os.environ.get('JSON_JAR', '')
        if not Path(jar).is_file(): raise unittest.SkipTest('Set JSON_JAR to the org.json dependency')
        cls.temp = tempfile.TemporaryDirectory(); cls.root = Path(cls.temp.name)
        paths = []
        for name, source in SOURCES.items():
            path = cls.root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(source); paths.append(str(path))
        compiler = [shutil.which('javac')] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
        subprocess.run(compiler + ['-cp',jar,'-d',str(cls.root),*paths,str(ROOT/'navigator-mod/src/main/java/ru/natro/navigation/StockManeuverCommands.java')],check=True,capture_output=True)
        cls.classpath = str(cls.root) + os.pathsep + jar
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def replay(self, name): subprocess.run(['java','-cp',self.classpath,'ru.natro.navigation.CommandsReplay',name],check=True)
    def test_epoch(self): self.replay('routeNeedsFreshCommand')
    def test_background_visibility(self): self.replay('backgroundWindowKeepsContentButNativeHideClears')
    def test_owner(self): self.replay('oldOwnerCannotCrossActivityOrPresenter')
    def test_orientation(self): self.replay('ineligibleOrientationCannotHideActiveOwner')
    def test_failure(self): self.replay('sourceWrappersAreNotRetainedAndFaultsDoNotEscape')


if __name__ == '__main__': unittest.main()
