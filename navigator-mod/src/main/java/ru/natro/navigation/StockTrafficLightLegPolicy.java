/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Admits only non-degenerate stock legs; it does not draw or replace Navigator artwork. */
final class StockTrafficLightLegPolicy {
    private StockTrafficLightLegPolicy() {}

    static boolean usable(String leg, float bodyHeight, float cornerRadius, float halfBase) {
        if (!"LEFT_CENTER".equals(leg) && !"RIGHT_CENTER".equals(leg)) return true;
        if (!Float.isFinite(bodyHeight) || !Float.isFinite(cornerRadius)
                || !Float.isFinite(halfBase) || bodyHeight <= 0f || cornerRadius < 0f
                || halfBase <= 0f) return false;
        float centre = bodyHeight * .5f;
        float start = Math.max(centre - halfBase, cornerRadius);
        float end = Math.min(centre + halfBase, bodyHeight - cornerRadius);
        // TrafficLightViewImpl sets radius=floor(height/2). Its capsule consequently leaves
        // zero (or one rounding pixel) of straight side for the stock centre-leg base. The
        // native Bezier construction then collapses. Use another original leg, not an
        // invented connector or a changed geographic anchor.
        return end - start > 1f;
    }
}
