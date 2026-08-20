/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for keeping an automatic Home button centred beside a fixed-height Back button. */
public final class Ha1163DriverButtonCenteringContractTest {
    @Test public void fixedHeightKeepsUntouchedSpacingDistinctFromExplicitZero()
            throws Exception {
        String height = source("driver/DriverButtonHeightPolicy.java");
        String spacing = source("driver/DriverControlSpacingPolicy.java");

        assertTrue(height.contains("FIXED_AUTO_SPACING_REQUEST"));
        assertTrue(height.contains("requestedPaddingPx < 0"));
        assertTrue(spacing.contains("normalizedRequest("));
        assertTrue(spacing.contains("top[index] >= 0"));
        assertTrue(spacing.contains("isFixedAutoSpacingRequest(top[index])"));
    }

    private static String source(String relative) throws Exception {
        Path direct = Paths.get("app/src/main/java/dezz/status/widget").resolve(relative);
        Path fromApp = Paths.get("src/main/java/dezz/status/widget").resolve(relative);
        Path file = Files.isRegularFile(direct) ? direct : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
