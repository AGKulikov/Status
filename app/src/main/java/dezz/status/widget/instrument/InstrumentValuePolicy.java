/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;

/** KX11 AdaptAPI normalization shared by every instrument presentation. */
final class InstrumentValuePolicy {
    private static final float SPEED_TO_KMH = 3.72f;
    private static final float FUEL_TO_LITRES = 1f / 1_000f;

    private InstrumentValuePolicy() {}

    static float normalize(@NonNull String metricId, float rawValue) {
        if (!Float.isFinite(rawValue)) return Float.NaN;
        if ("ISensor.speed".equals(metricId)) return rawValue * SPEED_TO_KMH;
        if ("ISensor.fuel_level".equals(metricId)) return rawValue * FUEL_TO_LITRES;
        return rawValue;
    }

    static boolean differs(float previous, float next) {
        return Float.floatToIntBits(previous) != Float.floatToIntBits(next);
    }
}
