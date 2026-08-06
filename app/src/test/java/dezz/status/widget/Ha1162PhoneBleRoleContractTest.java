/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for the mutually exclusive Peripheral/Central phone routes in HA1162. */
public final class Ha1162PhoneBleRoleContractTest {
    @Test public void existingIphonePeripheralRouteRemainsTheDefault() throws Exception {
        String preferences = source("Preferences.java");
        String roles = source("phone/PhoneBleRole.java");
        String controller = source("phone/PhoneConnectorController.java");

        assertTrue(preferences.contains("new Int(this, \"phoneBleRole\", 0)"));
        assertTrue(roles.contains("IPHONE_PERIPHERAL = 0"));
        assertTrue(roles.contains("value == IPHONE_CENTRAL"));
        assertTrue(controller.contains("? created.acceptIphoneCentral(address, classicAddress)"));
        assertTrue(controller.contains(": created.connectSavedIphone(address)"));
    }

    @Test public void reverseRouteAdvertisesGeelyAndNeverFallsBackToTheOldRole()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String incoming = between(transport, "public boolean acceptIphoneCentral",
                "private boolean scheduleColdBackgroundAttach");
        String reconnect = between(transport,
                "private void scheduleManagedIncomingRestart",
                "private static boolean requiresControllerRetry");

        assertTrue(incoming.contains("managedIncomingMode = true"));
        assertTrue(incoming.contains("startGeelyAncsAdvertising()"));
        assertTrue(incoming.contains("Classic Bluetooth не изменяется"));
        assertTrue(reconnect.contains("managedIncomingMode = true"));
        assertTrue(reconnect.contains("iphonePeripheralMode = false"));
        assertFalse(reconnect.contains("iphonePeripheralMode = true"));
        assertFalse(incoming.contains("setName("));
        assertFalse(incoming.contains("removeBond"));
    }

    @Test public void helper12SelectsOneRoleAndUsesPublicAncsReconnectOptions()
            throws Exception {
        String helper12 = project("ios/KX11-iPhone-ANCS-Helper-v12/"
                + "KX11ANCSHelper/ViewController.swift");
        String info = project("ios/KX11-iPhone-ANCS-Helper-v12/"
                + "KX11ANCSHelper/Info.plist");
        String helper11 = project("ios/KX11-iPhone-ANCS-Helper-v11/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(helper12.contains("case peripheral = 0"));
        assertTrue(helper12.contains("UISegmentedControl(items: [\"Peripheral\", \"Central\"])"));
        assertTrue(helper12.contains("CBConnectPeripheralOptionRequiresANCS: true"));
        assertTrue(helper12.contains("CBConnectPeripheralOptionEnableAutoReconnect"));
        assertTrue(helper12.contains("stopAllBleRoutes()"));
        assertTrue(helper12.contains("Data(\"PAIR\".utf8)"));
        assertTrue(helper12.contains("readValue(for: secure)"));
        assertTrue(info.contains("<string>bluetooth-central</string>"));
        assertTrue(info.contains("<string>bluetooth-peripheral</string>"));
        assertFalse(helper11.contains("CBCentralManager"));
        assertFalse(helper11.contains("Geely_ANCS"));
    }

    @Test public void currentReleaseIdentityIsHa1164() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) {
            build = project("../build.gradle");
        }
        assertTrue(build.contains("return 'v2.8.2-ha1177'"));
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
