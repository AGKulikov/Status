/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps the public Natro 2.0.2 identity separate from the stable Android update identity. */
public final class Ha1219NatroBrandingContractTest {
    @Test public void publicNameAndVersionAreExactWhilePackageStaysUpdateCompatible()
            throws IOException {
        String values = project("app/src/main/res/values/strings.xml");
        String valuesRu = project("app/src/main/res/values-ru/strings.xml");
        String manifest = project("app/src/main/AndroidManifest.xml");
        assertTrue(values.contains("<string name=\"app_name\">Natro</string>"));
        assertTrue(valuesRu.contains("<string name=\"app_name\">Natro</string>"));
        assertFalse(values.contains("<string name=\"app_name\">Natro 2.0</string>"));
        assertTrue(manifest.contains("android:label=\"@string/app_name\""));
        String frozenRelease = project(".github/workflows/release-ha1219.yml");
        assertTrue(frozenRelease.contains("VERSION_NAME: '2.0.2'"));
        assertTrue(frozenRelease.contains("VERSION_CODE: '208021219'"));
        assertTrue(project("app/build.gradle").contains(
                "applicationId \"ru.natro.statuswidget\""));
    }

    @Test public void adaptiveAndLegacyIconsUseTheNatroConnectedRoadMark()
            throws IOException {
        String foreground = project("app/src/main/res/mipmap-anydpi/ic_launcher_foreground.xml");
        String legacy = project("app/src/main/res/mipmap-anydpi/ic_launcher.xml");
        String round = project("app/src/main/res/mipmap-anydpi/ic_launcher_round.xml");
        String background = project("app/src/main/res/values/colors.xml");
        for (String icon : new String[]{foreground, legacy, round}) {
            assertTrue(icon.contains("#FF56E4FF"));
            assertTrue(icon.contains("#FF119DDB"));
            assertTrue(icon.contains("M40.3,30.9L75.7,76.9"));
            assertTrue(icon.contains("M44.2,36.1L48.3,41.4"));
        }
        assertTrue(background.contains(
                "<color name=\"ic_launcher_background\">#071923</color>"));
        assertTrue(project("tools/natro-app-icon.svg").contains(
                "one car identity, media, notifications, navigation and automations"));
    }

    @Test public void previousPublicReleaseKeepsItsFrozenManualGate()
            throws IOException {
        String gradle = project("build.gradle").replaceAll("\\s+", " ");
        String current = "if (version == '2.0.2') { return 208021219";
        String previous = "if (version == '2.0.1') { return 208021218";
        assertTrue(gradle.contains(current));
        assertTrue(gradle.contains(previous));
        assertTrue(gradle.indexOf(current) < gradle.indexOf(previous));
        assertTrue(project(".github/workflows/verify-ha1218.yml").contains(
                "# Frozen Natro 2.0.1 identity gate."));
        assertTrue(project(".github/workflows/verify-ha1218.yml").contains(
                "on:\n  workflow_dispatch:"));
        String release = project(".github/workflows/release-ha1218.yml");
        assertTrue(release.contains("VERSION_NAME: '2.0.1'"));
        assertTrue(release.contains("VERSION_CODE: '208021218'"));
    }

    @Test public void releaseManifestNamesTheExactHotfixesAndPhysicalAcceptanceGate()
            throws IOException {
        String notes = project("release-manifests/HA1219.md");
        assertTrue(notes.contains("normalized to upper case"));
        assertTrue(notes.contains("exact `BluetoothDevice` facade directly"));
        assertTrue(notes.contains("exact `callbackGatt` currently owned"));
        assertTrue(notes.contains("bounded, redacted typed v2 diagnostic"));
        assertTrue(notes.contains("`USER_RATING` overrides heart-style `RATING`"));
        assertTrue(notes.contains("`@surface/navigator_window` independently"));
        assertTrue(notes.contains("`tools/KX11_Bluetooth_Collect.command` is now collector v2"));
        assertTrue(notes.contains("60-second device-ready timeout"));
        assertTrue(notes.contains("5be62c4aea62439d36a380298a22dac0474f02e3"));
        assertTrue(notes.contains("does **not** claim successful physical"));
        String release = project(".github/workflows/release-ha1219.yml");
        assertTrue(release.contains("RELEASE_TAG: 'natro-v2.0.2'"));
        assertTrue(release.contains("VERSION_CODE: '208021219'"));
    }

    private static String project(String relative) throws IOException {
        return new String(Files.readAllBytes(projectPath(relative)), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String relative) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
