/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Pure geometry policy for the ECARX window inventory.
 *
 * <p>Yandex uses the same content Activity classes in its normal task and in the vendor freeform
 * task, so neither package nor class identity describes the presentation mode. The ECARX window
 * service does expose the actual frame. This policy intentionally leaves the middle band unknown:
 * a synthetic "Navigator in window" rule may match only an unmistakably bounded frame, while a
 * nearly display-sized frame is explicitly full-screen.</p>
 */
final class NavigatorWindowFramePolicy {
    enum State { UNKNOWN, ABSENT, WINDOWED, FULLSCREEN }

    static final class Frame {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Frame(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        long area() { return (long) width() * (long) height(); }
        boolean isValid() { return right > left && bottom > top; }

        @NonNull @Override public String toString() {
            return "[" + left + "," + top + "][" + right + "," + bottom + "]";
        }
    }

    static final class WindowSample {
        @NonNull final String packageName;
        final int displayId;
        final int type;
        final int visibility;
        final boolean yandexOwned;
        @Nullable final Frame frame;

        WindowSample(@NonNull String packageName, int displayId, int type, int visibility,
                     boolean yandexOwned, @Nullable Frame frame) {
            this.packageName = packageName;
            this.displayId = displayId;
            this.type = type;
            this.visibility = visibility;
            this.yandexOwned = yandexOwned;
            this.frame = frame;
        }
    }

    static final class Result {
        @NonNull final State state;
        @Nullable final WindowSample evidence;
        final int visibleCandidateCount;

        Result(@NonNull State state, @Nullable WindowSample evidence,
               int visibleCandidateCount) {
            this.state = state;
            this.evidence = evidence;
            this.visibleCandidateCount = visibleCandidateCount;
        }
    }

    private static final int VIEW_VISIBLE = 0;
    // Keep the classifier JVM-only: these are Android's stable FIRST/LAST_APPLICATION_WINDOW
    // values, copied here so geometry tests do not need android.jar.
    private static final int FIRST_APPLICATION_WINDOW = 1;
    private static final int LAST_APPLICATION_WINDOW = 99;

    private NavigatorWindowFramePolicy() {}

    @NonNull
    static Result classify(@Nullable Frame displayBounds, int targetDisplayId,
                           @Nullable List<WindowSample> windows) {
        if (displayBounds == null || !displayBounds.isValid() || windows == null) {
            return new Result(State.UNKNOWN, null, 0);
        }
        WindowSample largest = null;
        WindowSample uncertain = null;
        int candidates = 0;
        boolean candidateWithoutFrame = false;
        for (WindowSample sample : windows) {
            if (sample == null || !sample.yandexOwned
                    || sample.displayId != targetDisplayId
                    || sample.visibility != VIEW_VISIBLE) {
                continue;
            }
            candidates++;
            if (!isApplicationWindowType(sample.type)) {
                if (uncertain == null) uncertain = sample;
                continue;
            }
            if (sample.frame == null || !sample.frame.isValid()) {
                candidateWithoutFrame = true;
                if (uncertain == null) uncertain = sample;
                continue;
            }
            if (largest == null || sample.frame.area() > largest.frame.area()) {
                largest = sample;
            }
        }
        if (largest == null) {
            return new Result(candidates == 0 ? State.ABSENT : State.UNKNOWN,
                    uncertain, candidates);
        }
        if (isNearFullscreen(largest.frame, displayBounds)) {
            return new Result(State.FULLSCREEN, largest, candidates);
        }
        // Never call a small auxiliary frame "Navigator in window" while another visible
        // Yandex application window has no usable geometry.
        if (candidateWithoutFrame) {
            return new Result(State.UNKNOWN, largest, candidates);
        }
        if (isClearlyWindowed(largest.frame, displayBounds)) {
            return new Result(State.WINDOWED, largest, candidates);
        }
        return new Result(State.UNKNOWN, largest, candidates);
    }

    private static boolean isApplicationWindowType(int type) {
        return type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW;
    }

    private static boolean isNearFullscreen(@NonNull Frame frame, @NonNull Frame display) {
        int horizontalTolerance = Math.max(24, display.width() / 25); // 4%
        int verticalTolerance = Math.max(24, display.height() / 25);
        return Math.abs(frame.left - display.left) <= horizontalTolerance
                && Math.abs(frame.top - display.top) <= verticalTolerance
                && Math.abs(frame.right - display.right) <= horizontalTolerance
                && Math.abs(frame.bottom - display.bottom) <= verticalTolerance;
    }

    private static boolean isClearlyWindowed(@NonNull Frame frame, @NonNull Frame display) {
        int containmentTolerance = Math.max(16,
                Math.max(display.width(), display.height()) / 100);
        if (frame.left < display.left - containmentTolerance
                || frame.top < display.top - containmentTolerance
                || frame.right > display.right + containmentTolerance
                || frame.bottom > display.bottom + containmentTolerance) {
            return false;
        }
        long displayArea = display.area();
        if (displayArea <= 0L) return false;
        // A dialog/toast from the Yandex process is also an application window. It is not the
        // actual navigation surface, so require a substantial content-sized frame.
        boolean contentSized = (long) frame.width() * 100L
                >= (long) display.width() * 35L
                && (long) frame.height() * 100L
                >= (long) display.height() * 35L
                && frame.area() * 100L >= displayArea * 20L;
        if (!contentSized) return false;
        boolean materiallyNarrower = (long) frame.width() * 100L
                <= (long) display.width() * 90L;
        boolean materiallyShorter = (long) frame.height() * 100L
                <= (long) display.height() * 90L;
        boolean materiallySmallerArea = frame.area() * 100L <= displayArea * 88L;
        // A normal full-screen Activity may lose one axis to a persistent system inset. Requiring
        // both dimensions to be materially bounded keeps that layout out of the floating bucket.
        return materiallySmallerArea && materiallyNarrower && materiallyShorter;
    }
}
