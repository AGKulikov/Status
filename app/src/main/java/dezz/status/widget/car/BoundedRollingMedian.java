/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dezz.status.widget.car;

import java.util.Arrays;

/**
 * Small thread-safe rolling median for noisy finite sensor streams.
 *
 * <p>The window is bounded in both meanings that matter for a vehicle sensor: it retains at most
 * {@code capacity} accepted samples and rejects values outside the configured physical range.
 * Rejected values and samples arriving inside the throttle interval do not mutate the window.
 * The caller supplies a monotonic timestamp, which keeps this pure Java class deterministic in
 * tests and avoids wall-clock corrections affecting runtime filtering.</p>
 *
 * <p>For an even-sized warm-up window the lower of the two middle values is returned. This
 * deliberately mirrors the reference mHUD filter; the configured production capacity is odd, so
 * a full window always has one unambiguous median.</p>
 */
final class BoundedRollingMedian {

    private final float[] samples;
    private final float minimum;
    private final float maximum;
    private final long minimumIntervalMillis;

    private int size;
    /** Index which receives the next sample; once full, this is also the oldest sample. */
    private int next;
    private boolean hasAcceptedTimestamp;
    private long lastAcceptedTimestampMillis;
    /** Incremented by every real source/session reset; harmless listener replacement keeps it. */
    private long epoch;

    BoundedRollingMedian(int capacity, float minimum, float maximum,
                         long minimumIntervalMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum) {
            throw new IllegalArgumentException("bounds must be finite and ordered");
        }
        if (minimumIntervalMillis < 0L) {
            throw new IllegalArgumentException("minimumIntervalMillis must not be negative");
        }
        samples = new float[capacity];
        this.minimum = minimum;
        this.maximum = maximum;
        this.minimumIntervalMillis = minimumIntervalMillis;
    }

    /**
     * Adds a valid sample unless it arrives too soon after the last accepted sample.
     *
     * @return {@code true} only when the sample was inserted into the rolling window.
     */
    synchronized boolean offer(float sample, long observedAtMonotonicMillis) {
        if (!isValid(sample)) return false;

        if (hasAcceptedTimestamp) {
            if (observedAtMonotonicMillis < lastAcceptedTimestampMillis) {
                // A monotonic clock can only move backwards across a new boot/session. Do not
                // blend samples belonging to the old time domain with the new one.
                reset();
            } else if (observedAtMonotonicMillis - lastAcceptedTimestampMillis
                    < minimumIntervalMillis) {
                return false;
            }
        }

        samples[next] = sample;
        next = (next + 1) % samples.length;
        if (size < samples.length) size++;
        hasAcceptedTimestamp = true;
        lastAcceptedTimestampMillis = observedAtMonotonicMillis;
        return true;
    }

    /**
     * Atomically accepts and reads a callback only while it still belongs to the same source
     * session.
     *
     * <p>A real unsubscribe resets the filter and advances the epoch. If it races just after
     * insertion, its {@link #reset()} waits for this monitor and clears the sample; if it wins
     * first, the epoch mismatch rejects the callback. A harmless listener replacement deliberately
     * keeps the epoch, so a genuine live sample is still retained even when its old UI delivery is
     * cancelled.</p>
     *
     * @return the current median, or {@link Float#NaN} when the callback belongs to an old epoch.
     */
    synchronized float offerIfEpoch(float sample, long observedAtMonotonicMillis,
                                    long expectedEpoch) {
        if (epoch != expectedEpoch) return Float.NaN;
        offer(sample, observedAtMonotonicMillis);
        return median();
    }

    synchronized long epoch() {
        return epoch;
    }

    /** Advances and clears the window only when the caller still owns the current session. */
    synchronized boolean resetIfEpoch(long expectedEpoch) {
        if (epoch != expectedEpoch) return false;
        reset();
        return true;
    }

    /** Returns {@link Float#NaN} while the window is empty. */
    synchronized float median() {
        if (size == 0) return Float.NaN;
        float[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        return sorted[(size - 1) / 2];
    }

    synchronized int size() {
        return size;
    }

    synchronized void reset() {
        size = 0;
        next = 0;
        hasAcceptedTimestamp = false;
        lastAcceptedTimestampMillis = 0L;
        epoch++;
    }

    private boolean isValid(float sample) {
        if (!Float.isFinite(sample)) return false;
        float magnitude = Math.abs(sample);
        if (magnitude == Float.MIN_VALUE || magnitude == Float.MAX_VALUE) return false;
        return sample >= minimum && sample <= maximum;
    }
}
