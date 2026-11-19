"""Exercise the production placemark against a small recording MapKit surface."""
from pathlib import Path
import subprocess
import tempfile
import unittest


SOURCE = (Path(__file__).resolve().parents[2] / "navigator-mod/src/main/java/ru/natro/navigation")


class TestPrimaryTurnPlacemark(unittest.TestCase):
    def test_exact_texture_position_and_lane_replacement(self):
        fixtures = {
            "android.graphics.Bitmap": """public class Bitmap {
              public boolean isRecycled(){return false;}
              public int getWidth(){return 180;} public int getHeight(){return 90;}
            }""",
            "android.graphics.PointF": """public class PointF {
              public final float x,y; public PointF(float x,float y){this.x=x;this.y=y;}
            }""",
            "com.yandex.mapkit.geometry.Point": """public class Point {
              public final double x,y; public Point(double x,double y){this.x=x;this.y=y;}
            }""",
            "com.yandex.mapkit.map.RotationType": "public enum RotationType {NO_ROTATION}",
            "com.yandex.mapkit.map.MapObject": "public class MapObject {}",
            "com.yandex.mapkit.map.IconStyle": """public class IconStyle {
              public android.graphics.PointF anchor; public float scale;
              public void setAnchor(android.graphics.PointF p){anchor=p;}
              public void setScale(Float f){scale=f;} public void setFlat(Boolean f){}
              public void setRotationType(RotationType r){} public void setZIndex(Float f){}
            }""",
            "com.yandex.runtime.image.ImageProvider": """public class ImageProvider {
              public android.graphics.Bitmap bitmap;
              public static ImageProvider fromBitmap(android.graphics.Bitmap b){
                ImageProvider p=new ImageProvider(); p.bitmap=b; return p;
              }
            }""",
            "ru.natro.navigation.RecordingMap": """
              import android.graphics.Bitmap;
              import com.yandex.mapkit.geometry.Point;
              import com.yandex.mapkit.map.*;
              import com.yandex.runtime.image.ImageProvider;
              class RecordingMap {
                static final class Pin extends MapObject {
                  Point point; ImageProvider image; IconStyle style; boolean visible;
                  public void setVisible(boolean v){visible=v;}
                  public void setGeometry(Point p){point=p;}
                  public void setIcon(ImageProvider p,IconStyle s){image=p;style=s;}
                }
                Pin pin; int creations;
                public Pin addEmptyPlacemark(Point p){creations++;return pin=new Pin();}
                public void remove(MapObject p){if(pin==p)pin=null;}
                public static void main(String[] args)throws Exception{
                  RecordingMap map=new RecordingMap();
                  MapOverlayPlacementCoordinator c=new MapOverlayPlacementCoordinator();
                  PrimaryTurnPlacemark p=new PrimaryTurnPlacemark(); PrimaryTurnSample s=new PrimaryTurnSample();
                  NavigatorStatePublisher.LaneGuidanceFrame lane=new NavigatorStatePublisher.LaneGuidanceFrame();
                  check(PrimaryTurnPlacemark.isCurrent(s,4,true,2000,1500));
                  check(!PrimaryTurnPlacemark.isCurrent(s,4,true,2500,1500));
                  check(!PrimaryTurnPlacemark.isCurrent(s,5,true,2000,1500));
                  check(!PrimaryTurnPlacemark.isCurrent(s,4,false,2000,1500));
                  check(!PrimaryTurnPlacemark.isCurrent(s,4,true,999,1500));
                  check(!PrimaryTurnPlacemark.isCurrent(null,4,true,2000,1500));
                  p.show(map,s,125,5f,c);
                  check(map.pin.visible && map.pin.point.x==55 && map.pin.point.y==37);
                  check(map.pin.image.bitmap==s.texture.bitmap && map.pin.style.scale==1.25f);
                  check(map.pin.style.anchor.x==.15f && map.pin.style.anchor.y==.85f);
                  check(c.width==225 && c.height==113 && p.replaces(lane));
                  lane.routeSegmentIndex=6;lane.routeSegmentPosition=0;check(p.replaces(lane));
                  lane.routeSegmentIndex=8;check(!p.replaces(lane));
                  lane.routeSegmentIndex=5;lane.routeSegmentPosition=1;lane.latitude+=.0001;
                  check(!p.replaces(lane));lane.latitude=55;lane.routeSegmentPosition=Double.NaN;
                  check(!p.replaces(lane));lane.routeSegmentPosition=1;
                  s=new PrimaryTurnSample();s.texture.combined=false;p.show(map,s,90,5f,c);
                  check(!p.replaces(lane) && map.creations==1 && map.pin.style.scale==.9f);
                  p.clear(map,c);check(map.pin==null && !p.replaces(lane));
                }
                static void check(boolean condition){if(!condition)throw new AssertionError();}
              }
              class NavigatorStatePublisher {
                static class RouteTurnFrame {
                  String id="turn"; double latitude=55,longitude=37,routeSegmentPosition=1;
                  int routeSegmentIndex=5; boolean hasContent(){return true;}
                }
                static class LaneGuidanceFrame extends RouteTurnFrame {}
              }
              class StockTurnTextures {
                static class Texture {
                  Bitmap bitmap=new Bitmap(); boolean combined=true;float anchorX=.15f,anchorY=.85f;
                }
              }
              class PrimaryTurnSample {
                long routeEpoch=4,sampledAt=1000;
                NavigatorStatePublisher.RouteTurnFrame turn=new NavigatorStatePublisher.RouteTurnFrame();
                StockTurnTextures.Texture texture=new StockTurnTextures.Texture();
              }
              class MapObjectLayerFactory {
                static void hideUntilTextured(Object pin){((RecordingMap.Pin)pin).setVisible(false);}
              }
              class MapOverlayPlacementCoordinator {
                int width,height;
                void clearOwner(String name){}
                void reserveFixed(String owner,String key,double lat,double lon,int w,int h,float x,float y){
                  width=w;height=h;
                }
              }
            """,
        }
        with tempfile.TemporaryDirectory() as working:
            root = Path(working)
            paths = []
            for name, body in fixtures.items():
                package, simple = name.rsplit(".", 1)
                destination = root / (simple + ".java")
                destination.write_text(f"package {package};\n{body}")
                paths.append(str(destination))
            subprocess.run(["java", "com.sun.tools.javac.Main", "-d", str(root), *paths, str(SOURCE / "ReflectMethods.java"),
                            str(SOURCE / "PrimaryTurnPlacemark.java")], check=True)
            subprocess.run(["java", "-cp", str(root), "ru.natro.navigation.RecordingMap"], check=True)
