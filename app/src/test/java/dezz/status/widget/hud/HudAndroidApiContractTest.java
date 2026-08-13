/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps HUD code compilable against the public Android 36 SDK used by release CI. */
public final class HudAndroidApiContractTest {
    @Test public void hiddenDisplayFingerprintIsOptionalAndReflective() throws Exception {
        String source = read("HudDisplaySelector.java");
        assertFalse(source.contains("display.getUniqueId()"));
        assertTrue(source.contains("Display.class.getMethod(\"getUniqueId\")"));
        assertTrue(source.contains("Numeric displayId remains authoritative"));
        assertTrue(source.contains("HudViewportPolicy.VERIFIED_DISPLAY_ID"));
    }

    @Test public void updateStatusDoesNotDependOnDisabledBuildConfigGeneration()
            throws Exception {
        String source = read("HudRuntimeData.java");
        assertFalse(source.contains("import dezz.status.widget.BuildConfig"));
        assertFalse(source.contains("BuildConfig.VERSION_NAME"));
        assertTrue(source.contains("getPackageInfo(context.getPackageName(), 0).versionName"));
        assertTrue(source.contains("if (cachedAppVersion != null) return cachedAppVersion"));
    }

    @Test public void systemHudMaskLivesAboveEcarxDaemonAndHasSafeFallback()
            throws Exception {
        String bridge = read("HudSurfaceBridgeMain.java");
        String client = read("HudSystemSurfaceWindow.java");
        String service = read("HudPresentationService.java");

        assertTrue(bridge.contains("SURFACE_LAYER = Integer.MAX_VALUE - 1"));
        assertTrue(bridge.contains("setLayerStack"));
        assertTrue(bridge.contains("width <= 0 || height <= 0"));
        assertTrue(bridge.contains("FRAME_TIMEOUT_MS = 5_000"));
        assertFalse(bridge.contains("import android.view.SurfaceControl"));
        assertTrue(client.contains("display.getDisplayId() == HudViewportPolicy.VERIFIED_DISPLAY_ID"));
        assertTrue(client.contains("? HudViewportPolicy.VERIFIED_LAYER_STACK"));
        assertFalse(client.contains("? 1 : Math.max(0, display.getDisplayId())"));
        assertTrue(client.contains("pendingFrame = target"));
        assertTrue(client.contains("frame.bitmap.compress(Bitmap.CompressFormat.PNG"));
        assertTrue(client.contains("runLongRunningCommand"));
        assertFalse(client.contains(">/dev/null 2>&1 &)"));
        assertTrue(service.contains("showWindowManagerFallback(display)"));
        assertFalse(service.contains("dismissFallbackOnly(\"system HUD surface ready\")"));
        assertTrue(service.contains("кадр принят SurfaceFlinger"));
        assertTrue(service.contains("systemSurfaceRetryAfter"));
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
