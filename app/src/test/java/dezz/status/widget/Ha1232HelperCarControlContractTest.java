/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Natro 2.2.0 / Helper 53 encrypted Bluetooth vehicle-control boundary. */
public final class Ha1232HelperCarControlContractTest {
    @Test public void publicationIdentityRemainsInstallCompatible() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1232);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        String current = "if (version == '2.2.0') { return 208021232";
        assertTrue(build.contains(current));
        assertTrue(build.indexOf("if (version == '2.2.0')")
                < build.indexOf("if (version == '2.1.4')"));
    }

    @Test public void c5IsSeparateEncryptedAndBoundToTheExactOwner() throws Exception {
        String uuid = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "IphoneBleProtocolV2.java");
        String routeA = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String routeB = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidPeripheralTransportV2.java");
        assertTrue(uuid.toLowerCase().contains("d2d9e4c5-47f1-4e44-a8bb-a932fd5af200"));
        assertTrue(routeA.contains("carRemoteSubscriptionToken.sameOwner(owner.ownerToken)"));
        assertTrue(routeA.contains("WRITE_TYPE_DEFAULT"));
        assertTrue(routeB.contains("PERMISSION_WRITE_ENCRYPTED"));
        assertTrue(routeB.contains("PROPERTY_INDICATE"));
        assertTrue(routeB.contains("state.isReady()"));
        assertTrue(routeB.contains("ownsCarRemoteDescriptor"));
    }

    @Test public void wireCannotAddressArbitraryVehicleFunctions() throws Exception {
        String protocol = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "IphoneCarRemoteProtocolV1.java");
        String registry = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "CarRemoteControlRegistryV1.java");
        String bridge = project("app/src/main/java/dezz/status/widget/phone/"
                + "CarRemoteControllerV1.java");
        assertFalse(protocol.contains("functionId"));
        assertTrue(protocol.contains("FRAME_BYTES = 20"));
        assertTrue(protocol.contains("buffer.putInt(frame.value)"));
        assertTrue(registry.contains("forWireId(int wireId)"));
        assertTrue(registry.contains("30, \"vehicle.trunk\", true, true, false"));
        assertTrue(bridge.contains("forWireId(frame.controlId)"));
        assertTrue(bridge.contains("FLAG_CONFIRMED"));
        assertTrue(bridge.contains("MAX_COMMANDS_PER_SECOND"));
        assertTrue(bridge.contains("isNewerSequence"));
        assertTrue(bridge.contains("commandValueMatches"));
        assertTrue(bridge.contains("frame.value > 100 * entry.scale"));
    }

    @Test public void helperIsControlsFirstAndLegacyManagementLivesInSettings() throws Exception {
        String ui = project("ios/KX11-iPhone-ANCS-Helper-v53/KX11ANCSHelper/CarControlUI.swift");
        String settings = project(
                "ios/KX11-iPhone-ANCS-Helper-v53/KX11ANCSHelper/ViewController.swift");
        assertTrue(ui.contains("final class ViewController: UITabBarController"));
        for (String tab : new String[] {"Климат", "Сиденья", "Медиа", "Комфорт", "Настройки"}) {
            assertTrue(ui.contains("\"" + tab + "\""));
        }
        assertTrue(ui.contains("Быстро охладить"));
        assertTrue(ui.contains("Зимнее утро"));
        assertTrue(ui.contains("Комфортная поездка"));
        assertTrue(ui.contains("Всё выключить"));
        assertTrue(ui.contains("Ожидание режима AUTO"));
        assertTrue(ui.contains("selectableValues(for: definition)"));
        String carRemote = project("ios/KX11-iPhone-ANCS-Helper-v53/CarRemoteProtocolV1.swift");
        assertTrue(carRemote.contains("sendSceneCommand"));
        assertTrue(carRemote.contains("pendingResults"));
        assertTrue(settings.contains("final class HelperSettingsViewController"));
        assertTrue(settings.contains("beginEnrollment"));
        assertTrue(settings.contains("confirmEnrollmentSAS"));
        assertTrue(settings.contains("resetEnrollmentBinding"));
        assertFalse(ui.contains("http://"));
        assertFalse(ui.contains("https://"));
    }

    @Test public void passengerDerivedFunctionsStayCapabilityGated() throws Exception {
        String geely = project("app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java");
        for (String exact : new String[] {
                "HVAC_REAR_POWER_FUNCTION = 0x10010100",
                "HVAC_CIRCULATION_FUNCTION = 0x10030100",
                "HVAC_NATIVE_SYNC_FUNCTION = 0x10060500",
                "AMBIENT_BRIGHTNESS_FUNCTION = 0x2A010100",
                "PASSENGER_SCREEN_ENABLED_FUNCTION = 0x20280E00"}) {
            assertTrue(exact, geely.contains(exact));
        }
        assertTrue(geely.contains("controlAvailability(source, definition)"));
        assertTrue(geely.contains("readControlValue(source, definition)"));
        assertTrue(geely.contains("isControlCommandConfirmed(value, active.target)"));
        assertTrue(geely.contains("DEFAULT_ZONE = Integer.MIN_VALUE"));
        assertTrue(geely.contains("return new int[] { DEFAULT_ZONE, NO_ZONE }"));
        assertTrue(geely.contains(
                "return new int[] { DEFAULT_ZONE, 1, NO_ZONE, VehicleSeat.SEAT_ROW_1_RIGHT }"));
        assertTrue(geely.contains("firstSupportedControlRoute(source, definition)"));
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
