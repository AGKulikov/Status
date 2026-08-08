/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1184 barriers for the pre-didConnect LE-security bootstrap on the reverse ANCS route. */
public final class Ha1184IncomingAncsBondContractTest {
    @Test public void incomingF04LinkStartsOneLeBondBeforeHelperPair() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String prePair = between(transport,
                "private void requestIncomingPrePairLeBond",
                "private boolean createLeBond");
        String connection = between(transport,
                "public void onConnectionStateChange(BluetoothDevice device,",
                "public void onCharacteristicReadRequest");

        assertTrue(connection.contains("recordGattServerPeer(device, status, newState)"));
        assertTrue(connection.contains("requestIncomingPrePairLeBond(device)"));
        assertTrue(prePair.contains("managedIncomingMode"));
        assertTrue(prePair.contains("getVerifiedPeer() != null"));
        assertTrue(prePair.contains("peer.prePairBondRequested"));
        assertTrue(prePair.contains("createLeBond(device)"));
    }

    @Test public void bondUsesVerifierSafePublicApiAndDoesNotBypassPairVerification()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String create = between(transport,
                "private boolean createLeBond",
                "private void handleSecureAttSuccess");
        String prePair = between(transport,
                "private void requestIncomingPrePairLeBond",
                "private boolean createLeBond");
        String pair = between(transport,
                "private void handlePairCommand",
                "private void requestIncomingPrePairLeBond");

        assertTrue(create.contains("device.createBond()"));
        assertFalse(create.contains("getMethod("));
        assertFalse(create.contains("method.invoke("));
        assertFalse(transport.contains("import java.lang.reflect.Method;"));
        assertTrue(pair.contains("isVerifiedPeer(device)"));
        assertFalse(prePair.contains("claimVerifiedPeer"));
        assertTrue(transport.contains("claimVerifiedPeer(device)"));
    }

    @Test public void releaseIdentityAdvancesToHa1184() throws Exception {
        assertTrue(rootProject("build.gradle").contains("return 'v2.8.2-ha1186'"));
        assertTrue(project("release-manifests/HA1184.md").contains("208021184"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
