/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Freezes the historical public 2.0.3 identity and its field-correction release contract. */
public final class Ha1220NatroBrandingContractTest {
    @Test public void publicIdentityIsMonotonicAndInstallCompatible() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1220);
        String gradle = project("build.gradle").replaceAll("\\s+", " ");
        String historical = "if (version == '2.0.3') { return 208021220";
        assertTrue(gradle.contains("return '2.1.4'"));
        assertTrue(gradle.contains(historical));
        assertTrue(project(".github/workflows/verify-ha1220.yml").contains(
                "# Frozen Natro 2.0.3 identity gate."));
        assertTrue(project(".github/workflows/verify-ha1220.yml").contains(
                "on:\n  workflow_dispatch:"));
        assertTrue(project("app/build.gradle").contains(
                "applicationId \"ru.natro.statuswidget\""));
    }

    @Test public void previousReleaseIsFrozenAndHa1220NamesTheFieldFixes() throws Exception {
        String frozen = project(".github/workflows/verify-ha1219.yml");
        assertTrue(frozen.contains("# Frozen Natro 2.0.2 identity gate."));
        assertTrue(frozen.contains("on:\n  workflow_dispatch:"));

        String notes = project("release-manifests/HA1220.md");
        assertTrue(notes.contains("autoConnect=false"));
        assertTrue(notes.contains("same wrapper"));
        assertTrue(notes.contains("ECARX window inventory"));
        assertTrue(notes.contains("`@surface/navigator_window`"));
        assertTrue(notes.contains("`filtered` and `spoofing` are adverse states"));
        assertTrue(notes.contains("does **not** claim successful physical"));
        assertTrue(notes.contains("5be62c4aea62439d36a380298a22dac0474f02e3"));
    }

    private static String project(String relative) throws Exception {
        return new String(Files.readAllBytes(projectPath(relative)), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String relative) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
