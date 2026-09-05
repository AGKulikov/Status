/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Release boundary for the READY-before-ACTIVE ANCS delivery repair. */
public final class Natro2214AncsDeliveryContractTest {
    @Test public void controllerReconcilesRetainedReadyWhenCoordinatorBecomesActive()
            throws Exception {
        String controller = source(
                "app/src/main/java/dezz/status/widget/phone/PhoneConnectorController.java");
        assertTrue(controller.contains(
                "boolean routeDeliveryReady = v2ReadinessGate.onCoordinatorActive(activePhase)"));
        assertTrue(controller.contains("if (generationBoundary) {"));
        assertTrue(controller.contains("latestV2RouteStatus = null"));
        assertTrue(controller.contains(
                "routeDeliveryReady && !ancsReady && latestV2RouteStatus != null"));
        assertTrue(controller.contains("applyV2RouteStatus(token, latestV2RouteStatus)"));
        assertTrue(controller.contains(
                "boolean ready = v2ReadinessGate.onRouteLifecycle(lifecycle)"));
    }

    @Test public void completedAncsPayloadsReachThePrivacySafeJournal() throws Exception {
        String central = source("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String controller = source(
                "app/src/main/java/dezz/status/widget/phone/PhoneConnectorController.java");
        assertTrue(central.contains("ancsTrace.decodedNotification()"));
        assertTrue(controller.contains("decoded item ready; category="));
        assertTrue(controller.contains("notification presented; category="));
    }

    @Test public void releaseIdentityAdvancesInPlaceInstallCode() throws Exception {
        String build = source("build.gradle");
        assertTrue(build.contains("if (version == '2.2.14')"));
        assertTrue(build.contains("return 208021248"));
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Project source not found: " + relative);
    }
}
