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
                public boolean sameAs(Bitmap b){return b!=null;}
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
                        if (calls++==3) throw failure;
                        return image = new com.yandex.runtime.image.ImageProvider();
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
                        check(stock.both.calls==1 && first.combined && first.provider==image &&
                              image==stock.both.image && first.bitmap==image.getImage() &&
                              first.anchorX==.125f && first.anchorY==.95f,"genuine stock pixels and anchor");
                        check(StockTurnTextures.forWindow(other).isEmpty(),"distinct map windows");
                        joint.createTexture();
                        check(StockTurnTextures.forWindow(primary).get(0)==first,"same pixels keep observation identity");
                        stock.both.broken=true;
                        check(joint.createTexture()==stock.both.image &&
                              StockTurnTextures.forWindow(primary).isEmpty(),"failed sample preserves original result");
                        try {joint.createTexture(); throw new AssertionError("original exception lost");}
                        catch(RuntimeException expected){check(expected==stock.both.failure,"original exception identity");}
                        Turn single=wrapped.createManeuverBalloonView();
                        single.createTexture();
                        StockTurnTextures.Texture second=StockTurnTextures.forWindow(primary).get(0);
                        check(!second.combined && second.revision>first.revision,"ordinary maneuver");
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
