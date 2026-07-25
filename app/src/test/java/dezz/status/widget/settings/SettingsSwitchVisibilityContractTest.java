/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Release contract for switches whose OFF state must remain unmistakable. */
public final class SettingsSwitchVisibilityContractTest {
    @Test public void everySettingsSwitchInheritsOpaqueCheckedAndUncheckedColors()
            throws Exception {
        Path res = resources();
        String theme = read(res.resolve("values/themes.xml"));
        String nightTheme = read(res.resolve("values-night/themes.xml"));
        String track = read(res.resolve("color/settings_switch_track.xml"));
        String thumb = read(res.resolve("color/settings_switch_thumb.xml"));
        String colors = read(res.resolve("values/colors.xml"));
        String nightColors = read(res.resolve("values-night/colors.xml"));

        for (String value : new String[] {theme, nightTheme}) {
            assertTrue(value.contains(
                    "<item name=\"materialSwitchStyle\">@style/Widget.StatusWidget.MaterialSwitch"));
            assertTrue(value.contains(
                    "<item name=\"android:switchStyle\">@style/Widget.StatusWidget.PlatformSwitch"));
            assertTrue(value.contains("<item name=\"android:showText\">true</item>"));
            assertTrue(value.contains("<item name=\"android:textOn\">I</item>"));
            assertTrue(value.contains("<item name=\"android:textOff\">O</item>"));
        }
        assertTrue(track.contains("android:state_checked=\"true\""));
        assertTrue(track.contains("@color/settings_switch_track_off"));
        assertTrue(thumb.contains("@color/settings_switch_thumb_off"));
        assertTrue(colors.contains("settings_switch_track_off"));
        assertTrue(nightColors.contains("settings_switch_track_off"));
    }

    private static Path resources() {
        Path fromRoot = Paths.get("app", "src", "main", "res");
        return Files.isDirectory(fromRoot) ? fromRoot : Paths.get("src", "main", "res");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
