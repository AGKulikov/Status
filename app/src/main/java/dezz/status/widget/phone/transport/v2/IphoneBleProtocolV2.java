/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.UUID;

/** Stable, role-separated discovery surface for the clean transport. */
public final class IphoneBleProtocolV2 {
    public static final int VERSION = 2;

    /** Advertised and hosted only by an iPhone Helper running as a peripheral. */
    public static final UUID HELPER_PERIPHERAL_SERVICE =
            UUID.fromString("d2d9e4c0-47f1-4e44-a8bb-a932fd5af201");
    /** Advertised and hosted only by Android running as a peripheral. */
    public static final UUID ANDROID_PERIPHERAL_SERVICE =
            UUID.fromString("d2d9e4c0-47f1-4e44-a8bb-a932fd5af202");

    public static final UUID PEER_PROOF_CHARACTERISTIC =
            UUID.fromString("d2d9e4c1-47f1-4e44-a8bb-a932fd5af200");
    public static final UUID CONTROL_CHARACTERISTIC =
            UUID.fromString("d2d9e4c2-47f1-4e44-a8bb-a932fd5af200");
    public static final UUID TELEMETRY_CHARACTERISTIC =
            UUID.fromString("d2d9e4c3-47f1-4e44-a8bb-a932fd5af200");
    /** Plain pre-SMP enrollment/routine-auth channel introduced by Helper 51. */
    public static final UUID ENROLLMENT_CHARACTERISTIC =
            UUID.fromString("d2d9e4c4-47f1-4e44-a8bb-a932fd5af200");

    private IphoneBleProtocolV2() {
    }

    public static UUID advertisedServiceFor(IphoneBleMode mode) {
        return mode == IphoneBleMode.ANDROID_CENTRAL
                ? HELPER_PERIPHERAL_SERVICE : ANDROID_PERIPHERAL_SERVICE;
    }

    public static BlePeerRole advertiserRoleFor(IphoneBleMode mode) {
        return mode == IphoneBleMode.ANDROID_CENTRAL
                ? BlePeerRole.IPHONE_HELPER_PERIPHERAL
                : BlePeerRole.ANDROID_PERIPHERAL;
    }
}
