/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24 11:02 road journals. */
public final class Natro230RoadRecoveryContractTest {
    @Test public void versionAndYandexFallbackAreMonotonicAndVerified() throws Exception {
        String build = read("build.gradle");
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");

        assertTrue(build.contains("if (version == '2.3.0')"));
        assertTrue(build.contains("return 208021254"));
        assertTrue(controller.contains("YANDEX_RECEIVER_VERIFY_MS = 2_000L"));
        assertTrue(controller.contains("coldStartEscalation = attempt > 0"));
        assertTrue(controller.contains("receiver_result_verification"));
        assertTrue(command.contains("verified_media_browser_fallback"));
        assertTrue(command.contains("isUsablePlaySession"));
        assertTrue(command.contains("processState(context, target)"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));
    }

    @Test public void enrolledConnectionFailuresUseOneDirectRetryThenPresenceScan()
            throws Exception {
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String central = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");

        assertTrue(route.contains("ENROLLED_WAIT_SYSTEM_RECOVERY_MS = 5_000L"));
        assertTrue(route.contains("base.consecutiveFailures >= 2"));
        assertTrue(central.contains("enrolled connection status="));
        assertTrue(central.contains("one bounded direct exact-owner retry before presence scan"));
    }

    @Test public void helper69StillFencesLiveActivityWithoutChangingAncsCore()
            throws Exception {
        String helper = read("ios/KX11-iPhone-ANCS-Helper-v69-personal/"
                + "KX11ANCSHelper/NatroLiveActivityManager.swift");
        String client = read("ios/KX11-iPhone-ANCS-Helper-v69-personal/"
                + "CarRemoteProtocolV1.swift");

        assertTrue(helper.contains("if becameConnected {"));
        assertTrue(helper.contains("private var creatingActivities = false"));
        assertFalse(helper.contains("becameConnected || runningCount == 0"));
        assertTrue(client.contains("withTimeInterval: 5, repeats: true"));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}
