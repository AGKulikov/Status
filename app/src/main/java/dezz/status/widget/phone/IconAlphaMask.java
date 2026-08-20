/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

/** Applies a raster alpha mask without relying on Canvas compositing or hardware clipping. */
final class IconAlphaMask {
    private IconAlphaMask() {
    }

    static void apply(int[] pixels, int[] maskPixels) {
        if (pixels == null || maskPixels == null || pixels.length != maskPixels.length) {
            throw new IllegalArgumentException("Source and mask pixels must have equal lengths");
        }
        for (int index = 0; index < pixels.length; index++) {
            int sourceAlpha = pixels[index] >>> 24;
            int maskAlpha = maskPixels[index] >>> 24;
            int outputAlpha = (sourceAlpha * maskAlpha + 127) / 255;
            pixels[index] = (pixels[index] & 0x00FFFFFF) | (outputAlpha << 24);
        }
    }
}
