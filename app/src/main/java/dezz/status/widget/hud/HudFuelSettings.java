/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import org.json.JSONException;
import org.json.JSONObject;

/** Shared, exportable fuel presentation settings. No vehicle control or guessed missing data. */
public final class HudFuelSettings {
    public static final double DEFAULT_CAPACITY_LITRES = 64d;
    public double yellowBelowLitres = 15d;
    public double redBelowLitres = 10d;
    public double customCapacityLitres = DEFAULT_CAPACITY_LITRES;
    public boolean useDefaultCapacity = true;
    public boolean hideAboveThreshold = true;
    public boolean refillOnlyInPark;

    public HudFuelSettings copy() {
        HudFuelSettings out = new HudFuelSettings();
        out.yellowBelowLitres = yellowBelowLitres;
        out.redBelowLitres = redBelowLitres;
        out.customCapacityLitres = customCapacityLitres;
        out.useDefaultCapacity = useDefaultCapacity;
        out.hideAboveThreshold = hideAboveThreshold;
        out.refillOnlyInPark = refillOnlyInPark;
        return out;
    }

    public double capacityLitres() {
        return useDefaultCapacity ? DEFAULT_CAPACITY_LITRES : customCapacityLitres;
    }

    public double refillLitres(double remainingLitres) {
        return Double.isFinite(remainingLitres) && remainingLitres >= 0d
                ? Math.max(0d, capacityLitres() - remainingLitres) : Double.NaN;
    }

    public boolean showLevel(double remainingLitres) {
        return !hideAboveThreshold || !Double.isFinite(remainingLitres)
                || remainingLitres <= yellowBelowLitres;
    }

    public boolean showRefill(boolean inPark) {
        return !refillOnlyInPark || inPark;
    }

    public int levelColor(double remainingLitres, int normalColor) {
        if (!Double.isFinite(remainingLitres) || remainingLitres < 0d) return normalColor;
        if (remainingLitres < redBelowLitres) return 0xFFFF453A;
        if (remainingLitres < yellowBelowLitres) return 0xFFFFCC00;
        return normalColor;
    }

    public void validate() {
        if (!Double.isFinite(yellowBelowLitres) || yellowBelowLitres < 0d
                || !Double.isFinite(redBelowLitres) || redBelowLitres < 0d
                || redBelowLitres > yellowBelowLitres) {
            throw new IllegalArgumentException("Пороги должны быть неотрицательными; красный не выше жёлтого");
        }
        if (!Double.isFinite(customCapacityLitres) || customCapacityLitres <= 0d) {
            throw new IllegalArgumentException("Объём бака должен быть больше нуля");
        }
    }

    public JSONObject toJson() throws JSONException {
        validate();
        return new JSONObject().put("yellowBelowLitres", yellowBelowLitres)
                .put("redBelowLitres", redBelowLitres)
                .put("customCapacityLitres", customCapacityLitres)
                .put("useDefaultCapacity", useDefaultCapacity)
                .put("hideAboveThreshold", hideAboveThreshold)
                .put("refillOnlyInPark", refillOnlyInPark);
    }

    public static HudFuelSettings fromJson(JSONObject source) {
        HudFuelSettings out = new HudFuelSettings();
        if (source == null) return out;
        out.yellowBelowLitres = source.optDouble("yellowBelowLitres", 15d);
        out.redBelowLitres = source.optDouble("redBelowLitres", 10d);
        out.customCapacityLitres = source.optDouble("customCapacityLitres", DEFAULT_CAPACITY_LITRES);
        out.useDefaultCapacity = source.optBoolean("useDefaultCapacity", true);
        out.hideAboveThreshold = source.optBoolean("hideAboveThreshold", true);
        out.refillOnlyInPark = source.optBoolean("refillOnlyInPark", false);
        out.validate();
        return out;
    }

    /** Preserve each old widget's choices until the user explicitly applies shared HUD settings. */
    static HudFuelSettings fromLegacy(JSONObject options) {
        HudFuelSettings out = new HudFuelSettings();
        out.yellowBelowLitres = nonnegative(options.optDouble("yellowThreshold", 20d), 20d);
        out.redBelowLitres = Math.min(out.yellowBelowLitres,
                nonnegative(options.optDouble("redThreshold", 10d), 10d));
        out.customCapacityLitres = options.optDouble("tankCapacityLitres", DEFAULT_CAPACITY_LITRES);
        if (!Double.isFinite(out.customCapacityLitres) || out.customCapacityLitres <= 0d)
            out.customCapacityLitres = DEFAULT_CAPACITY_LITRES;
        out.useDefaultCapacity = options.optBoolean("automaticCapacity", true);
        out.hideAboveThreshold = options.optBoolean("hideAboveThreshold", false);
        out.refillOnlyInPark = options.optBoolean("onlyInPark", true);
        return out;
    }

    private static double nonnegative(double value, double fallback) {
        return Double.isFinite(value) && value >= 0d ? value : fallback;
    }
}
