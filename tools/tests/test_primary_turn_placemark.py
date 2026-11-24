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
              int w=180,h=90; public Bitmap(){} public Bitmap(int w,int h){this.w=w;this.h=h;}
              public boolean isRecycled(){return false;}
              public int getWidth(){return w;} public int getHeight(){return h;}
            }""",
            "android.os.SystemClock": "public class SystemClock {public static long now=1000; public static long elapsedRealtime(){return now;}}",
            "com.yandex.mapkit.map.Callback": "public interface Callback {void onTaskFinished();}",
            "android.graphics.RectF": """public class RectF { public float left,top,right,bottom;
              public RectF(float a,float b,float c,float d){left=a;top=b;right=c;bottom=d;} }""",
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
                  Point point; ImageProvider image; IconStyle style; boolean visible; Callback loaded;
                  static boolean immediate=true;
                  public void setVisible(boolean v){visible=v;}
                  public void setGeometry(Point p){point=p;}
                  public void setIcon(ImageProvider p,IconStyle s,Callback c){image=p;style=s;loaded=c;if(immediate)c.onTaskFinished();}
                }
                Pin pin; int creations; java.util.List<Pin> pins=new java.util.ArrayList<>();
                public Pin addEmptyPlacemark(Point p){creations++;pin=new Pin();pin.point=p;pins.add(pin);return pin;}
                public void remove(MapObject p){pins.remove(p);if(pin==p)pin=null;}
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
                  LaneGuidanceAppearance style = new LaneGuidanceAppearance();
                  p.show(map,s,125,5f,c,style,()->{});
                  check(map.pin.visible && map.pin.point.x==55 && map.pin.point.y==37);
                  check(map.pin.image.bitmap==s.texture.bitmap && map.pin.style.scale==1.25f);
                  check(map.pin.style.anchor.x==.15f && map.pin.style.anchor.y==.85f);
                  check(c.width==225 && c.height==113 && p.replaces(lane));
                  check(p.replacesCard(s,c));
                  c.onScreen=false;check(!p.replacesCard(s,c));c.onScreen=true;
                  lane.routeSegmentIndex=6;lane.routeSegmentPosition=0;check(p.replaces(lane));
                  lane.routeSegmentIndex=8;check(!p.replaces(lane));
                  lane.routeSegmentIndex=5;lane.routeSegmentPosition=1;lane.latitude+=.0001;
                  check(!p.replaces(lane));lane.latitude=55;lane.routeSegmentPosition=Double.NaN;
                  check(!p.replaces(lane));lane.routeSegmentPosition=1;
                  s=new PrimaryTurnSample();s.texture.combined=false;p.show(map,s,90,5f,c,style,()->{});
                  check(!p.replaces(lane) && map.creations==2 && map.pin.style.scale==.9f);

                  // A replacement stays hidden until its asynchronous upload completes.
                  Pin old=map.pin;Pin.immediate=false;
                  s=new PrimaryTurnSample();s.texture.combined=false;s.texture.artwork=new CombinedLaneArtwork();
                  p.show(map,s,125,5f,c,style,()->{});Pin replacement=map.pin;
                  check(old.visible && !replacement.visible && map.pins.size()==2);
                  check(replacement.style.scale==1f && replacement.image.bitmap.getWidth()==123);
                  replacement.loaded.onTaskFinished();
                  check(replacement.visible && !old.visible && map.pins.size()==1);
                  check(c.width==123 && c.height==56);
                  // Distance revisions arriving faster than GPU completion cannot starve the first
                  // replacement. Completing its callback still produces a visible bounded image.
                  PrimaryTurnSample update=new PrimaryTurnSample();update.texture.artwork=new CombinedLaneArtwork();update.texture.combined=false;
                  update.texture.source=s.texture.source;
                  p.show(map,update,125,5f,c,style,()->{});Pin inFlight=map.pin;
                  for(int i=0;i<10;i++){
                    PrimaryTurnSample newer=new PrimaryTurnSample();newer.texture.artwork=new CombinedLaneArtwork();newer.texture.combined=false;
                    newer.texture.source=s.texture.source;
                    p.show(map,newer,125,5f,c,style,()->{});
                    check(map.pin==inFlight && !inFlight.visible && replacement.visible);
                  }
                  inFlight.loaded.onTaskFinished();check(inFlight.visible && !replacement.visible);
                  replacement=inFlight;s=update;
                  // One destination may still have the turn outside its viewport. It keeps a
                  // complete fixed card; the other destination can attach to the same turn.
                  c.onScreen=false;
                  p.show(map,s,125,5f,c,style,()->{});Pin docked=map.pin;
                  check(replacement.visible && !docked.visible);
                  docked.loaded.onTaskFinished();
                  check(docked.visible && docked.style.anchor.x==0 && docked.style.anchor.y==1);
                  check(docked.point.x==6 && docked.point.y==184 && c.screenReserved);
                  check(docked.image.bitmap.getWidth()==113 && docked.image.bitmap.getHeight()==56);
                  check(p.replacesCard(s,c));
                  c.failProjection=true;p.repositionFixed(c);check(!p.replacesCard(s,c));
                  c.failProjection=false;p.repositionFixed(c);check(p.replacesCard(s,c));
                  check(!p.replaces(lane));

                  // Both standalone and combined signs respond to the same appearance.
                  LaneGuidanceAppearance other=new LaneGuidanceAppearance();
                  p.show(map,s,125,5f,c,other,()->{});Pin changed=map.pin;
                  check(changed!=docked && docked.visible && !changed.visible);
                  // Superseded callback cannot swap a stale sign after route cleanup.
                  p.clear(map,c);changed.loaded.onTaskFinished();
                  check(map.pins.isEmpty() && !changed.visible && !p.replaces(lane));
                  // A queued upload that outlives its evidence cannot show a stale sign.
                  p.show(map,s,125,5f,c,style,()->{});Pin expired=map.pin;
                  android.os.SystemClock.now=3000;expired.loaded.onTaskFinished();
                  check(map.pins.isEmpty() && !expired.visible);
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
                  Object source=new Object();CombinedLaneArtwork artwork; Bitmap bitmap=new Bitmap(); boolean combined=true;float anchorX=.15f,anchorY=.85f;
                }
              }
              class LaneGuidanceAppearance {boolean same(LaneGuidanceAppearance x){return x==this;}}
              class CombinedLaneArtwork {
                float bodyWidth(LaneGuidanceAppearance a){return 90;} float bodyHeight(LaneGuidanceAppearance a){return 45;}
                Bitmap renderPlaced(LaneGuidanceAppearance a,float scale,TurnBalloonLayout l){return new Bitmap(l.width,l.height);}
              }
              class PrimaryTurnSample {
                static final long FRESH_MS=1500;
                long routeEpoch=4,sampledAt=1000;
                NavigatorStatePublisher.RouteTurnFrame turn=new NavigatorStatePublisher.RouteTurnFrame();
                StockTurnTextures.Texture texture=new StockTurnTextures.Texture();
              }
              class MapObjectLayerFactory {
                static void hideUntilTextured(Object pin){((RecordingMap.Pin)pin).setVisible(false);}
              }
              class MapOverlayPlacementCoordinator {
                int width,height; boolean onScreen=true,screenReserved=false,failProjection=false;
                int viewportWidth(){return 728;} int viewportHeight(){return 190;}
                boolean isBitmapInsideViewport(double lat,double lon,int w,int h,float ax,float ay){return onScreen;}
                Object pointAtScreen(float x,float y){return failProjection?null:new Point(x,y);}
                void reserveScreen(String owner,String key,android.graphics.RectF b){screenReserved=true;}
                static String[] placementLegNames(){return new String[]{"LEFT_CENTER","RIGHT_CENTER","TOP_CENTER","BOTTOM_CENTER","TOP_LEFT","TOP_RIGHT","BOTTOM_LEFT","BOTTOM_RIGHT"};}
                static class Placement {String legName;Placement(float x,float y,String s){legName=s;}}
                static class Footprint {Footprint(String s,int w,int h,float x,float y,android.graphics.RectF r){}}
                Placement reserveIfClear(String o,String k,double lat,double lon,int w,int h,boolean right,int si,double sp,Placement previous,java.util.List<Footprint> f){return onScreen?new Placement(0,.5f,"LEFT_CENTER"):null;}
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
                            str(SOURCE / "PrimaryTurnPlacemark.java"), str(SOURCE / "TurnBalloonLayout.java")], check=True)
            subprocess.run(["java", "-cp", str(root), "ru.natro.navigation.RecordingMap"], check=True)
