/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Pixel geometry of a card. The geographic tip never moves to make its body fit. */
final class TurnBalloonLayout {
    static final int MARGIN = 6;
    final String side;
    final int width, height;
    final float left, top, right, bottom, tipX, tipY;
    final int variant;

    TurnBalloonLayout(int bodyWidth, int bodyHeight, int gap, String side) {
        this(bodyWidth, bodyHeight, gap, side, 0);
    }

    TurnBalloonLayout(int bodyWidth, int bodyHeight, int baseGap, String side, int variant) {
        this.side = side;
        this.variant = variant;
        int gap = baseGap * (1 + 2 * variant);
        boolean leftLeg = side.equals("LEFT_CENTER") || side.endsWith("_LEFT");
        boolean rightLeg = side.equals("RIGHT_CENTER") || side.endsWith("_RIGHT");
        boolean topLeg = side.startsWith("TOP_");
        boolean bottomLeg = side.startsWith("BOTTOM_");
        left = leftLeg ? gap : 0;
        top = topLeg ? gap : 0;
        right = left + bodyWidth;
        bottom = top + bodyHeight;
        width = bodyWidth + (leftLeg || rightLeg ? gap : 0);
        height = bodyHeight + (topLeg || bottomLeg ? gap : 0);
        tipX = leftLeg ? 0 : rightLeg ? width : width / 2f;
        tipY = topLeg ? 0 : bottomLeg ? height : height / 2f;
    }

    float anchorX() { return tipX / width; }
    float anchorY() { return tipY / height; }

    boolean sameImage(TurnBalloonLayout other) {
        return other != null && width == other.width && height == other.height
                && side.equals(other.side);
    }

    static float fitScale(float width, float height, int viewportWidth, int viewportHeight,
                          float requested) {
        if (!(width > 0 && height > 0 && requested > 0)
                || viewportWidth <= 2 * MARGIN || viewportHeight <= 2 * MARGIN) return 0;
        return Math.min(requested, Math.min((viewportWidth - 2f * MARGIN) / width,
                (viewportHeight - 2f * MARGIN) / height));
    }
}
