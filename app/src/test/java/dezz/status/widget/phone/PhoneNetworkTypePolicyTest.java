/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PhoneNetworkTypePolicyTest {
    @Test public void rendersIphoneStatusLabels() {
        assertEquals("5G", PhoneNetworkTypePolicy.display("5g"));
        assertEquals("5G+", PhoneNetworkTypePolicy.display("5G_PLUS"));
        assertEquals("5G UW", PhoneNetworkTypePolicy.display("5g-uw"));
        assertEquals("LTE", PhoneNetworkTypePolicy.display("LTE"));
        assertEquals("3G", PhoneNetworkTypePolicy.display("3G"));
        assertEquals("E", PhoneNetworkTypePolicy.display("EDGE"));
        assertEquals("G", PhoneNetworkTypePolicy.display("GPRS"));
        assertEquals("1x", PhoneNetworkTypePolicy.display("1X"));
        assertEquals("", PhoneNetworkTypePolicy.display("wifi"));
    }
}
