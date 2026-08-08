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

    @Test public void androidAndHelperUseMatchedFreshGattGenerations() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v25/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(transport.contains("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f04"));
        assertTrue(transport.contains("d2d9e4b2-47f1-4e44-a8bb-a932fd5a2f04"));
        assertTrue(transport.contains("d2d9e4b3-47f1-4e44-a8bb-a932fd5a2f04"));
        assertTrue(transport.contains("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f05"));
        assertTrue(transport.contains("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f05"));

        assertTrue(helper.contains("KX11 ANCS HELPER v25"));
        assertTrue(helper.contains("D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F04"));
        assertTrue(helper.contains("D2D9E4B2-47F1-4E44-A8BB-A932FD5A2F04"));
        assertTrue(helper.contains("D2D9E4B3-47F1-4E44-A8BB-A932FD5A2F04"));
        assertTrue(helper.contains("D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F05"));
        assertTrue(helper.contains("D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F05"));
        assertTrue(helper.contains("peripheral.v25.single-link-g5"));
        assertTrue(helper.contains("central.v25.geely-ancs-g4"));
    }

    @Test public void releaseIdentityAdvancesToHa1182AndHelper25() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        String helperProject = project("ios/KX11-iPhone-ANCS-Helper-v25/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");

        assertTrue(build.contains("return 'v2.8.2-ha1187'"));
        assertTrue(helperProject.contains("MARKETING_VERSION = 25.0"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 25"));
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
