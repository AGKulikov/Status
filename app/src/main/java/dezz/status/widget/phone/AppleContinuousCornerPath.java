/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;

/**
 * Android path equivalent of Apple's continuous rounded-rectangle family.
 *
 * <p>A circular round rect joins each straight edge to a quarter circle. Apple continuous
 * corners keep changing curvature through the whole corner, producing the familiar app-icon
 * squircle without a visible straight-to-arc join. Android 9 has no public equivalent, so this
 * class traces a local exponent-five superellipse into the caller's exact bounds. The same path
 * is used as the final alpha mask and as the border centreline.</p>
 */
final class AppleContinuousCornerPath {
    /** Exponent five closely matches the continuous iOS app-icon/notification silhouette. */
    static final double EXPONENT = 5.0d;
    /** Continuous corners occupy more edge length than a circular corner with the same radius. */
    static final float CONTINUOUS_EXTENT_MULTIPLIER = 1.5286648f;
    private static final int SAMPLES_PER_CORNER = 32;

    private AppleContinuousCornerPath() {
    }

    static float clampedExtent(@NonNull RectF bounds, float requestedRadius) {
        float maximum = Math.max(0f, Math.min(bounds.width(), bounds.height()) / 2f);
        return Math.max(0f, Math.min(requestedRadius * CONTINUOUS_EXTENT_MULTIPLIER,
                maximum));
    }

    /** Replaces {@code target} with one clockwise, closed continuous-corner contour. */
    static void set(@NonNull Path target, @NonNull RectF bounds, float requestedRadius) {
        target.reset();
        if (bounds.width() <= 0f || bounds.height() <= 0f) return;

        float extent = clampedExtent(bounds, requestedRadius);
        if (extent <= 0f) {
            target.addRect(bounds, Path.Direction.CW);
            return;
        }

        float left = bounds.left;
        float top = bounds.top;
        float right = bounds.right;
        float bottom = bounds.bottom;

        target.moveTo(left + extent, top);
        target.lineTo(right - extent, top);
        // Top-right: top edge -> right edge.
        for (int index = 1; index <= SAMPLES_PER_CORNER; index++) {
            float axis = axis(index);
            float partner = partner(axis);
            target.lineTo(
                    right - extent + extent * axis,
                    top + extent * partner);
        }

        target.lineTo(right, bottom - extent);
        // Bottom-right: right edge -> bottom edge.
        for (int index = 1; index <= SAMPLES_PER_CORNER; index++) {
            float axis = axis(index);
            float partner = partner(axis);
            target.lineTo(
                    right - extent * partner,
                    bottom - extent + extent * axis);
        }

        target.lineTo(left + extent, bottom);
        // Bottom-left: bottom edge -> left edge.
        for (int index = 1; index <= SAMPLES_PER_CORNER; index++) {
            float axis = axis(index);
            float partner = partner(axis);
            target.lineTo(
                    left + extent - extent * axis,
                    bottom - extent * partner);
        }

        target.lineTo(left, top + extent);
        // Top-left: left edge -> top edge.
        for (int index = 1; index <= SAMPLES_PER_CORNER; index++) {
            float axis = axis(index);
            float partner = partner(axis);
            target.lineTo(
                    left + extent * partner,
                    top + extent - extent * axis);
        }
        target.close();
    }

    /** Dense samples at both ends avoid faceting where the curve meets a straight side. */
    private static float axis(int index) {
        double unit = Math.max(0d, Math.min(1d,
                index / (double) SAMPLES_PER_CORNER));
        double sine = Math.sin(unit * Math.PI * 0.5d);
        return (float) (sine * sine);
    }

    /** Paired axis of x^n + y^n = 1, expressed as distance from the outer edge. */
    private static float partner(float axis) {
        double powered = Math.pow(Math.max(0d, Math.min(1d, axis)), EXPONENT);
        return (float) (1d - Math.pow(Math.max(0d, 1d - powered), 1d / EXPONENT));
    }
}
