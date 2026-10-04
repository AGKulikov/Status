/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

/** Exact decimal mapping shared by touch, one-step buttons and direct entry. */
public final class SettingsNumericRange {
    public final double minimum, step;
    public final int steps;
    public SettingsNumericRange(double minimum, double step, int steps) {
        if (!Double.isFinite(minimum) || !Double.isFinite(step) || step <= 0 || steps < 0)
            throw new IllegalArgumentException("Invalid slider range");
        this.minimum = minimum; this.step = step; this.steps = steps;
    }
    public double value(int progress) {
        return minimum + step * Math.max(0, Math.min(steps, progress));
    }
    public int progress(String text) {
        double value = Double.parseDouble(text.trim().replace(',', '.'));
        if (!Double.isFinite(value) || value < minimum - 1e-8
                || value > value(steps) + 1e-8) throw new IllegalArgumentException("Out of range");
        return Math.max(0, Math.min(steps, (int) Math.round((value - minimum) / step)));
    }
    public String format(int progress) {
        return java.math.BigDecimal.valueOf(value(progress)).setScale(6,
                java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
