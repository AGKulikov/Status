/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents the compact climate tile from regressing to the old fan image/homemade glyphs. */
public final class DriverClimateIconContractTest {
    @Test
    public void compactTileContainsOnlyTemperatureAndStableScale() throws IOException {
        String view = source();

        assertTrue(view.contains("if (!expanded) return;"));
        assertTrue(view.contains("drawBars("));
        assertFalse(view.contains("R.drawable.ic_fan"));
        assertFalse(view.contains("drawFan"));
    }

    @Test
    public void extendedAirflowUsesTheOfficialAutomotiveVectorGeometry()
            throws IOException {
        String face = drawable("ic_driver_airflow_face.xml");
        String feet = drawable("ic_driver_airflow_feet.xml");
        String windshield = drawable("ic_driver_airflow_windshield.xml");

        assertTrue(face.contains("android:viewportWidth=\"96\""));
        assertTrue(face.contains(
                "M40.79,7.79L39.38,9.21L41.17,11H27V13H41.17"));
        assertTrue(feet.contains("android:viewportHeight=\"49\""));
        assertTrue(feet.contains(
                "M40.209,16.7912L38.789,15.3812L36.999,17.1712"));
        assertTrue(windshield.contains(
                "M41.5603,10.0488C37.5686,12.8863"));
        assertTrue(windshield.contains("android:strokeLineJoin=\"round\""));
    }

    @Test
    public void autoAndAirflowRemainExtendedInformationOnly() throws IOException {
        String view = source();

        assertTrue(view.contains("boolean expanded = detailed"));
        assertTrue(view.contains("drawAutoBadge("));
        assertTrue(view.contains("drawAirflow("));
        assertTrue(view.contains("if (!expanded) return;"));
    }

    private static String source() throws IOException {
        return read(Paths.get("java", "dezz", "status", "widget", "driver",
                "DriverClimateShortcutView.java"));
    }

    private static String drawable(String name) throws IOException {
        return read(Paths.get("res", "drawable", name));
    }

    private static String read(Path relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main").resolve(relative);
        Path fromApp = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
