/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression boundary for the ANCS/music/crash evidence captured on 2026-08-23. */
public final class Natro2216RoadLogFixContractTest {
    @Test public void releaseIdentityAndAllThreeRoadFixesArePresent() throws Exception {
        String build = read("build.gradle");
        String media = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String lifecycle = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeLifecyclePolicy.java");
        String information = read("app/src/main/java/dezz/status/widget/launcher/information/"
                + "InformationPanelView.java");
        String hwgps = read("app/src/main/java/dezz/status/widget/integration/"
                + "HwgpsIntegration.java");

        assertTrue(build.contains("if (version == '2.2.16')"));
        assertTrue(build.contains("return 208021250"));
        assertTrue(lifecycle.contains("ACTION_LOCKED_BOOT_COMPLETED.equals(action)"));
        assertFalse(media.contains(".commit()"));
        assertTrue(media.contains("event=audio_route_kick"));
        assertTrue(information.contains("ConnectorValueSubscriptionHub.subscribe"));
        assertTrue(hwgps.contains("DR_INACTIVE_CONFIRM_MS = 2_500L"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(path)), StandardCharsets.UTF_8);
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
