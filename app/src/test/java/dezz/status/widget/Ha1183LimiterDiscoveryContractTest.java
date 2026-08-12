/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1183 barriers for passive limiter discovery and complete Android 9 usage grants. */
public final class Ha1183LimiterDiscoveryContractTest {
    @Test public void recorderAugmentsTheOriginalIdsOnlyWhileRecording() throws Exception {
        String fallback = geely("car/EcarxSignalFallback.java");
        String integration = geely("car/GeelyCarIntegration.java");
        String catalog = source("car/EcarxAdasSignalCatalog.java");

        assertTrue(fallback.contains("recorderDiscoveryIds"));
        assertTrue(fallback.contains("isDiscoveryPropertyName(name)"));
        assertTrue(fallback.contains("probeRecorderProperty"));
        assertTrue(fallback.contains("unsupportedRecorderIds"));
        assertTrue(fallback.contains("listener.onAdasSignal"));
        assertTrue(integration.contains("fixed_name_and_runtime_typed_callback_discovery"));
        assertTrue(integration.contains("fallback_discovery_property_ids"));
        assertTrue(catalog.contains("name.contains(\"spdlim\")"));
        assertTrue(catalog.contains("name.contains(\"speedwarn\")"));
        assertTrue(catalog.contains("vehicle_control_discovery"));
        assertFalse(fallback.contains("setProperty("));
    }

    @Test public void diagnosticsGrantPermissionAndAppOpAsSeparateRequirements()
            throws Exception {
        String activity = source("DiagnosticsActivity.java");
        String shell = source("shell/PrivilegedShell.java");
        String collector = source("diagnostics/PrivilegedActionCollector.java");
        String mac = project("tools/grant-ha1181-diagnostics.command");
        String windows = project("tools/grant-ha1181-diagnostics.bat");

        for (String text : new String[] { activity, shell, mac, windows }) {
            assertTrue(text.contains("android.permission.PACKAGE_USAGE_STATS"));
            assertTrue(text.contains("GET_USAGE_STATS allow"));
        }
        assertTrue(collector.contains("signalid|propertyid|hardkeyservice"));
    }

    @Test public void releaseIdentityAdvancesToHa1183() throws Exception {
        assertTrue(rootProject("build.gradle").contains("return 'v2.8.2-ha1214'"));
        assertTrue(project("release-manifests/HA1183.md").contains("208021183"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String geely(String relative) throws Exception {
        return project("app/src/geely/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }
}
