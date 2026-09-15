"""The actual placement coordinator must allow separate labels sharing a fork/tail tip."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AlternativeCalloutPlacementTest(unittest.TestCase):
    def test_shared_tip_is_not_body_overlap_and_viewport_is_still_enforced(self):
        with tempfile.TemporaryDirectory(prefix="natro-alternative-fork-") as tmp:
            out = Path(tmp)
            sources = {
                "android/graphics/RectF.java": '''package android.graphics;
public class RectF { public float left,top,right,bottom;
 public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
 public RectF(RectF r){this(r.left,r.top,r.right,r.bottom);}
 public void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;} }''',
                "com/yandex/mapkit/geometry/Point.java": '''package com.yandex.mapkit.geometry;
public class Point { public final double x,y; public Point(double x,double y){this.x=x;this.y=y;} }''',
                "ru/natro/navigation/ForkReplay.java": '''package ru.natro.navigation;
import java.util.*;import android.graphics.RectF;import com.yandex.mapkit.geometry.Point;
public class ForkReplay {
 public static class Screen {public float getX(){return 500;}public float getY(){return 350;}}
 public static class Window {public Screen worldToScreen(Point p){return new Screen();}}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] ignored){
   MapOverlayPlacementCoordinator p=new MapOverlayPlacementCoordinator();p.attach(new Window(),1000,700);
   p.reserveFixed("lanes","required",55,37,160,90,1f,1f);
   List<MapOverlayPlacementCoordinator.Footprint> full=new ArrayList<>(),body=new ArrayList<>();
   for(String leg:MapOverlayPlacementCoordinator.placementLegNames()) {
     full.add(new MapOverlayPlacementCoordinator.Footprint(leg,204,84,.01f,.5f));
     body.add(new MapOverlayPlacementCoordinator.Footprint(leg,204,84,.01f,.5f,new RectF(34,2,202,82)));
   }
   // Old whole-bitmap collision rejects every candidate: each rectangle includes the fork.
   check(p.reserveIfClear("alt","old",55,37,168,80,true,-1,0,null,full)==null);
   check(p.reserveIfClear("alt","new",55,37,168,80,true,-1,0,null,body)!=null);
   // A second opaque body cannot use the same slot, regardless of its transparent tail.
   check(p.reserveIfClear("alt2","overlap",55,37,168,80,true,-1,0,null,body)==null);
   p.attach(new Window(),550,400);
   check(p.reserveIfClear("alt","clipped",55,37,168,80,true,-1,0,null,body)==null);
   p.detach();check(p.reserveIfClear("alt","unknown",55,37,168,80,true,-1,0,null,body)==null);
 }
}'''
            }
            paths = []
            for name, source in sources.items():
                path = out / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            production = ROOT / "navigator-mod/src/main/java/ru/natro/navigation"
            paths += [str(production / name) for name in ["MapOverlayPlacementCoordinator.java", "ReflectMethods.java"]]
            result = subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(out), *paths], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", str(out), "ru.natro.navigation.ForkReplay"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
