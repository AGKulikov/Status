/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

/**
 * Persistent BLE role selected for the iPhone side of the phone connector.
 *
 * <p>The v2 implementation keeps both canonical topologies behind one confirmed-quiescence
 * switch coordinator.  Discovery is UUID-only in both directions: neither endpoint broadcasts a
 * synthetic BLE local name. Classic Bluetooth profiles are independent from this value.</p>
 */
public final class PhoneBleRole {
    /** iPhone Helper peripheral, KX11 public GATT central/client. */
    public static final int IPHONE_PERIPHERAL = 0;
    /** iPhone Helper central, KX11 GATT peripheral/server plus isolated reverse ANCS observer. */
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
