/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the canonical buttons/smart-home settings path and its shared HOME grid contract. */
public final class LauncherShortcutSettingsGridContractTest {
    @Test public void screenOwnsPanelVisibilityAndBothHomeEditors() throws IOException {
        String source = source();

        assertTrue(source.contains("preferences.launcherActionsVisible.set(checked)"));
        assertTrue(source.contains("PanelElementConfigStore.ACTION_TILES"));
        assertTrue(source.contains("PanelElementConfigStore.ACTION_ADD"));
        assertTrue(source.contains("Показывать кнопки и устройства в панели"));
        assertTrue(source.contains("Показывать плитку «Добавить»"));
        assertTrue(source.contains("changeActionElementScale"));
        assertTrue(source.contains("LauncherActivity.EXTRA_EDIT_MODE"));
        assertTrue(source.contains("LauncherActivity.EXTRA_EDIT_ACTIONS_CONTENT"));
    }

    @Test public void previewEditsTheSameStableGridAsHome() throws IOException {
        String source = source();

        assertTrue(source.contains("new LauncherActionsGridConfigStore(preferences)"));
        assertTrue(source.contains("previewGrid = new PanelGridLayout(this)"));
        assertTrue(source.contains("previewOverlay = new PanelContentEditOverlay(this)"));
        assertTrue(source.contains("gridStore.save(requireGridConfig())"));
        assertTrue(source.contains("LauncherActionsGridConfig.ADD_TILE_ID"));
    }

    @Test public void returningFromHomeReloadsBeforeAnyLaterWrite() throws IOException {
        String source = source();
        int resume = source.indexOf("protected void onResume()");
        int reload = source.indexOf("refresh();", resume);
        int stop = source.indexOf("protected void onStop()");

        assertTrue(resume >= 0);
        assertTrue(reload > resume);
        // This editor writes through on each explicit interaction; it must not flush a stale
        // pre-HOME object from onStop like older panel editors did.
        assertTrue(stop < 0);
    }

    @Test public void everyRowHasDirectVisibilityAndIconSizeControls() throws IOException {
        String source = source();

        assertTrue(source.contains("MaterialSwitch visible = new MaterialSwitch(this)"));
        assertTrue(source.contains("setShortcutVisible(shortcut.id, checked)"));
        assertTrue(source.contains("SeekBar iconSize = new SeekBar(this)"));
        assertTrue(source.contains("setShortcutIconSize(shortcut.id, selected)"));
        assertTrue(source.contains("LauncherShortcutStore.MIN_ICON_SIZE_PX"));
        assertTrue(source.contains("LauncherShortcutStore.MAX_ICON_SIZE_PX"));
    }

    @Test public void resizedSpansAreMirroredForRollbackBuilds() throws IOException {
        String source = source();

        assertTrue(source.contains("mirrorGridSpanToLegacy(id)"));
        assertTrue(source.contains("latest.columnSpan = placement.columnSpan"));
        assertTrue(source.contains("latest.rowSpan = placement.rowSpan"));
        assertTrue(source.contains("value.columnSpan = actual"));
        assertTrue(source.contains("value.rowSpan = actual"));
    }

    @Test public void previewPaddingAndResumeSynchronizationCannotDrift() throws IOException {
        String source = source();

        assertTrue(source.contains("previewHost.setPadding("));
        assertTrue(!source.contains("previewGrid.setPadding("));
        assertTrue(source.contains("if (!fromUser || syncingControls) return"));
        assertTrue(source.contains("if (!syncingControls) preferences.launcherActionsVisible"));
    }

    private static String source() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "LauncherShortcutSettingsActivity.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "LauncherShortcutSettingsActivity.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
