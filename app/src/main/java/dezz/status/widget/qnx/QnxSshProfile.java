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
        QnxSshProfile profile = loadOptional(context);
        if (profile == null) throw new IOException("Профиль SSH для QNX не настроен.");
        return profile;
    }
    /** Missing profile selects the existing direct qconn route. Invalid profiles remain errors. */
    public static synchronized QnxSshProfile loadOptional(Context context) throws IOException {
        AtomicFile profile = file(context);
        if (!profile.getBaseFile().exists() && !new File(profile.getBaseFile() + ".bak").exists()) return null;
        Properties values = new Properties();
        try (InputStream input = profile.openRead()) { values.load(input); }
        return new QnxSshProfile(values.getProperty("user"), values.getProperty("pin"));
    }
    public static synchronized void useDirect(Context context) throws IOException {
        AtomicFile profile = file(context);
        profile.delete();
        if (profile.getBaseFile().exists() || new File(profile.getBaseFile() + ".bak").exists())
            throw new IOException("Не удалось переключить QNX на прямое подключение.");
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
