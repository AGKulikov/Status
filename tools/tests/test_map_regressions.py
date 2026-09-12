#!/usr/bin/env python3
"""Execute production bootstrap/balloon/camera/Trip2 code without an APK or vehicle writes."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "android/graphics/Bitmap.java": """package android.graphics; public class Bitmap {
      public boolean isRecycled(){return false;} public int getWidth(){return 0;}
      public int getHeight(){return 0;} public void recycle(){}
      public void getPixels(int[] p,int o,int s,int x,int y,int w,int h){} }""",
    "android/view/TextureView.java": """package android.view; public class TextureView {
      public boolean isAvailable(){return false;}
      public android.graphics.Bitmap getBitmap(int w,int h){return null;} }""",
    "android/content/Context.java": "package android.content; public class Context { public Context getApplicationContext(){return this;} }",
    "android/car/CarNotConnectedException.java": "package android.car; public class CarNotConnectedException extends Exception {}",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long elapsedRealtimeNanos(){return 1000000000L;} }",
    "android/util/Log.java": """package android.util; public class Log {
      public static int w(String t,String s){return 0;} public static int w(String t,String s,Throwable e){return 0;}
      public static int d(String t,String s,Throwable e){return 0;} }""",
    "ecarx/car/ECarXCar.java": "package ecarx.car; public class ECarXCar {}",
    "ecarx/car/hardware/ECarXCarPropertyValue.java": "package ecarx.car.hardware; public class ECarXCarPropertyValue {}",
    "ecarx/car/hardware/signal/SignalFilter.java": "package ecarx.car.hardware.signal; public class SignalFilter { public void add(int id){} }",
    "ecarx/car/hardware/signal/CarSignalManager.java": """package ecarx.car.hardware.signal;
      public class CarSignalManager {
        public static final int SignalId_DstTrvld2=30878, SignalId_VehSpdAvgIndcdVehSpdIndcd=30957,
          SignalId_VehSpdAvgIndcdVeSpdIndcdUnit=30956;
        public int distance=174, speed=24, unit=0; public boolean failDistance;
        public boolean disconnectedDistance,disconnectedSpeed,disconnectedUnit;
        public CarSignalEventCallback callback; public int registrations, removals;
        public interface CarSignalEventCallback {
          void onChangeEvent(ecarx.car.hardware.ECarXCarPropertyValue value); void onErrorEvent(int p,int a); }
        public int getDstTrvld2()throws android.car.CarNotConnectedException{
          if(failDistance)throw new IllegalStateException();
          if(disconnectedDistance)throw new android.car.CarNotConnectedException();return distance;}
        public int getVehSpdAvgIndcdVehSpdIndcd()throws android.car.CarNotConnectedException{
          if(disconnectedSpeed)throw new android.car.CarNotConnectedException();return speed;}
        public int getVehSpdAvgIndcdVeSpdIndcdUnit()throws android.car.CarNotConnectedException{
          if(disconnectedUnit)throw new android.car.CarNotConnectedException();return unit;}
        public void registerCallback(CarSignalEventCallback c,SignalFilter f){callback=c;registrations++;}
        public void unregisterCallback(CarSignalEventCallback c){callback=null;removals++;}
      }""",
    "com/ecarx/xui/adaptapi/ECarXCarProxy.java": """package com.ecarx.xui.adaptapi;
      public class ECarXCarProxy {
        public interface ECarXCarProxyMethod {
          void onECarXCarServiceConnected(ecarx.car.ECarXCar root,ecarx.car.hardware.signal.CarSignalManager signals);
          void onECarXCarServiceDeath(); }
        public ECarXCarProxy(android.content.Context c,ECarXCarProxyMethod m){}
        public void initECarXCar(){} public void cleanup(){}
      }""",
    "dezz/status/widget/navigation/BootstrapReplay.java": r"""package dezz.status.widget.navigation;
      import java.util.*;
      public class BootstrapReplay {
        static void check(boolean b){if(!b)throw new AssertionError();}
        static boolean accept(int[] p){return MapFirstFrameDetector.hasRenderableContent(p,p.length);}
        static void blankFrames(){
          int[] p=new int[32*18];
          for(int c:new int[]{0,0xFFFFFFFF,0xFF000000,0xFF222222,0xFF888888,0xFFE8E8E8,0xFF123456}){
            Arrays.fill(p,c);check(!accept(p)); }
          for(int axis=0;axis<2;axis++){
            for(int i=0;i<p.length;i++){int v=180+(axis==0?i%32:i/32)*2;p[i]=0xFF000000|(v<<16)|(v<<8)|v;}
            check(!accept(p)); }
          check(!accept(new int[0]));
        }
        static void dayNightAndTransparentRoads(){
          for(int bg:new int[]{0xFFF2F1EB,0xFF101825,0}){
            int[] p=new int[32*18];Arrays.fill(p,bg);
            for(int i=0;i<p.length;i+=17)p[i]=0xFF55B830;
            check(accept(p)); }
        }
        static void startupSequence(){
          MapFirstFrameDetector.Gate gate=new MapFirstFrameDetector.Gate();
          for(int i=0;i<20;i++)check(!gate.acceptSample(false,true));
          check(!gate.acceptSample(true,true));check(!gate.acceptSample(true,false));
          check(!gate.acceptSample(true,true));check(!gate.acceptSample(true,true));
          check(gate.acceptSample(true,true));gate.reset();check(!gate.acceptSample(false,true));
          check(!gate.acceptSample(true,true));check(!gate.acceptSample(true,true));check(gate.acceptSample(true,true));
        }
        public static void main(String[] a)throws Exception{BootstrapReplay.class.getDeclaredMethod(a[0]).invoke(null);}
      }""",
    "ru/natro/navigation/MapRegressionReplay.java": r"""package ru.natro.navigation;
      import java.awt.geom.*;
      public class MapRegressionReplay {
        static void check(boolean b){if(!b)throw new AssertionError();}
        static void connectedBalloons(){
          for(float w:new float[]{28,38,76,240,640})for(float h:new float[]{28,38,90})
          for(float r:new float[]{0,6,15,180})for(float leader:new float[]{1,8,34,200}){
            float radius=Math.min(r,Math.min(w,h)/2);
            RoundRectangle2D body=new RoundRectangle2D.Float(0,0,w,h,radius*2,radius*2);
            for(float[] tip:new float[][]{{-leader,h/2},{w+leader,h/2},{w/2,-leader},{w/2,h+leader},
                {-leader,-leader},{w+leader,-leader},{-leader,h+leader},{w+leader,h+leader}}){
              float[] b=BalloonGeometry.tailBase(0,0,w,h,radius,tip[0],tip[1],h*.22f);
              check(body.contains(b[0],b[1]));check(body.contains(b[2],b[3]));
              Path2D tail=new Path2D.Float();tail.moveTo(tip[0],tip[1]);tail.lineTo(b[0],b[1]);tail.lineTo(b[2],b[3]);tail.closePath();
              Area overlap=new Area(tail);overlap.intersect(new Area(body));check(!overlap.isEmpty());
            }
          }
        }
        static void allConsecutiveControls(){
          int count=0;for(int segment=0;segment<80;segment++){
            if(RouteCameraPolicy.isAhead(segment,.5,81,true,0,.1))count++; }
          check(count==80);
          check(!RouteCameraPolicy.isAhead(0,.1,81,true,1,0));
          check(RouteCameraPolicy.isAhead(1,0,81,true,1,0));
          check(!RouteCameraPolicy.isAhead(81,.5,81,true,0,0));
          check(!RouteCameraPolicy.isAhead(1,Double.NaN,81,false,0,0));
          check(!RouteCameraPolicy.isAhead(1,1.1,81,false,0,0));
          check(!RouteCameraPolicy.sameSourceIdentity("cam-1","cam-2"));
          check(RouteCameraPolicy.sameSourceIdentity("cam-1","cam-1"));
          check(!RouteCameraPolicy.sameSourceIdentity("",""));
          for(String tag:new String[]{"SPEED_CONTROL","LANE_CONTROL","TRAFFIC_CONTROL","NO_STOPPING_CONTROL",
              "ROAD_MARKING_CONTROL","MOBILE_CONTROL","CROSS_ROAD_CONTROL"})check(RouteCameraPolicy.isControl(tag));
          check(!RouteCameraPolicy.isControl("ACCIDENT"));
        }
        public static void main(String[] a)throws Exception{MapRegressionReplay.class.getDeclaredMethod(a[0]).invoke(null);}
      }""",
    "dezz/status/widget/car/Trip2Replay.java": r"""package dezz.status.widget.car;
      import ecarx.car.hardware.signal.CarSignalManager;
      public class Trip2Replay {
        static void check(boolean b){if(!b)throw new AssertionError();}
        static void partialAndUnavailable(){
          EcarxTrip2Access source=new EcarxTrip2Access(new android.content.Context());
          CarSignalManager manager=new CarSignalManager();
          EcarxTrip2Access.Sample[] delivered={null};
          EcarxTrip2Access.Listener listener=s->delivered[0]=s;
          source.addListener(listener);source.onECarXCarServiceConnected(null,manager);
          check(delivered[0].distanceRaw==174);check(manager.registrations==1);
          manager.speed=-1;manager.callback.onChangeEvent(null);
          check(delivered[0].distanceRaw==174 && delivered[0].averageSpeedRaw==-1);
          check(source.latestSample().averageSpeedRaw==-1);
          manager.speed=24;manager.failDistance=true;manager.callback.onChangeEvent(null);
          check(delivered[0].distanceRaw==-1 && delivered[0].averageSpeedRaw==24);
          check(CurrentTripMetrics.averageSpeedKmh(delivered[0].averageSpeedRaw,0)==24);
          manager.failDistance=false;manager.disconnectedDistance=true;manager.callback.onChangeEvent(null);
          check(delivered[0].distanceRaw==-1 && delivered[0].averageSpeedRaw==24 && delivered[0].speedUnitRaw==0);
          manager.disconnectedDistance=false;manager.disconnectedUnit=true;manager.callback.onChangeEvent(null);
          check(delivered[0].distanceRaw==174 && delivered[0].averageSpeedRaw==24 && delivered[0].speedUnitRaw==-1);
          check(Float.isNaN(CurrentTripMetrics.averageSpeedKmh(delivered[0].averageSpeedRaw,delivered[0].speedUnitRaw)));
          manager.disconnectedUnit=false;manager.disconnectedSpeed=true;manager.callback.onChangeEvent(null);
          check(delivered[0].distanceRaw==174 && delivered[0].averageSpeedRaw==-1 && delivered[0].speedUnitRaw==0);
          source.onECarXCarServiceDeath();check(delivered[0].distanceRaw==-1 && delivered[0].averageSpeedRaw==-1);
          CarSignalManager fresh=new CarSignalManager();source.onECarXCarServiceConnected(null,fresh);
          check(delivered[0].distanceRaw==174);source.removeListener(listener);check(fresh.removals==1);
          source.close();
        }
        public static void main(String[] a)throws Exception{Trip2Replay.class.getDeclaredMethod(a[0]).invoke(null);}
      }""",
}


class MapRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="natro-map-regressions-")
        cls.directory = Path(cls.temp.name)
        files = []
        for name, source in SOURCES.items():
            path = cls.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        files += [str(ROOT / path) for path in (
            "app/src/main/java/dezz/status/widget/navigation/MapFirstFrameDetector.java",
            "app/src/main/java/dezz/status/widget/car/CurrentTripMetrics.java",
            "app/src/geely/java/dezz/status/widget/car/EcarxTrip2Access.java",
            "navigator-mod/src/main/java/ru/natro/navigation/BalloonGeometry.java",
            "navigator-mod/src/main/java/ru/natro/navigation/RouteCameraPolicy.java")]
        compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        compiled = subprocess.run([*compiler, "-d", str(cls.directory), *files], capture_output=True, text=True)
        if compiled.returncode:
            raise AssertionError(compiled.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def replay(self, package, name):
        subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(self.directory), package, name], check=True)


for package, cases in (
    ("dezz.status.widget.navigation.BootstrapReplay", ("blankFrames", "dayNightAndTransparentRoads", "startupSequence")),
    ("ru.natro.navigation.MapRegressionReplay", ("connectedBalloons", "allConsecutiveControls")),
    ("dezz.status.widget.car.Trip2Replay", ("partialAndUnavailable",)),
):
    for case in cases:
        setattr(MapRegressionTest, "test_" + case,
                lambda self, p=package, c=case: self.replay(p, c))


if __name__ == "__main__":
    unittest.main()
