"""Replay the production attachment path with a consumer that publishes synchronously.

This models the first-buffer race; it does not emulate the KX11 GPU or tile renderer.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_map_visibility_recovery import method

ROOT = Path(__file__).resolve().parents[2]


class MapPrepareBeforeSurfaceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-surface-order-")
        cls.path = Path(cls.temp.name)
        renderer = (ROOT / "navigator-mod/src/main/java/ru/natro/navigation/HudMapRenderer.java").read_text()
        sources = {
            "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long elapsedRealtime(){return 1000;} }",
            "android/view/Surface.java": "package android.view; public class Surface { public boolean valid=true; public boolean isValid(){return valid;} }",
            "com/yandex/runtime/view/Surface.java": "package com.yandex.runtime.view; public class Surface {}",
            "com/yandex/runtime/view/SurfaceFactory.java": "package com.yandex.runtime.view; public class SurfaceFactory { public static Surface from(android.view.Surface s){return new Surface();} }",
            "com/yandex/mapkit/MapKitFactory.java": "package com.yandex.mapkit; public class MapKitFactory { public static MapKit getInstance(){return new MapKit();} }",
            "com/yandex/mapkit/MapKit.java": """package com.yandex.mapkit;
                public class MapKit {
                  public Offscreen createOffscreenMapWindow(int w,int h){return new Offscreen();}
                  public static class Offscreen {
                    public com.yandex.mapkit.map.MapWindow getMapWindow(){return new com.yandex.mapkit.map.MapWindow();}
                  }
                }""",
            "com/yandex/mapkit/map/MapWindow.java": """package com.yandex.mapkit.map;
                public class MapWindow {
                  public final Map map=new Map(); public int frames; public boolean firstNight,firstTransparent;
                  public Map getMap(){return map;}
                  public void addSurface(com.yandex.runtime.view.Surface s){
                    frames++;firstNight=map.night;firstTransparent=map.transparent;
                  }
                  public static class Map {
                    public boolean night,transparent; public static boolean rejectNight,rejectTransparency;
                    public void setNightModeEnabled(boolean v){if(rejectNight)throw new IllegalStateException();night=v;}
                    public void setTransparentBackgroundEnabled(boolean v){if(rejectTransparency)throw new IllegalStateException();transparent=v;}
                  }
                }""",
            "Replay.java": r'''import android.view.Surface;
public class Replay {
 static class Profile { boolean enabled=true,nightMode,roadsOnly,automaticDayNight; }
 static class Layer {
  void attach(Object o){} void attach(Object o,int w,int h){} void updateRoute(long e,Object r){}
 }
 static class Log { static void i(String t,String s){} static void w(String t,String s,Throwable e){} static void e(String t,String s,Throwable e){} }
 static class NavigationBridgeClient { static void reportDiagnostic(String s){} }
 static class Reporter { int failures;void onSurfaceLost(long g,String d){failures++;} }
 Surface surface=new Surface(); Profile profile=new Profile(); Reporter reporter=new Reporter();
 int width=800,height=480;long generation=1,activeRouteEpoch=1;
 Object mapWindow,map,offscreenMapWindow,runtimeSurface,trafficLayer,activeRoute;
 boolean runtimeSurfaceAttached,mapConfigured;String TAG="test",displayName="cluster",profileSection="clusterMap";
 Layer overlayPlacement=new Layer(),trafficLightMapLayer=new Layer(),routeTrafficLightMapLayer=new Layer(),
  cameraDirectionMapLayer=new Layer(),speedBumpMapLayer=new Layer(),laneGuidanceMapLayer=new Layer(),
  alternativeRouteMapLayer=new Layer(),cursorStyler=new Layer();
 void reportMapReady(boolean v){} void syncOverlayNavigationState(){}
 Object createOptionalLayer(Object a,Class<?> b,Class<?> c,Object d,String e){return null;}
 void createRoadEventsLayer(Object a,Class<?> b,Class<?> c,Object d){}
 void applyProfile(){applyMapBackground(map,profile.nightMode,profile.roadsOnly);}
 boolean currentNightMode(){return profile.nightMode;}
 void observeMapLoading(){check(runtimeSurfaceAttached,"observer must not gate attachment");}
 void acknowledgeMapContent(){} void stopRenderer(boolean b){}
 String shortMessage(Throwable t){return t.toString();}
 static Object invoke(Object o,String n,Class<?>[] types,Object... args)throws Exception {
  return o.getClass().getMethod(n,types).invoke(o,args);
 }
 START_METHOD
 TRACE_METHOD
 BACKGROUND_METHOD
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  String scenario=args[0];
  for(boolean night:new boolean[]{false,true})for(boolean transparent:new boolean[]{false,true}){
   com.yandex.mapkit.map.MapWindow.Map.rejectNight=scenario.equals("nightFailure");
   com.yandex.mapkit.map.MapWindow.Map.rejectTransparency=scenario.equals("transparencyFailure");
   Replay r=new Replay();r.profile.nightMode=night;r.profile.roadsOnly=transparent;
   if(scenario.equals("invalid"))r.surface.valid=false;
   if(scenario.equals("disabled"))r.profile.enabled=false;
   r.startRenderer();
   check(r.reporter.failures==0,"optional appearance must not fail attachment");
   if(scenario.equals("invalid")||scenario.equals("disabled")){
    check(r.mapWindow==null,"inactive consumer must not attach");continue;
   }
   com.yandex.mapkit.map.MapWindow window=(com.yandex.mapkit.map.MapWindow)r.mapWindow;
   check(window.frames==1,"attach exactly once");
   check(window.firstNight==(night&&!scenario.equals("nightFailure")),"first buffer night mode");
   check(window.firstTransparent==(transparent&&!scenario.equals("transparencyFailure")),"first buffer transparency");
   r.startRenderer();check(window.frames==1,"reconcile must not attach twice");
  }
 }
}'''.replace("START_METHOD", method(renderer, "private void startRenderer()"))
                .replace("TRACE_METHOD", method(renderer, "private void traceStartup("))
                .replace("BACKGROUND_METHOD", method(renderer, "private void applyMapBackground(")),
        }
        files = []
        for name, source in sources.items():
            file = cls.path / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(source)
            files.append(str(file))
        result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(cls.path), *files],
                                capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, scenario):
        result = subprocess.run(["java", "-cp", str(self.path), "Replay", scenario], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_first_published_buffer_has_requested_appearance(self):
        self.replay("normal")

    def test_optional_night_failure_does_not_skip_transparency_or_attach(self):
        self.replay("nightFailure")

    def test_optional_transparency_failure_preserves_night_and_attach(self):
        self.replay("transparencyFailure")

    def test_invalid_surface(self):
        self.replay("invalid")

    def test_disabled_map(self):
        self.replay("disabled")
