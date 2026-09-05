/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PhoneNotificationLowBatteryIconContractTest {
    @Test
    public void iconUsesExistingBatteryVectorInsideRedRoundedTile() throws Exception {
        String source = project("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneNotificationLowBatteryIconFactory.java");
        assertTrue(source.contains("R.drawable.ic_status_iphone_battery"));
        assertTrue(source.contains("value.setColor(0xFFFF453A)"));
        assertTrue(source.contains("DrawableCompat.setTint(glyph, 0xFFFFFFFF)"));
        assertTrue(source.contains("result.setLayerInset"));
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
        throw new IllegalStateException("Missing " + relative);
    }
}
