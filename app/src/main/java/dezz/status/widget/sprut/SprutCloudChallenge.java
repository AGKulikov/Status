/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Answers the proof-of-password challenge used by Sprut's current cloud client.
 *
 * <p>The server-provided salt and KDF settings are public authentication parameters. Password
 * bytes and derived keys remain local. The temporary key is scoped to one authentication dialogue.</p>
 */
final class SprutCloudChallenge implements AutoCloseable {
    private static final int DERIVED_KEY_BYTES = 32;
    private static final int MAX_QUESTION_DATA_CHARS = 16_384;
    private static final int MAX_BINARY_FIELD_BYTES = 4_096;
    private static final int MAX_MEMORY_KIB = 262_144;
    private static final int MAX_ITERATIONS = 16;
    private static final int MAX_PARALLELISM = 16;

    private String password;
    private byte[] cachedKey;
    private byte[] cachedSalt;
    private KdfSettings cachedSettings;

    SprutCloudChallenge(String password) {
        this.password = Objects.requireNonNull(password, "password");
    }

    static String answer(String password, String questionData) throws IOException {
        try (SprutCloudChallenge dialogue = new SprutCloudChallenge(password)) {
            return dialogue.sign(questionData);
        }
    }

    String sign(String questionData) throws IOException {
        if (questionData == null || questionData.isEmpty()
                || questionData.length() > MAX_QUESTION_DATA_CHARS) {
            throw invalid("question data is missing or too large");
        }

        final JSONObject data;
        try {
            data = new JSONObject(questionData);
        } catch (JSONException malformed) {
            throw invalid("question data is not valid JSON", malformed);
        }

        byte[] rootSalt = decode(data, "rootSalt");
        byte[] challenge = decode(data, "challenge");
        KdfSettings settings = KdfSettings.parse(requiredString(data, "kdfParams"));
        if (rootSalt.length < 8 || rootSalt.length > MAX_BINARY_FIELD_BYTES) {
            throw invalid("rootSalt length is outside the supported range");
        }
        if (challenge.length == 0 || challenge.length > MAX_BINARY_FIELD_BYTES) {
            throw invalid("challenge length is outside the supported range");
        }

        byte[] argonResult = keyFor(rootSalt, settings);
        byte[] signature = new byte[Ed25519.SIGNATURE_SIZE];
        try {
            Ed25519.sign(argonResult, 0, challenge, 0, challenge.length, signature, 0);
            return Base64.getEncoder().encodeToString(signature);
        } catch (RuntimeException failure) {
            throw invalid("cryptographic proof failed", failure);
        } finally {
            Arrays.fill(argonResult, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }

    private byte[] keyFor(byte[] salt, KdfSettings settings) throws IOException {
        byte[] passwordBytes;
        synchronized (this) {
            if (password == null) throw invalid("authentication dialogue closed");
            if (password.isEmpty()) throw invalid("password is empty");
            if (settings.sameAs(cachedSettings) && Arrays.equals(cachedSalt, salt)) {
                return cachedKey.clone();
            }
            passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        }
        // Derivation can be slow. Closing a socket on the UI thread must never wait for it.
        byte[] key = new byte[DERIVED_KEY_BYTES];
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(settings.memoryKib)
                    .withIterations(settings.iterations)
                    .withParallelism(settings.parallelism)
                    .withSalt(salt)
                    .build();
            Argon2BytesGenerator argon2 = new Argon2BytesGenerator();
            argon2.init(parameters);
            argon2.generateBytes(passwordBytes, key);
            synchronized (this) {
                if (password == null) throw invalid("authentication dialogue closed");
                if (cachedKey != null) Arrays.fill(cachedKey, (byte) 0);
                cachedKey = key.clone();
                cachedSalt = salt;
                cachedSettings = settings;
                return key;
            }
        } catch (IOException | RuntimeException failure) {
            Arrays.fill(key, (byte) 0);
            if (failure instanceof IOException) throw (IOException) failure;
            throw invalid("cryptographic proof failed", failure);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    @Override public synchronized void close() {
        if (cachedKey != null) Arrays.fill(cachedKey, (byte) 0);
        cachedKey = null;
        cachedSalt = null;
        cachedSettings = null;
        password = null;
    }

    private static byte[] decode(JSONObject data, String name) throws IOException {
        String encoded = requiredString(data, name);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > MAX_BINARY_FIELD_BYTES) {
                throw invalid(name + " is too large");
            }
            return decoded;
        } catch (IllegalArgumentException malformed) {
            throw invalid(name + " is not valid base64", malformed);
        }
    }

    private static String requiredString(JSONObject data, String name) throws IOException {
        String value = data.optString(name, "");
        if (value.isEmpty()) throw invalid("missing " + name);
        return value;
    }

    private static IOException invalid(String detail) {
        return new IOException("Invalid Sprut.hub password challenge: " + detail);
    }

    private static IOException invalid(String detail, Throwable cause) {
        return new IOException("Invalid Sprut.hub password challenge: " + detail, cause);
    }

    private static final class KdfSettings {
        final int memoryKib;
        final int iterations;
        final int parallelism;

        KdfSettings(int memoryKib, int iterations, int parallelism) {
            this.memoryKib = memoryKib;
            this.iterations = iterations;
            this.parallelism = parallelism;
        }

        boolean sameAs(KdfSettings other) {
            return other != null && memoryKib == other.memoryKib
                    && iterations == other.iterations && parallelism == other.parallelism;
        }

        static KdfSettings parse(String raw) throws IOException {
            Map<String, Integer> values = new LinkedHashMap<>();
            for (String entry : raw.split(",")) {
                String[] pair = entry.trim().split("=", -1);
                if (pair.length != 2) throw invalid("malformed kdfParams");
                String key = pair[0].trim();
                if (!"m".equals(key) && !"t".equals(key) && !"p".equals(key)) continue;
                if (values.containsKey(key)) throw invalid("duplicate kdfParams field " + key);
                try {
                    values.put(key, Integer.parseInt(pair[1].trim()));
                } catch (NumberFormatException malformed) {
                    throw invalid("non-integer kdfParams field " + key, malformed);
                }
            }
            int memory = values.getOrDefault("m", 0);
            int iterations = values.getOrDefault("t", 0);
            int parallelism = values.getOrDefault("p", 0);
            if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
                throw invalid("parallelism is outside the supported range");
            }
            if (iterations < 1 || iterations > MAX_ITERATIONS) {
                throw invalid("iterations are outside the supported range");
            }
            if (memory < 8 * parallelism || memory > MAX_MEMORY_KIB) {
                throw invalid("memory cost is outside the supported range");
            }
            return new KdfSettings(memory, iterations, parallelism);
        }
    }
}
