/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/** Implements Yandex' documented SHA256withRSA signature for Navigator URL schemes. */
public final class YandexNavigatorUrlSigner {
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private YandexNavigatorUrlSigner() {}

    @NonNull
    public static Uri sign(@NonNull Uri unsigned, @NonNull String clientId,
                           @NonNull String privateKeyPem) throws Exception {
        String client = clientId.trim();
        if (client.isEmpty()) throw new IllegalArgumentException("Yandex client is empty");
        Uri identified = unsigned.buildUpon().appendQueryParameter("client", client).build();
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey(privateKeyPem));
        signature.update(identified.toString().getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
        return identified.buildUpon().appendQueryParameter("signature", encoded).build();
    }

    public static boolean isValid(@NonNull String clientId, @NonNull String privateKeyPem) {
        if (clientId.trim().isEmpty() || privateKeyPem.trim().isEmpty()) return false;
        try {
            privateKey(privateKeyPem);
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    @NonNull
    private static PrivateKey privateKey(@NonNull String pem) throws Exception {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String compact = pem
                .replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.decode(compact, Base64.DEFAULT);
        byte[] encoded = pkcs1 ? wrapPkcs1InPkcs8(decoded) : decoded;
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception first) {
            if (pkcs1) throw first;
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1InPkcs8(decoded)));
        }
    }

    @NonNull
    private static byte[] wrapPkcs1InPkcs8(@NonNull byte[] pkcs1) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02);
        body.write(0x01);
        body.write(0x00);
        body.write(RSA_ALGORITHM_IDENTIFIER, 0, RSA_ALGORITHM_IDENTIFIER.length);
        body.write(0x04);
        writeLength(body, pkcs1.length);
        body.write(pkcs1, 0, pkcs1.length);

        byte[] contents = body.toByteArray();
        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.write(0x30);
        writeLength(sequence, contents.length);
        sequence.write(contents, 0, contents.length);
        return sequence.toByteArray();
    }

    private static void writeLength(@NonNull ByteArrayOutputStream output, int length) {
        if (length < 0x80) {
            output.write(length);
        } else if (length <= 0xff) {
            output.write(0x81);
            output.write(length);
        } else if (length <= 0xffff) {
            output.write(0x82);
            output.write(length >>> 8);
            output.write(length);
        } else {
            output.write(0x83);
            output.write(length >>> 16);
            output.write(length >>> 8);
            output.write(length);
        }
    }
}
