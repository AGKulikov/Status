/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the weak-head-unit HUD path against fixed polling and main-thread PNG regressions. */
public final class HudSteadyStateContractTest {
    @Test public void animationDeadlineAlignsWithTheCanvasBlinkPhase() {
        assertEquals(500L, HudSystemSurfaceWindow.delayToBoundary(1_000L, 500L));
        assertEquals(1L, HudSystemSurfaceWindow.delayToBoundary(1_499L, 500L));
        assertEquals(1L, HudSystemSurfaceWindow.delayToBoundary(7L, 1L));
    }

    @Test public void directSurfaceRendersOnlyDirtyOrActivelyAnimatedFrames() throws Exception {
        String source = read("HudSystemSurfaceWindow.java");

        assertFalse(source.contains("private static final long FRAME_INTERVAL_MS ="));
        assertFalse(source.contains("main.postDelayed(this, FRAME_INTERVAL_MS)"));
        assertTrue(source.contains("scheduleNextAnimationFrame()"));
        assertTrue(source.contains("No timer exists for a static HUD"));
        assertTrue(source.contains("delayToBoundary(now, frequency)"));
    }

    @Test public void pngPipelineIsBoundedLatestOnlyAndOffMain() throws Exception {
        String source = read("HudSystemSurfaceWindow.java");
        String mainRender = between(source, "private void renderAndQueue()",
                "private FrameBuffer availableFrameLocked()");
        String workerDrain = between(source, "private void drainFrames()",
                "private void scheduleNextAnimationFrame()");

        assertFalse(mainRender.contains(".compress("));
        assertTrue(workerDrain.contains("frame.bitmap.compress("));
        assertTrue(source.contains("Executors.newSingleThreadExecutor"));
        assertTrue(source.contains("new FrameBuffer(), new FrameBuffer()"));
        assertTrue(source.contains("target = pendingFrame != null ? pendingFrame"));
        assertFalse(source.contains("encoded.toByteArray()"));
        assertTrue(source.contains("Process.THREAD_PRIORITY_BACKGROUND"));
    }

    @Test public void runtimeUsesEventsAndCachesStableCrossProcessState() throws Exception {
        String source = read("HudRuntimeData.java");

        assertFalse(source.contains("private final Runnable ticker"));
        assertFalse(source.contains("main.postDelayed(this, 1_000L)"));
        assertTrue(source.contains("if (!started || isolatedHudProcess) return"));
        assertTrue(source.contains("scheduleClockTick()"));
        assertTrue(source.contains("retainedAutomationCache.get(item.automationId)"));
        assertTrue(source.contains("retainedAutomationCache.clear()"));
        assertTrue(source.contains("if (cachedAppVersion != null) return cachedAppVersion"));
        assertTrue(source.contains("telemetryListener = value -> runOnMain"));
        assertTrue(source.contains("Process.THREAD_PRIORITY_BACKGROUND"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("source markers not found");
        return source.substring(from, to);
    }

    private static String read(String name) throws Exception {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "hud", name);
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status",
                "widget", "hud", name);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
