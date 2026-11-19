/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Application;
import android.graphics.*;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CombinedLaneArtworkTest {
    enum Placement { NONE }
    static final class Fixture {
        final FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        final View arrows = child(Color.WHITE), road = child(Color.YELLOW);
        View child(int color) {
            View v = new View(root.getContext());
            v.setBackgroundColor(color);
            root.addView(v);
            return v;
        }
        Fixture() {
            root.layout(0,0,80,40);
            arrows.layout(20,10,30,20);
            road.layout(40,10,50,20);
        }
        Fixture getContainer(){return this;}
        Fixture getTexture(){return this;}
        FrameLayout getView(){return root;}
        View getRoadsignContainer(){return road;}
        float getScale(){return 1f;}
        int getLegColor(){return Color.BLUE;}
        Fixture getRects(){return this;}
        RectF getBalloonRect(){return new RectF(0,0,80,40);}
        RectF getEnvelopingRect(){return getBalloonRect();}
        PointF bodyTopLeftCorner(){return new PointF();}
        PointF getBodySize(){return new PointF(80,40);}
        Path getBodyPath(PointF p){Path path=new Path();path.addRect(getBalloonRect(),Path.Direction.CW);return path;}
        Placement getLegPosition(){return Placement.NONE;}
    }
    @Test public void sameDisplayProfileStylesCombinedSignWithoutMutatingSource() {
        Fixture fixture = new Fixture();
        Bitmap stock = Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);
        stock.eraseColor(Color.BLUE);
        CombinedLaneArtwork layers = CombinedLaneArtwork.capture(fixture, stock);
        assertNotNull(layers);
        NavigationMapProfile profile = new NavigationMapProfile();
        assertSame(stock, layers.render(stock, new LaneGuidanceAppearance(profile)));
        profile.laneGuidanceCardColor = "#8000FF00";
        profile.laneGuidanceSignsColor = "#FFFF0000";
        profile.laneGuidanceBorderColor = "#FFFFFFFF";
        profile.laneGuidanceBorderWidthPx = 3;
        profile.laneGuidanceCornerRadiusPx = 10;
        Bitmap styled = layers.render(stock, new LaneGuidanceAppearance(profile));
        assertEquals(0x8000FF00, styled.getPixel(52,24));
        assertEquals(Color.RED, styled.getPixel(25,15));
        assertEquals(Color.YELLOW, styled.getPixel(45,15));
        assertEquals(Color.WHITE, styled.getPixel(1,20));
        assertEquals(0, styled.getPixel(0,0));
        assertEquals(Color.BLUE, stock.getPixel(25,15));
    }
}
