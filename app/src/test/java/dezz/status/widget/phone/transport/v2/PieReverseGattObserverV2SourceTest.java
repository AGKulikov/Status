/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class PieReverseGattObserverV2SourceTest {
    private static final Path SOURCE = Paths.get(
            "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                    + "PieReverseGattObserverV2.java");

    @Test public void hiddenPieSignatureIsIsolatedOneShotAndHasNoPublicFallback()
            throws Exception {
        String source = source();
        assertTrue(source.contains("Build.VERSION.SDK_INT != ANDROID_P_API"));
        assertTrue(source.contains("BluetoothDevice.class.getMethod("));
        assertTrue(source.contains("BluetoothGattCallback.class, int.class, boolean.class"));
        assertTrue(source.contains("BluetoothDevice.TRANSPORT_LE, true"));
        assertTrue(source.contains("BluetoothDevice.PHY_LE_1M_MASK, main"));
        assertEquals(1, count(source, "\"connectGatt\""));
        assertFalse(source.contains(".connectGatt("));
        assertFalse(source.contains("IphoneAncsTransport"));
        assertFalse(source.contains("while ("));
    }

    @Test public void oneExactFacadeOwnerIsCloseOnlyAndNeverDisconnects() throws Exception {
        String source = source();
        assertTrue(source.contains("if (current != null)"));
        assertTrue(source.contains("exact.gatt != gatt"));
        assertTrue(source.contains("exact.token.equals(token)"));
        assertTrue(source.contains("gatt.close()"));
        assertFalse(source.contains(".disconnect("));
        assertTrue(source.contains("returned.getDevice() != candidate.physicalFacade"));
        assertTrue(source.contains("candidate.gatt != returned"));
        assertTrue(source.contains(
                "gatt.getDevice() == candidate.physicalFacade"));
        assertTrue(source.contains("sameCapturedInboundPhysicalFacade, exactlyOneOwner"));
        assertFalse(source.contains("onObserved(candidate.token, gatt,\n"
                + "                    true, true)"));
    }

    @Test public void syncCallbackAndUnknownRegistrationAreQuarantinedByProcessGate()
            throws Exception {
        String source = source();
        assertTrue(source.contains("candidate.invocationReturned = true"));
        assertTrue(source.contains("candidate.pendingConnection"));
        assertTrue(source.contains("retiringWhenRegistrationProven"));
        assertTrue(source.contains("ProcessGattRegistrationGateV2.tryAcquire(candidate)"));
        assertTrue(source.contains("ProcessGattRegistrationGateV2.whenFree(candidate"));
        assertTrue(source.contains("ProcessGattRegistrationGateV2.release(candidate)"));
    }

    @Test public void everyForwardedCallbackUsesConditionalMainAndClonesPayloads()
            throws Exception {
        String source = source();
        String bridge = between(source, "private BluetoothGattCallback bridge",
                "private boolean isCurrent");
        assertTrue(bridge.contains("dispatchMain(() ->"));
        assertTrue(bridge.contains("value.clone()"));
        String dispatcher = between(source, "private void dispatchMain", "\n    }");
        assertTrue(dispatcher.contains("Looper.myLooper() == main.getLooper()"));
        assertTrue(dispatcher.contains("callbackBody.run()"));
    }

    private static String source() throws Exception {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static int count(String value, String needle) {
        int result = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(needle, cursor)) >= 0) {
            result++;
            cursor += needle.length();
        }
        return result;
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("missing source markers: " + start + " / " + end);
        }
        return value.substring(from, to);
    }
}
