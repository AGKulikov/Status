/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Release contract for the direct Navigator -> Status Widget HUD pipeline. */
public final class NavigatorMapIntegrationTest {
    @Test public void defaultStyleLeavesStockNavigatorMapUntouched() {
        assertEquals("[]", NavigatorMapStyle.build(new JSONObject()));
    }

    @Test public void roadOnlyStyleHidesDistractionsInsideMapKit() throws Exception {
        JSONObject options = new JSONObject().put("roadOnly", true);
        String style = NavigatorMapStyle.build(options);

        assertTrue(style.contains("\"label\""));
        assertTrue(style.contains("\"poi\""));
        assertTrue(style.contains("\"building\""));
        assertTrue(style.contains("\"vegetation\""));
        assertTrue(style.contains("\"water\""));
    }

    @Test public void providerUsesExplicitIndependentSurfaceWithoutScreenCapture()
            throws Exception {
        String source = readHud("NavigatorMapFrameProvider.java");

        assertTrue(source.contains("new ComponentName(NAVIGATOR_PACKAGE, NAVIGATOR_SERVICE)"));
        assertTrue(source.contains(".putExtra(\"surface\", current.getSurface())"));
        assertTrue(source.contains("ImageReader.newInstance(HudViewportPolicy.SAFE_WIDTH"));
        assertTrue(source.contains("ContextCompat.startForegroundService(context, intent)"));
        assertFalse(source.contains("import android.media.projection"));
        assertFalse(source.contains("import android.hardware.display.VirtualDisplay"));
    }

    @Test public void rendererClipsMapAndCursorToElementFrame() throws Exception {
        String source = readHud("HudCanvasView.java");

        assertTrue(source.contains("drawNavigatorMap(canvas, item, bounds, scale)"));
        assertTrue(source.contains("canvas.clipRect(bounds)"));
        assertTrue(source.contains("cursorXPercent"));
        assertTrue(source.contains("cursorYPercent"));
    }

    @Test public void editorAccountsForDriverAndStatusPanels() throws Exception {
        String source = readActivity("HudPanelSettingsActivity.java");

        assertTrue(source.contains("SettingsBackNavigation.applySafeTopInset(this, root)"));
        assertTrue(source.contains("driverPanelInset()"));
        assertTrue(source.contains("host.getStatusBarOverlayHeight()"));
        assertTrue(source.contains("window.setGravity(Gravity.BOTTOM"));
    }

    private static String readHud(String name) throws Exception {
        return read(Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "hud", name), Paths.get("src", "main", "java", "dezz",
                "status", "widget", "hud", name));
    }

    private static String readActivity(String name) throws Exception {
        return read(Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", name), Paths.get("src", "main", "java", "dezz",
                "status", "widget", name));
    }

    private static String read(Path fromRoot, Path fromApp) throws Exception {
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
