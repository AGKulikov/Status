"""Run the real in-process matcher on unscaled ARGB map crops and stock-like glyphs."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MATCHER = ROOT / 'navigator-mod/src/main/java/ru/natro/navigation/StockTurnPixelMatcher.java'
REPLAY = r'''
package ru.natro.navigation;
import java.util.Arrays;

public final class TurnTextureReplay {
    static final int W = 72, H = 38, BG = 0xff181c40;
    static final int BLUE = 0xff0e48cf, WHITE = 0xfff5f6fb;
    static int[] texture(boolean combined) {
        int[] a = new int[W * H];
        Arrays.fill(a, BLUE);
        for (int x = 0; x < W; x++) a[x] = 0;
        for (int y = 3; y < 19; y++) {
            for (int i = 0; i < 3; i++) {
                int shaft = 7 + 9 * i;
                int half = y < 9 ? y - 3 : 1;
                for (int x = shaft - half; x <= shaft + half; x++) a[y * W + x] = WHITE;
            }
            if (combined) for (int x = 41; x < 66; x++) {
                if (x % 8 == 0 || y == 4 || y == 15) a[y * W + x] = WHITE;
            }
        }
        if (combined) for (int y = 23; y < 32; y++) for (int x = 4; x < 63; x++)
            if (x % 6 == 0 || ((x / 6) % 2 == 0 && y == 28)) a[y * W + x] = WHITE;
        return a;
    }

    static void paint(int[] out, int width, int x0, int y0, int[] tex, double fx, double fy) {
        for (int y = 0; y < H - 1; y++) for (int x = 0; x < W - 1; x++) {
            int p = y * W + x;
            if ((tex[p] & tex[p+1] & tex[p+W] & tex[p+W+1]) >>> 24 != 255) continue;
            int v = 0xff000000;
            for (int s = 0; s <= 16; s += 8) {
                double a = (tex[p] >> s) & 255, b = (tex[p+1] >> s) & 255;
                double c = (tex[p+W] >> s) & 255, d = (tex[p+W+1] >> s) & 255;
                double top = a + (b-a)*fx, bottom = c + (d-c)*fx;
                v |= (int)Math.round(top + (bottom-top)*fy) << s;
            }
            out[(y+y0)*width + x0+x] = v;
        }
    }

    static void check(boolean condition, String scenario) {
        if (!condition) throw new AssertionError(scenario);
    }

    public static void main(String[] args) {
        final int w = 200, h = 64, x = 9, y = 10, other = 95;
        int[] frame = new int[w*h], src = texture(true), lanes = texture(false);
        StockTurnPixelMatcher.Pattern t = StockTurnPixelMatcher.prepare(src, W, H);
        check(t != null, "recognizable native texture");
        Arrays.fill(frame, BG);
        paint(frame,w,x,y,lanes,0.0,0.0);
        paint(frame,w,other,y,src,0.0,0.0);

        check(!StockTurnPixelMatcher.matches(t,frame,w,h,x,y),"ordinary lane sign at first turn");
        check(StockTurnPixelMatcher.matches(t,frame,w,h,other,y),"combined sign at second turn");
        check(!StockTurnPixelMatcher.matches(t,frame,w,h,other-5,y),"nearby unrelated turn");
        check(!StockTurnPixelMatcher.matches(t,frame,w,h,-8,y),"clipped candidate");

        int[] original = src.clone();
        Arrays.fill(src, WHITE);
        check(StockTurnPixelMatcher.matches(t,frame,w,h,other,y),"immutable prepared revision");

        Arrays.fill(frame, BG);
        check(!StockTurnPixelMatcher.matches(t,frame,w,h,x,y),"sign hidden");
        Arrays.fill(frame, BLUE);
        check(!StockTurnPixelMatcher.matches(t,frame,w,h,x,y),"blue background alone");
        int[] uniform = new int[W*H];
        Arrays.fill(uniform, BLUE);
        check(StockTurnPixelMatcher.prepare(uniform,W,H) == null,"plain source rejected");
        check(StockTurnPixelMatcher.prepare(null,W,H) == null,"missing source");
        check(!StockTurnPixelMatcher.matches(t,frame,Integer.MAX_VALUE,2,x,y),"invalid capture");

        for (double fx : new double[]{0.08,0.25,0.48,0.62,0.75,0.93,0.99}) {
            for (double fy : new double[]{0.0,0.25,0.5,0.75,0.95}) {
                Arrays.fill(frame, BG);
                paint(frame,w,x,y,original,fx,fy);
                check(StockTurnPixelMatcher.matches(t,frame,w,h,x-1,y+1),"bilinear subpixel "+fx+","+fy);
            }
        }
        int[] newText = original.clone();
        for(int row=24;row<30;row++) for(int col=40;col<44;col++) newText[row*W+col]=WHITE;
        Arrays.fill(frame, BG);
        paint(frame,w,x,y,newText,0,0);
        check(!StockTurnPixelMatcher.matches(t,frame,w,h,x,y),"stale street label");
        Arrays.fill(frame,BG);
        paint(frame,w,x,y,original,0,0);
        paint(frame,w,other,y,original,0,0);
        check(StockTurnPixelMatcher.matches(t,frame,w,h,x,y)
                && StockTurnPixelMatcher.matches(t,frame,w,h,other,y),
                "two identical candidates remain ambiguous to caller");
    }
}
'''


class TurnTextureTest(unittest.TestCase):
    def test_primary_map_crops(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'TurnTextureReplay.java'
            source.write_text(REPLAY)
            subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', work,
                            str(MATCHER), str(source)], check=True, capture_output=True)
            subprocess.run(['java', '-cp', work, 'ru.natro.navigation.TurnTextureReplay'],
                           check=True)


if __name__ == '__main__':
    unittest.main()
