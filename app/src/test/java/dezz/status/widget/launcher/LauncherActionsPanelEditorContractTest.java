/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the shared runtime/settings actions-grid contract. */
public final class LauncherActionsPanelEditorContractTest {
    @Test public void homeUsesPersistedExactCellGridAndDedicatedEditor() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");

        assertTrue(launcher.contains("EXTRA_EDIT_ACTIONS_CONTENT"));
        assertTrue(launcher.contains("shortcutGrid = new PanelGridLayout(this)"));
        assertTrue(launcher.contains("actionsContentEditOverlay = new PanelContentEditOverlay"));
        assertTrue(launcher.contains("setActionsContentEditMode(true)"));
        assertTrue(launcher.contains("actionsGridConfigStore.save(actionsGridConfig)"));
        assertTrue(launcher.contains("preferences.launcherActionsVisible.get()"));
    }

    @Test public void individualIconSizeIsNotMultipliedByGroupScale() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");

        assertTrue(launcher.contains(
                ": Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX, shortcut.iconSizePx)"));
        assertFalse(launcher.contains(
                "shortcut.iconSizePx) * contentScale / 100"));
    }

    @Test public void gridRebuildKeepsSmartHomeLiveBindingAndClickPath() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");

        assertTrue(launcher.contains("smartHomeShortcutBindings.put(shortcut.id, binding)"));
        assertTrue(launcher.contains("applySmartHomeStates();"));
        assertTrue(launcher.contains("card.setOnClickListener(v -> executeShortcut(shortcut))"));
        assertTrue(launcher.contains("binding.card.setClickable(true)"));
    }

    @Test public void invalidShortcutDocumentIsNeverOverwrittenWithDefaults() throws IOException {
        String store = source("dezz/status/widget/launcher/LauncherShortcutStore.java");
        int failure = store.indexOf("catch (JSONException error)");
        int nextMethod = store.indexOf("@NonNull", failure);
        String recovery = store.substring(failure, nextMethod);

        assertTrue(store.contains("if (json == null) throw new JSONException(\"item\")"));
        assertTrue(store.contains("if (value == null) throw new JSONException(\"item\")"));
        assertTrue(recovery.contains("previous.isEmpty() ? defaults() : previous"));
        assertFalse(recovery.contains("save();"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
