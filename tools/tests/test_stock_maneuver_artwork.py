#!/usr/bin/env python3
"""Exercise production capture ownership/cache against a mutable source-image fixture.

The fixture tests pixel transport and revisions, not Android's actual Canvas rendering.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/graphics/Bitmap.java": '''package android.graphics;
import java.util.Arrays;
public final class Bitmap {
    public enum Config { ARGB_8888 }
    public static int allocations;
    final int width,height; public final int[] pixels; boolean recycled;
    Bitmap(int w,int h){width=w;height=h;pixels=new int[w*h];allocations++;}
    public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(w,h);}
    public int getWidth(){return width;} public int getHeight(){return height;}
    public boolean isRecycled(){return recycled;}
    public void eraseColor(int color){Arrays.fill(pixels,color);}
    public boolean sameAs(Bitmap other){return width==other.width&&height==other.height&&Arrays.equals(pixels,other.pixels);}
    public Bitmap copy(Config c,boolean mutable){Bitmap b=new Bitmap(width,height);System.arraycopy(pixels,0,b.pixels,0,pixels.length);return b;}
}
''',
    "android/graphics/Canvas.java": '''package android.graphics;
public final class Canvas {
    public final Bitmap bitmap; public Canvas(Bitmap b){bitmap=b;}
    public int save(){return 1;} public void restoreToCount(int save){}
}
''',
    "android/widget/ImageView.java": '''package android.widget;
import android.graphics.Canvas;
public final class ImageView {
    public int width=4,height=4; public int[] pixels=new int[16];
    public final Object drawable=new Object(); public boolean fail;
    public int getWidth(){return width;} public int getHeight(){return height;}
    public Object getDrawable(){return drawable;}
    public void draw(Canvas c){if(fail)throw new IllegalStateException();System.arraycopy(pixels,0,c.bitmap.pixels,0,pixels.length);}
}
''',
    "ru/natro/navigation/ArtworkReplay.java": '''package ru.natro.navigation;
import android.graphics.Bitmap;import android.widget.ImageView;
public final class ArtworkReplay {
    static void check(boolean x){if(!x)throw new AssertionError();}
    static void sameDrawableChangesHeadOnlyToCompleteArrow(){
        StockManeuverArtwork capture=new StockManeuverArtwork();ImageView view=new ImageView();
        view.pixels[1]=-1;Bitmap head=capture.capture(view);int first=capture.revision();
        view.pixels[5]=-1;view.pixels[9]=-1;view.pixels[10]=-1;
        Bitmap arrow=capture.capture(view);check(arrow!=head);check(capture.revision()!=first);
        check(arrow.pixels[10]==-1);check(head.pixels[10]==0);
    }
    static void staticFrameDoesNotAllocateOrResend(){
        StockManeuverArtwork capture=new StockManeuverArtwork();ImageView view=new ImageView();
        Bitmap frame=capture.capture(view);int rev=capture.revision(),alloc=Bitmap.allocations;
        for(int n=0;n<100;n++){check(frame==capture.capture(view));check(rev==capture.revision());}
        check(alloc==Bitmap.allocations);
    }
    static void disappearingPixelsAreCleared(){
        StockManeuverArtwork capture=new StockManeuverArtwork();ImageView view=new ImageView();
        view.pixels[5]=-1;Bitmap old=capture.capture(view);view.pixels[5]=0;
        check(capture.capture(view).pixels[5]==0);check(old.pixels[5]==-1);
    }
    static void invalidOrFailedCaptureDoesNotReturnOldFrame(){
        StockManeuverArtwork capture=new StockManeuverArtwork();ImageView view=new ImageView();
        capture.capture(view);int rev=capture.revision();view.width=257;
        check(capture.capture(view)==null);check(capture.revision()==rev);view.width=4;view.fail=true;
        check(capture.capture(view)==null);view.fail=false;view.pixels[0]=-1;check(capture.capture(view)!=null);
    }
    static void changedBoundsCannotReuseOldFrame(){
        StockManeuverArtwork capture=new StockManeuverArtwork();ImageView view=new ImageView();
        capture.capture(view);int rev=capture.revision();view.width=8;view.height=2;
        Bitmap resized=capture.capture(view);check(resized.getWidth()==8);check(capture.revision()!=rev);
    }
    public static void main(String[] args)throws Exception{ArtworkReplay.class.getDeclaredMethod(args[0]).invoke(null);}
}
''',
}


class StockManeuverArtworkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        files = []
        for name, source in SOURCES.items():
            path = cls.directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            files.append(str(path))
        files.append(str(ROOT / "navigator-mod/src/main/java/ru/natro/navigation/StockManeuverArtwork.java"))
        subprocess.run(["javac", "-d", str(cls.directory), *files], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def replay(self, case):
        subprocess.run(["java", "-cp", str(self.directory),
                        "ru.natro.navigation.ArtworkReplay", case], check=True)

    def test_mutable_drawable(self): self.replay("sameDrawableChangesHeadOnlyToCompleteArrow")
    def test_static_frame(self): self.replay("staticFrameDoesNotAllocateOrResend")
    def test_clear(self): self.replay("disappearingPixelsAreCleared")
    def test_failure(self): self.replay("invalidOrFailedCaptureDoesNotReturnOldFrame")
    def test_resize(self): self.replay("changedBoundsCannotReuseOldFrame")


if __name__ == "__main__": unittest.main()
