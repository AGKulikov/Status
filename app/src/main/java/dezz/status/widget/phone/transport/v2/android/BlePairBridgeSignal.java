/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

public final class BlePairBridgeSignal {
    private static final java.lang.String EXTRA_GENERATION = "attempt_generation";
    private static final java.lang.String EXTRA_TOKEN = "attempt_token";
    private static final java.lang.String METHOD_ARM = "arm-v1";
    private static final java.lang.String METHOD_DISARM = "disarm-v1";
    private static final android.net.Uri CONTROL = android.net.Uri.parse("content://ru.natro.kx11.blepairbridge.control");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private BlePairBridgeSignal() {
    }

    public static boolean arm(android.content.Context context, android.bluetooth.BluetoothDevice bluetoothDevice) {
        long jNextLong;
        if (context == null || bluetoothDevice == null) {
            return false;
        }
        try {
            java.lang.String address = bluetoothDevice.getAddress();
            if (address == null) {
                return false;
            }
            java.lang.String upperCase = address.trim().toUpperCase(java.util.Locale.US);
            if (!canonicalAddress(upperCase)) {
                return false;
            }
            byte[] bArr = new byte[16];
            RANDOM.nextBytes(bArr);
            java.lang.StringBuilder sb = new java.lang.StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(java.lang.String.format(java.util.Locale.US, "%02x", java.lang.Integer.valueOf(bArr[i] & 255)));
            }
            do {
                jNextLong = RANDOM.nextLong() & Long.MAX_VALUE;
            } while (jNextLong == 0);
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString(EXTRA_TOKEN, sb.toString());
            bundle.putLong(EXTRA_GENERATION, jNextLong);
            android.os.Bundle bundleCall = context.getContentResolver().call(CONTROL, METHOD_ARM, upperCase, bundle);
            return bundleCall != null && bundleCall.getBoolean("ok", false);
        } catch (java.lang.RuntimeException e) {
            return false;
        }
    }

    public static void disarm(android.content.Context context) {
        if (context == null) {
            return;
        }
        try {
            context.getContentResolver().call(CONTROL, METHOD_DISARM, (java.lang.String) null, (android.os.Bundle) null);
        } catch (java.lang.RuntimeException e) {
        }
    }

    private static boolean canonicalAddress(java.lang.String str) {
        if (str.length() != 17 || "00:00:00:00:00:00".equals(str)) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (i % 3 == 2) {
                if (str.charAt(i) != ':') {
                    return false;
                }
            } else {
                char cCharAt = str.charAt(i);
                if ((cCharAt < '0' || cCharAt > '9') && (cCharAt < 'A' || cCharAt > 'F')) {
                    return false;
                }
            }
        }
        return true;
    }
}
