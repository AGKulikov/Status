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
    static class Standalone {
        final FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        final View arrows = child(Color.WHITE), road = directionSign();
        View directionSign(){View v=new ru.yandex.yandexnavi.ui.guidance.context.DirectionSignView(root.getContext());v.setBackgroundColor(Color.YELLOW);root.addView(v);return v;}
        View child(int color) {
            View v = new View(root.getContext());
            v.setBackgroundColor(color);
            root.addView(v);
            return v;
        }
        float sourceScale = 1f;
        Standalone() {
            root.layout(0,0,80,40);
            arrows.layout(20,10,30,20);
            road.layout(40,10,50,20);
        }
        Standalone getTexture(){return this;}
        FrameLayout getView(){return root;}
        float getScale(){return sourceScale;}
        int getLegColor(){return Color.BLUE;}
        Standalone getRects(){return this;}
        RectF getBalloonRect(){return new RectF(0,0,80,40);}
        RectF getEnvelopingRect(){return getBalloonRect();}
        PointF bodyTopLeftCorner(){return new PointF();}
        PointF getBodySize(){return new PointF(80,40);}
        Path getBodyPath(PointF p){Path path=new Path();path.addRect(getBalloonRect(),Path.Direction.CW);return path;}
        Placement getLegPosition(){return Placement.NONE;}
    }
    static final class Fixture extends Standalone { Standalone getContainer(){return this;} View getRoadsignContainer(){return road;} }
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

    @Test public void standaloneAndCombinedUseTargetScaleRegardlessOfPrimaryScale() {
        for (boolean combined : new boolean[]{false,true}) for (float primary : new float[]{.5f,1f,2f}) {
            Standalone source = combined ? new Fixture() : new Standalone();
            source.sourceScale = primary;
            Bitmap original = Bitmap.createBitmap(Math.round(80*primary),Math.round(40*primary),Bitmap.Config.ARGB_8888);
            CombinedLaneArtwork artwork = CombinedLaneArtwork.capture(source, original);
            assertNotNull(artwork);
            NavigationMapProfile profile = new NavigationMapProfile();
            profile.laneGuidanceCardColor="#FF00FF00";
            profile.laneGuidanceSignsColor="#FFFF0000";
            for (float target : new float[]{.5f,1f,1.5f,2.5f}) {
                Bitmap out = artwork.render(original,new LaneGuidanceAppearance(profile),target);
                assertEquals(Math.round(80*target),out.getWidth());
                assertEquals(Math.round(40*target),out.getHeight());
                assertEquals(Color.GREEN,out.getPixel(Math.round(60*target),Math.round(25*target)));
                assertEquals(Color.RED,out.getPixel(Math.round(25*target),Math.round(15*target)));
                assertEquals(Color.YELLOW,out.getPixel(Math.round(45*target),Math.round(15*target)));
            }
        }
    }

    @Test public void recordedArtworkIsImmutableAndProfilesRemainIndependent() {
        Standalone source = new Standalone();
        Bitmap original=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);
        CombinedLaneArtwork artwork=CombinedLaneArtwork.capture(source,original);
        source.arrows.setBackgroundColor(Color.CYAN);
        source.arrows.layout(0,0,10,10);
        NavigationMapProfile hud=new NavigationMapProfile(),cluster=new NavigationMapProfile();
        hud.laneGuidanceSignsColor="#FFFF0000";hud.laneGuidanceCardColor="#FF00FF00";
        cluster.laneGuidanceSignsColor="#FF0000FF";cluster.laneGuidanceCardColor="#FFFFFFFF";
        Bitmap a=artwork.render(original,new LaneGuidanceAppearance(hud),1f);
        Bitmap b=artwork.render(original,new LaneGuidanceAppearance(cluster),2f);
        assertEquals(Color.RED,a.getPixel(25,15));assertEquals(Color.GREEN,a.getPixel(60,25));
        assertEquals(Color.BLUE,b.getPixel(50,30));assertEquals(Color.WHITE,b.getPixel(120,50));
        assertEquals(Color.RED,a.getPixel(25,15));
    }
}
