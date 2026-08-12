/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone.transport.v2;

/**
 * The two deliberately separate BLE topologies supported by transport v2.
 *
 * <p>The name describes Android's GAP role.  Keeping that rule in the type name avoids the
 * historical ambiguity of a preference called "iPhone central" in Android code.  A running
 * transport owns exactly one value; changing it always requires a new {@link BleRouteEpoch}.</p>
 */
public enum IphoneBleMode {
    /** Android scans/connects; the iPhone Helper advertises and exposes GATT. */
    ANDROID_CENTRAL(1, "android_central", "iphone_peripheral"),
    /** Android advertises/exposes GATT; the iPhone Helper scans/connects. */
    ANDROID_PERIPHERAL(2, "android_peripheral", "iphone_central");

    public final int wireId;
    public final String stableKey;
    public final String iphoneRoleKey;

    IphoneBleMode(int wireId, String stableKey, String iphoneRoleKey) {
        this.wireId = wireId;
        this.stableKey = stableKey;
        this.iphoneRoleKey = iphoneRoleKey;
    }

    public static IphoneBleMode fromWireId(int wireId) {
        for (IphoneBleMode value : values()) {
            if (value.wireId == wireId) return value;
        }
        return null;
    }
}
