/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for HA1168's pixel-exact visuals and event-driven iOS background telemetry. */
public final class Ha1168BackgroundWakeAndPixelGeometryContractTest {
    @Test public void appBadgeIsPaintedAsARoundedShaderPrimitive() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String rounded = between(card, "private static final class RoundedIconView",
                "private Drawable phoneAppIcon");

        assertTrue(rounded.contains("Bitmap.createBitmap"));
        assertTrue(rounded.contains("new BitmapShader"));
        assertTrue(rounded.contains("canvas.drawRoundRect(maskBounds, radius, radius"));
        assertFalse(rounded.contains("setClipToOutline("));
        assertFalse(rounded.contains("new PorterDuffXfermode"));
    }

    @Test public void climateCentersItsActualOpaquePixelBounds() throws Exception {
        String climate = source("driver/DriverClimateShortcutView.java");

        assertTrue(climate.contains("drawClimateContent(new Canvas(layer), width, height)"));
        assertTrue(climate.contains("findPaintedContent(layer)"));
        assertTrue(climate.contains("pixels[row + x] >>> 24"));
        assertTrue(climate.contains("height / 2f - contentCenterY"));
        assertTrue(climate.contains("canvas.drawBitmap(layer, dx, dy, compositePaint)"));
    }

    @Test public void geelyB4NotifiesTheSubscribedIphoneInBackground() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");

        assertTrue(transport.contains("HELPER_CENTRAL_BACKGROUND_POLL_MS = 5_000L"));
        assertTrue(transport.contains("BluetoothGattCharacteristic.PROPERTY_NOTIFY"));
        assertTrue(transport.contains("CLIENT_CHARACTERISTIC_CONFIGURATION"));
        assertTrue(transport.contains("onDescriptorWriteRequest"));
        assertTrue(transport.contains("telemetryNotificationsEnabled"));
        assertTrue(transport.contains("notifyCharacteristicChanged(device, characteristic, false)"));
        assertTrue(transport.contains("B4 background poll → iPhone Central"));
    }

    @Test public void helper16SubscribesAndAnswersEveryBluetoothWake() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v16/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("KX11 ANCS HELPER v16"));
        assertTrue(helper.contains("telemetry.properties.contains(.notify)"));
        assertTrue(helper.contains("peripheral.setNotifyValue(true, for: telemetry)"));
        assertTrue(helper.contains("didUpdateNotificationStateFor characteristic"));
        assertTrue(helper.contains("KX11 background wake poll"));
        assertTrue(helper.contains("let liveInfo = CTTelephonyNetworkInfo()"));
        String plist = project("ios/KX11-iPhone-ANCS-Helper-v16/KX11ANCSHelper/Info.plist");
        assertTrue(plist.contains("<string>bluetooth-central</string>"));
    }

    @Test public void releaseIdentityIsHa1168() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1168'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
