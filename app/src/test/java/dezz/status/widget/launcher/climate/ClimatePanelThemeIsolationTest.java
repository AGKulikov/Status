/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Guards the display-context overlay against accidentally restoring a theme-enforced tile. */
public final class ClimatePanelThemeIsolationTest {
    @Test
    public void climatePanelBytecodeDoesNotReferenceMaterialCardView() throws IOException {
        String resource = ClimatePanelView.class.getName().replace('.', '/') + ".class";
        InputStream input = ClimatePanelView.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull("compiled ClimatePanelView class must be available", input);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        input.close();

        String constantPool = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse("overlay tiles must not require an Activity Material theme",
                constantPool.contains("com/google/android/material/card/MaterialCardView"));
        assertTrue("the lightweight theme-independent tile must stay in use",
                constantPool.contains("ClimateTileView"));
    }
}
