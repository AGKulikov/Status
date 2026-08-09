/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1185 barriers for isolating unsupported ECARX discovery properties. */
public final class Ha1185ResilientSignalRegistrationContractTest {
    @Test public void optionalIdsCannotTakeDownConfirmedLimiterSignals() throws Exception {
        String fallback = geely("car/EcarxSignalFallback.java");
        String registration = between(fallback,
                "private boolean registerCallback",
                "private static Object buildSignalFilter");

        assertTrue(registration.contains("requiredIds.add(propertyId)"));
        assertTrue(registration.contains("probeRecorderProperty"));
        assertTrue(registration.contains("Combined recorder filter rejected"));
        assertTrue(registration.contains("ids.addAll(requiredIds)"));
        assertTrue(registration.contains("activeRecorderIds.addAll(recorderIds)"));
        assertTrue(registration.contains("onAdasCaptureReady(recorderIds.size()"));
    }

    @Test public void invalidPropertyIdsAreProbedAndCachedIndividually() throws Exception {
        String fallback = geely("car/EcarxSignalFallback.java");
        String probe = between(fallback,
                "private boolean probeRecorderProperty",
                "private static void invokeRegistration");

        assertTrue(probe.contains("Collections.singleton(propertyId)"));
        assertTrue(probe.contains("isInvalidPropertyIdError(error)"));
        assertTrue(probe.contains("unsupportedRecorderIds.add(propertyId)"));
        assertTrue(probe.contains("unregisterSpecificCallback(manager, passiveCallback)"));
        assertFalse(fallback.contains("setProperty("));
    }

    @Test public void releaseIdentityAdvancesToHa1185() throws Exception {
        assertTrue(rootProject("build.gradle").contains("return 'v2.8.2-ha1196'"));
        assertTrue(project("release-manifests/HA1185.md").contains("208021185"));
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

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
