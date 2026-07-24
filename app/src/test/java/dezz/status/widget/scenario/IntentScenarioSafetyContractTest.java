/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.scenario;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards shared Intent rules from connector conversion and dangling HOME references. */
public final class IntentScenarioSafetyContractTest {
    @Test
    public void nonSprutRuleIsRejectedBeforeSprutEditorConstruction() throws IOException {
        String source = activitySource();
        int connectorGuard = source.indexOf(
                "source.command.connectorType != ConnectorType.SPRUTHUB");
        int editorConstruction = source.indexOf("EditorViews views = new EditorViews(source)");

        assertTrue("Unsupported connector guard must exist", connectorGuard >= 0);
        assertTrue("Unsupported connector must be rejected before the Sprut-only editor",
                connectorGuard < editorConstruction);
        assertTrue(source.contains("showUnsupportedConnectorDialog(source)"));
        assertTrue(source.contains("Текущий редактор поддерживает только Sprut.hub"));
    }

    @Test
    public void referencedRuleDeletionChecksPrimaryAndLongPressActions() throws IOException {
        String source = activitySource();

        assertTrue(source.contains(
                "shortcut.kind == LauncherShortcutStore.Kind.RULE"));
        assertTrue(source.contains("ruleId.equals(shortcut.target)"));
        assertTrue(source.contains("shortcut.hasLongAction"));
        assertTrue(source.contains(
                "shortcut.longKind == LauncherShortcutStore.Kind.RULE"));
        assertTrue(source.contains("ruleId.equals(shortcut.longTarget)"));
        assertTrue("Deletion must recheck references inside the confirmation callback",
                occurrences(source, "referencingHomeShortcuts(rule.id)") >= 2);
        assertTrue(source.contains("showReferencedRuleDialog(rule, currentReferences)"));
    }

    @Test
    public void blockedRuleOffersHomeButtonEditorAndKeepsStableId() throws IOException {
        String source = activitySource();

        assertTrue(source.contains(".setTitle(\"Правило используется на HOME\")"));
        assertTrue(source.contains(".setPositiveButton(\"Открыть кнопки HOME\""));
        assertTrue(source.contains(
                "startActivity(new Intent(this, LauncherShortcutSettingsActivity.class))"));
        assertTrue("Existing rule IDs are addresses used by HOME shortcuts",
                source.contains("id.setEnabled(false)"));
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static String activitySource() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "IntentScenarioSettingsActivity.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "IntentScenarioSettingsActivity.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
