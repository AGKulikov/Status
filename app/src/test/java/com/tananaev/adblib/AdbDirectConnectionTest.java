package com.tananaev.adblib;

import android.app.Application;
import dezz.status.widget.shell.AdbTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AdbDirectConnectionTest {
    private void probe(byte[] response, boolean expected) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    assertEquals(AdbProtocol.CMD_CNXN,
                            AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).command);
                    peer.getOutputStream().write(response);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            assertEquals(expected, AdbTransport.probe(server.getInetAddress().getHostAddress(), server.getLocalPort()));
            responder.get(6, TimeUnit.SECONDS);
        }
    }

    @Test public void discoversReadyDaemon() throws Exception {
        probe(AdbProtocol.generateConnect(), true);
    }
    @Test public void discoversDaemonRequestingKey() throws Exception {
        probe(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_TOKEN, new byte[20]), true);
    }
    @Test public void rejectsMalformedChallenge() throws Exception {
        probe(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_TOKEN, new byte[19]), false);
    }
    @Test public void rejectsNonAdbBanner() throws Exception {
        probe("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII), false);
    }

    private void checkQnx(boolean goodGreeting) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    assertEquals(AdbProtocol.CMD_CNXN,
                            AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).command);
                    peer.getOutputStream().write(AdbProtocol.generateConnect());
                    AdbProtocol.AdbMessage open = AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream());
                    assertEquals(AdbProtocol.CMD_OPEN, open.command);
                    assertEquals("shell:toybox nc -w 30 198.18.34.2 8000\0",
                            new String(open.payload, StandardCharsets.US_ASCII));
                    peer.getOutputStream().write(AdbProtocol.generateReady(1, open.arg0));
                    byte[] greeting = goodGreeting
                            ? new byte[]{'Q','C','O','N','N','\r','\n',(byte)255,(byte)253,34}
                            : "Connection refused\r\n".getBytes(StandardCharsets.US_ASCII);
                    // Split the greeting across real ADB packets to exercise stream buffering.
                    for (byte value : greeting) {
                        peer.getOutputStream().write(AdbProtocol.generateWrite(1, open.arg0, new byte[]{value}));
                        assertEquals(AdbProtocol.CMD_OKAY,
                                AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).command);
                    }
                    // The access check closes its stream without any file request.
                    assertEquals(AdbProtocol.CMD_CLSE,
                            AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).command);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try (AdbTransport transport = AdbTransport.connect(RuntimeEnvironment.getApplication(),
                    server.getInetAddress().getHostAddress(), server.getLocalPort())) {
                if (goodGreeting) transport.verifyQnx(null);
                else assertThrows(IOException.class, () -> transport.verifyQnx(null));
            }
            responder.get(6, TimeUnit.SECONDS);
        }
    }
    @Test public void verifiesQnxThroughReadyAdbWithoutSshProfile() throws Exception { checkQnx(true); }
    @Test public void reportsQnxServiceRefusal() throws Exception { checkQnx(false); }
}
