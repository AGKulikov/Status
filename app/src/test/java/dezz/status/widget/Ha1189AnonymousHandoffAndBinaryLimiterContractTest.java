/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1189 regressions derived from the latest Android 9/v26 in-car traces. */
public final class Ha1189AnonymousHandoffAndBinaryLimiterContractTest {
    @Test public void limiterRecorderPreservesReadOnlyByteArrayChanges() throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String integration = project(
                "app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java");
        String decoder = project(
                "app/src/main/java/dezz/status/widget/car/EcarxSignalDecoder.java");

        assertTrue(fallback.contains("EcarxSignalDecoder.coerceByteArray(value)"));
        assertTrue(fallback.contains("listener.onAdasBinarySignal"));
        assertTrue(decoder.contains("if (value instanceof byte[])"));
        assertTrue(decoder.contains("((byte[]) value).clone()"));
        assertTrue(integration.contains("ECARX_ADAS_BINARY_BASELINE"));
        assertTrue(integration.contains("ECARX_ADAS_BINARY_CHANGE"));
        assertTrue(integration.contains("changed_indices"));
        assertTrue(integration.contains("\"write_enabled\", false"));
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
