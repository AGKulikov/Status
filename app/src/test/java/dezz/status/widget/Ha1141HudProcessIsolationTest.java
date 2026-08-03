/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression boundary for the hardware crash triggered when Navigator opens with HUD enabled. */
public final class Ha1141HudProcessIsolationTest {
    @Test public void hudRendererCannotTerminateTheStatusRowProcess() throws Exception {
        String manifest = resource("AndroidManifest.xml");
        String application = source("StatusWidgetApplication.java");

        assertTrue(manifest.contains("android:name=\".hud.HudPresentationService\""));
        assertTrue(manifest.contains("android:process=\":hud\""));
        assertTrue(application.contains("AppProcessPolicy.isHudProcess()"));
        assertTrue(application.contains("if (hudProcess)"));
        assertTrue(application.contains("HUD_CRASH_FILE"));
        assertTrue(application.contains("return;"));
    }

    @Test public void androidNineNeverMaterializesNavigatorAccessibilityWindows()
            throws Exception {
        String accessibility = source("WidgetAccessibilityService.java");

        assertTrue(accessibility.contains("supportsSafeWindowTraversal()"));
        assertTrue(accessibility.contains(
                "return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q"));
        assertTrue(accessibility.contains("publishAndroidNineForegroundEvent"));
        assertTrue(accessibility.contains("navigationDemand.isNeeded() && windowTraversalAllowed"));
        assertTrue(accessibility.contains("supportsSafeWindowTraversal()\n"
                + "                && serviceConnected"));
    }

    @Test public void hudCommandsAndDiagnosticsStillCrossTheProcessBoundary()
            throws Exception {
        String service = source("hud/HudPresentationService.java");
        String settings = source("HudPanelSettingsActivity.java");
        String preferences = source("Preferences.java");

        assertTrue(service.contains("ContextCompat.startForegroundService(app, command)"));
        assertTrue(service.contains("EXTRA_CONFIG_JSON"));
        assertTrue(service.contains("MAX_COMMAND_CONFIG_CHARS"));
        assertTrue(service.contains("HudRuntimeStatusStore.read(context)"));
        assertTrue(settings.contains("HudPresentationService.runtimeDetail(this)"));
        assertTrue(preferences.contains("AppProcessPolicy.preferenceMode()"));
        assertFalse(service.contains("if (current != null) {\n"
                + "                try {\n"
                + "                    app.startService(new Intent(app, HudPresentationService.class)\n"
                + "                            .setAction(ACTION_STOP))"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("java", "dezz", "status", "widget").resolve(relative));
    }

    private static String resource(String relative) throws Exception {
        Path fromRoot = Paths.get("app", "src", "main").resolve(relative);
        Path fromApp = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String read(Path relative) throws Exception {
        Path fromRoot = Paths.get("app", "src", "main").resolve(relative);
        Path fromApp = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
