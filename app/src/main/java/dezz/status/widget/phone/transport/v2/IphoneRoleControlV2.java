/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Arrays;
import java.util.Objects;

/** Exact v2 role-close request or acknowledgement delivered over the subscribed control link. */
public final class IphoneRoleControlV2 {
    public enum Type { CLOSE_REQUEST, CLOSE_ACK }

    public final Type type;
    public final IphoneBleMode targetMode;
    private final byte[] switchToken;

    public IphoneRoleControlV2(Type type, IphoneBleMode targetMode, byte[] switchToken) {
        this.type = Objects.requireNonNull(type, "type");
        this.targetMode = Objects.requireNonNull(targetMode, "targetMode");
        if (switchToken == null
                || switchToken.length != IphoneBleControlProtocolV2.PAYLOAD_BYTES
                || allZero(switchToken)) {
            throw new IllegalArgumentException(
                    "switchToken must be a non-zero 16-byte value");
        }
        this.switchToken = switchToken.clone();
    }

    public byte[] switchToken() {
        return switchToken.clone();
    }

    public boolean sameTransaction(IphoneRoleControlV2 other) {
        return other != null && targetMode == other.targetMode
                && Arrays.equals(switchToken, other.switchToken);
    }

    private static boolean allZero(byte[] value) {
        int combined = 0;
        for (byte item : value) combined |= item & 0xff;
        return combined == 0;
    }
}
