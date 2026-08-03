/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Architecture guard for the climate panel's service/overlay hosting path.
 *
 * <p>{@code ClimatePanelOverlayController} creates this view from a display context owned by a
 * service. Such a context intentionally has no Activity/Material theme. Material widgets enforce
 * theme attributes in their constructors and therefore crash before the overlay can be attached.
 * Keep the reusable panel surface on framework/AppCompat widgets that do not require an Activity
 * theme; Material controls remain fine in settings Activities.</p>
 */
public final class ClimatePanelServiceContextSafetyTest {
    private static final String SOURCE =
            "dezz/status/widget/launcher/climate/ClimatePanelView.java";

    @Test
    public void serviceHostedPanelDoesNotInstantiateThemeEnforcedMaterialWidgets()
            throws IOException {
        String source = climatePanelSource();

        assertFalse("The service-hosted view must not import Material widgets",
                source.contains("com.google.android.material"));
        assertFalse("MaterialCardView requires a Material-themed Activity context",
                source.contains("new MaterialCardView(")
                        || source.contains("extends MaterialCardView")
                        || source.contains("MaterialCardView.LayoutParams"));
        assertFalse("MaterialButton requires a Material-themed Activity context",
                source.contains("MaterialButton"));
        assertFalse("Material text fields require a Material-themed Activity context",
                source.contains("TextInputLayout") || source.contains("TextInputEditText"));
        assertFalse("Material chips require a Material-themed Activity context",
                source.contains("new Chip(") || source.contains("ChipGroup"));
        assertFalse("Material switches require a Material-themed Activity context",
                source.contains("SwitchMaterial"));
    }

    @Test
    public void overlayStillBuildsPanelFromItsDisplayContext() throws IOException {
        String source = source("dezz/status/widget/climate/ClimatePanelOverlayController.java");

        // The display context is required so WindowManager attaches to the configured car display.
        // The panel itself must therefore remain safe without relying on an Activity theme.
        assertTrue(source.contains("new ClimatePanelView(windowContext"));
    }

    private static String climatePanelSource() throws IOException {
        return source(SOURCE);
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
