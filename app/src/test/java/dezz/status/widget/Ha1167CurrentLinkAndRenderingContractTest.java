/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the HA1167 OEM rendering and current-link reconnect fixes. */
public final class Ha1167CurrentLinkAndRenderingContractTest {
    @Test public void notificationIconUsesARealAndroid9AlphaMask() throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String rounded = between(card, "private static final class RoundedIconView",
                "private Drawable phoneAppIcon");

        assertTrue(rounded.contains("setLayerType(View.LAYER_TYPE_SOFTWARE, null)"));
        assertTrue(rounded.contains("canvas.saveLayer(maskBounds, null)"));
        assertTrue(rounded.contains("PorterDuff.Mode.DST_IN"));
        assertTrue(rounded.contains("canvas.drawRoundRect(maskBounds, radius, radius"));
        assertFalse(rounded.contains("canvas.clipPath"));
    }

    @Test public void climateIsRecenteredFromTheCurrentPhysicalButtonBounds()
            throws Exception {
        String exact = source("driver/DriverExactCenterFrameLayout.java");
        String runtime = source("driver/DriverPanelOverlayController.java");
        String settings = source("DriverPanelSettingsActivity.java");

        assertTrue(exact.contains("protected void onLayout"));
        assertTrue(exact.contains("childTop = contentTop"));
        assertTrue(exact.contains("child.layout(childLeft, childTop"));
        assertTrue(runtime.contains("button.addExactlyCentered(icon, requested"));
        assertTrue(settings.contains("cell.addExactlyCentered(icon, iconSize"));
    }

    @Test public void reverseRouteChallengesSecurityInsideTheCurrentGattCallback()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String server = between(transport, "private void openGattServer()",
                "private void closeGattServer()");
        String reads = between(transport, "public void onCharacteristicReadRequest",
                "public void onCharacteristicWriteRequest");

        assertTrue(server.contains("BluetoothGattCharacteristic.PERMISSION_READ"));
        assertTrue(server.contains("BluetoothGattCharacteristic.PERMISSION_WRITE"));
        assertFalse(server.contains("PERMISSION_READ_ENCRYPTED"));
        assertFalse(server.contains("PERMISSION_WRITE_ENCRYPTED"));
        assertTrue(reads.contains("issueCurrentLinkSecurityChallenge(device)"));
        assertTrue(reads.contains("STATUS_INSUFFICIENT_AUTHENTICATION"));
        assertTrue(reads.contains("isVerifiedPeer(device)"));
        assertTrue(transport.contains("linkSecurityChallengeIssued = false"));
        assertTrue(transport.contains("current-link challenge confirmed"));
    }

    @Test public void helper15RetriesOneCurrentLinkChallengeAndBoundsFailures()
            throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v15/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("KX11 ANCS HELPER v15"));
        assertTrue(helper.contains("B3 current-link challenge получен"));
        assertTrue(helper.contains("centralSecureReadAttempt >= 5"));
        assertTrue(helper.contains("current-link security did not advance"));
        assertTrue(helper.contains("peripheral.discoverCharacteristics(nil, for: service)"));
        assertTrue(helper.contains("CBConnectPeripheralOptionRequiresANCS: true"));
    }

    @Test public void releaseIdentityIsHa1167() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1167'"));
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
