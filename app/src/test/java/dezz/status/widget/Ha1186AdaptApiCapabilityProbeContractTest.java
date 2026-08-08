/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1186 barriers for the passive limiter/cruise AdaptAPI capability probe. */
public final class Ha1186AdaptApiCapabilityProbeContractTest {
    @Test public void recorderProbesAllFourPublicLimiterAndCruiseFunctions() throws Exception {
        String integration = geely("car/GeelyCarIntegration.java");

        assertTrue(integration.contains("IVehicle.SETTING_FUNC_SPEED_LIMITATION"));
        assertTrue(integration.contains("IVehicle.SETTING_FUNC_SPEED_LIMITATION_MODE"));
        assertTrue(integration.contains("IVehicle.SETTING_FUNC_SPEED_CONTROL"));
        assertTrue(integration.contains("IVehicle.SETTING_FUNC_SPEED_CONTROL_MODE"));
        assertTrue(integration.contains("ECARX_ADAPTAPI_CAPABILITY_PROBE_REQUESTED"));
        assertTrue(integration.contains("ECARX_ADAPTAPI_CAPABILITY"));
        assertTrue(integration.contains("ECARX_ADAPTAPI_CAPABILITY_PROBE_COMPLETE"));
    }

    @Test public void capabilityProbeIsSessionBoundAndStrictlyReadOnly() throws Exception {
        String integration = geely("car/GeelyCarIntegration.java");
        String probe = between(integration,
                "private void probeAdaptApiCapabilities",
                "private static String decodeAdaptApiCapabilityValues");

        assertTrue(integration.contains("adaptApiCapabilityProbeGeneration"));
        assertTrue(probe.contains("source.isFunctionSupported(functionId)"));
        assertTrue(probe.contains("source.getSupportedFunctionValue(functionId)"));
        assertTrue(probe.contains("source.getFunctionValue(functionId)"));
        assertTrue(probe.contains("\"write_enabled\", false"));
        assertFalse(probe.contains("setFunctionValue("));
        assertFalse(probe.contains("setCustomizeFunctionValue("));
        assertFalse(probe.contains("setProperty("));
    }

    @Test public void releaseIdentityAdvancesToHa1186() throws Exception {
        assertTrue(rootProject("build.gradle").contains("return 'v2.8.2-ha1187'"));
    }

    @Test public void discoverySignalsRemainCallbackOnlyDuringHealthPolling() throws Exception {
        String fallback = geely("car/EcarxSignalFallback.java");
        String healthRead = between(fallback,
                "private ReadResult readCurrentValues",
                "private void scheduleHealthRead");

        assertTrue(healthRead.contains("EcarxAdasSignalCatalog.propertyIds()"));
        assertFalse(healthRead.contains("ids.addAll(activeRecorderIds)"));
    }

    @Test public void incomingLeBondPathIsAndroid9VerifierSafe() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String createBond = between(transport,
                "private boolean createLeBond",
                "private void handleSecureAttSuccess");

        assertTrue(createBond.contains("device.createBond()"));
        assertFalse(createBond.contains("getMethod("));
        assertFalse(createBond.contains("method.invoke("));
        assertFalse(transport.contains("import java.lang.reflect.Method;"));
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
