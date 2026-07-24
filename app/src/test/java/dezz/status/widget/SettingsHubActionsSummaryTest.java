/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Focused card-summary contract for the independently visible HOME actions panel. */
public final class SettingsHubActionsSummaryTest {
    @Test
    public void hiddenPanelReportsHiddenWithoutDiscardingSavedShortcuts() {
        String raw = "{\"version\":1,\"items\":[{\"enabled\":true},{\"enabled\":false}]}";
        assertEquals("Скрыта", SettingsHubActivity.actionsPanelSummary(false, raw));
    }

    @Test
    public void visiblePanelReportsEnabledAndTotalCounts() {
        String raw = "{\"version\":1,\"items\":["
                + "{\"id\":\"one\",\"enabled\":true},"
                + "{\"id\":\"two\",\"enabled\":false},"
                + "{\"id\":\"legacy-without-enabled\"}]}";
        assertEquals("Включено: 2 из 3",
                SettingsHubActivity.actionsPanelSummary(true, raw));
    }

    @Test
    public void emptyOrBrokenDocumentsHaveStableZeroSummary() {
        assertEquals("Включено: 0 из 0",
                SettingsHubActivity.actionsPanelSummary(true, ""));
        assertEquals("Включено: 0 из 0",
                SettingsHubActivity.actionsPanelSummary(true, "{broken"));
    }
}
