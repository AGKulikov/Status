/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.List;

/** Pure relative-step semantics; a multi-step command produces only one vehicle write. */
public final class DriveSelectorStepPolicy {
    private DriveSelectorStepPolicy() {}

    public static Integer target(List<Integer> order, Integer current, int steps) {
        if (steps < -3 || steps > 3) throw new IllegalArgumentException("Steps outside -3..3");
        if (current == null || steps == 0 || order.size() < 2) return null;
        int index = order.indexOf(current);
        // Match the reference boundary behavior for a KNOWN mode excluded from the list.
        if (index < 0) index = steps > 0 ? -1 : order.size();
        int selected = order.get(Math.floorMod(index + steps, order.size()));
        return selected == current ? null : selected;
    }
}
