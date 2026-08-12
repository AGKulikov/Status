/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Keeps the current Android release identity and package contract covered. */
public final class Ha1208PublicationRecoveryContractTest {
    @Test public void releaseIdentityAdvancesWithoutChangingInstallationIdentity()
            throws Exception {
        String build = rootProject("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1214'"));
        String manifest = project("app/src/main/AndroidManifest.xml");
        assertFalse(manifest.contains("package=\""));
        String app = project("app/build.gradle");
        assertTrue(app.contains("applicationId \"ru.natro.statuswidget\""));
        String workflow = project(".github/workflows/verify-ha1214.yml");
        assertTrue(workflow.contains("name: Verify HA1214 immediate cooperative startup candidate"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1214'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021214'"));
        assertTrue(workflow.contains("ha1214-startup-source-bundle"));
        assertTrue(workflow.contains("ha1214-startup-unsigned-release"));
        assertTrue(workflow.contains("ha1214-android-build-tools"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative).normalize();
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    /** Avoids resolving app/build.gradle when Gradle runs unit tests with app/ as cwd. */
    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative).normalize();
            if (Files.isRegularFile(candidate)
                    && Files.isDirectory(current.resolve("app"))) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }
}
