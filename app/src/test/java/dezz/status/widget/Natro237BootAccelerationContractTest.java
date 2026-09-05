/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class Natro237BootAccelerationContractTest {
    @Test public void mediaDeadlineIsNotMovedAndYandexColdStartHasNoUiRace() throws Exception {
        String lifecycle = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeLifecyclePolicy.java");
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");
        assertTrue(lifecycle.contains("ACTION_USER_UNLOCKED"));
        assertTrue(lifecycle.contains("static boolean shouldMovePlanAnchor"));
        assertTrue(lifecycle.contains("return false;"));
        assertFalse(controller.contains("YandexMusicBrowserStarter.requestWarmup(app)"));
        assertTrue(controller.contains("YANDEX_RECEIVER_GRACE_MS = 10_000L"));
        assertTrue(browser.contains("playBrowserSession"));
        assertTrue(browser.contains("if (connected && playRequested)"));
        assertFalse(browser.contains("startActivity("));
        assertFalse(browser.contains("startService("));
    }

    @Test public void phoneOwnsFirstControllerStageAndSafetyDelaysRemain() throws Exception {
        String service = read("app/src/main/java/dezz/status/widget/WidgetService.java");
        String dispatch = between(service,
                "private PreparedInitialIntegrationStage prepareInitialIntegrationWorkerStage(",
                "private PreparedInitialIntegrationStage preparePhonePresenceStage(");
        assertTrue(dispatch.indexOf("return preparePhoneStage(stage)")
                < dispatch.indexOf("return preparePhonePresenceStage(stage)"));
        assertTrue(read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java").contains("STARTUP_QUIET_MS = 3_000L"));
        assertTrue(read("app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                + "AndroidCentralTransportV2.java").contains(
                "REGISTERED_GATT_RETIRE_SETTLE_MS = 2_000L"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
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
