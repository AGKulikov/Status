/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM wrapper backed by Android Keystore; exported settings never contain its ciphertext. */
final class SecretStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PREFIX = "v1:";

    private SecretStore() {}

    static String encrypt(@NonNull Context context, @NonNull String plaintext) throws Exception {
        if (plaintext.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(context));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String body = Base64.encodeToString(cipher.doFinal(
                plaintext.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return PREFIX + iv + ":" + body;
    }

    static String decrypt(@NonNull Context context, @NonNull String stored) throws Exception {
        if (stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored; // one-shot compatibility with old plaintext
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(context),
                new GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    private static SecretKey key(Context context) throws Exception {
        String alias = context.getPackageName() + ".mqtt.credentials";
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(alias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
