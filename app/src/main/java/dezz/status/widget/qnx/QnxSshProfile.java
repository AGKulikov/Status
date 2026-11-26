package dezz.status.widget.qnx;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.util.Properties;

public final class QnxSshProfile {
    private static final String ALIAS = "Natro.QNX.SSH.v1";
    public final String user, pin;
    public QnxSshProfile(String user, String pin) {
        this.user = QnxSsh.validateUser(user); QnxSsh.decodePin(pin); this.pin = pin;
    }
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(new File(context.getNoBackupFilesDir(), "qnx-ssh"), "profile"));
    }
    public static synchronized QnxSshProfile load(Context context) throws IOException {
        Properties values = new Properties();
        try (InputStream input = file(context).openRead()) { values.load(input); }
        catch (FileNotFoundException empty) { throw new IOException("Откройте «Доступ к QNX» и укажите учётную запись и отпечаток приборки."); }
        return new QnxSshProfile(values.getProperty("user"), values.getProperty("pin"));
    }
    public static synchronized void save(Context context, QnxSshProfile profile) throws IOException {
        AtomicFile file = file(context);
        File parent = file.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Не удалось сохранить параметры QNX.");
        Properties values = new Properties();
        values.setProperty("user", profile.user); values.setProperty("pin", profile.pin);
        FileOutputStream out = file.startWrite();
        try { values.store(out, null); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
    public static synchronized KeyPair clientKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512).setKeySize(3072).build());
            return generator.generateKeyPair();
        }
        return new KeyPair(keyStore.getCertificate(ALIAS).getPublicKey(), (PrivateKey)keyStore.getKey(ALIAS,null));
    }
}
