/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Release boundary for Natro 2.2.7 autonomous exact-owner ANCS recovery. */
public final class Ha1241AncsRecoveryContractTest {
    @Test public void releaseIdentityAndRecoveryInvariantsArePinned() throws Exception {
        String build = read("build.gradle");
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String adapter = read("app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                + "AndroidCentralTransportV2.java");
        String policy = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "ClassicAncsRecoveryPolicy.java");
        String manifest = read("release-manifests/HA1241.md");

        assertTrue(build.contains("if (version == '2.2.7')"));
        assertTrue(build.contains("return 208021241"));
        assertTrue(route.contains("systemConnectionAdvertisement"));
        assertTrue(route.contains("Classic is not required"));
        assertTrue(route.contains("base.consecutiveFailures >= 1"));
        assertTrue(adapter.contains("scan_start mode=unfiltered_enrolled_identity"));
        assertTrue(adapter.contains("filters.add(new ScanFilter.Builder()"));
        assertTrue(adapter.contains("jitterRetryDelay"));
        assertTrue(adapter.contains("gatt_cache_refresh guarded=true"));
        assertTrue(policy.contains("DelayJitter"));
        assertTrue(manifest.contains("Android version: `2.2.7`"));
        assertTrue(manifest.contains("Compatible Helper: build `59`"));
    }

    private static String read(String relative) throws Exception {
        return new String(
                Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}
