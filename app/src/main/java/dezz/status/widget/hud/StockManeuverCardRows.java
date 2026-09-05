/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

/** Content-driven rows of the visible stock card; missing fields consume no space. */
final class StockManeuverCardRows {
    static final class Row {
        final float left, top, right, bottom;

        Row(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    final String roadText;
    final Row main, nextRoad, signs, auxiliary;

    StockManeuverCardRows(float left, float top, float right, float bottom,
                         int distanceAreaPercent, float rowGap,
                         String stockNextRoad, boolean showNextRoad,
                         boolean hasSigns, boolean hasAuxiliary) {
        roadText = showNextRoad && stockNextRoad != null ? stockNextRoad.trim() : "";
        int details = (roadText.isEmpty() ? 0 : 1) + (hasSigns ? 1 : 0)
                + (hasAuxiliary ? 1 : 0);
        float height = Math.max(0f, bottom - top);
        float gap = details == 0 ? 0f
                : Math.min(Math.max(0f, rowGap), height / (details * 2f));
        float available = height - details * gap;
        float mainHeight = details == 0 ? height
                : available * Math.max(20, Math.min(80, distanceAreaPercent)) / 100f;
        main = new Row(left, top, right, top + mainHeight);
        float detailHeight = details == 0 ? 0f : (available - mainHeight) / details;
        float y = main.bottom;
        if (!roadText.isEmpty()) {
            nextRoad = new Row(left, y + gap, right, y + gap + detailHeight);
            y = nextRoad.bottom;
        } else nextRoad = null;
        if (hasSigns) {
            signs = new Row(left, y + gap, right, y + gap + detailHeight);
            y = signs.bottom;
        } else signs = null;
        auxiliary = hasAuxiliary
                ? new Row(left, y + gap, right, bottom) : null;
    }
}
