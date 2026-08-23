/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class HwgpsDrDebounceContractTest {
    @Test public void activeIsImmediateAndInactiveRequiresAStableFallingEdge() throws Exception {
        String source = new String(Files.readAllBytes(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/integration/HwgpsIntegration.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("DR_INACTIVE_CONFIRM_MS = 2_500L"));
        assertTrue(source.contains("if (next == HwgpsDrStatePolicy.State.DR_ACTIVE)"));
        assertTrue(source.contains("mainHandler.postDelayed(confirmInactive"));
        assertTrue(source.contains("mainHandler.removeCallbacks(confirmInactive)"));
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
