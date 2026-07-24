/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the live subscription and full-card interaction contract for smart-home RULE tiles. */
public final class SmartHomeShortcutLiveContractTest {
    @Test public void ruleTileHasLiveBadgeWithoutReplacingItsClickAction() throws IOException {
        String source = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(source.contains("shortcut.kind == LauncherShortcutStore.Kind.RULE)"));
        assertTrue(source.contains("smartHomeShortcutBindings.put(shortcut.id, binding)"));
        assertTrue(source.contains("applySmartHomeState(binding)"));
        assertTrue(source.contains("card.setOnClickListener(v -> executeShortcut(shortcut))"));
        assertTrue(source.contains("binding.card.setClickable(true)"));
    }

    @Test public void launcherRebindsAfterServiceStartupAndReceivesInitialSnapshot()
            throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        String service = source("dezz/status/widget/WidgetService.java");
        assertTrue(launcher.contains("ensureSmartHomeValueSubscription"));
        assertTrue(launcher.contains("current.addConnectorValueListener(smartHomeValueListener)"));
        assertTrue(launcher.contains("removeConnectorValueListener(smartHomeValueListener)"));
        assertTrue(service.contains("current.addListener(listener)"));
        assertTrue(service.contains("return current.snapshot()"));
    }

    @Test public void emptyHomeAssistantCatalogIsRefreshedBeforeReportingFailure()
            throws IOException {
        String picker = source("dezz/status/widget/launcher/SmartHomeShortcutPicker.java");
        assertTrue(picker.contains("active.refreshCatalog()"));
        assertTrue(picker.contains("active.catalog().values()"));
        assertTrue(picker.contains("Загружаю полный список сущностей"));
    }

    @Test public void resolvedActiveStateDrivesIconTintBackgroundAndStatusText()
            throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(launcher.contains("state.activeKnown && state.active"));
        assertTrue(launcher.contains(
                "? shortcut.activeBackgroundColor : shortcut.backgroundColor"));
        assertTrue(launcher.contains(
                "active ? shortcut.activeIconColor : shortcut.iconColor"));
        assertTrue(launcher.contains("binding.stateLabel.setText(state.valueLabel)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
