/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class HwgpsDrDebounceContractTest {
    @Test public void activeIsImmediateAndInactiveRequiresAStableFallingEdge() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/dezz/status/widget/integration/HwgpsIntegration.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("DR_INACTIVE_CONFIRM_MS = 2_500L"));
        assertTrue(source.contains("if (next == HwgpsDrStatePolicy.State.DR_ACTIVE)"));
        assertTrue(source.contains("mainHandler.postDelayed(confirmInactive"));
        assertTrue(source.contains("mainHandler.removeCallbacks(confirmInactive)"));
    }
}
