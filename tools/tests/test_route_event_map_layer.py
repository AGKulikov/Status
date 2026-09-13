#!/usr/bin/env python3
"""Behaviour replay for real-API route pins; APK declaration verification is a separate release gate."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_navigation_event_visibility import SOURCES as BASE, ROOT

SOURCES = {k:v for k,v in BASE.items() if not k.endswith('VisibilityReplay.java')}
SOURCES.update({
'ru/natro/navigation/NavigationMapProfile.java': '''package ru.natro.navigation;
final class NavigationMapProfile {static float layerZ(int p){return p;}}''',
'com/yandex/runtime/image/ImageProvider.java': '''package com.yandex.runtime.image;
public class ImageProvider {public final String tag; public ImageProvider(String tag){this.tag=tag;}}''',
'com/yandex/mapkit/ConflictResolutionMode.java': '''package com.yandex.mapkit;
public enum ConflictResolutionMode {IGNORE,MINOR,EQUAL,MAJOR}''',
'com/yandex/mapkit/road_events_layer/RoadEventSignificance.java': '''package com.yandex.mapkit.road_events_layer;
public enum RoadEventSignificance {TEST}''',
'com/yandex/mapkit/road_events_layer/RoadEventStylingProperties.java': '''package com.yandex.mapkit.road_events_layer;
import java.util.List;
public interface RoadEventStylingProperties {List<?> getTags(); boolean isOnRoute();
boolean hasSignificanceGreaterOrEqual(RoadEventSignificance s); boolean isSelected();
boolean isUserEvent();boolean isInFuture();boolean isValid();}''',
'com/yandex/mapkit/road_events_layer/RoadEventStyle.java': '''package com.yandex.mapkit.road_events_layer;
import android.graphics.PointF; import java.util.List; import com.yandex.runtime.image.ImageProvider;
public interface RoadEventStyle {void setIconImage(ImageProvider image); void setIconAnchor(PointF p);
PointF getIconAnchor(); void setZoomScaleFunction(List<?> p); List<?> getZoomScaleFunction();
void setZoomMin(int min);int getZoomMin();boolean isValid();}''',
'com/yandex/mapkit/map/IconStyle.java': '''package com.yandex.mapkit.map;
import android.graphics.PointF;
public class IconStyle {public float scale;public IconStyle setScale(Float s){scale=s;return this;}
public IconStyle setAnchor(PointF p){return this;}public IconStyle setZIndex(Float z){return this;}}''',
'ru/natro/navigation/RoutePinsReplay.java': '''package ru.natro.navigation;
import java.util.*;
import android.graphics.PointF;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.road_events_layer.*;
import com.yandex.runtime.image.ImageProvider;
public class RoutePinsReplay {
 public static class Pin {
  ImageProvider image;Point point;float scale;List<?> function;
  public void setGeometry(Point p){point=p;}
  public void setIcon(ImageProvider image,IconStyle style){this.image=image;scale=style.scale;}
  public void setScaleFunction(List<?> f){function=f;}
 }
 public static class Collection {
  final List<Pin> pins=new ArrayList<>();
  public Pin addPlacemark(){Pin pin=new Pin();pins.add(pin);return pin;}
  public void clear(){pins.clear();}
  public void setZIndex(float v){}
  public void setConflictResolutionMode(com.yandex.mapkit.ConflictResolutionMode v){}
 }
 public static class MapObject {
  final Collection collection=new Collection();
  public Collection addMapObjectLayer(String id){return collection;}
 }
 public static class Stock {
  final List<List<?>> tags=new ArrayList<>();boolean rejected;
  public boolean provideStyle(RoadEventStylingProperties p,boolean night,float scale,RoadEventStyle style){
   check(p.isOnRoute());check(p.isValid());check(!p.isUserEvent());
   if(rejected)return false;tags.add(new ArrayList<>(p.getTags()));
   style.setIconImage(new ImageProvider(String.valueOf(p.getTags().get(0))));
   style.setIconAnchor(new PointF(0.5f,0.5f));
   style.setZoomScaleFunction(Arrays.asList(new PointF(10,0.5f),new PointF(20,1f)));
   return true;
  }
 }
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static List<RoadEventRouteSynchronizer.Event> events(String... tags){
  List<RoadEventRouteSynchronizer.Event> out=new ArrayList<>();
  for(int i=0;i<80;i++)out.add(new RoadEventRouteSynchronizer.Event("id"+i,new Point(55+i*0.0001,37),Arrays.asList(tags),""));
  return out;
 }
 static void allTagsAndEveryEventReachNativePins() throws Exception {
  String[] tags={"ACCIDENT","RECONSTRUCTION","CHAT","LOCAL_CHAT","CLOSED","DRAWBRIDGE",
  "DANGER","OTHER","SPEED_CONTROL","NO_STOPPING_CONTROL","LANE_CONTROL","ROAD_MARKING_CONTROL",
  "MOBILE_CONTROL","CROSS_ROAD_CONTROL","TRAFFIC_CONTROL","CROSS_ROAD_DANGER","OVERTAKING_DANGER",
  "PEDESTRIAN_DANGER","SCHOOL","POLICE","POLICE_PATROL","FEEDBACK"};
  Map<String,String> modes=new HashMap<>();for(String tag:tags)modes.put(tag,"ROUTE_ONLY");
  MapObject map=new MapObject();RouteRoadEventMapLayer layer=new RouteRoadEventMapLayer("HUD");
  Stock stock=new Stock();layer.attach(map,stock);layer.configure(modes,true,false,false,150,125,20);
  for(String tag:tags){layer.render(events(tag));check(map.collection.pins.size()==80);
   for(Pin p:map.collection.pins){check(p.image.tag.equals(tag));check(p.point!=null);check(p.function.size()==2);}}
  layer.configure(modes,false,false,false,150,125,20);check(map.collection.pins.isEmpty());
 }
 static void profilesAndMixedTagsStayIndependent() throws Exception {
  MapObject a=new MapObject(),b=new MapObject();Stock sa=new Stock(),sb=new Stock();
  RouteRoadEventMapLayer hud=new RouteRoadEventMapLayer("HUD"),dim=new RouteRoadEventMapLayer("cluster");
  hud.attach(a,sa);dim.attach(b,sb);
  Map<String,String> hidden=new HashMap<>();hidden.put("ACCIDENT","HIDDEN");hidden.put("SPEED_CONTROL","HIDDEN");
  Map<String,String> shown=new HashMap<>();shown.put("ACCIDENT","ALWAYS");shown.put("SPEED_CONTROL","HIDDEN");
  hud.configure(hidden,true,false,true,100,100,20);dim.configure(shown,true,true,true,100,100,20);
  hud.render(events("SPEED_CONTROL","ACCIDENT"));dim.render(events("SPEED_CONTROL","ACCIDENT"));
  check(a.collection.pins.isEmpty());check(b.collection.pins.size()==80);
  for(Pin pin:b.collection.pins)check(pin.image.tag.equals("ACCIDENT"));
  dim.render(events("SPEED_CONTROL"));check(b.collection.pins.isEmpty());
  dim.render(Collections.emptyList());check(b.collection.pins.isEmpty());
 }
 static void cameraReplacementAndUnavailableStockNeverInventArtwork() throws Exception {
  MapObject map=new MapObject();Stock stock=new Stock();RouteRoadEventMapLayer layer=new RouteRoadEventMapLayer("HUD");
  Map<String,String> modes=new HashMap<>();modes.put("SPEED_CONTROL","ALWAYS");modes.put("ACCIDENT","ALWAYS");
  layer.attach(map,stock);layer.configure(modes,true,false,true,100,100,20);
  layer.render(events("SPEED_CONTROL"));check(map.collection.pins.isEmpty());
  layer.render(events("SPEED_CONTROL","ACCIDENT"));check(map.collection.pins.size()==80);
  stock.rejected=true;layer.render(events("ACCIDENT"));check(map.collection.pins.isEmpty());
  layer.detach();check(map.collection.pins.isEmpty());
 }
 public static void main(String[] args)throws Exception{RoutePinsReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}'''
})
class RouteEventMapLayerTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();cls.path=Path(cls.temp.name);files=[]
  for name,source in SOURCES.items():
   path=cls.path/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source);files.append(str(path))
  src=ROOT/'navigator-mod/src/main/java/ru/natro/navigation'
  files += [str(src/name) for name in ('RouteRoadEventMapLayer.java','RoadEventRouteSynchronizer.java',
   'RoadEventVisibility.java','RouteCameraPolicy.java','ReflectMethods.java','MapObjectLayerFactory.java')]
  result=subprocess.run(['java','com.sun.tools.javac.Main','-d',str(cls.path),*files],capture_output=True,text=True)
  if result.returncode: raise AssertionError(result.stderr)
 @classmethod
 def tearDownClass(cls):cls.temp.cleanup()
 def replay(self,name):subprocess.run(['java','-cp',str(self.path),'ru.natro.navigation.RoutePinsReplay',name],check=True)
 def test_all_22_tags_and_80_events(self):self.replay('allTagsAndEveryEventReachNativePins')
 def test_two_displays_and_mixed_tags(self):self.replay('profilesAndMixedTagsStayIndependent')
 def test_camera_replacement_and_stock_failure(self):self.replay('cameraReplacementAndUnavailableStockNeverInventArtwork')
