/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Frozen source-native Natro 2.1.4 / Helper 52 publication identity. */
public final class Ha1231NatroBrandingContractTest {
    @Test public void restoredSourceIsAnInstallCompatiblePublicRelease() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1231);
        String gradle = project("build.gradle").replaceAll("\\s+", " ");
        String current = "if (version == '2.1.4') { return 208021231";
        String previous = "if (version == '2.0.3') { return 208021220";
        assertTrue(gradle.contains("if (version == '2.1.4')"));
        assertTrue(gradle.contains(current));
        assertTrue(gradle.contains(previous));
        assertTrue(gradle.indexOf(current) < gradle.indexOf(previous));
    }

    @Test public void manifestNamesRestorationFeaturesAndHonestPhysicalGate() throws Exception {
        String notes = project("release-manifests/HA1231.md").replaceAll("\\s+", " ");
        assertTrue(notes.contains("standard BLE Battery Service"));
        assertTrue(notes.contains("two independently configurable low-battery thresholds"));
        assertTrue(notes.contains("external overlay windows"));
        assertTrue(notes.contains("HWGPS"));
        assertTrue(notes.contains("Helper 52"));
        assertTrue(notes.contains("does **not** claim byte-for-byte reconstruction"));
        assertTrue(notes.contains("does **not** claim successful physical KX11/iPhone acceptance"));
        assertTrue(notes.contains("1626f9e3187133af9715c849cfb17103b8864904"));
    }

    @Test public void signedPublishNeverOverwritesAnExistingReleasePayload() throws Exception {
        String workflow = project(".github/workflows/release-ha1231.yml");
        assertTrue(workflow.contains("The GitHub UI creates the tag and its empty prerelease"));
        assertTrue(workflow.contains("'.assets | length' <<<\"$EXISTING\""));
        assertTrue(workflow.contains("already has assets; refusing to overwrite them"));
        assertTrue(workflow.contains("gh release upload \"$RELEASE_TAG\" \"$OUT\"/*"));
        assertTrue(workflow.contains("EXPECTED_COUNT=$(find \"$OUT\" -maxdepth 1 -type f"));
        assertTrue(workflow.contains(".name == $name and .size == $size"));
    }

    @Test public void oneShotAuthorizationCreatesOnlyTheExactNatroTag() throws Exception {
        String trigger = project(".github/workflows/create-ha1231-release-tag.yml");
        String intent = project("release-manifests/HA1231.publish.json");
        String release = project(".github/workflows/release-ha1231.yml");
        assertTrue(trigger.contains("refs/heads/agent/natro-source-restoration-2.1.4"));
        assertTrue(trigger.contains("Existing $TAG points to another commit; refusing to move it"));
        assertTrue(trigger.contains("refs/tags/${TAG}"));
        assertTrue(trigger.contains("uses: ./.github/workflows/release-ha1231.yml"));
        assertTrue(trigger.contains("secrets: inherit"));
        assertTrue(intent.contains("\"appName\": \"Natro\""));
        assertTrue(intent.contains("\"tag\": \"natro-v2.1.4\""));
        assertTrue(intent.contains("\"publication\": \"signed-prerelease\""));
        assertTrue(release.contains("workflow_call:"));
        assertTrue(release.contains("ref: ${{ inputs.release_ref || github.ref }}"));
    }

    private static String project(String relative) throws Exception {
        return new String(Files.readAllBytes(projectPath(relative)), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String relative) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
