/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Typed ATT/GATT outcome retained across the platform boundary. */
public enum GattResultV2 {
    SUCCESS,
    AUTHENTICATION_REQUIRED,
    AUTHORIZATION_DENIED,
    INVALID_HANDLE,
    TRANSIENT_FAILURE;

    /** Android callback/ATT status mapping used by both framework adapters. */
    public static GattResultV2 fromAndroidStatus(int status) {
        switch (status) {
            case 0: return SUCCESS;
            case 5: return AUTHENTICATION_REQUIRED;
            case 8: return AUTHORIZATION_DENIED;
            case 15: return AUTHENTICATION_REQUIRED;
            case 1: return INVALID_HANDLE;
            default: return TRANSIENT_FAILURE;
        }
    }

    public boolean authorizationFailure() {
        return this == AUTHENTICATION_REQUIRED || this == AUTHORIZATION_DENIED;
    }
}
