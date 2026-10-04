/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.security.keystore.KeyGenParameterSpec;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import javax.crypto.*;

/** In-memory key isolation fixture; AES-GCM and EC parsing still use actual JCE providers. */
public final class TestAndroidKeyStore implements AutoCloseable {
    private final Provider previous=Security.getProvider("AndroidKeyStore");
    public TestAndroidKeyStore(){
        keys.clear();Security.removeProvider("AndroidKeyStore");
        Provider provider=new Provider("AndroidKeyStore",1.0,"Test-only volatile keys"){};
        provider.put("KeyStore.AndroidKeyStore",Store.class.getName());provider.put("KeyGenerator.AES",Generator.class.getName());
        Security.addProvider(provider);
    }
    private static final Map<String,Key> keys=new HashMap<>();
    public void erase(){keys.clear();}
    @Override public void close(){keys.clear();Security.removeProvider("AndroidKeyStore");if(previous!=null)Security.addProvider(previous);}
    public static final class Generator extends KeyGeneratorSpi {
        private String alias;
        @Override protected void engineInit(SecureRandom random){throw new UnsupportedOperationException();}
        @Override protected void engineInit(int bits,SecureRandom random){throw new UnsupportedOperationException();}
        @Override protected void engineInit(AlgorithmParameterSpec spec,SecureRandom random){alias=((KeyGenParameterSpec)spec).getKeystoreAlias();}
        @Override protected SecretKey engineGenerateKey(){try{KeyGenerator generator=KeyGenerator.getInstance("AES","SunJCE");generator.init(256);SecretKey key=generator.generateKey();keys.put(alias,key);return key;}catch(Exception failure){throw new IllegalStateException(failure);}}
    }
    public static final class Store extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias,char[] password){return keys.get(alias);}
        @Override public Certificate[] engineGetCertificateChain(String alias){return null;}
        @Override public Certificate engineGetCertificate(String alias){return null;}
        @Override public Date engineGetCreationDate(String alias){return new Date(0);}
        @Override public void engineSetKeyEntry(String alias,Key key,char[] password,Certificate[] chain){keys.put(alias,key);}
        @Override public void engineSetKeyEntry(String alias,byte[] key,Certificate[] chain){throw new UnsupportedOperationException();}
        @Override public void engineSetCertificateEntry(String alias,Certificate cert){throw new UnsupportedOperationException();}
        @Override public void engineDeleteEntry(String alias){keys.remove(alias);}
        @Override public Enumeration<String> engineAliases(){return Collections.enumeration(keys.keySet());}
        @Override public boolean engineContainsAlias(String alias){return keys.containsKey(alias);}
        @Override public int engineSize(){return keys.size();}
        @Override public boolean engineIsKeyEntry(String alias){return keys.containsKey(alias);}
        @Override public boolean engineIsCertificateEntry(String alias){return false;}
        @Override public String engineGetCertificateAlias(Certificate cert){return null;}
        @Override public void engineStore(OutputStream out,char[] password){}
        @Override public void engineLoad(InputStream in,char[] password){}
    }
    public static byte[] apnsPem()throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return ("-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
