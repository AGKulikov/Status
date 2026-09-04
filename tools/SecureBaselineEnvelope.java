/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Minimal hybrid envelope used to move a reviewed proprietary APK through
 * untrusted storage. The payload key is wrapped for the release certificate;
 * only the matching private key in the protected signing job can open it.
 */
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

public final class SecureBaselineEnvelope {
    private static final byte[] MAGIC =
            "NATROBL1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int BUFFER_BYTES = 128 * 1024;
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private SecureBaselineEnvelope() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "encrypt".equals(args[0])) {
            encrypt(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            return;
        }
        if (args.length == 5 && "decrypt".equals(args[0])) {
            char[] password = requiredPassword();
            try {
                decrypt(Path.of(args[1]), args[2], password,
                        Path.of(args[3]), Path.of(args[4]));
            } finally {
                Arrays.fill(password, '\0');
            }
            return;
        }
        throw new IllegalArgumentException(
                "encrypt <certificate.pem> <input> <output> | "
                        + "decrypt <keystore.jks> <alias> <input> <output>");
    }

    private static void encrypt(Path certificatePath, Path input, Path output)
            throws Exception {
        X509Certificate certificate;
        try (InputStream stream = new BufferedInputStream(
                Files.newInputStream(certificatePath))) {
            certificate = (X509Certificate) CertificateFactory
                    .getInstance("X.509").generateCertificate(stream);
        }
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey payloadKey = generator.generateKey();
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);

        Cipher wrapper = rsaCipher(Cipher.ENCRYPT_MODE,
                certificate.getPublicKey());
        byte[] wrappedKey = wrapper.doFinal(payloadKey.getEncoded());

        Cipher payload = Cipher.getInstance("AES/GCM/NoPadding");
        payload.init(Cipher.ENCRYPT_MODE, payloadKey,
                new GCMParameterSpec(128, nonce));
        try (InputStream source = new BufferedInputStream(
                    Files.newInputStream(input), BUFFER_BYTES);
             DataOutputStream header = new DataOutputStream(
                    new BufferedOutputStream(
                            Files.newOutputStream(output), BUFFER_BYTES))) {
            header.write(MAGIC);
            header.writeInt(wrappedKey.length);
            header.write(wrappedKey);
            header.writeInt(nonce.length);
            header.write(nonce);
            try (CipherOutputStream encrypted =
                         new CipherOutputStream(header, payload)) {
                copy(source, encrypted);
            }
        }
    }

    private static void decrypt(Path keystorePath, String alias,
            char[] password, Path input, Path output) throws Exception {
        KeyStore store = KeyStore.getInstance(
                keystorePath.toFile(), password);
        Key key = store.getKey(alias, password);
        if (!(key instanceof PrivateKey)) {
            throw new IllegalArgumentException(
                    "The selected alias does not contain a private key");
        }

        try (DataInputStream encrypted = new DataInputStream(
                    new BufferedInputStream(
                            Files.newInputStream(input), BUFFER_BYTES))) {
            byte[] magic = encrypted.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IllegalArgumentException("Invalid envelope magic");
            }
            int wrappedLength = encrypted.readInt();
            if (wrappedLength < 128 || wrappedLength > 1024) {
                throw new IllegalArgumentException(
                        "Invalid wrapped-key length");
            }
            byte[] wrappedKey = encrypted.readNBytes(wrappedLength);
            if (wrappedKey.length != wrappedLength) {
                throw new IllegalArgumentException("Truncated wrapped key");
            }
            int nonceLength = encrypted.readInt();
            if (nonceLength != NONCE_BYTES) {
                throw new IllegalArgumentException("Invalid nonce length");
            }
            byte[] nonce = encrypted.readNBytes(nonceLength);
            if (nonce.length != nonceLength) {
                throw new IllegalArgumentException("Truncated nonce");
            }

            Cipher wrapper = rsaCipher(Cipher.DECRYPT_MODE, key);
            byte[] rawPayloadKey = wrapper.doFinal(wrappedKey);
            try {
                Cipher payload = Cipher.getInstance("AES/GCM/NoPadding");
                payload.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(rawPayloadKey, "AES"),
                        new GCMParameterSpec(128, nonce));
                try (CipherInputStream plaintext =
                             new CipherInputStream(encrypted, payload);
                     OutputStream destination = new BufferedOutputStream(
                             Files.newOutputStream(output), BUFFER_BYTES)) {
                    copy(plaintext, destination);
                }
            } finally {
                Arrays.fill(rawPayloadKey, (byte) 0);
            }
        }
    }

    private static Cipher rsaCipher(int mode, Key key) throws Exception {
        Cipher cipher = Cipher.getInstance(
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(mode, key, OAEP_SHA256);
        return cipher;
    }

    private static char[] requiredPassword() {
        String value = System.getenv("KEY_PASSWORD");
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("KEY_PASSWORD is required");
        }
        return value.toCharArray();
    }

    private static void copy(InputStream source, OutputStream destination)
            throws Exception {
        byte[] buffer = new byte[BUFFER_BYTES];
        int count;
        while ((count = source.read(buffer)) != -1) {
            destination.write(buffer, 0, count);
        }
    }
}
