/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Explicit endpoint role carried by the v2 discovery/hello protocol. */
public enum BlePeerRole {
    IPHONE_HELPER_PERIPHERAL(1),
    ANDROID_PERIPHERAL(2),
    IPHONE_HELPER_CENTRAL(3);

    public final int wireId;

    BlePeerRole(int wireId) {
        this.wireId = wireId;
    }

    public static BlePeerRole fromWireId(int wireId) {
        for (BlePeerRole value : values()) {
            if (value.wireId == wireId) return value;
        }
        return null;
    }
}
