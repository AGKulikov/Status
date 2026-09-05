/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dezz.status.widget.shell.PrivilegedShell;

/**
 * Applies a real, global screen reservation through Android's WindowManager shell command.
 *
 * <p>Every operation is asynchronous. Starting a newer operation supersedes callbacks and
 * follow-up commands from an older generation. Shell commands are always verified by reading the
 * effective overscan back. Normal restore writes the exact journalled baseline; it never uses
 * {@code reset}, because a head unit may already have non-zero factory overscan.
 */
public final class ScreenReservationController {
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern DISPLAY_HEADER = Pattern.compile("(?i)^\\s*Display\\s+(\\d+)\\s*:");
    private static final Pattern DISPLAY_ID_FIELD = Pattern.compile("(?i)\\bmDisplayId\\s*=\\s*(\\d+)");
    private static final Pattern INLINE_DISPLAY_ID = Pattern.compile("(?i)\\bdisplayId\\s*[=: ]\\s*(\\d+)");
    private static final Pattern DISPLAY_INFO_OVERSCAN = Pattern.compile(
            "(?i)\\boverscan\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,"
                    + "\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");

    public enum Edge { TOP, BOTTOM, LEFT, RIGHT }

    public enum Operation { APPLY, RESTORE, RECONCILE, EMERGENCY_RESET }

    /** Four WindowManager overscan values in left, top, right, bottom order. */
    public static final class Insets {
        public static final Insets ZERO = new Insets(0, 0, 0, 0);

        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Insets(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @NonNull
        Insets add(@NonNull Edge edge, int extentPx) {
            switch (edge) {
                case TOP:
                    return new Insets(left, checkedAdd(top, extentPx), right, bottom);
                case BOTTOM:
                    return new Insets(left, top, right, checkedAdd(bottom, extentPx));
                case LEFT:
                    return new Insets(checkedAdd(left, extentPx), top, right, bottom);
                case RIGHT:
                    return new Insets(left, top, checkedAdd(right, extentPx), bottom);
                default:
                    throw new IllegalArgumentException("Unknown edge " + edge);
            }
        }

        private static int checkedAdd(int value, int delta) {
            long result = (long) value + delta;
            if (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) {
                throw new IllegalArgumentException("Reservation extent overflows overscan");
            }
            return (int) result;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) return true;
            if (!(other instanceof Insets)) return false;
            Insets value = (Insets) other;
            return left == value.left && top == value.top
                    && right == value.right && bottom == value.bottom;
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, top, right, bottom);
        }

