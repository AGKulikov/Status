/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Keeps the current Android workflow and release-manifest identity covered. */
public final class Ha1211PhysicalFacadeAttachContractTest {
    @Test public void releaseIdentityWorkflowAndManifestAdvanceTogether() throws Exception {
        String build = rootProject("build.gradle");
        String workflow = project(".github/workflows/verify-ha1214.yml");
        String manifest = project("release-manifests/HA1214.md");
        assertTrue(build.contains("return 'v2.8.2-ha1214'"));
        assertTrue(workflow.contains("name: Verify HA1214 immediate cooperative startup candidate"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1214'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021214'"));
        assertTrue(manifest.contains("v2.8.2-ha1214"));
        assertTrue(manifest.contains("208021214"));
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
