/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Keeps the current Android workflow and release-manifest identity covered. */
public final class Ha1210OpportunisticReverseAttachContractTest {
    @Test public void releaseIdentityAndAndroidWorkflowAdvanceTogether() throws Exception {
        String build = rootProject("build.gradle");
        String workflow = project(".github/workflows/verify-ha1212.yml");
        String manifest = project("release-manifests/HA1212.md");
        assertTrue(build.contains("return 'v2.8.2-ha1212'"));
        assertTrue(workflow.contains("name: Verify HA1212 ANCS transport v2 candidate"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1212'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021212'"));
        assertTrue(manifest.contains("v2.8.2-ha1212"));
        assertTrue(manifest.contains("208021212"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    /** Avoids resolving app/build.gradle when Gradle runs unit tests with app/ as cwd. */
    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate) && Files.isDirectory(current.resolve("app"))) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }
}
