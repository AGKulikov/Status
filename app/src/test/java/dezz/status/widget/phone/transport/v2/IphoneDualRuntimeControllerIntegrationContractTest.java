/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Production wiring gate: the rewritten runtime, not the HA1124-HA1212 monolith, owns ANCS. */
public final class IphoneDualRuntimeControllerIntegrationContractTest {
    @Test public void controllerConstructsOneV2RuntimeAndSwitchesRoleInPlace() throws Exception {
        String source = controller();
        assertTrue(source.contains("AndroidIphoneDualRuntimeV2.create(context, prefs)"));
        assertTrue(source.contains("ancsRuntimeV2 = created;"));
        assertTrue(source.indexOf("created.start(new IphoneDualTransportRuntimeV2.Config(")
                < source.indexOf("ancsRuntimeV2 = created;"));
        assertTrue(source.contains("runtime.requestMode(v2Mode(next.bleRole))"));
        String roleOnly = between(source,
                "if (running && config != null\n                    && config.bleRole",
                "stopLocked(next.enabled");
        assertTrue(roleOnly.contains("signatureWithoutBleRole"));
        assertTrue(roleOnly.contains("runtime.requestMode"));
        assertFalse(roleOnly.contains("closeAncsTransport"));
        assertFalse(roleOnly.contains("stopLocked"));
    }

    @Test public void pendingConstructionConsumesLatestDesiredRole() throws Exception {
        String source = controller();
        String start = between(source,
                "private void startV2RuntimeOnMain",
                "private final class V2TransportListener");
        assertTrue(start.contains("if (mayStart && config != null) startBleRole = config.bleRole;"));
        assertTrue(start.contains("if (config != null) latestBleRole = config.bleRole;"));
        assertTrue(start.contains("created.requestMode(v2Mode(latestBleRole))"));
        assertTrue(start.contains("created.radioChanged(currentBluetooth != null"));
        assertTrue(start.contains("v2Mode(startBleRole)"));
        assertFalse(start.contains("v2Mode(bleRole),"));
    }

    @Test public void recoverySignalsUseOnlySameRoleDrainAndNoHiddenCacheHook()
            throws Exception {
        String source = controller();
        String manual = between(source,
                "public boolean reconnectForDiagnostics()",
                "private void stopLocked");
        assertTrue(manual.contains("runtime.requestSameModeRecovery()"));

        String oem = between(source,
                "private void handleOemDeviceStateChange",
                "private void replaceOemPowerObservation");
        assertTrue(oem.contains("reconcileClassicAncsRecovery(token)"));

        String policyEffects = between(source,
                "private void applyClassicAncsRecoveryTransition",
                "private void scheduleClassicAncsRecoveryWakeup");
        assertTrue(policyEffects.contains("runtime.requestSameModeRecovery()"));
        assertTrue(policyEffects.contains("ensureGatt(token)"));

        assertFalse(source.contains("scheduleGattReconnect("));
        assertFalse(source.contains("refreshGattCache("));
        assertFalse(source.contains("BluetoothGattCallback"));
    }

    @Test public void helperTelemetryCannotCrossSwitchOrRouteGeneration() throws Exception {
        String source = controller();
        String roleOnly = between(source,
                "if (running && config != null\n                    && config.bleRole",
                "stopLocked(next.enabled");
        assertTrue(roleOnly.contains("clearHelperTelemetry()"));
        assertTrue(roleOnly.indexOf("clearHelperTelemetry()")
                < roleOnly.indexOf("runtime.requestMode"));

        String dual = between(source,
                "private void applyV2DualStatus",
                "private void applyV2RouteStatus");
        assertTrue(dual.contains("if (!activePhase)"));
        assertTrue(dual.contains("clearHelperTelemetry()"));

        String route = between(source,
                "private void applyV2RouteStatus",
                "private void dispatchAncsTransport");
        assertTrue(route.contains("else if (!linkActive)"));
        assertTrue(route.contains("clearHelperTelemetry()"));
    }

    @Test public void eachAdapterBeginsAncsCoreExactlyOncePerSession() throws Exception {
        for (String relative : new String[]{
                "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                        + "AndroidCentralTransportV2.java",
                "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                        + "AndroidPeripheralTransportV2.java"
        }) {
            String adapter = text(relative);
            assertTrue(count(adapter, "ancs.begin(ancsSession);") == 1);
        }
    }

    @Test public void radioBoundaryRetainsRuntimeAndNamesAreNotPublished() throws Exception {
        String source = controller();
        String radio = between(source,
                "if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action))",
                "if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action))");
        assertTrue(radio.contains("runtime.radioChanged(enabled)"));
        assertTrue(radio.contains("invalidateSelectedPhone(token, \"bluetooth_off\", "
                + "runtime != null)"));

        assertTrue(source.contains("device.put(\"ancs_discovery_identity\", \"uuid_only\")"));
        assertTrue(source.contains("value(\"transport.ancs.local_name\",\n"
                + "                null, false"));
        assertFalse(source.contains("device.put(\"ancs_local_name\", "
                + "IphoneAncsTransport.LOCAL_LOGICAL_NAME)"));
    }

    @Test public void ordinaryRuntimeCloseCannotPersistPermanentTombstone() throws Exception {
        String runtime = text("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "IphoneDualTransportRuntimeV2.java");
        String ordinary = between(runtime,
                "@Override public void close()",
                "/** Explicit irreversible protocol tombstone");
        assertTrue(ordinary.contains("closeSlot()"));
        assertFalse(ordinary.contains("coordinator.close("));
        String permanent = between(runtime,
                "public void closePermanently()",
                "private void initialize");
        assertTrue(permanent.contains("coordinator.close(newWireToken())"));
    }

    @Test public void typedRouteErrorIsJournaledBeforeGenericSwitchFailure() throws Exception {
        String source = controller();
        String listener = between(source,
                "private final class V2TransportListener",
                "private void applyV2DualStatus");
        assertTrue(listener.contains("PhoneConnectionJournal.append(\"v2-error\""));
        assertTrue(listener.contains("kind=\" + error.kind"));
        assertTrue(listener.contains("retryable=\""));
        assertTrue(listener.contains("redactedDiagnostic(error.detail)"));
    }

    private static String controller() throws Exception {
        return text("app/src/main/java/dezz/status/widget/phone/PhoneConnectorController.java");
    }

    private static String text(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve(relative);
        if (!Files.exists(path) && root.getParent() != null) {
            path = root.getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("missing source range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