        @NonNull
        @Override
        public String toString() {
            return left + "," + top + "," + right + "," + bottom;
        }
    }

    /** Desired state used after boot, service restart, or a settings change. */
    public static final class Desired {
        public final boolean reserveSpace;
        @Nullable public final Edge edge;
        public final int extentPx;
        public final int displayId;

        private Desired(boolean reserveSpace, @Nullable Edge edge, int extentPx, int displayId) {
            this.reserveSpace = reserveSpace;
            this.edge = edge;
            this.extentPx = extentPx;
            this.displayId = displayId;
        }

        /** Overlay mode: remove any reservation previously managed by this application. */
        @NonNull
        public static Desired overlay() {
            return new Desired(false, null, 0, 0);
        }

        /** Reservation mode for one physical display. */
        @NonNull
        public static Desired reservation(@NonNull Edge edge, int extentPx, int displayId) {
            return new Desired(true, edge, extentPx, displayId);
        }
    }

    public static final class Result {
        public final boolean success;
        @NonNull public final Operation operation;
        public final int displayId;
        @NonNull public final String message;
        @Nullable public final Insets baseline;
        @Nullable public final Insets target;
        @Nullable public final Insets actual;

        private Result(boolean success, @NonNull Operation operation, int displayId,
                       @NonNull String message, @Nullable Insets baseline,
                       @Nullable Insets target, @Nullable Insets actual) {
            this.success = success;
            this.operation = operation;
            this.displayId = displayId;
            this.message = message;
            this.baseline = baseline;
            this.target = target;
            this.actual = actual;
        }
    }

    public interface Callback {
        /** With the production runner this is delivered on Android's main thread. */
        void onResult(@NonNull Result result);
    }

    interface CommandRunner {
        void run(@NonNull String command, @NonNull CommandResultCallback callback);
    }

    interface CommandResultCallback {
        void onResult(@Nullable String output, @Nullable String error);
    }

    private static final class PrivilegedCommandRunner implements CommandRunner {
        private final PrivilegedShell shell;

        PrivilegedCommandRunner(@NonNull PrivilegedShell shell) {
            this.shell = shell;
        }

        @Override
        public void run(@NonNull String command, @NonNull CommandResultCallback callback) {
            shell.runCommand(command, callback::onResult);
        }
    }

    private final Object lock = new Object();
    private final ScreenReservationStateStore stateStore;
    private final CommandRunner commandRunner;
    private final int sdkInt;
    private long generation;
    /** Set only after read-only `wm help` explicitly advertises per-display overscan. */
    private boolean nonDefaultDisplayTargetingProven;

    public ScreenReservationController(@NonNull Context context) {
        Context app = context.getApplicationContext();
        stateStore = new ScreenReservationStateStore(app);
        commandRunner = new PrivilegedCommandRunner(PrivilegedShell.get(app));
        sdkInt = Build.VERSION.SDK_INT;
    }

    /** Test-only dependency-injection constructor. */
    ScreenReservationController(@NonNull ScreenReservationStateStore stateStore,
                                @NonNull CommandRunner commandRunner) {
        this.stateStore = stateStore;
        this.commandRunner = commandRunner;
        this.sdkInt = Build.VERSION_CODES.Q;
    }

    /** Applies one reservation, first restoring any other display managed by this app. */
    public void apply(@NonNull Edge edge, int extentPx, int displayId,
                      @NonNull Callback callback) {
        startReservation(Operation.APPLY, edge, extentPx, displayId, callback);
    }

    /** Invalidates callbacks and follow-up mutations from an in-flight apply operation. */
    public void cancelPending() {
        beginGeneration();
    }

    /** Restores every managed display to its exact journalled baseline. */
    public void restore(@NonNull Callback callback) {
        long current = beginGeneration();
        restoreEntries(current, Operation.RESTORE,
                new ArrayList<>(stateStore.managedEntries()), 0, callback, null);
    }

    /** Converges global WindowManager state to the currently desired panel mode. */
    public void reconcile(@NonNull Desired desired, @NonNull Callback callback) {
        if (!desired.reserveSpace) {
            long current = beginGeneration();
            restoreEntries(current, Operation.RECONCILE,
                    new ArrayList<>(stateStore.managedEntries()), 0, callback, null);
            return;
        }
        if (desired.edge == null) {
            long current = beginGeneration();
            deliver(current, callback, failure(Operation.RECONCILE, desired.displayId,
                    "Reservation edge is missing", null, null, null));
            return;
        }
        startReservation(Operation.RECONCILE, desired.edge, desired.extentPx,
                desired.displayId, callback);
    }

    /**
     * Last-resort recovery only. Unlike {@link #restore}, this deliberately discards factory or
     * user overscan and asks WindowManager to reset the selected display to zero/default.
     */
    public void emergencyReset(int displayId, @NonNull Callback callback) {
        long current = beginGeneration();
        if (displayId < 0) {
            deliver(current, callback, failure(Operation.EMERGENCY_RESET, displayId,
                    "Display id must be non-negative", null, null, null));
            return;
        }
        requireSafeDisplayTargeting(displayId, current, capabilityError -> {
            if (!isCurrent(current)) return;
            if (capabilityError != null) {
                deliver(current, callback, failure(Operation.EMERGENCY_RESET, displayId,
                        capabilityError, null, Insets.ZERO, null));
                return;
            }
            runEmergencyReset(current, displayId, callback);
        });
    }

    private void runEmergencyReset(long current, int displayId, @NonNull Callback callback) {
        commandRunner.run(resetCommand(displayId), (output, error) -> {
            if (!isCurrent(current)) return;
            if (error != null) {
                deliver(current, callback, failure(Operation.EMERGENCY_RESET, displayId,
                        "Emergency reset failed: " + error, null, Insets.ZERO, null));
                return;
            }
            query(displayId, current, (actual, queryError) -> {
                if (!isCurrent(current)) return;
                if (queryError != null || actual == null || !Insets.ZERO.equals(actual)) {
                    deliver(current, callback, failure(Operation.EMERGENCY_RESET, displayId,
                            queryError != null ? queryError : "Emergency reset verification failed",
                            null, Insets.ZERO, actual));
                    return;
                }
                boolean cleared;
                synchronized (lock) {
                    if (generation != current) return;
                    cleared = stateStore.completeRestore(displayId);
                }
                if (!cleared) {
                    deliver(current, callback, failure(Operation.EMERGENCY_RESET, displayId,
                            "Screen was reset but recovery journal could not be cleared",
                            null, Insets.ZERO, actual));
                    return;
                }
                deliver(current, callback, success(Operation.EMERGENCY_RESET, displayId,
                        "Emergency reset verified", null, Insets.ZERO, actual));
            });
        });
    }

    private void startReservation(@NonNull Operation operation, @NonNull Edge edge,
                                  int extentPx, int displayId, @NonNull Callback callback) {
        long current = beginGeneration();
        if (edge == null) {
            deliver(current, callback, failure(operation, displayId,
                    "Reservation edge is missing", null, null, null));
            return;
        }
        if (extentPx <= 0) {
            deliver(current, callback, failure(operation, displayId,
                    "Reservation extent must be greater than zero", null, null, null));
            return;
        }
        if (displayId < 0) {
            deliver(current, callback, failure(operation, displayId,
                    "Display id must be non-negative", null, null, null));
            return;
        }
        if (!legacyOverscanSupported(sdkInt)) {
            deliver(current, callback, failure(operation, displayId,
                    "Legacy WindowManager overscan is unavailable on Android "
                            + sdkInt,
                    null, null, null));
            return;
        }
        ArrayList<ScreenReservationStateStore.Entry> otherDisplays = new ArrayList<>();
        for (ScreenReservationStateStore.Entry entry : stateStore.managedEntries()) {
            if (entry.displayId != displayId) otherDisplays.add(entry);
        }
        restoreEntries(current, operation, otherDisplays, 0, callback,
                () -> applyOne(current, operation, edge, extentPx, displayId, callback));
    }

    private void applyOne(long expectedGeneration, @NonNull Operation operation,
                          @NonNull Edge edge, int extentPx, int displayId,
                          @NonNull Callback callback) {
        if (!isCurrent(expectedGeneration)) return;
        requireSafeDisplayTargeting(displayId, expectedGeneration, capabilityError -> {
            if (!isCurrent(expectedGeneration)) return;
            if (capabilityError != null) {
                deliver(expectedGeneration, callback, failure(operation, displayId,
                        capabilityError, null, null, null));
                return;
            }
            queryAndApply(expectedGeneration, operation, edge, extentPx, displayId, callback);
        });
    }

    private void queryAndApply(long expectedGeneration, @NonNull Operation operation,
                               @NonNull Edge edge, int extentPx, int displayId,
                               @NonNull Callback callback) {
        query(displayId, expectedGeneration, (observed, queryError) -> {
            if (!isCurrent(expectedGeneration)) return;
            if (queryError != null || observed == null) {
                deliver(expectedGeneration, callback, failure(operation, displayId,
                        queryError != null ? queryError : "Unable to read current overscan",
                        null, null, observed));
                return;
            }

            ScreenReservationStateStore.Entry existing = stateStore.get(displayId);
            Insets baseline = existing == null ? observed : existing.baseline;
            final Insets target;
            try {
                target = baseline.add(edge, extentPx);
            } catch (IllegalArgumentException error) {
                deliver(expectedGeneration, callback, failure(operation, displayId,
                        error.getMessage() == null ? "Invalid reservation" : error.getMessage(),
                        baseline, null, observed));
                return;
            }

            boolean journalled;
            synchronized (lock) {
                if (generation != expectedGeneration) return;
                journalled = stateStore.journalBeforeMutation(displayId, baseline, target);
            }
            if (!journalled) {
                deliver(expectedGeneration, callback, failure(operation, displayId,
                        "Recovery baseline could not be saved; screen was not changed",
                        baseline, target, observed));
                return;
            }

            if (target.equals(observed)) {
                deliver(expectedGeneration, callback, success(operation, displayId,
                        "Reservation already matches desired state", baseline, target, observed));
                return;
            }
            commandRunner.run(setCommand(displayId, target), (output, error) -> {
                if (!isCurrent(expectedGeneration)) return;
                if (error != null) {
                    deliver(expectedGeneration, callback, failure(operation, displayId,
                            "Reservation command failed: " + error,
                            baseline, target, observed));
                    return;
                }
                verify(expectedGeneration, operation, displayId, baseline, target, callback);
            });
        });
    }

    private void verify(long expectedGeneration, @NonNull Operation operation, int displayId,
                        @NonNull Insets baseline, @NonNull Insets target,
                        @NonNull Callback callback) {
        query(displayId, expectedGeneration, (actual, error) -> {
            if (!isCurrent(expectedGeneration)) return;
            if (error != null || actual == null) {
                deliver(expectedGeneration, callback, failure(operation, displayId,
                        error != null ? error : "Reservation verification produced no value",
                        baseline, target, actual));
            } else if (!target.equals(actual)) {
                deliver(expectedGeneration, callback, failure(operation, displayId,
                        "Reservation verification mismatch", baseline, target, actual));
            } else {
                deliver(expectedGeneration, callback, success(operation, displayId,
                        "Reservation verified", baseline, target, actual));
            }
        });
    }

    private void restoreEntries(long expectedGeneration, @NonNull Operation operation,
                                @NonNull List<ScreenReservationStateStore.Entry> entries,
                                int index, @NonNull Callback callback,
                                @Nullable Runnable afterSuccess) {
        if (!isCurrent(expectedGeneration)) return;
        if (index >= entries.size()) {
            if (afterSuccess != null) {
                afterSuccess.run();
            } else {
                deliver(expectedGeneration, callback, success(operation, -1,
                        "Exact screen baseline restored", null, null, null));
            }
            return;
        }

        ScreenReservationStateStore.Entry entry = entries.get(index);
        requireSafeDisplayTargeting(entry.displayId, expectedGeneration, capabilityError -> {
            if (!isCurrent(expectedGeneration)) return;
            if (capabilityError != null) {
                deliver(expectedGeneration, callback, failure(operation, entry.displayId,
                        capabilityError,
                        entry.baseline, entry.baseline, null));
                return;
            }
            runExactRestore(expectedGeneration, operation, entries, index, callback,
                    afterSuccess, entry);
        });
    }

    private void runExactRestore(long expectedGeneration, @NonNull Operation operation,
                                 @NonNull List<ScreenReservationStateStore.Entry> entries,
                                 int index, @NonNull Callback callback,
                                 @Nullable Runnable afterSuccess,
                                 @NonNull ScreenReservationStateStore.Entry entry) {
        commandRunner.run(setCommand(entry.displayId, entry.baseline), (output, error) -> {
            if (!isCurrent(expectedGeneration)) return;
            if (error != null) {
                deliver(expectedGeneration, callback, failure(operation, entry.displayId,
                        "Exact restore command failed: " + error,
                        entry.baseline, entry.baseline, null));
                return;
            }
            query(entry.displayId, expectedGeneration, (actual, queryError) -> {
                if (!isCurrent(expectedGeneration)) return;
                if (queryError != null || actual == null || !entry.baseline.equals(actual)) {
                    deliver(expectedGeneration, callback, failure(operation, entry.displayId,
                            queryError != null ? queryError : "Exact restore verification failed",
                            entry.baseline, entry.baseline, actual));
                    return;
                }

                boolean cleared;
                synchronized (lock) {
                    if (generation != expectedGeneration) return;
                    cleared = stateStore.completeRestore(entry.displayId);
                }
                if (!cleared) {
                    deliver(expectedGeneration, callback, failure(operation, entry.displayId,
                            "Screen was restored but recovery journal could not be cleared",
                            entry.baseline, entry.baseline, actual));
                    return;
                }
                restoreEntries(expectedGeneration, operation, entries, index + 1,
                        callback, afterSuccess);
            });
        });
    }

    private interface CapabilityCallback {
        void onResult(@Nullable String error);
    }

    /**
     * Old/vendor `wm` implementations silently ignore a trailing {@code -d} for overscan and
     * mutate display 0. For every non-default display we fail closed until the read-only help
     * output explicitly advertises per-display addressing for the overscan subcommand.
     */
    private void requireSafeDisplayTargeting(int displayId, long expectedGeneration,
                                             @NonNull CapabilityCallback callback) {
        if (displayId == 0) {
            callback.onResult(null);
            return;
        }
        synchronized (lock) {
            if (generation != expectedGeneration) return;
            if (nonDefaultDisplayTargetingProven) {
                callback.onResult(null);
                return;
            }
        }
        commandRunner.run(capabilityCommand(), (output, error) -> {
            if (!isCurrent(expectedGeneration)) return;
            if (error == null && advertisesPerDisplayOverscan(output)) {
                synchronized (lock) {
                    if (generation != expectedGeneration) return;
                    nonDefaultDisplayTargetingProven = true;
                }
                callback.onResult(null);
            } else {
                callback.onResult("This firmware does not explicitly support safe overscan "
                        + "targeting for display " + displayId + "; screen was not changed");
            }
        });
    }

    static boolean advertisesPerDisplayOverscan(@Nullable String output) {
        if (output == null) return false;
        for (String line : output.split("\\r?\\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("overscan") && lower.contains("-d")
                    && lower.contains("display")) return true;
        }
        return false;
    }

    private interface QueryCallback {
        void onResult(@Nullable Insets value, @Nullable String error);
    }

    private void query(int displayId, long expectedGeneration,
                       @NonNull QueryCallback callback) {
        commandRunner.run(queryCommand(displayId), (output, error) -> {
            if (!isCurrent(expectedGeneration)) return;
            Insets parsed = error == null ? parseOverscan(output) : null;
            if (parsed != null) {
                callback.onResult(parsed, null);
                return;
            }

            // Stock Android 9 accepts `wm overscan` only as a setter, while some vendor builds
            // also print the current value. Fall back to DisplayManager's read-only dump on the
            // stock implementation. DisplayInfo includes non-zero overscan and omits it at zero.
            commandRunner.run(displayDumpCommand(), (dump, dumpError) -> {
                if (!isCurrent(expectedGeneration)) return;
                if (dumpError != null) {
                    String detail = error != null ? error + "; " + dumpError : dumpError;
                    callback.onResult(null, "Unable to read overscan: " + detail);
                    return;
                }
                Insets fallback = parseDisplayDumpOverscan(dump, displayId);
                if (fallback == null) {
                    callback.onResult(null,
                            "WindowManager returned an unreadable overscan value");
                } else {
                    callback.onResult(fallback, null);
                }
            });
        });
    }

    @Nullable
    static Insets parseOverscan(@Nullable String output) {
        if (output == null) return null;
        Insets last = null;
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            int overscanAt = lower.indexOf("overscan");
            if (overscanAt < 0) continue;
            if (overscanAt > 0) {
                char previous = lower.charAt(overscanAt - 1);
                if (Character.isLetterOrDigit(previous) || previous == '_') continue;
            }

            int separator = Math.max(line.indexOf(':', overscanAt),
                    line.indexOf('=', overscanAt));
            String values = separator >= 0
                    ? line.substring(separator + 1) : line.substring(overscanAt + 8);
            Matcher matcher = INTEGER.matcher(values);
            int[] parsed = new int[4];
            int count = 0;
            try {
                while (count < parsed.length && matcher.find()) {
                    parsed[count++] = Integer.parseInt(matcher.group());
                }
            } catch (NumberFormatException ignored) {
                count = 0;
            }
            if (count == 4) last = new Insets(parsed[0], parsed[1], parsed[2], parsed[3]);
        }
        return last;
    }

    /** Parses the selected logical display from `dumpsys display` without mistaking frame bounds
     * such as mOverscan=(0,0)-(1920,1080) for overscan inset values. */
    @Nullable
    static Insets parseDisplayDumpOverscan(@Nullable String output, int displayId) {
        if (output == null || displayId < 0) return null;
        Integer currentDisplay = null;
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            Matcher header = DISPLAY_HEADER.matcher(line);
            if (header.find()) {
                currentDisplay = parseDisplayId(header.group(1));
            } else {
                Matcher idField = DISPLAY_ID_FIELD.matcher(line);
                if (idField.find()) currentDisplay = parseDisplayId(idField.group(1));
            }

            if (!line.contains("DisplayInfo{")) continue;
            Matcher inlineId = INLINE_DISPLAY_ID.matcher(line);
            Integer infoDisplay = inlineId.find()
                    ? parseDisplayId(inlineId.group(1)) : currentDisplay;
            if (infoDisplay == null || infoDisplay != displayId) continue;

            Matcher overscan = DISPLAY_INFO_OVERSCAN.matcher(line);
            if (overscan.find()) {
                try {
                    return new Insets(Integer.parseInt(overscan.group(1)),
                            Integer.parseInt(overscan.group(2)),
                            Integer.parseInt(overscan.group(3)),
                            Integer.parseInt(overscan.group(4)));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            // Android 8/9's DisplayInfo.toString() emits the overscan tuple only when at least
            // one side is non-zero. Finding the selected DisplayInfo without it therefore means
            // the exact effective value is zero on those versions.
            return Insets.ZERO;
        }
        return null;
    }

    @Nullable
    private static Integer parseDisplayId(@Nullable String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @NonNull
    static String queryCommand(int displayId) {
        return displayId == DisplayIds.DEFAULT
                ? "wm overscan" : "wm overscan -d " + displayId;
    }

    @NonNull
    static String displayDumpCommand() {
        return "dumpsys display";
    }

    @NonNull
    static String capabilityCommand() {
        return "wm help";
    }

    @NonNull
    static String setCommand(int displayId, @NonNull Insets value) {
        return displayId == DisplayIds.DEFAULT
                ? "wm overscan " + value
                : "wm overscan " + value + " -d " + displayId;
    }

    @NonNull
    static String resetCommand(int displayId) {
        return displayId == DisplayIds.DEFAULT
                ? "wm overscan reset" : "wm overscan reset -d " + displayId;
    }

    static boolean legacyOverscanSupported(int sdkInt) {
        return sdkInt <= Build.VERSION_CODES.Q;
    }

    /** Avoids an android.view.Display dependency in pure command-builder tests. */
    private static final class DisplayIds {
        static final int DEFAULT = 0;
        private DisplayIds() {}
    }

    private long beginGeneration() {
        synchronized (lock) {
            return ++generation;
        }
    }

    private boolean isCurrent(long expected) {
        synchronized (lock) {
            return generation == expected;
        }
    }

    private void deliver(long expected, @NonNull Callback callback, @NonNull Result result) {
        if (isCurrent(expected)) callback.onResult(result);
    }

    @NonNull
    private static Result success(@NonNull Operation operation, int displayId,
                                  @NonNull String message, @Nullable Insets baseline,
                                  @Nullable Insets target, @Nullable Insets actual) {
        return new Result(true, operation, displayId, message, baseline, target, actual);
    }

    @NonNull
    private static Result failure(@NonNull Operation operation, int displayId,
                                  @NonNull String message, @Nullable Insets baseline,
                                  @Nullable Insets target, @Nullable Insets actual) {
        return new Result(false, operation, displayId, message, baseline, target, actual);
    }
}
