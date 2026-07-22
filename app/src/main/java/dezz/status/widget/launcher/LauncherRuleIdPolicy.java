/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

/** Prevents editing a long-press action from overwriting the primary shortcut command. */
public final class LauncherRuleIdPolicy {
    private static final String PREFIX = "launcher_";

    private LauncherRuleIdPolicy() {}

    /** Empty means that the caller must allocate a new launcher rule id. */
    @NonNull
    public static String reusableId(boolean editingLongAction,
                                    boolean mainIsRule, String mainTarget,
                                    boolean hasLongAction, boolean longIsRule,
                                    String longTarget) {
        if (editingLongAction) {
            return hasLongAction && longIsRule && generated(longTarget) ? longTarget : "";
        }
        return mainIsRule && generated(mainTarget) ? mainTarget : "";
    }

    private static boolean generated(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() > PREFIX.length();
    }
}
