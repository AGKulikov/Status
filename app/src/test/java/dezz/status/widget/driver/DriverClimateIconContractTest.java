/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/** Keeps the live tile on the exact user-supplied MonjaroPanel climate artwork. */
public final class DriverClimateIconContractTest {
    @Test
    public void compactTileUsesMonjaroFanAndStableNineOrFiveStepScale()
            throws IOException {
        String view = source();

        assertTrue(view.contains("if (!expanded) return;"));
        assertTrue(view.contains("drawBars("));
        assertTrue(view.contains("drawMonjaroFanScale("));
        assertTrue(view.contains("FAN_ARTWORK_SOURCE"));
        assertTrue(view.contains("ClimateFanScaleGeometry.physicalSlot("));
        assertTrue(view.contains("drawRoundRect(shape"));
        assertFalse(view.contains("R.drawable.ic_fan"));
    }

    @Test
    public void extendedAirflowUsesExactMonjaroPanelPngAssets()
            throws IOException {
        assertEquals("4ed6def4edb5419a2c44dbb71d38ea3d2d0124bf4d354be970a17b19d893dadd",
                sha256(drawable("ic_driver_monjaro_blow_face.png")));
        assertEquals("cfdb6b177c65346b2798e3113e29a49d25e71340a94b824b0ae9dee032c9a620",
                sha256(drawable("ic_driver_monjaro_blow_leg.png")));
        assertEquals("3ded2ed96383e9dae5e7c6af28a012a05d07f1e023e0d82768af608460198061",
                sha256(drawable("ic_driver_monjaro_blow_window.png")));
        assertEquals("9dbff864010696d10d7c0bd04004a25f035a7e47a7e24183dc51cb9762479d4d",
                sha256(drawable("ic_driver_monjaro_blow_all.png")));
        assertEquals("1ad8ee3d4a8f53b90641ddf982e04dd9850387408b2c02d5b30a6bac0c004a0a",
                sha256(drawable("ic_driver_monjaro_temperature_source.png")));
    }

    @Test
    public void autoIsPlainTextAndConfigurableGapRemainsExtendedOnly() throws IOException {
        String view = source();

        assertTrue(view.contains("boolean expanded = detailed"));
        assertTrue(view.contains("drawAutoText("));
        assertTrue(view.contains("drawAirflow("));
        assertTrue(view.contains("detailsGapPx"));
        assertTrue(view.contains("height * .14f"));
        assertFalse(view.contains("R.drawable.ic_driver_monjaro_blow_auto_badge"));
        assertTrue(view.contains("if (!expanded) return;"));
    }

    private static String source() throws IOException {
        return read(Paths.get("java", "dezz", "status", "widget", "driver",
                "DriverClimateShortcutView.java"));
    }

    private static Path drawable(String name) {
        Path fromRoot = Paths.get("app", "src", "main", "res",
                "drawable-nodpi", name);
        Path fromApp = Paths.get("src", "main", "res", "drawable-nodpi", name);
        return Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String read(Path relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main").resolve(relative);
        Path fromApp = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
