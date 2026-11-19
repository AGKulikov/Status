/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.shell.PrivilegedShell;

/** Guards silent, idempotent accessibility maintenance on the KX11 boot path. */
public final class AccessibilityAutoGrantContractTest {
    @Test
    public void normalStartupAndMediaDoNotGrantSystemPermissions() throws Exception {
        Path root = Paths.get("src/main/java/dezz/status/widget");
        if (!Files.isDirectory(root)) root = Paths.get("app").resolve(root);
        for (String name : Arrays.asList("AppRuntimeBootstrap.java", "launcher/LauncherMediaController.java",
                "ClimatePanelSettingsActivity.java", "media/VehicleButtonController.java")) {
            String code = new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
            org.junit.Assert.assertFalse(name, code.contains(".ensurePrivileges("));
            org.junit.Assert.assertFalse(name, code.contains("pm grant "));
        }
        String hub = new String(Files.readAllBytes(root.resolve("SettingsHubActivity.java")), StandardCharsets.UTF_8);
        assertTrue(hub.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"));
        assertTrue(hub.contains("Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
    }

    @Test
    public void secureSettingsCheckAcceptsAndroidShortComponentNotation() throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        String shell = new String(Files.readAllBytes(
                root.resolve("shell/PrivilegedShell.java")), StandardCharsets.UTF_8);

        assertTrue(shell.contains("ComponentName.unflattenFromString(component)"));
        assertTrue(shell.contains("target.equals(parsed)"));
        assertTrue(shell.contains("settings get secure accessibility_enabled"));
        assertTrue(shell.contains("Accessibility service already enabled in secure settings"));
    }
}
