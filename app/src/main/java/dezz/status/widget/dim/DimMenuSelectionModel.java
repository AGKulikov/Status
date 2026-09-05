/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

/** Small deterministic cursor model used by both runtime and JVM tests. */
public final class DimMenuSelectionModel {
    private int itemCount;
    private int selectedIndex;

    public DimMenuSelectionModel(int count) {
        setItemCount(count);
    }

    public int itemCount() { return itemCount; }
    public int selectedIndex() { return selectedIndex; }
    public boolean hasSelection() { return itemCount > 0; }

    public void setItemCount(int count) {
        itemCount = Math.max(0, count);
        if (itemCount == 0) selectedIndex = 0;
        else selectedIndex = Math.min(selectedIndex, itemCount - 1);
    }

    public int move(int delta, boolean wrap) {
        if (itemCount == 0 || delta == 0) return selectedIndex;
        int requested = selectedIndex + (delta < 0 ? -1 : 1);
        if (wrap) {
            selectedIndex = (requested % itemCount + itemCount) % itemCount;
        } else {
            selectedIndex = Math.max(0, Math.min(itemCount - 1, requested));
        }
        return selectedIndex;
    }

    public void reset() { selectedIndex = 0; }
}
