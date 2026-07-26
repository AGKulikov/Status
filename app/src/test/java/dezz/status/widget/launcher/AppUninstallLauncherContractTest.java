/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps long-press removal on Android's visible, reversible package-installer flow. */
public final class AppUninstallLauncherContractTest {
    @Test public void usesTheStandardAndroidUninstallConfirmation() throws Exception {
        String source = source();
        String manifest = manifest();
        assertTrue(source.contains("Intent.ACTION_DELETE"));
        assertTrue(source.contains("Uri.fromParts(\"package\", target, null)"));
        assertTrue(source.contains("Intent.FLAG_ACTIVITY_NEW_TASK"));
        assertTrue(source.contains("context.startActivity(uninstall)"));
        assertTrue(manifest.contains("android.permission.REQUEST_DELETE_PACKAGES"));
        assertFalse(source.contains("pm uninstall"));
        assertFalse(source.contains("PrivilegedShell"));
    }

    private static String source() throws Exception {
        String relative = "dezz/status/widget/launcher/AppUninstallLauncher.java";
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String manifest() throws Exception {
        Path fromRoot = Paths.get("app", "src", "main", "AndroidManifest.xml");
        Path fromApp = Paths.get("src", "main", "AndroidManifest.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
