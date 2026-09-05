/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Executable behavior contract for action-independent live-climate presentation. */
public final class LiveClimateIconPolicyTest {
    @Test public void editingExistingButtonPreservesUserChoiceForEveryAction() {
        for (LauncherShortcutStore.Kind kind : LauncherShortcutStore.Kind.values()) {
            String target = kind == LauncherShortcutStore.Kind.BUILTIN
                    ? LauncherShortcutStore.Builtin.STOCK_CLIMATE.key : "action";
            assertTrue(kind.name(), LiveClimateIconPolicy.afterPrimaryActionChange(
                    true, true, kind, target));
            assertFalse(kind.name(), LiveClimateIconPolicy.afterPrimaryActionChange(
                    true, false, kind, target));
        }
    }

    @Test public void onlyNewStockClimateButtonGetsLiveDefault() {
        assertTrue(LiveClimateIconPolicy.afterPrimaryActionChange(
                false,
                false,
                LauncherShortcutStore.Kind.BUILTIN,
                LauncherShortcutStore.Builtin.STOCK_CLIMATE.key));
        for (LauncherShortcutStore.Kind kind : LauncherShortcutStore.Kind.values()) {
            String target = kind == LauncherShortcutStore.Kind.BUILTIN
                    ? LauncherShortcutStore.Builtin.ALL_APPS.key
                    : LauncherShortcutStore.Builtin.STOCK_CLIMATE.key;
            assertFalse(kind.name(), LiveClimateIconPolicy.afterPrimaryActionChange(
                    false, true, kind, target));
        }
    }

    @Test public void allActionKindsAreInteractiveButInformationAndDividerAreNot() {
        LauncherShortcutStore.Shortcut value = new LauncherShortcutStore.Shortcut();
        LauncherShortcutStore.Kind[] interactive = {
                LauncherShortcutStore.Kind.APP,
                LauncherShortcutStore.Kind.BUILTIN,
                LauncherShortcutStore.Kind.CAR,
                LauncherShortcutStore.Kind.RULE,
                LauncherShortcutStore.Kind.PHONE,
                LauncherShortcutStore.Kind.INTENT
        };
        for (LauncherShortcutStore.Kind kind : interactive) {
            value.kind = kind;
            assertTrue(kind.name(), LauncherShortcutStore.isInteractive(value));
        }
        value.kind = LauncherShortcutStore.Kind.INFO;
        assertFalse(LauncherShortcutStore.isInteractive(value));
        value.kind = LauncherShortcutStore.Kind.DIVIDER;
        assertFalse(LauncherShortcutStore.isInteractive(value));
    }

    @Test public void shortcutCopyPreservesLivePresentationFlag() {
        LauncherShortcutStore.Shortcut value = new LauncherShortcutStore.Shortcut();
        value.kind = LauncherShortcutStore.Kind.RULE;
        value.liveClimateIcon = true;
        assertTrue(value.copy().liveClimateIcon);
        value.liveClimateIcon = false;
        assertFalse(value.copy().liveClimateIcon);
    }
}
