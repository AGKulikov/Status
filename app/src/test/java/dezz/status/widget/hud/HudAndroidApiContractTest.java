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
