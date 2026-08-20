/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

public final class IphoneLeEnrollmentProtocolV2 {
    public static final int AUTH_FRAME_BYTES = 66;
    public static final int AUTH_HELLO_BYTES = 34;
    public static final int ENROLLMENT_HELLO_BYTES = 99;
    public static final int ENROLLMENT_RESPONSE_BYTES = 99;
    public static final int KEY_BYTES = 32;
    public static final byte KIND_ENROLLMENT_CONFIRM = 3;
    public static final byte KIND_ENROLLMENT_FINAL_ACK = -123;
    public static final byte KIND_ENROLLMENT_FINAL_COMMIT = 5;
    public static final byte KIND_ENROLLMENT_HELLO = 1;
    public static final byte KIND_ENROLLMENT_PREAUTH_ACK = -125;
    public static final byte KIND_ENROLLMENT_RESPONSE = -127;
    public static final byte KIND_ENROLLMENT_WAITING_SAS = -128;
    public static final byte KIND_ROUTINE_ACK = -124;
    public static final byte KIND_ROUTINE_CONFIRM = 4;
    public static final byte KIND_ROUTINE_HELLO = 2;
    public static final byte KIND_ROUTINE_PROOF = -126;
    public static final int NONCE_BYTES = 16;
    public static final int P256_PUBLIC_BYTES = 65;
    public static final int WIRE_VERSION = 1;
    private static final byte[] DOMAIN = utf8("NATRO-F201-ENROLLMENT-V1");
    private static final byte[] INFO_SESSION_MASTER = utf8("session-master");
    private static final byte[] INFO_LONG_TERM = utf8("long-term");
    private static final byte[] LABEL_SAS = utf8("sas");
    private static final byte[] LABEL_ANDROID_CONFIRM = utf8("android-confirm");
    private static final byte[] LABEL_HELPER_ACK = utf8("helper-ack");
    private static final byte[] LABEL_HELPER_WAITING_SAS = utf8("helper-waiting-sas");
    private static final byte[] LABEL_ANDROID_COMMIT = utf8("android-commit");
    private static final byte[] LABEL_HELPER_COMMIT_ACK = utf8("helper-commit-ack");
    private static final byte[] LABEL_ROUTINE_PROOF = utf8("routine-proof");
    private static final byte[] LABEL_ANDROID_ROUTINE_CONFIRM = utf8("android-routine-confirm");

    private IphoneLeEnrollmentProtocolV2() {
    }

