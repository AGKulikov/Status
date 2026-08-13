/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source integration gate for one exact Classic identity plus its ANCS Route-A recovery. */
public final class Ha1215ClassicAncsRecoveryContractTest {
    @Test public void onlyExactClassicProfilesDriveOuterRecovery() throws Exception {
        String source = controller();
        String predicate = between(source,
                "private boolean classicProfileConnected()",
                "private void applyClassicAncsRecoveryTransition");
        assertTrue(predicate.contains("bredrAclConnected"));
        assertTrue(predicate.contains("a2dpConnected"));
        assertTrue(predicate.contains("hfpConnected"));
        assertTrue(predicate.contains("mapConnected"));
        assertFalse(predicate.contains("aclConnected ||"));
        assertTrue(source.contains("if (!isSelected(device)) return;"));
        assertTrue(source.contains("selectBondedPhone(adapter"));
        assertFalse(source.contains("createBond("));
        assertFalse(source.contains("removeBond("));
    }

    @Test public void typedWaitStatesPreventDetailStringRecoveryHeuristics() throws Exception {
        String source = controller();
        assertTrue(source.contains("status.recoveryState"));
        assertTrue(source.contains("IphoneTransportRecoveryStateV2.WAIT_SERVICE_CHANGED"));
        assertTrue(source.contains("IphoneTransportRecoveryStateV2.WAIT_AUTHORIZATION"));
        String reconcile = between(source,
                "private void reconcileClassicAncsRecovery",
                "private boolean classicProfileConnected");
        assertFalse(reconcile.contains("status.detail"));
        assertFalse(reconcile.contains("contains("));
    }

    @Test public void routeBIsHiddenAndRequiresExplicitLocalDiagnosticLatch() throws Exception {
        String activity = source("app/src/main/java/dezz/status/widget/"
                + "PhoneConnectorSettingsActivity.java");
        assertTrue(activity.contains("EXTRA_EXPERIMENTAL_ROUTE_B"));
        assertTrue(activity.contains("if (experimentalRouteB)"));
        assertTrue(activity.contains("phoneBleExperimentalRouteBEnabled.set("));

        String source = controller();
        assertTrue(source.contains("productionBleRole(prefs.phoneBleRole.get(),"));
        assertTrue(source.contains("prefs.phoneBleExperimentalRouteBEnabled.get()"));
        assertTrue(source.contains("? PhoneBleRole.normalize(storedRole)"));
        assertTrue(source.contains(": PhoneBleRole.IPHONE_PERIPHERAL"));
        assertTrue(source.contains(
                "boolean diagnosticRouteB = config != null && config.experimentalRouteBEnabled;"));
        assertTrue(source.contains(
                "int storedRole = diagnosticRouteB\n"
                        + "                    && status.desiredMode == "
                        + "IphoneBleMode.ANDROID_PERIPHERAL"));
    }

    @Test public void recoveryNeverMutatesGlobalBluetoothOrGattCache() throws Exception {
        String source = controller();
        String recovery = between(source,
                "private void reconcileClassicAncsRecovery",
                "/**\n     * Do not destroy a healthy ANCS owner");
        assertTrue(recovery.contains("runtime.requestSameModeRecovery()"));
        assertTrue(recovery.contains("ensureGatt(token)"));
        assertFalse(recovery.contains("disable()"));
        assertFalse(recovery.contains("enable()"));
        assertFalse(recovery.contains("refresh("));
        assertFalse(recovery.contains("createBond"));
    }

    private static String controller() throws Exception {
        return source("app/src/main/java/dezz/status/widget/phone/PhoneConnectorController.java");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve(relative);
        if (!Files.isRegularFile(path) && root.getParent() != null) {
            path = root.getParent().resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("missing source range");
        return source.substring(from, to);
    }
}
