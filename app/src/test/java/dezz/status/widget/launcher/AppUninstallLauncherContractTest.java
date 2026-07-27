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

/** Keeps long-press removal visibly confirmed inside All Apps with a safe system fallback. */
public final class AppUninstallLauncherContractTest {
    @Test public void confirmsInsideOverlayAndRetainsSystemFallback() throws Exception {
        String source = source();
        String proxy = proxy();
        String manifest = manifest();
        assertTrue(source.contains("new AlertDialog.Builder"));
        assertTrue(source.contains("TYPE_APPLICATION_OVERLAY"));
        assertTrue(source.contains("pm uninstall --user 0 "));
        assertTrue(source.contains("safePackageName(target)"));
        assertTrue(source.contains("PrivilegedShell.get(context).runCommand"));
        assertTrue(source.contains("notifyFinished(context)"));
        assertTrue(source.contains("AppUninstallProxyActivity.class"));
        assertTrue(source.contains("Intent.FLAG_ACTIVITY_NEW_TASK"));
        assertTrue(source.contains("context.startActivity(uninstall)"));
        assertTrue(proxy.contains("Intent.ACTION_UNINSTALL_PACKAGE"));
        assertTrue(proxy.contains("Intent.EXTRA_RETURN_RESULT"));
        assertTrue(proxy.contains("Uri.fromParts(\"package\", packageName.trim(), null)"));
        assertTrue(proxy.contains("startActivityForResult("));
        assertTrue(proxy.contains("AppUninstallLauncher.ACTION_FINISHED"));
        assertTrue(manifest.contains("android.permission.REQUEST_DELETE_PACKAGES"));
        assertTrue(manifest.contains(".launcher.AppUninstallProxyActivity"));
        assertFalse(proxy.contains("pm uninstall"));
    }

    private static String proxy() throws Exception {
        String relative = "dezz/status/widget/launcher/AppUninstallProxyActivity.java";
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
