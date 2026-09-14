/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Includes the shadow union used by stock BalloonTextureImpl.create(), then its raster scale. */
final class BalloonTextureBounds {
    private BalloonTextureBounds() {}

    static int pixels(float extentWithLeg, float shadowRadius, float shadowOffset, float scale) {
        float radius = Math.max(0f, shadowRadius);
        float before = Math.max(0f, radius - shadowOffset);
        float after = Math.max(0f, radius + shadowOffset);
        return Math.max(1, (int) ((extentWithLeg + before + after) * Math.max(.01f, scale)));
    }
}
