/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the shared runtime/settings actions-grid contract. */
public final class LauncherActionsPanelEditorContractTest {
    private static String launcherSource;
    private static String shortcutStoreSource;

    @BeforeClass
    public static void loadSourcesOnce() throws IOException {
        launcherSource = source("dezz/status/widget/LauncherActivity.java");
        shortcutStoreSource =
                source("dezz/status/widget/launcher/LauncherShortcutStore.java");
    }

    @Test public void homeUsesPersistedExactCellGridAndSharedScreenEditor() throws IOException {
        String launcher = launcherSource;

        assertTrue(launcher.contains("EXTRA_EDIT_ACTIONS_CONTENT"));
        assertTrue(launcher.contains("shortcutGrid = new PanelGridLayout(this)"));
        assertTrue(launcher.contains("actionsContentEditOverlay = new PanelContentEditOverlay"));
        assertTrue(launcher.contains("requestsAnyHomeEditor(intent)"));
        assertTrue(launcher.contains("activateGlobalElements()"));
        assertTrue(launcher.contains("private void applyRequestedHomeEditor"));
        assertTrue(launcher.contains("if (!requestsAnyHomeEditor(intent)) return;\n"
                + "        setEditMode(true);"));
        assertTrue(launcher.contains("actionsGridConfigStore.save(actionsGridConfig)"));
        assertTrue(launcher.contains("preferences.launcherActionsVisible.get()"));
    }

    @Test public void individualIconSizeIsNotMultipliedByGroupScale() throws IOException {
        String launcher = launcherSource;

        assertTrue(launcher.contains(
                ": Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX, shortcut.iconSizePx)"));
        assertFalse(launcher.contains(
                "shortcut.iconSizePx) * contentScale / 100"));
    }

    @Test public void gridRebuildKeepsSmartHomeLiveBindingAndClickPath() throws IOException {
        String launcher = launcherSource;

        assertTrue(launcher.contains("smartHomeShortcutBindings.put(shortcut.id, binding)"));
        assertTrue(launcher.contains("applySmartHomeStates();"));
        assertTrue(launcher.contains("card.setOnClickListener(v -> executeShortcut(shortcut))"));
        assertTrue(launcher.contains("binding.card.setClickable(true)"));
    }

    @Test public void invalidShortcutDocumentIsNeverOverwrittenWithDefaults() throws IOException {
        String store = shortcutStoreSource;
        int failure = store.indexOf("catch (JSONException error)");
        int nextMethod = store.indexOf("@NonNull", failure);
        String recovery = store.substring(failure, nextMethod);

        assertTrue(store.contains("if (json == null) throw new JSONException(\"item\")"));
        assertTrue(store.contains("if (value == null) throw new JSONException(\"item\")"));
        assertTrue(recovery.contains("previous.isEmpty() ? defaults() : previous"));
        assertFalse(recovery.contains("save();"));
    }

    private static String source(String relative) throws IOException {
        String githubWorkspace = System.getenv("GITHUB_WORKSPACE");
        if (githubWorkspace != null && !githubWorkspace.trim().isEmpty()) {
            Path fromWorkspace = Paths.get(githubWorkspace).resolve(Paths.get(
                    "app", "src", "main", "java")).resolve(relative);
            if (Files.isRegularFile(fromWorkspace)) {
                return new String(Files.readAllBytes(fromWorkspace), StandardCharsets.UTF_8);
            }
        }
        Path cursor = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
            Path fromRoot = cursor.resolve(Paths.get(
                    "app", "src", "main", "java")).resolve(relative);
            if (Files.isRegularFile(fromRoot)) {
                return new String(Files.readAllBytes(fromRoot), StandardCharsets.UTF_8);
            }
            Path fromApp = cursor.resolve(Paths.get(
                    "src", "main", "java")).resolve(relative);
            if (Files.isRegularFile(fromApp)) {
                return new String(Files.readAllBytes(fromApp), StandardCharsets.UTF_8);
            }
        }
        throw new NoSuchFileException(relative);
    }
}
