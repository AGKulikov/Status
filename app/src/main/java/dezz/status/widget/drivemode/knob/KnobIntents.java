/*
 * Copyright © 2026 Dezz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget.drivemode.knob;

/**
 * Intent actions used to receive mode switch events from MConfig+.
 *
 * Binding in MConfig+:
 *   knobLeftClick1Cfg  -> "Send intent" -> action: PREV_1   package: dezz.status.widget.drivemode
 *   knobRightClick1Cfg -> "Send intent" -> action: NEXT_1   package: dezz.status.widget.drivemode
 * (separate actions for 2/3-click series are optional).
 */
public final class KnobIntents {

    public static final String ACTION_PREV_1 =
            "dezz.status.widget.drivemode.PREV_1";
    public static final String ACTION_PREV_2 =
            "dezz.status.widget.drivemode.PREV_2";
    public static final String ACTION_PREV_3 =
            "dezz.status.widget.drivemode.PREV_3";

    public static final String ACTION_NEXT_1 =
            "dezz.status.widget.drivemode.NEXT_1";
    public static final String ACTION_NEXT_2 =
            "dezz.status.widget.drivemode.NEXT_2";
    public static final String ACTION_NEXT_3 =
            "dezz.status.widget.drivemode.NEXT_3";

    /**
     * Just show the overlay with the current mode. Does not change the mode.
     * Used for the "steering wheel button -> show selector to tap" scenario.
     */
    public static final String ACTION_SHOW =
            "dezz.status.widget.drivemode.SHOW";

    /**
     * Broadcast we emit while the overlay is visible or hidden so MConfig+
     * (v43+) can collapse 1/2/3-click series into a single PREV_1/NEXT_1
     * intent: when the overlay is already showing it forwards as a step,
     * otherwise as a show. Carries {@link #EXTRA_IS_SHOWING}.
     */
    public static final String ACTION_OVERLAY_VISIBILITY =
            "dezz.status.widget.drivemode.ISSHOWING";

    /** Boolean extra: true while the overlay is visible, false once it has faded out. */
    public static final String EXTRA_IS_SHOWING = "is_showing";

    private KnobIntents() {}
}
