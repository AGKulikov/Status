/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** Both sides use one clock phase; absence of a signal never enables its arrow. */
public final class HudTurnSignals {
    private HudTurnSignals() {}

    public static boolean visible(boolean active, boolean editor, boolean animated,
                                  long nowMillis, int phaseMillis) {
        if (editor) return true;
        if (!active) return false;
        return !animated || (nowMillis / Math.max(150, phaseMillis)) % 2L != 0L;
    }
}
