/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for attaching ANCS to the retained iPhone-Central LE connection. */
public final class Ha1195ExistingLinkAncsOwnerContractTest {
    @Test public void reverseRouteUsesBackgroundOpenOnTheExistingConnection() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String start = between(transport,
                "private void scheduleSecureClientStart",
                "private void scheduleDirectFallback");

        assertTrue(start.contains("startSamePeerAttach(true"));
        assertFalse(start.contains("startSamePeerAttach(false, \"same-owner ANCS-READY"));
        assertTrue(start.contains("if (!autoConnect)"));
        assertTrue(start.contains("device.connectGatt(context, autoConnect, gattCallback"));
        assertTrue(start.contains("SAME-PEER ATTACH · BACKGROUND OWNER"));
        assertFalse(start.contains("SAME-PEER ATTACH · DIRECT #"));
    }

    @Test public void status133RearmsTheSameClientIfInsteadOfAllocatingThreeOwners()
            throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String retained = between(transport,
                "private void awaitIncomingBackgroundOwner",
                "private void cancelClientAttemptCallbacks");
        String persistent = between(transport,
                "private void rearmPersistentGattOwner",
                "private void restartDiscoveryOnPersistentOwner");
        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "@Override\n        public void onServicesDiscovered");

        assertTrue(retained.contains("gatt != expected"));
        assertTrue(retained.contains("rearmPersistentGattOwner(expected"));
        assertFalse(retained.contains("expected.close()"));
        assertFalse(retained.contains("connectGatt("));
        assertTrue(persistent.contains("expected.connect()"));
        assertFalse(persistent.contains("expected.close()"));
        assertTrue(callback.contains("same-peer GATT status="));
        assertTrue(callback.contains("awaitIncomingBackgroundOwner(callbackGatt"));
        assertTrue(callback.contains("status == BluetoothGatt.GATT_FAILURE"));
    }

    @Test public void onlyMissingClientRegistrationMayCreateAReplacement() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String retry = between(transport,
                "private void scheduleIncomingClientAttachRetry",
                "private void recoverIncomingClientRole");
        String recovery = between(transport,
                "private void recoverIncomingClientRole",
                "private void cancelClientAttemptCallbacks");

        assertTrue(retry.contains("startSamePeerAttach(true"));
        assertFalse(retry.contains("startSamePeerAttach(false"));
        assertTrue(recovery.contains("BluetoothGatt owner = gatt"));
        assertTrue(recovery.contains("awaitIncomingBackgroundOwner(owner"));
        assertFalse(recovery.contains("owner.close()"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
