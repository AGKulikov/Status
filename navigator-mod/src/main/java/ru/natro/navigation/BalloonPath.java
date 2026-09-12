/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Path;
import android.graphics.RectF;

/** A single filled/stroked silhouette; no seam or separately positioned connector. */
final class BalloonPath {
    private BalloonPath() {}

    static Path create(RectF body, float radius, float tipX, float tipY, float halfWidth) {
        Path shape = new Path();
        shape.addRoundRect(body, radius, radius, Path.Direction.CW);
        float[] base = BalloonGeometry.tailBase(body.left, body.top, body.right, body.bottom,
                radius, tipX, tipY, halfWidth);
        Path tail = new Path();
        tail.moveTo(tipX, tipY);
        tail.lineTo(base[0], base[1]);
        tail.lineTo(base[2], base[3]);
        tail.close();
        if (!shape.op(tail, Path.Op.UNION)) {
            throw new IllegalStateException("Balloon silhouette union failed");
        }
        return shape;
    }
}
