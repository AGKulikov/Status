package dezz.status.widget.qnx;

import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.SocketFactory;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.UserAuthNoneFactory;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.junit.*;
import static org.junit.Assert.*;

public class QnxSshTest {
    private SshServer server;
    private KeyPair client, host;
    private String pin;
    private final AtomicInteger keyRequests = new AtomicInteger();

    @Before public void setup() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048); client = rsa.generateKeyPair(); host = rsa.generateKeyPair();
        String[] line = QnxSsh.publicLine((RSAPublicKey)host.getPublic()).split(" ");
        pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(line[1])));
        server = SshServer.setUpDefaultServer(); server.setHost("127.0.0.1"); server.setPort(0);
        server.setKeyPairProvider(session -> Collections.singletonList(host));
        server.setUserAuthFactories(Collections.singletonList(UserAuthPublicKeyFactory.INSTANCE));
        server.setPublickeyAuthenticator((user,key,session) -> {
            keyRequests.incrementAndGet();
            return "operator".equals(user) && client.getPublic().equals(key);
        });
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
    }

    @After public void close() throws Exception { if (server != null) server.stop(true); }

    private QnxSsh open(String user, String fingerprint) throws Exception {
        return QnxSsh.connect(new Proxy() {
            Socket socket;
            public void connect(SocketFactory unused, String host, int port, int timeout) throws IOException {
                assertEquals(QnxSsh.HOST,host); assertEquals(22,port);
                socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(10000);
            }
            public Socket getSocket() { return socket; }
            public InputStream getInputStream() { try { return socket.getInputStream(); } catch (IOException e) { throw new UncheckedIOException(e); } }
            public OutputStream getOutputStream() { try { return socket.getOutputStream(); } catch (IOException e) { throw new UncheckedIOException(e); } }
            public void close() { if (socket!=null) try { socket.close(); } catch (IOException ignored) {} }
        }, user, fingerprint, client);
    }

    @Test public void rejectsWrongHostBeforeOfferingClientKey() throws Exception {
        server.start();
        String wrong = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        assertTrue(assertThrows(IOException.class, () -> open("operator", wrong)).getMessage().contains("не совпал"));
        assertEquals(0,keyRequests.get());
    }
    @Test public void requiresAcceptedClientKey() throws Exception {
        server.start();
        assertThrows(IOException.class, () -> open("unknown", pin));
        assertTrue(keyRequests.get() > 0);
    }
    @Test public void rejectsSshNoneEvenOnPinnedServer() throws Exception {
        server.setUserAuthFactories(Collections.singletonList(UserAuthNoneFactory.INSTANCE)); server.start();
        assertTrue(assertThrows(IOException.class, () -> open("operator", pin)).getMessage().contains("не подтвердила"));
        assertEquals(0,keyRequests.get());
    }
    @Test public void reportsForwardDeniedAfterSuccessfulAuthentication() throws Exception {
        server.start();
        try (QnxSsh session = open("operator",pin)) {
            assertTrue(assertThrows(IOException.class, session::openDiagnostics).getMessage().contains("Канал"));
        }
    }
    @Test public void probeReadsOnlyGreetingOnAuthenticatedChannel() throws Exception {
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE); server.start();
        try (ServerSocket greeting = new ServerSocket(8000,1,InetAddress.getByName("127.0.0.1"))) {
            greeting.setSoTimeout(10000);
            CompletableFuture<Integer> request = CompletableFuture.supplyAsync(() -> {
                try (Socket channel = greeting.accept()) {
                    channel.setSoTimeout(10000);
                    channel.getOutputStream().write("QCONN\r\n".getBytes("US-ASCII"));
                    return channel.getInputStream().read();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            try (QnxSsh session = open("operator",pin); QnxSsh.Forward forward = session.openDiagnostics()) {
                QnxSsh.verifyGreeting(forward.input);
            }
            assertEquals(-1,request.get(12,TimeUnit.SECONDS).intValue());
        }
    }
    @Test public void rejectsInvalidGreetingOrIncompleteFingerprint() {
        assertThrows(IOException.class, () -> QnxSsh.verifyGreeting(new ByteArrayInputStream("QCONN?".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> QnxSsh.decodePin("SHA256:1234"));
    }
}
