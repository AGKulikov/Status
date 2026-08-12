/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class IphoneControlBoundaryV2Test {
    @Test public void androidAuthorizationStatusesNeverBecomeTransientReconnects() {
        assertEquals(GattResultV2.AUTHENTICATION_REQUIRED,
                GattResultV2.fromAndroidStatus(5));
        assertEquals(GattResultV2.AUTHORIZATION_DENIED,
                GattResultV2.fromAndroidStatus(8));
        assertEquals(GattResultV2.AUTHENTICATION_REQUIRED,
                GattResultV2.fromAndroidStatus(15));
    }

    @Test public void roleControlRejectsZeroTransactionIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new IphoneRoleControlV2(
                IphoneRoleControlV2.Type.CLOSE_REQUEST,
                IphoneBleMode.ANDROID_PERIPHERAL,
                new byte[IphoneBleControlProtocolV2.PAYLOAD_BYTES]));
    }
}
