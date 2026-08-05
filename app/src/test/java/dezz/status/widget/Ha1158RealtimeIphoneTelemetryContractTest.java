/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for power/radio Helper telemetry and its coarse battery fallback. */
public final class Ha1158RealtimeIphoneTelemetryContractTest {
    @Test public void helperObservesEveryPublicIosSourceAndKeepsChangedFrames() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v10/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("telemetrySampleInterval: TimeInterval = 1"));
        assertTrue(helper.contains("UIDevice.batteryLevelDidChangeNotification"));
        assertTrue(helper.contains("UIDevice.batteryStateDidChangeNotification"));
        assertTrue(helper.contains("CTServiceRadioAccessTechnologyDidChange"));
        assertTrue(helper.contains("dataServiceIdentifierDidChange"));
        assertTrue(helper.contains("scheduleSettledTelemetryRefresh"));
        assertTrue(helper.contains("pendingTelemetryFrames.append(frame)"));
        assertTrue(helper.contains("Double(device.batteryLevel) * 100.0"));
        assertFalse(helper.contains("withTimeInterval: 15"));
    }

    @Test public void androidSubscribesBeforeAncsAndReadsAtOneSecond() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");

        assertTrue(transport.contains("HELPER_TELEMETRY_POLL_MS = 1_000L"));
        assertTrue(transport.contains(
                "Helper B4 realtime subscription started before ANCS subscriptions"));
        assertTrue(transport.indexOf(
                "Helper B4 realtime subscription started before ANCS subscriptions")
                < transport.indexOf("ANCS найден. Подписываюсь Data Source"));
    }

    @Test public void unchangedControlReadsDoNotRebuildUiEverySecond() throws Exception {
        String controller = source("phone/PhoneConnectorController.java");

        assertTrue(controller.contains("boolean powerChanged = hasPower"));
        assertTrue(controller.contains("boolean networkChanged = hasNetwork"));
        assertTrue(controller.contains(
                "if (powerChanged || networkChanged) publishSnapshot(token)"));
        assertTrue(controller.contains(
                "Do not rewrite\n            // SharedPreferences or replace the UI snapshot"));
    }

    @Test public void releaseIdentityMovesForward() throws Exception {
        String build = project("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1174'"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("app/src/main/java/dezz/status/widget").resolve(relative),
                Paths.get("src/main/java/dezz/status/widget").resolve(relative));
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        // Gradle executes Android unit tests with app/ as the working directory,
        // where app/build.gradle also exists. Prefer the parent only when it is
        // demonstrably the project root; keep direct paths for repository-root runs.
        if (Files.isRegularFile(Paths.get("..", "settings.gradle"))
                && Files.isRegularFile(parent)) {
            return text(parent);
        }
        if (Files.isRegularFile(direct)) return text(direct);
        return text(parent);
    }

    private static String read(Path root, Path app) throws Exception {
        return text(Files.isRegularFile(root) ? root : app);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
