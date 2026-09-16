"""Execute unchanged camera diff/load/removal methods and real callback binding.

MapKit objects and rasterisation are doubles; this tests ownership and delayed
upload races, not texture appearance on the car.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_map_visibility_recovery import method, ROOT

SRC = ROOT / 'navigator-mod/src/main/java/ru/natro/navigation'

SOURCES = {
 'android/graphics/Bitmap.java': 'package android.graphics; public class Bitmap {public int getWidth(){return 80;}public int getHeight(){return 80;}}',
 'android/graphics/PointF.java': 'package android.graphics; public class PointF {public PointF(float x,float y){}}',
 'com/yandex/mapkit/geometry/Point.java': 'package com.yandex.mapkit.geometry; public class Point {public Point(double a,double b){}}',
 'com/yandex/mapkit/map/MapObject.java': 'package com.yandex.mapkit.map; public interface MapObject {}',
 'com/yandex/mapkit/map/RotationType.java': 'package com.yandex.mapkit.map; public enum RotationType {NO_ROTATION}',
 'com/yandex/mapkit/map/Callback.java': 'package com.yandex.mapkit.map; public interface Callback {void onTaskFinished();}',
 'com/yandex/mapkit/map/IconStyle.java': '''package com.yandex.mapkit.map;
import android.graphics.PointF;public class IconStyle {
 public void setAnchor(PointF p){}public void setRotationType(RotationType r){}
 public void setScale(Float f){}public void setFlat(Boolean b){}public void setVisible(Boolean b){}public void setZIndex(Float f){}
}''',
 'com/yandex/runtime/image/ImageProvider.java': '''package com.yandex.runtime.image;
public class ImageProvider {public static ImageProvider fromBitmap(android.graphics.Bitmap b){return new ImageProvider();}}''',
}
REPLAY = r'''package ru.natro.navigation;
import java.util.*;import java.lang.reflect.Method;
import android.graphics.Bitmap;import android.graphics.PointF;
import com.yandex.mapkit.map.*;import com.yandex.runtime.image.ImageProvider;
public class CameraReplay {
 static final String TAG="test",SOURCE_HUD_SPEED="HUD_SPEED";static final int MIN_CAMERA_TEXTURE_DIAMETER_PX=80;
 static class Log {static void w(String a,String b,Throwable t){}}
 static class Main {final ArrayDeque<Runnable> queue=new ArrayDeque<>();boolean post(Runnable r){queue.add(r);return true;}void drain(){while(!queue.isEmpty())queue.remove().run();}}
 static class MapSublayerOrder {static final String CAMERA_SIGNS="signs",CAMERA_SECTORS="sectors";}
 static class MapOverlayPlacementCoordinator {
  static final String OWNER_CAMERAS="camera";boolean inside=true;
  static class Placement {float anchorX=.5f,anchorY=.5f;boolean sameSlot(Placement p){return true;}}
  void clearOwner(String s){}boolean isPointInsideViewport(double a,double b){return inside;}
 }
 static class MapObjectLayerFactory {
  static final int IGNORE=0;
  static Object create(Object m,String id,int mode,float z){return id.equals("signs")?((MapFixture)m).signs:((MapFixture)m).sectors;}
  static void hideUntilTextured(Object p){((Visual)p).setVisible(false);}
 }
 public static class Visual implements MapObject {
  boolean visible,removed;public void setVisible(boolean v){check(!v||!removed,"removed visual resurrected");visible=v;}
 }
 public static class Pin extends Visual {
  static boolean sync;Callback callback;int uploads;boolean styleSet;
  public void setIcon(ImageProvider p,IconStyle s,Callback c){uploads++;callback=c;check(!visible,"empty pin exposed before upload");c.hashCode();check(c.equals(c),"callback equals");c.toString();if(sync)c.onTaskFinished();}
  public void setIconStyle(IconStyle s){styleSet=true;}
 }
 public static class CollectionFixture {
  List<Visual> values=new ArrayList<>();int creations;
  public Pin addEmptyPlacemark(com.yandex.mapkit.geometry.Point p){Pin v=new Pin();values.add(v);creations++;return v;}
  public void remove(MapObject o){Visual v=(Visual)o;v.removed=true;v.visible=false;values.remove(v);}
  public void clear(){for(Visual v:new ArrayList<>(values))remove(v);}
 }
 static class MapFixture {CollectionFixture signs=new CollectionFixture(),sectors=new CollectionFixture();}
 static class CameraMarker {
  String id,source="YANDEX";double latitude=55,longitude=37;int speedLimit;
  List<String> controlTags=Arrays.asList("SPEED_CONTROL");List<Double> directions=Arrays.asList(20d);
  CameraMarker(String id,int speed){this.id=id;speedLimit=speed;}
  boolean hasMapPosition(){return true;}
 }
 final Main main=new Main();final MapOverlayPlacementCoordinator placementCoordinator=new MapOverlayPlacementCoordinator();
 final ArrayList<CameraSign> cameraSigns=new ArrayList<>();final ArrayList<CameraMarker> visibleScratch=new ArrayList<>();
 Object map=new MapFixture(),signCollection,sectorCollection;long presentationRevision,latestVisualFingerprint,renderedFingerprint=Long.MIN_VALUE;float zIndex=20;
 boolean rejectBitmap;
 int cameraDisplayDiameter(){return 40;}
 Bitmap createCameraBitmap(CameraMarker c,int d){if(rejectBitmap&&c.id.equals("a"))throw new IllegalStateException("raster failed");return new Bitmap();}
 MapOverlayPlacementCoordinator.Placement reservePlacement(CameraMarker c,int w,int h){return new MapOverlayPlacementCoordinator.Placement();}
 Object addSector(Object target,double a,double b,double direction){Visual v=new Visual();((CollectionFixture)target).values.add(v);return v;}
 METHODS
 void update(CameraMarker... cameras){visibleScratch.clear();visibleScratch.addAll(Arrays.asList(cameras));latestVisualFingerprint=visualFingerprint(visibleScratch);render();}
 CameraSign sign(String id){for(CameraSign s:cameraSigns)if(s.camera.id.equals(id))return s;throw new AssertionError("missing "+id);}
 void ready(CameraSign s){((Pin)s.placemark).callback.onTaskFinished();main.drain();}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void visible(CameraSign s,boolean expected){check(((Visual)s.placemark).visible==expected,"sign visibility");for(Object v:s.sectors)check(((Visual)v).visible==expected,"atomic sector visibility");}
 public static void main(String[] args)throws Exception {
  CameraReplay r=new CameraReplay();MapFixture map=(MapFixture)r.map;
  r.update(new CameraMarker("a",60),new CameraMarker("b",40));
  CameraSign a=r.sign("a"),b=r.sign("b");visible(a,false);visible(b,false);
  r.update(new CameraMarker("a",60),new CameraMarker("b",40));
  check(map.signs.creations==2&&r.sign("a")==a&&r.sign("b")==b,"unchanged neighbours retained");
  r.ready(a);r.ready(b);visible(a,true);visible(b,true);
  r.update(new CameraMarker("a",80),new CameraMarker("b",40));
  check(r.sign("b")==b&&((Pin)b.placemark).uploads==1,"one change must not reupload neighbour");visible(b,true);
  check(((Visual)a.placemark).removed,"old sign removed");r.ready(a);check(!((Visual)a.placemark).visible,"late callback fenced");
  CameraSign replacement=r.sign("a");visible(replacement,false);r.ready(replacement);visible(replacement,true);
  r.placementCoordinator.inside=false;r.relayout();visible(b,false);visible(replacement,false);
  r.placementCoordinator.inside=true;r.relayout();visible(b,true);check(((Pin)b.placemark).uploads==1,"relayout no reupload");
  r.rejectBitmap=true;r.update(new CameraMarker("a",90),new CameraMarker("b",40));
  check(r.cameraSigns.size()==1&&r.sign("b")==b,"failed image leaves no default pin");visible(b,true);
  r.rejectBitmap=false;Pin.sync=true;r.update(new CameraMarker("a",90),new CameraMarker("b",40));r.main.drain();
  CameraSign recovered=r.sign("a");visible(recovered,true);visible(b,true);
  r.update();check(map.signs.values.isEmpty()&&map.sectors.values.isEmpty(),"empty route removes both layers");
  r.ready(recovered);check(!((Visual)recovered.placemark).visible,"clear fences callbacks");
  CameraMarker directionless=new CameraMarker("plain",50);directionless.directions=Collections.emptyList();
  r.update(directionless);r.main.drain();CameraSign plain=r.sign("plain");
  check(plain.sectors.isEmpty(),"missing source direction must not invent sector");visible(plain,true);r.update();
  Pin.sync=false;r.update(new CameraMarker("a",60));CameraSign detached=r.sign("a");
  r.map=new MapFixture();r.ready(detached);visible(detached,false);
 }
}
'''

class CameraImageLifecycleTest(unittest.TestCase):
    def test_incremental_async_sync_stale_failure_and_viewport(self):
        source = (SRC / 'CameraDirectionMapLayer.java').read_text()
        signatures = ('private void render()', 'private static String cameraKey(', 'private long markerRevision(',
                      'private void removeSign(', 'private static void removeObject(', 'private static boolean hasDirections(',
                      'private CameraSign addSign(', 'private void applyAtomicVisibility(', 'private static void setAtomicVisibility(',
                      'private void clearVisual()', 'private static long visualFingerprint(', 'private static long mix(',
                      'private static Object invoke(', 'private static final class CameraSign', 'void relayout()')
        replay = REPLAY.replace('METHODS', '\n'.join(method(source, s) for s in signatures))
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); files = []
            for name, code in {**SOURCES, 'ru/natro/navigation/CameraReplay.java': replay}.items():
                target = folder / name;target.parent.mkdir(parents=True, exist_ok=True);target.write_text(code);files.append(str(target))
            files += [str(SRC / n) for n in ('PlacemarkImageBinding.java', 'ReflectMethods.java')]
            result = subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', temp, *files], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(['java', '-cp', temp, 'ru.natro.navigation.CameraReplay'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
