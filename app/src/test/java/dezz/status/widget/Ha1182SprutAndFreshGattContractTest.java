/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1182 barriers for the official Sprut proof and fresh iPhone GATT namespaces. */
public final class Ha1182SprutAndFreshGattContractTest {
    @Test public void sprutProofUsesOfficialDirectArgon2Ed25519Flow() throws Exception {
        String challenge = source("sprut/SprutCloudChallenge.java");

        assertTrue(challenge.contains(
                "Ed25519.sign(argonResult, 0, challenge, 0, challenge.length, signature, 0)"));
        assertFalse(challenge.contains("HKDFBytesGenerator"));
        assertFalse(challenge.contains("HKDFParameters"));
        assertFalse(challenge.contains("signingSeed"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
