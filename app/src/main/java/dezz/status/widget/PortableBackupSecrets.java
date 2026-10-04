/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Only used inside the password-encrypted backup pipeline; never logs secret content. */
public final class PortableBackupSecrets {
    private static final String APNS_ALIAS="natro_live_activity_apns_p8_v1";
    private PortableBackupSecrets() {}
    public static String unwrapPreference(Context context,String stored)throws Exception {
        if(!stored.startsWith("v1:"))throw new IOException("Unsupported secret encoding");
        String value=SecretStore.decrypt(context,stored);
        if(value.isEmpty())throw new IOException("Encrypted credential could not be read");
        return value;
    }
    public static String wrapPreference(Context context,String plaintext)throws Exception {
        return SecretStore.encrypt(context,plaintext);
    }
    public static byte[] unwrapApns(byte[] encoded)throws Exception {
        if(encoded.length<31||encoded[0]!=1)throw new IOException("Invalid APNs credential");
        int size=encoded[1]&255;
        if(size<12||size>16||encoded.length<=size+2)throw new IOException("Invalid APNs credential");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        Key key=store.getKey(APNS_ALIAS,null);
        if(key==null)throw new IOException("APNs wrapping key is unavailable");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(encoded,2,2+size)));
        byte[] plain=cipher.doFinal(Arrays.copyOfRange(encoded,2+size,encoded.length));
        validateApns(plain);return plain;
    }
    public static byte[] wrapApns(byte[] plain)throws Exception {
        validateApns(plain);
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        Key key=store.getKey(APNS_ALIAS,null);
        if(key==null) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(APNS_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key=generator.generateKey();
        }
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(plain),output=new byte[2+iv.length+encrypted.length];
        output[0]=1;output[1]=(byte)iv.length;System.arraycopy(iv,0,output,2,iv.length);
        System.arraycopy(encrypted,0,output,2+iv.length,encrypted.length);return output;
    }
    private static void validateApns(byte[] plain)throws Exception {
        if(plain.length==0||plain.length>16384)throw new IOException("Invalid APNs key size");
        String pem=new String(plain,java.nio.charset.StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
        PrivateKey key=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        if(!(key instanceof java.security.interfaces.ECPrivateKey)
                ||((java.security.interfaces.ECPrivateKey)key).getParams().getOrder().bitLength()!=256)
            throw new IOException("APNs key must use P-256");
    }
}
