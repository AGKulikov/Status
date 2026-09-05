/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Natro 2.2.1 automatic iPhone reconnect and direct HWGPS DR-state boundary. */
public final class Ha1233ReconnectHwgpsContractTest {
    @Test public void publicationIsTheNextVersionAndPreserves220() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1233);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        String current = "if (version == '2.2.1') { return 208021233";
        String frozen = "if (version == '2.2.0') { return 208021232";
        assertTrue(build.contains(current));
        assertTrue(build.contains(frozen));
        assertTrue(build.indexOf(current) < build.indexOf(frozen));
    }

    @Test public void reconnectUsesTheExactSavedOwnerWithoutClassicDeadlock() throws Exception {
        String controller = project("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String route = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String platform = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        assertFalse(controller.contains("waiting_for_selected_classic_hfp"));
        assertTrue(controller.contains("runtime.selectedPhonePresent()"));
        assertTrue(route.contains("selectedPhonePresent(State state)"));
        assertTrue(route.contains("exact Classic presence; same public BluetoothGatt.connect()"));
        assertTrue(route.contains("autoConnect=false"));
        assertTrue(platform.contains("createGattOwner(token, enrolled, false, null)"));
        assertTrue(platform.contains("one bounded direct exact-owner retry before presence scan"));
    }

    @Test public void hwgpsTriggerUsesItsDirectDrContract() throws Exception {
        String policy = project("app/src/main/java/dezz/status/widget/integration/"
                + "HwgpsDrStatePolicy.java").toLowerCase();
        String integration = project("app/src/main/java/dezz/status/widget/integration/"
                + "HwgpsIntegration.java");
        String resolver = project("app/src/main/java/dezz/status/widget/integration/"
                + "SystemConditionResolver.java");
        for (String active : new String[] {
                "fix_dr", "fix_sw_dr", "fix_sw_dr_mm", "fix_sw_yl_safe"}) {
            assertTrue(active, policy.contains("\"" + active + "\""));
        }
        for (String inactive : new String[] {"notfixed", "fix_ok", "filtered", "spoofing"}) {
            assertTrue(inactive, policy.contains("\"" + inactive + "\""));
        }
        assertTrue(integration.contains("EXTRA_DR_ACTIVE = \"dr_active\""));
        assertTrue(resolver.contains("HWGPS_DR_ACTIVE_RESOURCE = \"hwgps.dr_active\""));
        assertFalse(resolver.contains("HwgpsRouteStateTracker"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
