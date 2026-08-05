/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression barriers for the photographed icon barrel and reverse-route teardown loop. */
public final class Ha1175CircularIconAndSamePeerHandoffContractTest {
    @Test public void maximumIconRadiusIsAnExactCircleInsideCenteredSquareBounds()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String path = source("phone/AppleContinuousCornerPath.java");
        String config = source("phone/PhoneNotificationLayoutConfig.java");
        String icon = between(card, "private static final class AppleContinuousIconView",
                "private Drawable phoneAppIcon");

        assertTrue(config.contains("ICON_CORNER_RADIUS_MAX_PX = 120"));
        assertTrue(config.contains("clamp(iconCornerRadiusPx, 0, "
                + "ICON_CORNER_RADIUS_MAX_PX)"));
        assertTrue(icon.contains("float iconSide = Math.min(width, height)"));
        assertTrue(icon.contains("float iconLeft = (width - iconSide) / 2f"));
        assertTrue(icon.contains("float iconTop = (height - iconSide) / 2f"));
        assertTrue(icon.contains("canvas.clipRect(iconBounds)"));
        assertTrue(icon.contains("AppleContinuousCornerPath.setIconMask("));
        assertTrue(path.contains("if (safeRadius >= safeMaximum)"));
        assertTrue(path.contains("target.addOval(squareBounds, Path.Direction.CW)"));
        assertFalse(icon.contains("outputBounds.set(0f, 0f, width, height)"));
    }

    @Test public void serverReleaseCannotCloseTheSamePeerAncsClient() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String handler = between(transport,
                "private void handleVerifiedServerLinkDisconnected",
                "private static String deviceKey");

        assertTrue(handler.contains("boolean samePeerClientHandoff = managedIncomingMode"));
        assertTrue(handler.contains("clientConnectInFlight || gattClientConnected"
                + " || activeClientEstablished"));
        assertTrue(handler.contains("SAME-PEER HANDOFF · ANCS CLIENT PRESERVED"));
        assertTrue(handler.contains("Android ANCS client remains owner"));
        assertTrue(handler.indexOf("if (samePeerClientHandoff)")
                < handler.indexOf("BluetoothGatt current = gatt"));
        assertTrue(handler.indexOf("return;")
                < handler.indexOf("BluetoothGatt current = gatt"));
        assertTrue(transport.contains("iphoneAncsSeen = true"));
    }

    @Test public void helperV18UsesOneFreshAdvertisementReconnectOwner() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v18/"
                + "KX11ANCSHelper/ViewController.swift");
        String workflow = project(".github/workflows/verify-helper-v18.yml");

        assertTrue(helper.contains("KX11 ANCS HELPER v18"));
        assertTrue(helper.contains("peripheral.v18.single-link-g2"));
        assertTrue(helper.contains("central.v18.geely-ancs-g2"));
        assertTrue(helper.contains("AutoReconnect=false"));
        assertTrue(helper.contains("ManualFreshAdvertisement=true"));
        assertTrue(helper.contains("centralRequireFreshAdvertisement = true"));
        assertTrue(helper.contains("clearCentralRuntime(keepPeripheral: false)"));
        assertFalse(helper.contains("kCBConnectOptionEnableAutoReconnect"));
        assertTrue(workflow.contains("MARKETING_VERSION = 18.0"));
        assertTrue(workflow.contains("CURRENT_PROJECT_VERSION = 18"));
    }

    @Test public void releaseIdentityIsHa1175() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1175'"));
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
