/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

public final class IphoneLeEnrollmentRecordV2 {
    private static final java.lang.String PREFIX = "ELR1";
    public final java.util.UUID androidInstallationId;
    public final long committedAtEpochMillis;
    public final java.util.UUID helperInstallationId;
    public final java.lang.String leIdentityAddress;
    private final byte[] longTermKey;
    public final java.lang.String selectedClassicAddress;

    public IphoneLeEnrollmentRecordV2(java.lang.String str, java.lang.String str2, java.util.UUID uuid, java.util.UUID uuid2, byte[] bArr, long j) {
        java.lang.String strCanonicalAddress = canonicalAddress(str);
        this.selectedClassicAddress = strCanonicalAddress;
        java.lang.String strCanonicalAddress2 = canonicalAddress(str2);
        this.leIdentityAddress = strCanonicalAddress2;
        this.helperInstallationId = requireUuid(uuid, "Helper UUID");
        this.androidInstallationId = requireUuid(uuid2, "Android UUID");
        if (strCanonicalAddress.isEmpty() || strCanonicalAddress2.isEmpty()) {
            throw new java.lang.IllegalArgumentException("both Classic and LE addresses are required");
        }
        if (bArr == null || bArr.length != 32 || allZero(bArr)) {
            throw new java.lang.IllegalArgumentException("non-zero 256-bit long-term key required");
        }
        if (j < 0) {
            throw new java.lang.IllegalArgumentException("commit time must be non-negative");
        }
        this.longTermKey = (byte[]) bArr.clone();
        this.committedAtEpochMillis = j;
    }

    public boolean matchesBinding(java.lang.String str, java.lang.String str2, java.lang.String str3) {
        java.lang.String strCanonicalAddress = canonicalAddress(str);
        java.util.UUID uuid = parseUuid(str2);
        java.util.UUID uuid2 = parseUuid(str3);
        return !strCanonicalAddress.isEmpty() && uuid != null && this.selectedClassicAddress.equals(strCanonicalAddress) && this.helperInstallationId.equals(uuid) && uuid2 != null && this.androidInstallationId.equals(uuid2);
    }

    public byte[] copyLongTermKey() {
        return (byte[]) this.longTermKey.clone();
    }

    public java.lang.String encode() {
        java.util.Base64.Encoder encoderWithoutPadding = java.util.Base64.getUrlEncoder().withoutPadding();
        return "ELR1." + field(encoderWithoutPadding, this.selectedClassicAddress) + "." + field(encoderWithoutPadding, this.leIdentityAddress) + "." + field(encoderWithoutPadding, this.helperInstallationId.toString()) + "." + field(encoderWithoutPadding, this.androidInstallationId.toString()) + "." + encoderWithoutPadding.encodeToString(this.longTermKey) + "." + java.lang.Long.toString(this.committedAtEpochMillis);
    }

    public static dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 parse(java.lang.String str) {
        if (str != null && !str.trim().isEmpty()) {
            java.lang.String[] strArrSplit = str.trim().split("\\.", -1);
            if (strArrSplit.length == 7 && PREFIX.equals(strArrSplit[0])) {
                try {
                    java.util.Base64.Decoder urlDecoder = java.util.Base64.getUrlDecoder();
                    return new dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2(text(urlDecoder, strArrSplit[1]), text(urlDecoder, strArrSplit[2]), java.util.UUID.fromString(text(urlDecoder, strArrSplit[3])), java.util.UUID.fromString(text(urlDecoder, strArrSplit[4])), urlDecoder.decode(strArrSplit[5]), java.lang.Long.parseLong(strArrSplit[6]));
                } catch (java.lang.RuntimeException unused) {
                }
            }
        }
        return null;
    }

    public static dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 validForSelectedClassic(java.lang.String str, java.lang.String str2) {
        dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 iphoneLeEnrollmentRecordV2 = parse(str);
        if (iphoneLeEnrollmentRecordV2 == null || !iphoneLeEnrollmentRecordV2.selectedClassicAddress.equals(canonicalAddress(str2))) {
            return null;
        }
        return iphoneLeEnrollmentRecordV2;
    }

    private static java.lang.String field(java.util.Base64.Encoder encoder, java.lang.String str) {
        return encoder.encodeToString(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static java.lang.String text(java.util.Base64.Decoder decoder, java.lang.String str) {
        return new java.lang.String(decoder.decode(str), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static java.util.UUID requireUuid(java.util.UUID uuid, java.lang.String str) {
        if (uuid == null || (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0)) {
            throw new java.lang.IllegalArgumentException(str + " must be non-zero");
        }
        return uuid;
    }

    private static java.util.UUID parseUuid(java.lang.String str) {
        if (str != null && !str.trim().isEmpty()) {
            try {
                return requireUuid(java.util.UUID.fromString(str.trim()), "UUID");
            } catch (java.lang.RuntimeException unused) {
            }
        }
        return null;
    }

    private static java.lang.String canonicalAddress(java.lang.String str) {
        if (str == null) {
            return "";
        }
        java.lang.String upperCase = str.trim().toUpperCase(java.util.Locale.US);
        if (upperCase.length() != 17) {
            return "";
        }
        for (int i = 0; i < upperCase.length(); i++) {
            char cCharAt = upperCase.charAt(i);
            if (i % 3 == 2) {
                if (cCharAt != ':') {
                    return "";
                }
            } else if ((cCharAt < '0' || cCharAt > '9') && (cCharAt < 'A' || cCharAt > 'F')) {
                return "";
            }
        }
        return upperCase;
    }

    private static boolean allZero(byte[] bArr) {
        int i = 0;
        for (byte b : bArr) {
            i |= b;
        }
        return i == 0;
    }

    public boolean equals(java.lang.Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2) {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2 iphoneLeEnrollmentRecordV2 = (dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentRecordV2) obj;
            if (this.selectedClassicAddress.equals(iphoneLeEnrollmentRecordV2.selectedClassicAddress) && this.leIdentityAddress.equals(iphoneLeEnrollmentRecordV2.leIdentityAddress) && this.helperInstallationId.equals(iphoneLeEnrollmentRecordV2.helperInstallationId) && this.androidInstallationId.equals(iphoneLeEnrollmentRecordV2.androidInstallationId) && this.committedAtEpochMillis == iphoneLeEnrollmentRecordV2.committedAtEpochMillis && java.util.Arrays.equals(this.longTermKey, iphoneLeEnrollmentRecordV2.longTermKey)) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        return (((((((((this.selectedClassicAddress.hashCode() * 31) + this.leIdentityAddress.hashCode()) * 31) + this.helperInstallationId.hashCode()) * 31) + this.androidInstallationId.hashCode()) * 31) + java.lang.Long.hashCode(this.committedAtEpochMillis)) * 31) + java.util.Arrays.hashCode(this.longTermKey);
    }
}
