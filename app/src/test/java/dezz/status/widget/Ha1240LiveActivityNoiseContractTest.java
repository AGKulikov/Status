/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release boundary for Natro 2.2.6 and Helper 58 ActivityKit diagnostics repairs. */
public final class Ha1240LiveActivityNoiseContractTest {
    @Test public void releaseAndHelperVersionsAdvanceTogether() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1240);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        assertTrue(build.contains("return '2.2.6'"));
        assertTrue(build.contains("if (version == '2.2.6') { return 208021240"));

        String manifest = project("release-manifests/HA1240.md");
        assertTrue(manifest.contains("Android version: `2.2.6`"));
        assertTrue(manifest.contains("Helper: build `58`, marketing `58.0`"));
    }

    @Test public void helperSuppressesInvalidSuiteAndDuplicateUpdates() throws Exception {
        String shared = helper("NatroLiveActivityShared.swift");
        String manager = helper("KX11ANCSHelper/NatroLiveActivityManager.swift");
        String widget = helper("NatroLiveActivityExtension/NatroLiveActivityWidget.swift");

        assertTrue(shared.contains("FileManager.default.fileExists(atPath: container.path)"));
        assertTrue(shared.contains("FileManager.default.isWritableFile(atPath: container.path)"));
        assertTrue(manager.contains("stableState.updatedAtEpoch = 0"));
        assertTrue(manager.contains("lastActivityFingerprints[activity.id] != fingerprint"));
        assertTrue(widget.contains("Color(\n                .sRGB"));
    }

    private static String helper(String relative) throws Exception {
        return read(projectRoot().resolve("ios/KX11-iPhone-ANCS-Helper-v58").resolve(relative));
    }

    private static String project(String relative) throws Exception {
        return read(projectRoot().resolve(relative));
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("settings.gradle"))) return current;
        if (current.getParent() != null
                && Files.isRegularFile(current.getParent().resolve("settings.gradle"))) {
            return current.getParent();
        }
        throw new IllegalStateException("Project root not found from " + current);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
