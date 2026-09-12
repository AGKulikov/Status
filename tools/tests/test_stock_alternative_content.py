#!/usr/bin/env python3
"""Replay the production stock-content adapter against the reviewed baseline API shape."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/content/res/Resources.java": """package android.content.res; public class Resources {
      public int getIdentifier(String n,String t,String p){return n.startsWith("text_")?1:2;} }""",
    "android/content/Context.java": """package android.content; public class Context {
      public android.content.res.Resources getResources(){return new android.content.res.Resources();}
      public String getPackageName(){return "ru.yandex.yandexnavi";} }""",
    "android/graphics/drawable/Drawable.java": """package android.graphics.drawable;
      public class Drawable { public final String name;public Drawable(String n){name=n;}
        private int[] state=new int[0];private int level,alpha=255;
        public int[] getState(){return state;}public void setState(int[] s){state=s;}
        public int getLevel(){return level;}public void setLevel(int l){level=l;}
        public int getAlpha(){return alpha;}public void setAlpha(int a){alpha=a;}
        public Drawable mutate(){return this;}public ConstantState getConstantState(){return new ConstantState(name);}
        public static class ConstantState {final String name;ConstantState(String n){name=n;}
          public Drawable newDrawable(android.content.res.Resources r){return new Drawable(name);} }
      }""",
    "android/view/View.java": """package android.view; public class View {
      public static final int VISIBLE=0,GONE=8;public int visibility;
      public View text,icon; public View findViewById(int id){return id==1?text:icon;}
      public int getVisibility(){return visibility;} }""",
    "android/widget/TextView.java": """package android.widget;public class TextView extends android.view.View {
      public String label;public CharSequence getText(){return label;}public int getCurrentTextColor(){return 0xFFABCDEF;} }""",
    "android/widget/ImageView.java": """package android.widget;public class ImageView extends android.view.View {
      public android.graphics.drawable.Drawable drawable;
      public android.graphics.drawable.Drawable getDrawable(){return drawable;} }""",
    "com/yandex/mapkit/LocalizedValue.java": """package com.yandex.mapkit;public class LocalizedValue {
      final double value;public LocalizedValue(double v,String text){value=v;}public double getValue(){return value;} }""",
    "com/yandex/mapkit/geometry/PolylinePosition.java": """package com.yandex.mapkit.geometry;
      public class PolylinePosition {public final int segment;public PolylinePosition(int s){segment=s;} }""",
    "com/yandex/mapkit/directions/driving/Weight.java": """package com.yandex.mapkit.directions.driving;
      import com.yandex.mapkit.LocalizedValue;public class Weight {
        public final LocalizedValue time,traffic,distance;
        public Weight(LocalizedValue t,LocalizedValue tt,LocalizedValue d){time=t;traffic=tt;distance=d;}
        public LocalizedValue getTime(){return time;}public LocalizedValue getTimeWithTraffic(){return traffic;} }""",
    "com/yandex/mapkit/directions/driving/Flags.java": 'package com.yandex.mapkit.directions.driving;public class Flags {public String badge="";}',
    "com/yandex/mapkit/directions/driving/NonAvoidedFeatures.java": "package com.yandex.mapkit.directions.driving;public class NonAvoidedFeatures {}",
    "com/yandex/mapkit/directions/driving/Summary.java": """package com.yandex.mapkit.directions.driving;
      public class Summary {public final Weight weight;public final Flags flags;
        public Summary(Weight w,Flags f,NonAvoidedFeatures n){weight=w;flags=f;} }""",
    "com/yandex/mapkit/navigation/automotive/layer/AlternativeBalloon.java": """package com.yandex.mapkit.navigation.automotive.layer;
      import com.yandex.mapkit.directions.driving.*;public class AlternativeBalloon {
        public final Summary summary;public final Weight relative;
        public AlternativeBalloon(Summary s,Weight w){summary=s;relative=w;} }""",
    "com/yandex/mapkit/navigation/automotive/layer/Balloon.java": """package com.yandex.mapkit.navigation.automotive.layer;
      public class Balloon {public AlternativeBalloon alternative;
        public static Balloon fromAlternative(AlternativeBalloon a){Balloon b=new Balloon();b.alternative=a;return b;} }""",
    "com/yandex/mapkit/styling/automotive/balloons/BalloonColors.java": "package com.yandex.mapkit.styling.automotive.balloons;public class BalloonColors {}",
    "com/yandex/mapkit/styling/automotivenavigation/balloons/AlternativeBalloonTextureFactory.java": """package com.yandex.mapkit.styling.automotivenavigation.balloons;
      import android.view.*;import android.widget.*;import android.graphics.drawable.*;
      import com.yandex.mapkit.navigation.automotive.layer.*;
      public class AlternativeBalloonTextureFactory {
        private static final double NEGLECTABLE_TIME_DIFFERENCE=120;
        public static Balloon last;public final View view=new View();
        public AlternativeBalloonTextureFactory(android.content.Context c,com.yandex.mapkit.styling.automotive.balloons.BalloonColors colors){
          view.text=new TextView();view.icon=new ImageView();}
        public View createView(Balloon b,boolean night){last=b;
          ((TextView)view.text).label="STOCK:"+b.alternative.relative.traffic.getValue()+":"+night;
          String badge=b.alternative.summary.flags.badge;
          view.icon.visibility=badge.isEmpty()?View.GONE:View.VISIBLE;
          Drawable icon=new Drawable(badge);icon.setState(new int[]{night?7:3});icon.setLevel(9);icon.setAlpha(230);
          ((ImageView)view.icon).drawable=icon;return view;}
      }""",
    "ru/natro/navigation/AlternativeContentReplay.java": """package ru.natro.navigation;
      import com.yandex.mapkit.*;import com.yandex.mapkit.geometry.*;
      import com.yandex.mapkit.directions.driving.*;
      import com.yandex.mapkit.styling.automotivenavigation.balloons.AlternativeBalloonTextureFactory;
      public class AlternativeContentReplay {
        static void check(boolean b){if(!b)throw new AssertionError();}
        public static class Metadata {
          public final Flags flags=new Flags();final double time;
          Metadata(double t){time=t;}public Flags getFlags(){return flags;}
          public Weight getWeight(){return new Weight(new LocalizedValue(time,""),new LocalizedValue(time,""),new LocalizedValue(1000,""));}
          public NonAvoidedFeatures getNonAvoidedFeatures(){return new NonAvoidedFeatures();}
        }
        public static class Route {final String id;final Metadata metadata;public int lastSegment;
          Route(String id,double time){this.id=id;metadata=new Metadata(time);}
          public String getRouteId(){return id;}public Metadata metadataAt(PolylinePosition p){lastSegment=p.segment;return metadata;} }
        public static class Fork {final String id;final double time,distance;
          Fork(String id,double t,double d){this.id=id;time=t;distance=d;}
          public PolylinePosition positionOnRoute(String route){return id.equals(route)?new PolylinePosition(4):null;}
          public double timeToFinish(){return time;}public double distanceToFinish(){return distance;} }
        static void exactContentAndRouteBoundBadge()throws Exception {
          StockAlternativeContent adapter=new StockAlternativeContent(new android.content.Context());
          Route alternative=new Route("alt",900),current=new Route("main",1000);
          alternative.metadata.flags.badge="toll";current.metadata.flags.badge="wrong-route";
          StockAlternativeContent.Content first=adapter.read(alternative,new Fork("alt",1120.25,1450),
            current,new Fork("main",1000,1000),true);
          check(first.text.equals("STOCK:120.25:true"));check(!first.neutral);check(first.color==0xFFABCDEF);
          check(first.icon.name.equals("toll"));check(alternative.lastSegment==4 && current.lastSegment==4);
          check(first.icon.getState()[0]==7 && first.icon.getLevel()==9 && first.icon.getAlpha()==230);
          check(AlternativeBalloonTextureFactory.last.alternative.relative.time.getValue()==-100);
          check(AlternativeBalloonTextureFactory.last.alternative.relative.distance.getValue()==450);
          alternative.metadata.flags.badge="";
          StockAlternativeContent.Content second=adapter.read(alternative,new Fork("alt",1000,1000),
            current,new Fork("main",1000,1000),false);
          check(second.neutral && second.icon==null);check(first.icon.name.equals("toll"));
          check(second.text.equals("STOCK:0.0:false"));
        }
        static void staleRouteAndInvalidWeightFailClosed()throws Exception {
          StockAlternativeContent adapter=new StockAlternativeContent(new android.content.Context());
          Route a=new Route("alt",1),c=new Route("main",1);
          try{adapter.read(a,new Fork("old",1,1),c,new Fork("main",1,1),false);throw new AssertionError();}
          catch(IllegalArgumentException expected){}
          try{adapter.read(a,new Fork("alt",Double.NaN,1),c,new Fork("main",1,1),false);throw new AssertionError();}
          catch(IllegalArgumentException expected){}
        }
        public static void main(String[] a)throws Exception{AlternativeContentReplay.class.getDeclaredMethod(a[0]).invoke(null);}
      }""",
}


class StockAlternativeContentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-stock-alternative-")
        cls.directory = Path(cls.temp.name)
        files = []
        for name, source in SOURCES.items():
            path = cls.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        production = ROOT / "navigator-mod/src/main/java/ru/natro/navigation"
        files += [str(production / name) for name in ("StockAlternativeContent.java", "ReflectMethods.java")]
        compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        result = subprocess.run([*compiler, "-d", str(cls.directory), *files], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, case):
        subprocess.run(["java", "-cp", str(self.directory), "ru.natro.navigation.AlternativeContentReplay", case], check=True)

    def test_exact_content(self): self.replay("exactContentAndRouteBoundBadge")
    def test_stale_route(self): self.replay("staleRouteAndInvalidWeightFailClosed")


if __name__ == "__main__":
    unittest.main()
