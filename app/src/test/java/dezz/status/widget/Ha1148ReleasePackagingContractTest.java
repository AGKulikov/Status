/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents publishing another signed APK that has code but no Android resources. */
public final class Ha1148ReleasePackagingContractTest {
    @Test public void signedReleaseAlwaysStartsCleanAndValidatesItsResourceTable()
            throws Exception {
        String workflow = projectFile(".github/workflows/release.yml");

        assertTrue(workflow.contains("--no-configuration-cache clean"));
        assertTrue(workflow.contains("--max-workers=1 testGeelyDebugUnitTest"));
        assertTrue(workflow.contains("--max-workers=1 assembleGeelyRelease"));
        assertTrue(workflow.contains("grep -Fx 'resources.arsc'"));
        assertTrue(workflow.contains("grep -c '^res/'"));
        assertTrue(workflow.contains("aapt\" dump resources"));
        assertTrue(workflow.contains("aapt\" dump badging"));
        assertTrue(workflow.contains("apksigner\" verify --verbose --print-certs"));
        assertTrue(workflow.contains(
                "6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75"));
    }

    @Test public void unsignedHandoffUsesTheSameCompletePackageGate() throws Exception {
        String workflow = projectFile(".github/workflows/unsigned-release.yml");

        assertTrue(workflow.contains("--no-configuration-cache clean"));
        assertTrue(workflow.contains("--max-workers=1 assembleGeelyRelease"));
        assertTrue(workflow.contains("grep -Fx 'resources.arsc'"));
        assertTrue(workflow.contains("aapt\" dump resources"));
    }

    @Test public void releaseBranchPullRequestProducesTheExactSignedRelease() throws Exception {
        String workflow = projectFile(".github/workflows/geely-debug.yml");

        assertTrue(workflow.contains("'release/**'"));
        assertTrue(workflow.contains("^release/(v2\\.8\\.2-ha([0-9]+))$"));
        assertTrue(workflow.contains("BUILD_TASK=\"assembleGeelyRelease\""));
        assertTrue(workflow.contains("test -f .stable-update-signing"));
        assertTrue(workflow.contains("grep -Fx 'resources.arsc'"));
        assertTrue(workflow.contains("aapt\" dump resources"));
        assertTrue(workflow.contains("versionCode='${VERSION_CODE}' versionName='${VERSION_NAME}'"));
    }

    private static String projectFile(String relative) throws Exception {
        Path root = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(root) ? root : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
