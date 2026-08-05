/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1169 fresh-GATT generation and background telemetry wake path. */
public final class Ha1169GattGenerationAndBackgroundWakeContractTest {
    @Test public void iconContinuousMaskIsBakedBeforeTheCardOverlayIsComposited()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String rounded = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(rounded.contains("Bitmap.Config.ARGB_8888"));
        assertTrue(rounded.contains("new PorterDuffXfermode(PorterDuff.Mode.DST_IN)"));
        assertTrue(rounded.contains("currentMaskedCanvas.drawBitmap(mask, 0f, 0f, alphaMaskPaint)"));
        assertTrue(rounded.contains("canvas.drawBitmap(masked, 0f, 0f, bitmapPaint)"));
        assertTrue(rounded.contains("AppleContinuousCornerPath.set(outputPath"));
        assertTrue(rounded.contains("source.eraseColor(Color.TRANSPARENT)"));
    }

    @Test public void multilineTextVerticalPlacementIsPersistedAndRendered() throws Exception {
        String config = source("phone/PhoneNotificationLayoutConfig.java");
        String card = source("phone/PhoneNotificationCardView.java");
        String editor = source("PhoneNotificationLayoutEditorActivity.java");

        assertTrue(config.contains("SCHEMA_VERSION = 4"));
        assertTrue(config.contains("TEXT_VERTICAL_TOP = \"top\""));
        assertTrue(config.contains(".put(\"verticalAlignment\", verticalAlignment)"));
        assertTrue(config.contains("target.verticalAlignment = origin.verticalAlignment"));
        assertTrue(editor.contains("Текст по центру поля по вертикали"));
        assertTrue(card.contains("element.verticalAlignment"));
        assertTrue(card.contains("? Gravity.TOP : Gravity.CENTER_VERTICAL"));
        assertTrue(card.contains("slotBottom - childTop - visibleHeight"));
    }

    @Test public void androidPublishesGenerationTwoB4NotifyWithRealCccd() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String server = between(transport, "private void openGattServer()",
                "private void startPreparedAdvertising()");
        String callbacks = between(transport, "private final BluetoothGattServerCallback",
                "private void handleIphonePeripheralConnectionState");

        assertTrue(transport.contains("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f02"));
        assertTrue(transport.contains("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f02"));
        assertTrue(server.contains("BluetoothGattCharacteristic.PROPERTY_NOTIFY"));
        assertTrue(server.contains("telemetry.addDescriptor(telemetryCccd)"));
        assertTrue(callbacks.contains("public void onDescriptorWriteRequest"));
        assertTrue(callbacks.contains("setServerTelemetrySubscription(device, enable)"));
        assertTrue(transport.contains("SERVER_TELEMETRY_WAKE_POLL_MS = 5_000L"));
        assertTrue(transport.contains("notifyCharacteristicChanged(target, telemetry, false)"));
    }

    @Test public void helper17AvoidsOldGattCacheAndBoundsRetainedConnect() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v17/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("KX11 ANCS HELPER v17"));
        assertTrue(helper.contains("D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F02"));
        assertTrue(helper.contains("peripheral.v17.single-link-g2"));
        assertTrue(helper.contains("central.v17.geely-ancs-g2"));
        assertTrue(helper.contains("source: \"current Core Bluetooth cache\""));
        assertTrue(helper.contains("peripheral.discoverCharacteristics(missing, for: service)"));
        assertTrue(helper.contains("cache fallback after uuidNotAllowed"));
        assertTrue(helper.contains("connect timeout; fresh D2D9 advertisement required"));
        assertTrue(helper.contains("deadline: .now() + 15"));
    }

    @Test public void releaseIdentityIsHa1169() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1173'"));
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
