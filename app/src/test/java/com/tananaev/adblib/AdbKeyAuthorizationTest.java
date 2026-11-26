package com.tananaev.adblib;

import org.junit.Test;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class AdbKeyAuthorizationTest {
    private enum Reply { ACCEPT_KEY, REJECT_KEY, NO_AUTH, APPROVE_NEW_KEY }

    private static void handshake(Reply reply, boolean interactive) throws Exception {
        byte[] challenge = new byte[20];
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(server.getInetAddress(), server.getLocalPort());
             Socket peer = server.accept();
             AdbConnection connection = AdbConnection.create(client,
                     AdbCrypto.generateAdbKeyPair(bytes -> Base64.getEncoder().encodeToString(bytes)))) {
            peer.setSoTimeout(5000);
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try {
                    assertEquals(AdbProtocol.CMD_CNXN, AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).command);
                    if (reply != Reply.NO_AUTH) {
                        byte[] token = AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_TOKEN, challenge);
                        peer.getOutputStream().write(token);
                        AdbProtocol.AdbMessage signed = AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream());
                        assertEquals(AdbProtocol.CMD_AUTH, signed.command);
                        assertEquals(AdbProtocol.AUTH_TYPE_SIGNATURE, signed.arg0);
                        if (reply == Reply.REJECT_KEY || reply == Reply.APPROVE_NEW_KEY) {
                            peer.getOutputStream().write(token);
                            if (reply == Reply.REJECT_KEY) return;
                            assertEquals(AdbProtocol.AUTH_TYPE_RSA_PUBLIC,
                                    AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).arg0);
                        }
                    }
                    peer.getOutputStream().write(AdbProtocol.generateConnect());
                } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            });
            if (reply == Reply.NO_AUTH || reply == Reply.REJECT_KEY) {
                assertThrows(AdbAuthenticationFailedException.class,
                        () -> connection.connectWithKey(5, TimeUnit.SECONDS, !interactive));
            } else {
                assertTrue(connection.connectWithKey(5, TimeUnit.SECONDS, !interactive));
            }
            responder.get(6, TimeUnit.SECONDS);
        }
    }

    @Test public void usesPreviouslyApprovedKey() throws Exception { handshake(Reply.ACCEPT_KEY, false); }
    @Test public void backgroundDoesNotEnrollRejectedKey() throws Exception { handshake(Reply.REJECT_KEY, false); }
    @Test public void backgroundRequiresAuthentication() throws Exception { handshake(Reply.NO_AUTH, false); }
    @Test public void consoleAlsoRequiresAuthentication() throws Exception { handshake(Reply.NO_AUTH, true); }
    @Test public void consoleAllowsOwnerToApproveNewKey() throws Exception { handshake(Reply.APPROVE_NEW_KEY, true); }
}
