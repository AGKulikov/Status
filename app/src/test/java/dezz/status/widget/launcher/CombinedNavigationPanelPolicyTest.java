/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CombinedNavigationPanelPolicyTest {
    @Test public void favoritesAreShownOnlyWhileThereIsNoRoute() {
        assertTrue(CombinedNavigationPanelPolicy.showFavorites(false, true));
        assertFalse(CombinedNavigationPanelPolicy.showFavorites(true, true));
        assertFalse(CombinedNavigationPanelPolicy.showFavorites(false, false));
    }

    @Test public void eitherLegacyVisibilitySwitchKeepsCombinedPanelEnabled() {
        assertFalse(CombinedNavigationPanelPolicy.isEnabled(false, false));
        assertTrue(CombinedNavigationPanelPolicy.isEnabled(true, false));
        assertTrue(CombinedNavigationPanelPolicy.isEnabled(false, true));
        assertTrue(CombinedNavigationPanelPolicy.isEnabled(true, true));
    }

    @Test public void emptyActiveConfigurationDoesNotLeaveAnEmptyCard() {
        assertFalse(CombinedNavigationPanelPolicy.hasVisibleContent(
                true, true, false, true));
        assertTrue(CombinedNavigationPanelPolicy.hasVisibleContent(
                true, false, true, false));
        assertTrue(CombinedNavigationPanelPolicy.hasVisibleContent(
                false, true, false, false));
        assertTrue(CombinedNavigationPanelPolicy.hasVisibleContent(
                false, false, false, true));
    }

    @Test public void favoriteOnlyUpgradeKeepsFavoritePanelRectangle() {
        assertTrue(CombinedNavigationPanelPolicy.shouldUseLegacyFavoriteGeometry(
                false, false, true));
        assertFalse(CombinedNavigationPanelPolicy.shouldUseLegacyFavoriteGeometry(
                false, true, true));
        assertFalse(CombinedNavigationPanelPolicy.shouldUseLegacyFavoriteGeometry(
                false, true, false));
        assertFalse(CombinedNavigationPanelPolicy.shouldUseLegacyFavoriteGeometry(
                true, false, true));
    }
}
