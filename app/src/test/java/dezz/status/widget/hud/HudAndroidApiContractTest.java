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
        assertTrue(service.contains("Exactly one compositor owns the 728x190 HUD plane"));
        assertTrue(service.contains("direct SurfaceFlinger is now strictly a fallback"));
        assertTrue(service.contains(
                "if (systemSurfaceWindow != null || overlayWindow != null "
                        + "|| presentation != null"));
        assertTrue(service.contains("единственный compositor owner"));
        assertFalse(service.contains("dismissFallbackOnly(\"system HUD surface ready\")"));
        assertTrue(service.contains("кадр принят SurfaceFlinger"));
        assertTrue(service.contains("systemSurfaceRetryAfter"));
        assertTrue(service.contains("scheduleSystemSurfaceRetry()"));
        assertTrue(service.contains("setCustomFrameReady(true)"));
        assertTrue(service.contains("setCustomFrameReady(false)"));
        assertTrue(service.contains("HudStockMaskPolicy.shouldHideStockCar"));
        assertTrue(service.contains("SYSTEM_SURFACE_RETRY_MS = 15_000L"));
        assertTrue(service.contains("apply(app);"));
        assertFalse(service.contains("sendCommand(app, ACTION_DATA_CHANGED, null);"));
        assertTrue(service.contains("DiagnosticJournal.info(\"hud-runtime\""));
        assertTrue(service.contains("DiagnosticJournal.error(\"hud-runtime\""));
        assertTrue(service.contains("HUD service создан в основном процессе Natro"));
        assertTrue(service.contains("elements=\" + config.elements.size()"));
    }

    @Test public void textureViewNeverReceivesUnsupportedBackgroundDrawable()
            throws Exception {
        String composite = read("HudCompositeView.java");
        assertTrue(composite.contains("mapTexture.setOpaque(true)"));
        assertTrue(composite.contains("mapTexture.setOpaque(!transparentMap)"));
        assertTrue(composite.contains("transparentBackground"));
        assertFalse(composite.contains("mapTexture.setBackgroundColor"));
        assertFalse(composite.contains("mapTexture.setBackground("));
    }

    @Test public void hudOwnersStayInMainProcessForKx11LifecycleReliability()
            throws Exception {
        Path fromRoot = Paths.get("app", "src", "main", "AndroidManifest.xml");
        Path fromApp = Paths.get("src", "main", "AndroidManifest.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        String manifest = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(manifest.contains(".hud.HudPresentationService"));
        assertTrue(manifest.contains(".navigation.NavigationHudEndpointService"));
        assertTrue(manifest.contains(".navigation.NavigationConfigurationRelayService"));
        assertFalse(manifest.contains("android:process=\":hud\""));
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
