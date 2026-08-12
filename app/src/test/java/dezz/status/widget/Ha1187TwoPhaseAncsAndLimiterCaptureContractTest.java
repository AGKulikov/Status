/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Retained car-runtime coverage for the subscribed-but-never-polled integer catalog. */
public final class Ha1187TwoPhaseAncsAndLimiterCaptureContractTest {
    @Test public void runtimeIntegerCatalogIsSubscribedButNeverHealthPolled()
            throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String scan = between(fallback,
                "private void scanPropertyIds",
                "private LinkedHashSet<Integer> recorderPropertyIds");
        String healthRead = between(fallback,
                "private ReadResult readCurrentValues",
                "private void scheduleHealthRead");

        assertTrue(scan.contains("isIntegerCallbackProperty"));
        assertTrue(fallback.contains("typedRecorderDiscoveryIds"));
        assertTrue(fallback.contains("recorderIds.addAll(typedRecorderDiscoveryIds)"));
        assertTrue(fallback.contains("ids.addAll(typedRecorderDiscoveryIds)"));
        assertFalse(healthRead.contains("typedRecorderDiscoveryIds"));
        assertFalse(healthRead.contains("activeRecorderIds"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            // Gradle runs unit tests with app/ as the working directory. Requiring the root
            // settings file prevents a request for build.gradle from accidentally reading
            // app/build.gradle before reaching the repository root.
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) {
                continue;
            }
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
