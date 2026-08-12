/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Retained limiter type-discovery coverage from the HA1188 regression set. */
public final class Ha1188PhysicalAncsHandoffAndFastLimiterCaptureContractTest {
    @Test public void limiterTypeDiscoveryBuildsOneMethodIndex() throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String scan = between(fallback,
                "private void scanPropertyIds",
                "private LinkedHashSet<Integer> recorderPropertyIds");

        assertTrue(scan.contains("discoverIntegerCallbackGetterNames(manager)"));
        assertTrue(scan.contains("discoverIntegerCallbackPropertyIds("));
        assertTrue(scan.contains("manager, integerCallbackGetters)"));
        assertTrue(scan.contains("for (Method method : manager.getClass().getMethods())"));
        assertTrue(scan.contains("getterNames.contains(getterName)"));
        assertFalse(scan.contains("findMethodIgnoreCase"));
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

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
