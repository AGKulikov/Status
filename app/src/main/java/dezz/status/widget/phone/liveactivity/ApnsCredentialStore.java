/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.liveactivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the user-imported APNs signing key encrypted by Android Keystore. The encrypted blob is
 * placed under no-backup storage and neither the key nor its plaintext is written to preferences,
 * logs, APK resources, source control or Android backup.
 */
public final class ApnsCredentialStore {
    public static final String DEFAULT_TOPIC =
            "ru.natro.kx11ancshelper.push-type.liveactivity";

    private static final String PREFS = "live_activity_apns_metadata_v1";
    private static final String KEY_TEAM = "team";
    private static final String KEY_ID = "keyId";
    private static final String KEY_TOPIC = "topic";
    private static final String KEY_PRODUCTION = "production";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "natro_live_activity_apns_p8_v1";
    private static final String FILE_NAME = "live_activity_apns_p8_v1.bin";
    private static final byte FILE_VERSION = 1;
    private static final int MAX_P8_BYTES = 16 * 1024;

    public static final class Credentials {
        @NonNull public final String teamId;
        @NonNull public final String keyId;
        @NonNull public final String topic;
        public final boolean production;
        @NonNull final byte[] privateKeyPem;

        Credentials(@NonNull String teamId, @NonNull String keyId, @NonNull String topic,
                    boolean production, @NonNull byte[] privateKeyPem) {
            this.teamId = teamId;
            this.keyId = keyId;
            this.topic = topic;
            this.production = production;
            this.privateKeyPem = privateKeyPem;
        }

        @NonNull public String endpoint() {
            return production ? "https://api.push.apple.com"
                    : "https://api.sandbox.push.apple.com";
        }

        @NonNull PrivateKey privateKey() throws Exception {
            return parsePrivateKey(privateKeyPem);
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    public ApnsCredentialStore(@NonNull Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(@NonNull String teamId, @NonNull String keyId,
                                  @NonNull String topic, boolean production,
                                  @NonNull byte[] privateKeyPem) throws Exception {
        String exactTeam = normalizedIdentifier(teamId, "Team ID");
        String exactKey = normalizedIdentifier(keyId, "Key ID");
        String exactTopic = topic.trim();
        if (exactTopic.isEmpty() || exactTopic.length() > 255
                || !exactTopic.endsWith(".push-type.liveactivity")) {
            throw new IllegalArgumentException("Некорректный Live Activity topic");
        }
        if (privateKeyPem.length < 64 || privateKeyPem.length > MAX_P8_BYTES) {
            throw new IllegalArgumentException("Некорректный размер APNs .p8");
        }
        // Reject a wrong/corrupt key before replacing the last working configuration.
        parsePrivateKey(privateKeyPem);
        SecretKey wrappingKey = wrappingKey();
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(privateKeyPem);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(2 + iv.length + encrypted.length);
        encoded.write(FILE_VERSION);
        encoded.write(iv.length);
        encoded.write(iv);
        encoded.write(encrypted);
        writeAtomically(encoded.toByteArray());
        if (!prefs.edit().putString(KEY_TEAM, exactTeam).putString(KEY_ID, exactKey)
                .putString(KEY_TOPIC, exactTopic).putBoolean(KEY_PRODUCTION, production)
                .commit()) {
            throw new IOException("Не удалось сохранить метаданные APNs");
        }
    }

    @Nullable
    public synchronized Credentials load() {
        String team = prefs.getString(KEY_TEAM, "").trim();
        String keyId = prefs.getString(KEY_ID, "").trim();
        String topic = prefs.getString(KEY_TOPIC, DEFAULT_TOPIC).trim();
        if (team.isEmpty() || keyId.isEmpty() || topic.isEmpty()) return null;
        byte[] encoded;
        try {
            encoded = readAll(secretFile());
            if (encoded.length < 3 || encoded[0] != FILE_VERSION) return null;
            int ivLength = encoded[1] & 0xff;
            if (ivLength < 12 || ivLength > 16 || encoded.length <= 2 + ivLength) return null;
            byte[] iv = Arrays.copyOfRange(encoded, 2, 2 + ivLength);
            byte[] ciphertext = Arrays.copyOfRange(encoded, 2 + ivLength, encoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(128, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            parsePrivateKey(plaintext);
            return new Credentials(team, keyId, topic,
                    prefs.getBoolean(KEY_PRODUCTION, false), plaintext);
        } catch (Exception unavailable) {
            return null;
        }
    }

    public synchronized boolean isConfigured() {
        Credentials credentials = load();
        if (credentials == null) return false;
        Arrays.fill(credentials.privateKeyPem, (byte) 0);
        return true;
    }

    @NonNull public String teamId() { return prefs.getString(KEY_TEAM, "").trim(); }
    @NonNull public String keyId() { return prefs.getString(KEY_ID, "").trim(); }
    @NonNull public String topic() {
        String value = prefs.getString(KEY_TOPIC, DEFAULT_TOPIC).trim();
        return value.isEmpty() ? DEFAULT_TOPIC : value;
    }
    public boolean production() { return prefs.getBoolean(KEY_PRODUCTION, false); }

    public synchronized void clear() {
        prefs.edit().clear().commit();
        File secret = secretFile();
        if (secret.isFile() && !secret.delete()) secret.deleteOnExit();
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }

    @NonNull
    static PrivateKey parsePrivateKey(@NonNull byte[] pemBytes) throws Exception {
        String pem = new String(pemBytes, StandardCharsets.US_ASCII).trim();
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (base64.isEmpty()) throw new IllegalArgumentException("APNs .p8 пуст");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("APNs .p8 повреждён", invalid);
        }
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        if (!"EC".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("APNs key должен быть EC P-256");
        }
        if (key instanceof ECPrivateKey
                && ((ECPrivateKey) key).getParams() != null
                && ((ECPrivateKey) key).getParams().getCurve().getField().getFieldSize() != 256) {
            throw new IllegalArgumentException("APNs key должен использовать кривую P-256");
        }
        return key;
    }

    @NonNull
    private SecretKey wrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private void writeAtomically(@NonNull byte[] bytes) throws IOException {
        File target = secretFile();
        File temporary = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Не удалось заменить APNs secret");
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("Не удалось зафиксировать APNs secret");
        }
    }

    @NonNull private File secretFile() {
        return new File(context.getNoBackupFilesDir(), FILE_NAME);
    }

    @NonNull private static byte[] readAll(@NonNull File file) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_P8_BYTES + 128) {
            throw new IOException("APNs secret отсутствует");
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @NonNull private static String normalizedIdentifier(@NonNull String value,
                                                        @NonNull String label) {
        String exact = value.trim().toUpperCase(Locale.ROOT);
        if (!exact.matches("[A-Z0-9]{10}")) {
            throw new IllegalArgumentException(label + " должен содержать 10 символов");
        }
        return exact;
    }
}
