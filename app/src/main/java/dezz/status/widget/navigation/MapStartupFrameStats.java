/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

/** Diagnostic evidence only: a sampled producer buffer is not proof of the physical display. */
final class MapStartupFrameStats {
    static final int WIDTH = 32, HEIGHT = 18;
    static final long WINDOW_MS = 120_000L;
    static final int MAX_COPIES = 64;
    int samples, whiteSamples, whiteEpisodes, contentSamples;
    long firstWhiteMs = -1, firstContentMs = -1, whiteSampleSpanMs;
    private long previousSampleMs;
    private String previousKind = "none";

    static final class Sample {
        final String kind;
        final int whitePermille, opaquePermille, alphaPermille, minLuma, maxLuma, edges;
        Sample(String kind, int white, int opaque, int alpha, int min, int max, int edges) {
            this.kind = kind; this.whitePermille = white; this.opaquePermille = opaque;
            this.alphaPermille = alpha; this.minLuma = min; this.maxLuma = max; this.edges = edges;
        }
        String detail() {
            return "white_permille=" + whitePermille + ", opaque_permille=" + opaquePermille
                    + ", alpha_permille=" + alphaPermille + ", luma=" + minLuma + ".." + maxLuma
                    + ", edges=" + edges;
        }
    }

    static Sample classify(int[] pixels) {
        if (pixels == null || pixels.length == 0) return new Sample("empty", 0, 0, 0, 0, 0, 0);
        int white = 0, black = 0, opaque = 0, alpha = 0, edges = 0, colored = 0;
        int min = 255, max = 0, previous = -1;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i], a = p >>> 24;
            if (a >= 16) alpha++;
            if (a < 240) { previous = -1; continue; }
            opaque++;
            int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            int lo = Math.min(r, Math.min(g, b)), hi = Math.max(r, Math.max(g, b));
            int luma = (r * 54 + g * 183 + b * 19) >> 8;
            if (lo >= 240 && hi - lo <= 16) white++;
            if (hi <= 16) black++;
            if (hi - lo >= 24) colored++;
            min = Math.min(min, luma); max = Math.max(max, luma);
            if (previous >= 0 && i % WIDTH != 0 && Math.abs(luma - previous) >= 24) edges++;
            previous = luma;
        }
        int n = pixels.length, evidence = Math.max(3, n / 100);
        String kind = white * 100L >= n * 95L ? "white_candidate"
                : black * 100L >= n * 95L ? "black_candidate"
                : opaque >= evidence && (edges >= evidence || colored >= evidence)
                    && (max - min >= 18 || alpha < n / 2) ? "content_candidate"
                : alpha * 100L <= n * 5L ? "transparent"
                : opaque > 0 && max - min <= 8 ? "uniform" : "mixed_unclassified";
        return new Sample(kind, white * 1000 / n, opaque * 1000 / n, alpha * 1000 / n,
                opaque == 0 ? 0 : min, max, edges);
    }

    void accept(Sample sample, long elapsedMs) {
        samples++;
        if ("white_candidate".equals(sample.kind)) {
            whiteSamples++;
            if (firstWhiteMs < 0) firstWhiteMs = elapsedMs;
            if ("white_candidate".equals(previousKind))
                whiteSampleSpanMs += Math.max(0L, elapsedMs - previousSampleMs);
            else whiteEpisodes++;
        }
        if ("content_candidate".equals(sample.kind)) {
            contentSamples++;
            if (firstContentMs < 0) firstContentMs = elapsedMs;
        }
        previousKind = sample.kind;
        previousSampleMs = elapsedMs;
    }

    /** Failed reads break observation continuity; their time must not count as observed white. */
    void gap() { previousKind = "unknown"; }

    static long intervalMs(long elapsedMs) {
        return elapsedMs < 10_000 ? 500 : elapsedMs < 30_000 ? 2_000 : 5_000;
    }

    String summary() {
        return "samples=" + samples + ", white_samples=" + whiteSamples
                + ", white_episodes=" + whiteEpisodes + ", white_sample_span_ms=" + whiteSampleSpanMs
                + ", first_white_ms=" + firstWhiteMs + ", first_content_candidate_ms=" + firstContentMs
                + ", content_samples=" + contentSamples;
    }
}
