package dezz.status.widget.qnx;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** One pinned, client-authenticated SSH session for one diagnostic collection. */
public final class QnxSsh implements AutoCloseable {
    public static final String HOST = "198.18.34.2";
    public static final int PORT = 22;
    private static final int TIMEOUT = 10_000;
    private final Session session;

    private QnxSsh(Session session) { this.session = session; }

    public static String validateUser(String user) {
        if (user == null || !user.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Введите имя учётной записи SSH на приборке.");
        return user;
    }

    public static byte[] decodePin(String pin) {
        if (pin == null || !pin.matches("SHA256:[A-Za-z0-9+/]{43}"))
            throw new IllegalArgumentException("Введите отпечаток открытого ключа приборки в формате SHA256.");
        byte[] raw = Base64.getDecoder().decode(pin.substring(7));
        if (!Base64.getEncoder().withoutPadding().encodeToString(raw).equals(pin.substring(7)))
            throw new IllegalArgumentException("Некорректный отпечаток SSH.");
        return raw;
    }

    private static byte[] fields(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (byte[] part : parts) { out.writeInt(part.length); out.write(part); }
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        return bytes.toByteArray();
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] blob(RSAPublicKey key) {
        return fields(ascii("ssh-rsa"), key.getPublicExponent().toByteArray(), key.getModulus().toByteArray());
    }

    public static String publicLine(RSAPublicKey publicKey) {
        return "ssh-rsa " + Base64.getEncoder().encodeToString(blob(publicKey)) + " Natro-QNX";
    }

    private static final class ClientKey implements Identity {
        final KeyPair keys;
        boolean signed;
        ClientKey(KeyPair keys) { this.keys = keys; }
        public byte[] getSignature(byte[] data, String algorithm) {
            String jca;
            if ("rsa-sha2-512".equals(algorithm)) jca = "SHA512withRSA";
            else if ("rsa-sha2-256".equals(algorithm)) jca = "SHA256withRSA";
            else return null;
            try {
                Signature signature = Signature.getInstance(jca);
                signature.initSign(keys.getPrivate());
                signature.update(data);
                byte[] result = fields(ascii(algorithm), signature.sign());
                signed = true;
                return result;
            } catch (GeneralSecurityException error) { throw new IllegalStateException("Не удалось использовать ключ Natro.", error); }
        }
        public byte[] getPublicKeyBlob() { return blob((RSAPublicKey) keys.getPublic()); }
        public byte[] getSignature(byte[] data) { return null; }
        public String getName() { return "Natro-QNX"; }
        public String getAlgName() { return "ssh-rsa"; }
        public boolean isEncrypted() { return false; }
        public boolean setPassphrase(byte[] unused) { return false; }
        public void clear() {}
    }

    private static final class PinnedHost implements HostKeyRepository {
        final byte[] expected;
        boolean matched, rejected;
        PinnedHost(byte[] expected) { this.expected = expected; }
        public int check(String host, byte[] key) {
            try {
                matched = HOST.equals(host) && MessageDigest.isEqual(expected,
                        MessageDigest.getInstance("SHA-256").digest(key));
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            rejected = !matched;
            return matched ? OK : CHANGED;
        }
        public void add(HostKey key, UserInfo ignored) { throw new UnsupportedOperationException(); }
        public void remove(String host, String type) { throw new UnsupportedOperationException(); }
        public void remove(String host, String type, byte[] key) { throw new UnsupportedOperationException(); }
        public String getKnownHostsRepositoryID() { return "QNX"; }
        public HostKey[] getHostKey() { return new HostKey[0]; }
        public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    public static QnxSsh connect(Proxy route, String user, String pin, KeyPair keys) throws Exception {
        validateUser(user);
        PinnedHost host = new PinnedHost(decodePin(pin));
        ClientKey client = new ClientKey(keys);
        JSch ssh = new JSch();
        ssh.setHostKeyRepository(host);
        ssh.addIdentity(client, null);
        Session session = ssh.getSession(user, HOST, PORT);
        session.setProxy(route);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("PreferredAuthentications", "publickey");
        session.setConfig("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256");
        boolean ready = false;
        try {
            session.connect(TIMEOUT);
            // A server may accept the SSH "none" probe. That does not authorize this key.
            if (!host.matched || !client.signed)
                throw new IOException("Приборка не подтвердила отдельный ключ Natro.");
            ready = true;
            return new QnxSsh(session);
        } catch (JSchException error) {
            String message = host.rejected ? "Отпечаток SSH приборки не совпал."
                    : host.matched ? "Вход по ключу Natro не завершён. Проверьте учётную запись и разрешение ключа."
                    : "SSH приборки недоступен или не удалось согласовать защищённое соединение.";
            throw new IOException(message, error);
        } finally { if (!ready) session.disconnect(); }
    }

    public static final class Forward implements AutoCloseable {
        private final ChannelDirectTCPIP channel;
        public final InputStream input;
        public final OutputStream output;
        private Forward(ChannelDirectTCPIP channel, InputStream input, OutputStream output) {
            this.channel = channel; this.input = input; this.output = output;
        }
        public void close() { channel.disconnect(); }
    }

    public Forward openDiagnostics() throws IOException {
        ChannelDirectTCPIP channel = null;
        try {
            channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            channel.setHost("127.0.0.1");
            channel.setPort(8000);
            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();
            channel.connect(TIMEOUT);
            if (!channel.isConnected()) throw new IOException("SSH channel closed");
            return new Forward(channel, in, out);
        } catch (Exception error) {
            if (channel != null) channel.disconnect();
            throw new IOException("Вход по SSH выполнен. Канал к диагностической службе QNX не открылся.", error);
        }
    }

    public static void verifyGreeting(InputStream input) throws IOException {
        for (byte expected : ascii("QCONN"))
            if (input.read() != expected) throw new IOException("Диагностическая служба QNX не подтверждена.");
        int end = input.read();
        if (end == '\r') end = input.read();
        if (end != '\n') throw new IOException("Неверное приветствие диагностической службы QNX.");
    }

    public void close() { session.disconnect(); }
}
