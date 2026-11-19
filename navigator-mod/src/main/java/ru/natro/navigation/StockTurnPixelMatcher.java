/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

/** Verifies the native texture at one projected point in physical map pixels. */
final class StockTurnPixelMatcher {
    private static final int MAX_PIXELS = 512 * 1024;
    private static final int PROBES = 40;
    // The nearest quarter-pixel phase is within 1/8 pixel of hardware filtering.
    private static final int[] PHASES = {0, 2, 4, 1, 3};
    private static final int[] SHIFTS = {0, -1, 1};

    static final class Pattern {
        final int width, height;
        private final int[] pixels;
        private final int[] probes;

        private Pattern(int width, int height, int[] pixels, int[] probes) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.probes = probes;
        }
    }

    static Pattern prepare(int[] texture, int width, int height) {
        if (!valid(texture, width, height) || width < 8 || height < 8) return null;
        int opaqueBlocks = 0, details = 0;
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int p = y * width + x;
                if (!opaque(texture, p, width)) continue;
                opaqueBlocks++;
                if (!detail(texture, p, width)) continue;
                details++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        // A blank panel, a shadow or a single horizontal/vertical edge cannot identify a sign.
        if (opaqueBlocks < 64 || details < 16 || maxX - minX < 4 || maxY - minY < 4) return null;
        int[] probes = new int[Math.min(details, PROBES)];
        int seen = 0, used = 0;
        for (int y = 0; y < height - 1 && used < probes.length; y++) {
            for (int x = 0; x < width - 1 && used < probes.length; x++) {
                int p = y * width + x;
                if (opaque(texture, p, width) && detail(texture, p, width)) {
                    if (seen == (long)used * (details - 1) / (probes.length - 1)) probes[used++] = p;
                    seen++;
                }
            }
        }
        return new Pattern(width, height, texture.clone(), probes);
    }

    static boolean matches(Pattern texture, int[] captured, int width, int height, int left, int top) {
        if (texture == null || !valid(captured, width, height)
                || left < -1 || left > width || top < -1 || top > height) return false;
        // Rank possible subpixel phases from the original texture. Bound the expensive
        // whole-texture pass even for a near-identical sign with one changed street letter.
        Alignment[] best = new Alignment[4];
        for (int dy : SHIFTS) {
            int y = top + dy;
            if (y < 0 || y + texture.height > height) continue;
            for (int dx : SHIFTS) {
                int x = left + dx;
                if (x < 0 || x + texture.width > width) continue;
                int start = y * width + x;
                for (int fy : PHASES) {
                    for (int fx : PHASES) {
                        int error = probeError(texture, captured, width, start, fx, fy);
                        if (error < 0) continue;
                        for (int i = 0; i < best.length; i++) {
                            if (best[i] != null && error >= best[i].error) continue;
                            System.arraycopy(best, i, best, i + 1, best.length - i - 1);
                            best[i] = new Alignment(start, fx, fy, error);
                            break;
                        }
                    }
                }
            }
        }
        for (Alignment alignment : best) {
            if (alignment != null && matchesAll(texture, captured, width, alignment)) return true;
        }
        return false;
    }

    private static final class Alignment {
        final int start, fx, fy, error;
        Alignment(int start, int fx, int fy, int error) {
            this.start = start;
            this.fx = fx;
            this.fy = fy;
            this.error = error;
        }
    }

    private static int probeError(Pattern t, int[] image, int stride, int start, int fx, int fy) {
        int sum = 0;
        for (int p : t.probes) {
            int q = start + (p / t.width) * stride + (p % t.width);
            int error = pixelError(t, p, image[q], fx, fy);
            if (error < 0) return -1;
            sum += error;
        }
        return sum;
    }

    private static boolean matchesAll(Pattern t, int[] image, int stride, Alignment alignment) {
        // An early match on the arrows/background is insufficient. Check every opaque
        // neighbourhood, including the actual lettering and the distance for this revision.
        for (int y = 0; y < t.height - 1; y++) {
            int p = y * t.width, q = alignment.start + y * stride;
            for (int x = 0; x < t.width - 1; x++, p++, q++) {
                if (opaque(t.pixels, p, t.width) && pixelError(t, p, image[q], alignment.fx, alignment.fy) < 0) return false;
            }
        }
        return true;
    }

    private static int pixelError(Pattern t, int p, int sample, int fx, int fy) {
        if (sample >>> 24 < 250) return -1;
        int error = 0;
        int a = t.pixels[p], b = t.pixels[p + 1], c = t.pixels[p + t.width], d = t.pixels[p + t.width + 1];
        for (int shift = 0; shift <= 16; shift += 8) {
            int av = (a >> shift) & 255, bv = (b >> shift) & 255;
            int cv = (c >> shift) & 255, dv = (d >> shift) & 255;
            int upper = av * (4 - fx) + bv * fx, lower = cv * (4 - fx) + dv * fx;
            int expected = (upper * (4 - fy) + lower * fy + 8) / 16;
            int gradientX = Math.max(Math.abs(av - bv), Math.abs(cv - dv));
            int gradientY = Math.max(Math.abs(av - cv), Math.abs(bv - dv));
            int tolerance = 4 + (gradientX + gradientY + 7) / 8;
            int delta = Math.abs(expected - ((sample >> shift) & 255));
            if (delta > tolerance) return -1;
            error += delta;
        }
        return error;
    }

    private static boolean opaque(int[] a, int p, int w) {
        return (a[p] & a[p + 1] & a[p + w] & a[p + w + 1]) >>> 24 == 255;
    }

    private static boolean detail(int[] a, int p, int w) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int v = (a[p] >> shift) & 255;
            if (Math.abs(v - ((a[p + 1] >> shift) & 255)) >= 24
                    || Math.abs(v - ((a[p + w] >> shift) & 255)) >= 24) return true;
        }
        return false;
    }

    private static boolean valid(int[] pixels, int width, int height) {
        long size = (long) width * height;
        return pixels != null && width > 0 && height > 0 && size <= MAX_PIXELS && size == pixels.length;
    }

    private StockTurnPixelMatcher() {}
}
