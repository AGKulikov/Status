/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Looper;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HudDriveModeRuntimeTest {
    @Test public void actualRuntimeSubscribesOnceExpiresAndRejectsOldCallbacksAfterStop() {
        CarIntegration.ControlStateListener[] registered = {null};
        int[] counts = {0, 0, 0};
        CarIntegration fake = (CarIntegration) Proxy.newProxyInstance(
                CarIntegration.class.getClassLoader(), new Class<?>[]{CarIntegration.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("subscribeControlStates")) {
                        assertEquals(java.util.Collections.singleton("vehicle.drive_mode"), args[0]);
                        registered[0] = (CarIntegration.ControlStateListener) args[1]; counts[0]++;
                    }
                    if (method.getName().equals("unsubscribeControlStates")) counts[1]++;
                    if (method.getName().equals("executeControl")) fail("HUD must never select a mode");
                    return method.getReturnType() == boolean.class ? false : null;
                });
        Object previous = ReflectionHelpers.getStaticField(CarIntegrations.class, "instance");
        ReflectionHelpers.setStaticField(CarIntegrations.class, "instance", fake);
        HudPanelConfig config = new HudPanelConfig();
        HudRuntimeData data = new HudRuntimeData(RuntimeEnvironment.getApplication(), config,
                () -> counts[2]++);
        try {
            data.start();
            assertEquals(1, counts[0]);
            data.updateConfig(config);
            assertEquals(1, counts[0]);
            registered[0].onControlState(HudDriveModeNotificationTest.state(570491138, true, 1));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("", data.driveModeNotificationText());
            registered[0].onControlState(HudDriveModeNotificationTest.state(570491137, true, 2));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(data.driveModeNotificationText().isEmpty());
            int beforeExpiry = counts[2];
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000));
            assertEquals("", data.driveModeNotificationText());
            assertTrue(counts[2] > beforeExpiry);
            CarIntegration.ControlStateListener old = registered[0];
            config.showDriveModeNotification = false;
            data.updateConfig(config);
            assertEquals(1, counts[1]);
            config.showDriveModeNotification = true;
            data.updateConfig(config);
            old.onControlState(HudDriveModeNotificationTest.state(570491139, true, 3));
            registered[0].onControlState(HudDriveModeNotificationTest.state(570491137, true, 4));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("", data.driveModeNotificationText());
            registered[0].onControlState(HudDriveModeNotificationTest.state(570491139, true, 5));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(data.driveModeNotificationText().isEmpty());
            data.stop();
            registered[0].onControlState(HudDriveModeNotificationTest.state(570491138, true, 6));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("", data.driveModeNotificationText());
        } finally {
            data.stop();
            ReflectionHelpers.setStaticField(CarIntegrations.class, "instance", previous);
        }
    }

    @Test public void nativeRendererMakesOnlyASmallTopCentrePillInsideHud() throws Exception {
        Bitmap image = Bitmap.createBitmap(800, 260, Bitmap.Config.ARGB_8888);
        RectF viewport = new RectF(36, 35, 764, 225);
        new HudDriveModeBanner().draw(new Canvas(image), viewport, "Комфорт", 2f, 2f);
        int minX=800, maxX=-1, minY=260, maxY=-1, white=0, black=0;
        for (int y=0;y<260;y++) for (int x=0;x<800;x++) {
            int color=image.getPixel(x,y);
            if (Color.alpha(color)==0) continue;
            assertTrue(viewport.contains(x,y));
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);
            minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            if(color==Color.WHITE)white++; if(color==Color.BLACK)black++;
        }
        assertTrue(white>100); assertTrue(black>10);
        assertEquals(35,minY); assertTrue(maxY<70);
        assertEquals(viewport.centerX(),(minX+maxX+1)/2f,1f);
        assertTrue(maxX-minX<240);
        File folder=new File("build/settings-screenshots");
        assertTrue(folder.isDirectory()||folder.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(folder,"hud-drive-mode-banner.png"))){
            assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));
        }
        image.recycle();
    }
}
