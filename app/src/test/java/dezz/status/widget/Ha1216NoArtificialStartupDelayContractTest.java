/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards immediate startup while keeping expensive work off the visual thread. */
public final class Ha1216NoArtificialStartupDelayContractTest {
    @Test public void stickyHudRestartHasNoProcessSettleTimer() throws Exception {
        String source = source("hud/HudPresentationService.java");
        assertFalse(source.contains("ISOLATED_STICKY_SETTLE_MS"));
        assertFalse(source.contains("PROCESS_CREATED_ELAPSED"));
        assertFalse(source.contains("localSettle"));
        assertTrue(source.contains("initializeRuntime();"));
    }

    @Test public void sprutCacheLoadsImmediatelyOnItsBackgroundWorker() throws Exception {
        String source = source("sprut/SprutHubController.java");
        String method = between(source, "private void scheduleCachedCatalogLoad(",
                "private void scheduleReconnect(");
        assertTrue(method.contains(
                "scheduler.execute(() -> loadCachedCatalog(expectedSignature))"));
        assertFalse(method.contains("scheduler.schedule("));
        assertFalse(method.contains("1_500L"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        String projectRelative = "app/src/main/java/dezz/status/widget/" + relative;
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(projectRelative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + projectRelative);
    }
}
