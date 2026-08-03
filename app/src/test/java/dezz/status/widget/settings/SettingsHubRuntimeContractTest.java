/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the runtime behavior that must survive moving the launcher entry to Settings Hub. */
public final class SettingsHubRuntimeContractTest {
    @Test
    public void launcherHubAndLegacyEditorShareTheSameBootstrap() throws IOException {
        String manifest = resource("AndroidManifest.xml");
        String hub = javaSource("SettingsHubActivity.java");
        String main = javaSource("MainActivity.java");
        String bootstrap = javaSource("AppRuntimeBootstrap.java");

        assertTrue(manifest.contains("android:name=\".SettingsHubActivity\""));
        assertTrue(manifest.contains("<category android:name=\"android.intent.category.LAUNCHER\""));
        assertTrue(manifest.contains("android:name=\".MainActivity\"\n"
                + "            android:exported=\"false\""));
        assertTrue(hub.contains("AppRuntimeBootstrap.run(this, preferences)"));
        assertTrue(main.contains("AppRuntimeBootstrap.run(this, prefs)"));
        assertTrue(bootstrap.contains("startForegroundService("));
        assertTrue(bootstrap.contains("ClimatePanelService.apply("));
        assertTrue(bootstrap.contains("maybeShowCrashReport(activity)"));
        assertTrue(bootstrap.contains("tryAutoGrant(activity.getApplicationContext()"));
        assertTrue(bootstrap.contains("static void reconcileServices("));
        assertTrue(hub.contains("AppRuntimeBootstrap.reconcileServices(this, preferences)"));
    }

    @Test
    public void safeAreaOverlapsBarsInsteadOfAddingThem() throws IOException {
        String hub = javaSource("SettingsHubActivity.java");
        String back = javaSource(Paths.get("settings", "SettingsBackNavigation.java"));

        assertTrue(hub.contains("LauncherSafeAreaPolicy.topInset("));
        assertFalse(hub.contains("bars.top + widget"));
        assertTrue(back.contains("Math.max(0, statusOverlayHeight() - systemTop)"));
        assertTrue(back.contains("postDelayed(updater[0], 750L)"));
    }

    @Test
    public void connectionAndPermissionSummariesAreLive() throws IOException {
        String hub = javaSource("SettingsHubActivity.java");

        assertTrue(hub.contains("controller != null && controller.isOnline()"));
        assertTrue(hub.contains("SprutHubController.isSynced()"));
        assertTrue(hub.contains("MqttController.isConnected()"));
        assertFalse(hub.contains("connectionDetail()"));
        assertTrue(hub.contains("ScrollView scroll = new ScrollView(this)"));
        assertTrue(hub.contains("permissionsDialog != null && permissionsDialog.isShowing()"));
        assertTrue(hub.contains("protected void onDestroy()"));
        assertTrue(hub.contains("dialog != null && dialog.isShowing()"));
    }

    private static String javaSource(String name) throws IOException {
        return javaSource(Paths.get(name));
    }

    private static String javaSource(Path relative) throws IOException {
        return source(Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative),
                Paths.get("src", "main", "java", "dezz", "status", "widget")
                        .resolve(relative));
    }

    private static String resource(String name) throws IOException {
        return source(Paths.get("app", "src", "main", name),
                Paths.get("src", "main", name));
    }

    private static String source(Path fromRoot, Path fromApp) throws IOException {
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
