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

/** Keeps the public Natro 2.0.1 identity separate from the stable Android update identity. */
public final class Ha1218NatroBrandingContractTest {
    @Test public void publicNameAndVersionAreExactWhilePackageStaysUpdateCompatible()
            throws IOException {
        String values = project("app/src/main/res/values/strings.xml");
        String valuesRu = project("app/src/main/res/values-ru/strings.xml");
        String manifest = project("app/src/main/AndroidManifest.xml");
        String gradle = project("build.gradle");
        assertTrue(values.contains("<string name=\"app_name\">Natro</string>"));
        assertTrue(valuesRu.contains("<string name=\"app_name\">Natro</string>"));
        assertFalse(values.contains("<string name=\"app_name\">Natro 2.0</string>"));
        assertTrue(manifest.contains("android:label=\"@string/app_name\""));
        assertTrue(gradle.contains("return '2.0.1'"));
        assertTrue(gradle.contains("return 208021218"));
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