    public static java.security.KeyPair generateEphemeralKeyPair(java.security.SecureRandom secureRandom) throws java.security.GeneralSecurityException {
        java.security.KeyPairGenerator keyPairGenerator = java.security.KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), (java.security.SecureRandom) java.util.Objects.requireNonNull(secureRandom, "random"));
        return keyPairGenerator.generateKeyPair();
    }

    public static byte[] randomNonce(java.security.SecureRandom secureRandom) {
        byte[] bArr = new byte[16];
        do {
            ((java.security.SecureRandom) java.util.Objects.requireNonNull(secureRandom, "random")).nextBytes(bArr);
        } while (allZero(bArr));
        return bArr;
    }

    public static byte[] encodeEnrollmentHello(java.util.UUID uuid, byte[] bArr, java.security.PublicKey publicKey) {
        requireNonZeroUuid(uuid, "Android installation UUID");
        requireNonce(bArr, "Android nonce");
        byte[] bArrEncodeUncompressedP256 = encodeUncompressedP256(publicKey);
        java.nio.ByteBuffer byteBufferAllocate = java.nio.ByteBuffer.allocate(99);
        byteBufferAllocate.put((byte) 1).put((byte) 1);
        putUuid(byteBufferAllocate, uuid);
        byteBufferAllocate.put(bArr).put(bArrEncodeUncompressedP256);
        return byteBufferAllocate.array();
    }

    public static dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentResponse parseEnrollmentResponse(byte[] bArr) throws java.security.GeneralSecurityException {
        requireFrame(bArr, 99, KIND_ENROLLMENT_RESPONSE, "enrollment response");
        java.nio.ByteBuffer byteBufferWrap = java.nio.ByteBuffer.wrap(bArr);
        byteBufferWrap.get();
        byteBufferWrap.get();
        java.util.UUID uuid = readUuid(byteBufferWrap);
        requireNonZeroUuid(uuid, "Helper installation UUID");
        byte[] bArr2 = new byte[16];
        byteBufferWrap.get(bArr2);
        requireNonce(bArr2, "Helper nonce");
        byte[] bArr3 = new byte[65];
        byteBufferWrap.get(bArr3);
        return new dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentResponse(uuid, bArr2, decodeUncompressedP256(bArr3), bArr);
    }

    public static dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession establishEnrollmentSession(byte[] bArr, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentResponse enrollmentResponse, java.security.PrivateKey privateKey) throws java.security.GeneralSecurityException {
        requireFrame(bArr, 99, (byte) 1, "enrollment hello");
        java.util.Objects.requireNonNull(enrollmentResponse, "response");
        java.util.Objects.requireNonNull(privateKey, "androidPrivateKey");
        byte[] bArrConcat = concat(DOMAIN, bArr, enrollmentResponse.raw);
        byte[] bArrSha256 = sha256(bArrConcat);
        javax.crypto.KeyAgreement keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(enrollmentResponse.ephemeralPublicKey, true);
        byte[] bArrGenerateSecret = keyAgreement.generateSecret();
        if (bArrGenerateSecret.length == 0 || allZero(bArrGenerateSecret)) {
            throw new java.security.GeneralSecurityException("invalid all-zero ECDH shared secret");
        }
        byte[] bArrHkdfSha256 = hkdfSha256(bArrGenerateSecret, bArrSha256, INFO_SESSION_MASTER, 32);
        java.math.BigInteger bigIntegerValueOf = java.math.BigInteger.valueOf(100000000L);
        java.math.BigInteger bigIntegerSubtract = java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE);
        java.math.BigInteger bigIntegerSubtract2 = bigIntegerSubtract.subtract(bigIntegerSubtract.mod(bigIntegerValueOf).add(java.math.BigInteger.ONE).mod(bigIntegerValueOf));
        int i = 0;
        while (true) {
            int i2 = i + 1;
            java.math.BigInteger bigInteger = new java.math.BigInteger(1, java.util.Arrays.copyOf(hmacSha256(bArrHkdfSha256, bArrConcat, LABEL_SAS, java.nio.ByteBuffer.allocate(4).putInt(i).array()), 8));
            if (bigInteger.compareTo(bigIntegerSubtract2) <= 0) {
                java.lang.String str = java.lang.String.format(java.util.Locale.US, "%08d", java.lang.Long.valueOf(bigInteger.mod(bigIntegerValueOf).longValue()));
                byte[] bArrHkdfSha257 = hkdfSha256(bArrHkdfSha256, bArrSha256, INFO_LONG_TERM, 32);
                java.util.Arrays.fill(bArrGenerateSecret, (byte) 0);
                return new dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentSession(bArr, enrollmentResponse, bArrConcat, bArrHkdfSha256, bArrHkdfSha257, str);
            }
            i = i2;
        }
    }

    public static byte[] encodeRoutineHello(java.util.UUID uuid, byte[] bArr) {
        requireNonZeroUuid(uuid, "Android installation UUID");
        requireNonce(bArr, "Android nonce");
        java.nio.ByteBuffer byteBufferAllocate = java.nio.ByteBuffer.allocate(34);
        byteBufferAllocate.put((byte) 1).put((byte) 2);
        putUuid(byteBufferAllocate, uuid);
        byteBufferAllocate.put(bArr);
        return byteBufferAllocate.array();
    }

    public static dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.RoutineSession verifyRoutineProof(byte[] bArr, byte[] bArr2, byte[] bArr3) throws java.security.GeneralSecurityException {
        requireFrame(bArr, 34, (byte) 2, "routine hello");
        requireFrame(bArr2, 66, KIND_ROUTINE_PROOF, "routine proof");
        requireKey(bArr3);
        byte[] bArrCopyOf = java.util.Arrays.copyOf(bArr2, 34);
        java.util.UUID uuidUuidAt = uuidAt(bArrCopyOf, 2);
        requireNonZeroUuid(uuidUuidAt, "Helper installation UUID");
        requireNonce(java.util.Arrays.copyOfRange(bArrCopyOf, 18, 34), "Helper nonce");
        if (!java.security.MessageDigest.isEqual(hmacSha256(bArr3, DOMAIN, bArr, bArrCopyOf, LABEL_ROUTINE_PROOF), java.util.Arrays.copyOfRange(bArr2, 34, 66))) {
            throw new java.security.GeneralSecurityException("routine Helper proof mismatch");
        }
        return new dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.RoutineSession(bArr, bArr2, uuidUuidAt, bArr3);
    }

    public static final class EnrollmentResponse {
        public final java.security.PublicKey ephemeralPublicKey;
        public final java.util.UUID helperInstallationId;
        public final byte[] helperNonce;
        private final byte[] raw;

        private EnrollmentResponse(java.util.UUID uuid, byte[] bArr, java.security.PublicKey publicKey, byte[] bArr2) {
            this.helperInstallationId = uuid;
            this.helperNonce = (byte[]) bArr.clone();
            this.ephemeralPublicKey = publicKey;
            this.raw = (byte[]) bArr2.clone();
        }
    }

    public static final class EnrollmentSession {
        private final byte[] hello;
        public final java.util.UUID helperInstallationId;
        private final byte[] longTermKey;
        private final dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentResponse response;
        public final java.lang.String sas;
        private final byte[] sessionMaster;
        private final byte[] transcript;

        private EnrollmentSession(byte[] bArr, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.EnrollmentResponse enrollmentResponse, byte[] bArr2, byte[] bArr3, byte[] bArr4, java.lang.String str) {
            this.hello = (byte[]) bArr.clone();
            this.response = enrollmentResponse;
            this.transcript = (byte[]) bArr2.clone();
            this.sessionMaster = (byte[]) bArr3.clone();
            this.longTermKey = (byte[]) bArr4.clone();
            this.helperInstallationId = enrollmentResponse.helperInstallationId;
            this.sas = str;
        }

        public byte[] encodeConfirm() throws java.security.GeneralSecurityException {
            byte[] bArrAuthCore = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.authCore((byte) 3, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(this.hello, 2), java.util.Arrays.copyOfRange(this.hello, 18, 34));
            return dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.concat(bArrAuthCore, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.sessionMaster, this.transcript, bArrAuthCore, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_ANDROID_CONFIRM));
        }

        public boolean verifyPreauthAck(byte[] bArr, byte[] bArr2) throws java.security.GeneralSecurityException {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr, 66, (byte) 3, "enrollment confirm");
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr2, 66, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.KIND_ENROLLMENT_PREAUTH_ACK, "enrollment preauth ack");
            byte[] bArrCopyOf = java.util.Arrays.copyOf(bArr2, 34);
            if (this.helperInstallationId.equals(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(bArrCopyOf, 2)) && java.util.Arrays.equals(this.response.helperNonce, java.util.Arrays.copyOfRange(bArrCopyOf, 18, 34))) {
                return java.security.MessageDigest.isEqual(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.sessionMaster, this.transcript, bArr, bArrCopyOf, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_HELPER_ACK), java.util.Arrays.copyOfRange(bArr2, 34, 66));
            }
            return false;
        }

        public boolean verifyWaitingSas(byte[] bArr, byte[] bArr2) throws java.security.GeneralSecurityException {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr, 66, (byte) 3, "enrollment confirm");
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr2, 66, (byte) -128, "enrollment waiting-sas");
            byte[] bArrCopyOf = java.util.Arrays.copyOf(bArr2, 34);
            if (this.helperInstallationId.equals(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(bArrCopyOf, 2)) && java.util.Arrays.equals(this.response.helperNonce, java.util.Arrays.copyOfRange(bArrCopyOf, 18, 34))) {
                return java.security.MessageDigest.isEqual(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.sessionMaster, this.transcript, bArr, bArrCopyOf, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_HELPER_WAITING_SAS), java.util.Arrays.copyOfRange(bArr2, 34, 66));
            }
            return false;
        }

        public byte[] encodeFinalCommit(byte[] bArr) throws java.security.GeneralSecurityException {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr, 66, (byte) 3, "enrollment confirm");
            byte[] bArrAuthCore = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.authCore((byte) 5, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(this.hello, 2), java.util.Arrays.copyOfRange(this.hello, 18, 34));
            return dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.concat(bArrAuthCore, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.sessionMaster, this.transcript, bArr, bArrAuthCore, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_ANDROID_COMMIT));
        }

        public boolean verifyFinalAck(byte[] bArr, byte[] bArr2, byte[] bArr3) throws java.security.GeneralSecurityException {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr, 66, (byte) 3, "enrollment confirm");
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr2, 66, (byte) 5, "enrollment final commit");
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr3, 66, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.KIND_ENROLLMENT_FINAL_ACK, "enrollment final ack");
            byte[] bArrCopyOf = java.util.Arrays.copyOf(bArr3, 34);
            if (!this.helperInstallationId.equals(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(bArrCopyOf, 2)) || !java.util.Arrays.equals(this.response.helperNonce, java.util.Arrays.copyOfRange(bArrCopyOf, 18, 34))) {
                return false;
            }
            return java.security.MessageDigest.isEqual(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.longTermKey, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.DOMAIN, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.sha256(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.concat(this.transcript, bArr, bArr2)), bArrCopyOf, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_HELPER_COMMIT_ACK), java.util.Arrays.copyOfRange(bArr3, 34, 66));
        }

        public byte[] transactionId(byte[] bArr, byte[] bArr2) throws java.security.GeneralSecurityException {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr, 66, (byte) 3, "enrollment confirm");
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr2, 66, (byte) 5, "enrollment final commit");
            return dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.sha256(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.concat(this.transcript, bArr, bArr2));
        }

        public byte[] copyLongTermKey() {
            return (byte[]) this.longTermKey.clone();
        }

        public void destroy() {
            java.util.Arrays.fill(this.sessionMaster, (byte) 0);
            java.util.Arrays.fill(this.longTermKey, (byte) 0);
        }
    }

    public static final class RoutineSession {
        private final byte[] hello;
        public final java.util.UUID helperInstallationId;
        private final byte[] longTermKey;
        private final byte[] proof;

        private RoutineSession(byte[] bArr, byte[] bArr2, java.util.UUID uuid, byte[] bArr3) {
            this.hello = (byte[]) bArr.clone();
            this.proof = (byte[]) bArr2.clone();
            this.helperInstallationId = uuid;
            this.longTermKey = (byte[]) bArr3.clone();
        }

        public byte[] encodeConfirm() throws java.security.GeneralSecurityException {
            byte[] bArrAuthCore = dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.authCore((byte) 4, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(this.hello, 2), java.util.Arrays.copyOfRange(this.hello, 18, 34));
            return dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.concat(bArrAuthCore, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.longTermKey, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.DOMAIN, this.hello, this.proof, bArrAuthCore, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_ANDROID_ROUTINE_CONFIRM));
        }

        public boolean verifyAck(byte[] bArr, byte[] bArr2) throws java.security.GeneralSecurityException {
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr, 66, (byte) 4, "routine confirm");
            dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.requireFrame(bArr2, 66, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.KIND_ROUTINE_ACK, "routine ack");
            byte[] bArrCopyOf = java.util.Arrays.copyOf(this.proof, 34);
            byte[] bArrCopyOf2 = java.util.Arrays.copyOf(bArr2, 34);
            if (this.helperInstallationId.equals(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.uuidAt(bArrCopyOf2, 2)) && java.util.Arrays.equals(java.util.Arrays.copyOfRange(bArrCopyOf, 18, 34), java.util.Arrays.copyOfRange(bArrCopyOf2, 18, 34))) {
                return java.security.MessageDigest.isEqual(dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.hmacSha256(this.longTermKey, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.DOMAIN, this.hello, this.proof, bArr, bArrCopyOf2, dezz.status.widget.phone.transport.v2.IphoneLeEnrollmentProtocolV2.LABEL_HELPER_ACK), java.util.Arrays.copyOfRange(bArr2, 34, 66));
            }
            return false;
        }

        public void destroy() {
            java.util.Arrays.fill(this.longTermKey, (byte) 0);
        }
    }

    public static byte[] encodeUncompressedP256(java.security.PublicKey publicKey) {
        if (!(publicKey instanceof java.security.interfaces.ECPublicKey)) {
            throw new java.lang.IllegalArgumentException("P-256 EC public key required");
        }
        java.security.interfaces.ECPublicKey eCPublicKey = (java.security.interfaces.ECPublicKey) publicKey;
        try {
            java.security.spec.ECParameterSpec eCParameterSpecP256Parameters = p256Parameters();
            if (!sameP256Parameters(eCPublicKey.getParams(), eCParameterSpecP256Parameters) || !validP256Point(eCPublicKey.getW(), eCParameterSpecP256Parameters)) {
                throw new java.lang.IllegalArgumentException("secp256r1 public key required");
            }
            java.security.spec.ECPoint w = eCPublicKey.getW();
            byte[] bArr = new byte[65];
            bArr[0] = 4;
            putFixedUnsigned(w.getAffineX(), bArr, 1);
            putFixedUnsigned(w.getAffineY(), bArr, 33);
            return bArr;
        } catch (java.security.GeneralSecurityException e) {
            throw new java.lang.IllegalArgumentException("P-256 parameters unavailable", e);
        }
    }

    public static java.security.PublicKey decodeUncompressedP256(byte[] bArr) throws java.security.GeneralSecurityException {
        if (bArr == null || bArr.length != 65 || bArr[0] != 4) {
            throw new java.security.GeneralSecurityException("invalid uncompressed P-256 public key");
        }
        java.security.spec.ECParameterSpec eCParameterSpecP256Parameters = p256Parameters();
        java.security.spec.ECPoint eCPoint = new java.security.spec.ECPoint(new java.math.BigInteger(1, java.util.Arrays.copyOfRange(bArr, 1, 33)), new java.math.BigInteger(1, java.util.Arrays.copyOfRange(bArr, 33, 65)));
        if (!validP256Point(eCPoint, eCParameterSpecP256Parameters)) {
            throw new java.security.GeneralSecurityException("P-256 public point is out of range/off curve");
        }
        return java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.ECPublicKeySpec(eCPoint, eCParameterSpecP256Parameters));
    }

    private static java.security.spec.ECParameterSpec p256Parameters() throws java.security.GeneralSecurityException {
        java.security.AlgorithmParameters algorithmParameters = java.security.AlgorithmParameters.getInstance("EC");
        algorithmParameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return (java.security.spec.ECParameterSpec) algorithmParameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
    }

    private static boolean validP256Point(java.security.spec.ECPoint eCPoint, java.security.spec.ECParameterSpec eCParameterSpec) {
        if (eCPoint != null && !java.security.spec.ECPoint.POINT_INFINITY.equals(eCPoint) && eCParameterSpec != null) {
            java.security.spec.ECField field = eCParameterSpec.getCurve().getField();
            if (field instanceof java.security.spec.ECFieldFp) {
                java.math.BigInteger p = ((java.security.spec.ECFieldFp) field).getP();
                java.math.BigInteger affineX = eCPoint.getAffineX();
                java.math.BigInteger affineY = eCPoint.getAffineY();
                if (affineX != null && affineY != null && affineX.signum() >= 0 && affineY.signum() >= 0 && affineX.compareTo(p) < 0 && affineY.compareTo(p) < 0) {
                    return affineY.multiply(affineY).mod(p).equals(affineX.multiply(affineX).multiply(affineX).add(eCParameterSpec.getCurve().getA().multiply(affineX)).add(eCParameterSpec.getCurve().getB()).mod(p));
                }
            }
        }
        return false;
    }

    private static boolean sameP256Parameters(java.security.spec.ECParameterSpec eCParameterSpec, java.security.spec.ECParameterSpec eCParameterSpec2) {
        return eCParameterSpec != null && eCParameterSpec2 != null && eCParameterSpec.getCofactor() == eCParameterSpec2.getCofactor() && eCParameterSpec.getOrder().equals(eCParameterSpec2.getOrder()) && eCParameterSpec.getGenerator().equals(eCParameterSpec2.getGenerator()) && eCParameterSpec.getCurve().getA().equals(eCParameterSpec2.getCurve().getA()) && eCParameterSpec.getCurve().getB().equals(eCParameterSpec2.getCurve().getB()) && eCParameterSpec.getCurve().getField().equals(eCParameterSpec2.getCurve().getField());
    }

    public static byte[] authCore(byte b, java.util.UUID uuid, byte[] bArr) {
        java.nio.ByteBuffer byteBufferAllocate = java.nio.ByteBuffer.allocate(34);
        byteBufferAllocate.put((byte) 1).put(b);
        putUuid(byteBufferAllocate, uuid);
        byteBufferAllocate.put(bArr);
        return byteBufferAllocate.array();
    }

    private static byte[] hkdfSha256(byte[] bArr, byte[] bArr2, byte[] bArr3, int i) throws java.security.GeneralSecurityException {
        if (bArr2 == null) {
            bArr2 = new byte[32];
        }
        byte[] bArrHmacSha256 = hmacSha256(bArr2, bArr);
        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream(i);
        byte[] bArrHmacSha257 = new byte[0];
        int i2 = 1;
        while (byteArrayOutputStream.size() < i) {
            bArrHmacSha257 = hmacSha256(bArrHmacSha256, bArrHmacSha257, bArr3, new byte[]{(byte) i2});
            byteArrayOutputStream.write(bArrHmacSha257, 0, java.lang.Math.min(bArrHmacSha257.length, i - byteArrayOutputStream.size()));
            i2++;
        }
        java.util.Arrays.fill(bArrHmacSha256, (byte) 0);
        java.util.Arrays.fill(bArrHmacSha257, (byte) 0);
        return byteArrayOutputStream.toByteArray();
    }

    public static byte[] hmacSha256(byte[] bArr, byte[]... bArr2) throws java.security.GeneralSecurityException {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(bArr, "HmacSHA256"));
        for (byte[] bArr3 : bArr2) {
            mac.update(bArr3);
        }
        return mac.doFinal();
    }

    public static byte[] sha256(byte[] bArr) throws java.security.GeneralSecurityException {
        return java.security.MessageDigest.getInstance("SHA-256").digest(bArr);
    }

    public static void requireFrame(byte[] bArr, int i, byte b, java.lang.String str) {
        if (bArr == null || bArr.length != i || bArr[0] != 1 || bArr[1] != b) {
            throw new java.lang.IllegalArgumentException("invalid " + str + " frame");
        }
    }

    private static void requireKey(byte[] bArr) {
        if (bArr == null || bArr.length != 32 || allZero(bArr)) {
            throw new java.lang.IllegalArgumentException("non-zero 256-bit enrollment key required");
        }
    }

    private static void requireNonce(byte[] bArr, java.lang.String str) {
        if (bArr == null || bArr.length != 16 || allZero(bArr)) {
            throw new java.lang.IllegalArgumentException(str + " must be 16 non-zero bytes");
        }
    }

    private static void requireNonZeroUuid(java.util.UUID uuid, java.lang.String str) {
        if (uuid == null || (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0)) {
            throw new java.lang.IllegalArgumentException(str + " must be non-zero");
        }
    }

    public static java.util.UUID uuidAt(byte[] bArr, int i) {
        java.nio.ByteBuffer byteBufferWrap = java.nio.ByteBuffer.wrap(bArr, i, 16);
        return new java.util.UUID(byteBufferWrap.getLong(), byteBufferWrap.getLong());
    }

    private static java.util.UUID readUuid(java.nio.ByteBuffer byteBuffer) {
        return new java.util.UUID(byteBuffer.getLong(), byteBuffer.getLong());
    }

    private static void putUuid(java.nio.ByteBuffer byteBuffer, java.util.UUID uuid) {
        byteBuffer.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
    }

    private static void putFixedUnsigned(java.math.BigInteger bigInteger, byte[] bArr, int i) {
        byte[] byteArray = bigInteger.toByteArray();
        int length = byteArray.length > 32 ? byteArray.length - 32 : 0;
        int iMin = java.lang.Math.min(32, byteArray.length);
        java.lang.System.arraycopy(byteArray, length, bArr, (i + 32) - iMin, iMin);
    }

    private static boolean allZero(byte[] bArr) {
        int i = 0;
        for (byte b : bArr) {
            i |= b;
        }
        return i == 0;
    }

    public static byte[] concat(byte[]... bArr) {
        int length = 0;
        for (byte[] bArr2 : bArr) {
            length += bArr2.length;
        }
        byte[] bArr3 = new byte[length];
        int length2 = 0;
        for (byte[] bArr4 : bArr) {
            java.lang.System.arraycopy(bArr4, 0, bArr3, length2, bArr4.length);
            length2 += bArr4.length;
        }
        return bArr3;
    }

    private static byte[] utf8(java.lang.String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
