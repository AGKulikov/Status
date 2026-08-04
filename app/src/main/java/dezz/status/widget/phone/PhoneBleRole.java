/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

/**
 * Persistent BLE role selected for the iPhone side of the phone connector.
 *
 * <p>The default deliberately remains {@link #IPHONE_PERIPHERAL}: it is the production route
 * used through HA1161, where Android initiates the connection. The alternate route makes KX11
 * advertise {@code Geely_ANCS}; iPhone Helper then initiates the link as a central. Classic
 * Bluetooth profiles are independent from this value.</p>
 */
public final class PhoneBleRole {
    /** Current/legacy route: iPhone peripheral, KX11 central. */
    public static final int IPHONE_PERIPHERAL = 0;
    /** Alternate route: iPhone central, KX11 peripheral. */
    public static final int IPHONE_CENTRAL = 1;

    private PhoneBleRole() {
    }

    public static int normalize(int value) {
        return value == IPHONE_CENTRAL ? IPHONE_CENTRAL : IPHONE_PERIPHERAL;
    }

    public static boolean isIphoneCentral(int value) {
        return normalize(value) == IPHONE_CENTRAL;
    }

    public static String diagnosticName(int value) {
        return isIphoneCentral(value) ? "iphone_central" : "iphone_peripheral";
    }
}
