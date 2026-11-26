"""The observer is scoped to the real factory invocation and preserves native callback results."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "navigator-mod/src/main/java/ru/natro/navigation/StockTurnTextures.java"


class StockTurnTexturesTest(unittest.TestCase):
    def test_original_factory_callbacks_surfaces_and_lifetime(self):
        sources = {
            "android.graphics.Bitmap": """public class Bitmap {
                public int version; public boolean sameAs(Bitmap b){return b!=null && version==b.version;}
                public int getWidth() {return 132;} public int getHeight() {return 56;}
                public boolean isRecycled() {return false;}
            }""",
            "com.yandex.runtime.image.ImageProvider": """public class ImageProvider {
                public android.graphics.Bitmap bitmap = new android.graphics.Bitmap();
                public android.graphics.Bitmap getImage() {return bitmap;}
            }""",
            "com.yandex.mapkit.ScreenPoint": """public class ScreenPoint {
                public float getX() {return .125f;} public float getY() {return .95f;}
            }""",
            "com.yandex.navikit.ui.guidance.context.Turn": """public interface Turn {
                com.yandex.mapkit.ScreenPoint getAnchor();
                com.yandex.runtime.image.ImageProvider createTexture();
                void setDistance(String text, String unit);
            }""",
            "com.yandex.navikit.ui.balloons.BalloonFactory": """
                import com.yandex.navikit.ui.guidance.context.Turn;
                public interface BalloonFactory {
                    Turn createManeuverBalloonView(); Turn createLaneAndManeuverBalloonView(boolean next);
                    Object createLaneBalloonView();
                }
            """,
            "ru.natro.navigation.StockManeuverCommands": """
                final class StockManeuverCommands {static class MapCard {
                    String text="";void observe(String name,Object[] args,Object view){
                      if(name.equals("setDistance"))text=args[0]+" "+args[1];}
                    String freeze(Object view,boolean combined){return text;}
                }}""",
            "ru.yandex.yandexnavi.ui.guidance.context.ManeuverBalloonViewImpl": """
                public class ManeuverBalloonViewImpl implements com.yandex.navikit.ui.guidance.context.Turn {
                    public int calls;
                    public String distance;
                    public boolean broken;
                    public com.yandex.runtime.image.ImageProvider image;
                    public final RuntimeException failure = new IllegalStateException("stock callback failed");
                    public void setDistance(String text, String unit) {distance=text+unit;}
                    public com.yandex.mapkit.ScreenPoint getAnchor() {
                        if (broken) throw new AssertionError("unreadable");
                        return new com.yandex.mapkit.ScreenPoint();
                    }
                    public com.yandex.runtime.image.ImageProvider createTexture() {
                        if (calls++==4) throw failure;
                        image = new com.yandex.runtime.image.ImageProvider();
                        image.bitmap.version=distance==null?0:distance.hashCode();return image;
                    }
                }
            """,
            "ru.yandex.yandexnavi.ui.guidance.context.LaneAndManeuverBalloonViewImpl":
                "public class LaneAndManeuverBalloonViewImpl extends ManeuverBalloonViewImpl {}",
            "ru.natro.navigation.CombinedLaneArtwork": "class CombinedLaneArtwork {static CombinedLaneArtwork capture(Object v,android.graphics.Bitmap b){return new CombinedLaneArtwork();}}",
            "ru.natro.navigation.TurnSourceTest": """
                import com.yandex.navikit.ui.balloons.BalloonFactory;
                import com.yandex.navikit.ui.guidance.context.Turn;
                import com.yandex.runtime.image.ImageProvider;
                import ru.yandex.yandexnavi.ui.guidance.context.*;
                public final class TurnSourceTest {
                    static class Factory implements BalloonFactory {
                        final ManeuverBalloonViewImpl single=new ManeuverBalloonViewImpl();
                        final LaneAndManeuverBalloonViewImpl both=new LaneAndManeuverBalloonViewImpl();
                        final Object ordinary=new Object();
                        public Turn createManeuverBalloonView(){return single;}
                        public Turn createLaneAndManeuverBalloonView(boolean b){return both;}
                        public Object createLaneBalloonView(){return ordinary;}
                    }
                    static void check(boolean test, String name){if(!test) throw new AssertionError(name);}
                    public static void main(String[] args){
                        Object primary=new Object(), other=new Object();
                        Factory stock=new Factory();
                        check(StockTurnTextures.wrapFactory(null,stock)==stock,"missing window");
                        check(StockTurnTextures.wrapFactory(primary,other)==other,"unknown factory");
                        BalloonFactory wrapped=(BalloonFactory)StockTurnTextures.wrapFactory(primary,stock);
                        check(wrapped!=stock && wrapped.createLaneBalloonView()==stock.ordinary,"plain lane passes through");
                        Turn joint=wrapped.createLaneAndManeuverBalloonView(true);
                        joint.setDistance("500","м");
                        check("500м".equals(stock.both.distance),"original setter");
                        check(StockTurnTextures.forWindow(primary).isEmpty(),"no pixels on factory/view creation");
                        ImageProvider image=joint.createTexture();
                        StockTurnTextures.Texture first=StockTurnTextures.forWindow(primary).get(0);
                        check(stock.both.calls==1 && first.combined && first.card.equals("500 м") && first.provider==image &&
                              image==stock.both.image && first.bitmap==image.getImage() &&
                              first.anchorX==.125f && first.anchorY==.95f,"genuine stock pixels and anchor");
                        check(StockTurnTextures.forWindow(other).isEmpty(),"distinct map windows");
                        joint.createTexture();
                        check(StockTurnTextures.forWindow(primary).get(0)==first,"same pixels keep observation identity");
                        joint.setDistance("400","м");joint.createTexture();
                        StockTurnTextures.Texture changed=StockTurnTextures.forWindow(primary).get(0);
                        check(changed!=first && changed.source==first.source && changed.card.equals("400 м") && first.card.equals("500 м"),"distance revision preserves native source identity");
                        stock.both.broken=true;
                        check(joint.createTexture()==stock.both.image &&
                              StockTurnTextures.forWindow(primary).isEmpty(),"failed sample preserves original result");
                        try {joint.createTexture(); throw new AssertionError("original exception lost");}
                        catch(RuntimeException expected){check(expected==stock.both.failure,"original exception identity");}
                        Turn single=wrapped.createManeuverBalloonView();
                        single.createTexture();
                        StockTurnTextures.Texture second=StockTurnTextures.forWindow(primary).get(0);
                        check(!second.combined && second.revision>first.revision && second.artwork!=null && second.source!=first.source,"ordinary maneuver uses same styling with separate source identity");
                        StockTurnTextures.clearWindow(other);
                        check(StockTurnTextures.forWindow(primary).size()==1,"clear only requested window");
                        StockTurnTextures.clearWindow(primary);
                        check(StockTurnTextures.forWindow(primary).isEmpty(),"route/view detach");
                    }
                }""",
        }
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            files = []
            for name, body in sources.items():
                pkg, unit = name.rsplit(".", 1)
                path = tmp.joinpath(*pkg.split("."), unit + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + pkg + ";\n" + body)
                files.append(str(path))
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(tmp), str(SOURCE), *files], check=True)
            subprocess.run(["java", "-cp", os.fspath(tmp), "ru.natro.navigation.TurnSourceTest"], check=True)


if __name__ == "__main__":
    unittest.main()
