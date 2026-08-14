/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered in-memory hold queue; payload persistence would retain private notification text. */
public final class PhoneNotificationDeferralQueue<T> {
    /**
     * A ten-minute blocker would need more than one notification every 2.3 seconds to hit this
     * ceiling. Keeping the sanitized text of 256 items is bounded to a few MiB on the 2 GB KX11;
     * an impossible/untrusted flood is reported as an explicit summary by WidgetService instead
     * of growing until Android kills the process.
     */
    public static final int MAX_ITEMS = 256;
    private final ArrayDeque<Entry<T>> values = new ArrayDeque<>();

    /** @return true when retained verbatim, false when the caller must aggregate overflow. */
    public boolean offer(@NonNull T value, long enqueuedAtElapsed) {
        if (values.size() >= MAX_ITEMS) return false;
        values.addLast(new Entry<>(value, enqueuedAtElapsed));
        return true;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public void clear() {
        values.clear();
    }

    /** Removes every item whose individual maximum wait has elapsed, preserving arrival order. */
    @NonNull
    public List<T> drainDue(long nowElapsed, int maxWaitSeconds) {
        if (values.isEmpty()) return Collections.emptyList();
        List<T> due = new ArrayList<>();
        while (!values.isEmpty()) {
            Entry<T> first = values.peekFirst();
            if (first == null || PhoneNotificationDeferralPolicy.deadline(
                    first.enqueuedAtElapsed, maxWaitSeconds) > nowElapsed) {
                break;
            }
            due.add(values.removeFirst().value);
        }
        return due;
    }

    /** Removes the complete queue in arrival order when the foreground blocker leaves. */
    @NonNull
    public List<T> drainAll() {
        if (values.isEmpty()) return Collections.emptyList();
        List<T> result = new ArrayList<>(values.size());
        while (!values.isEmpty()) result.add(values.removeFirst().value);
        return result;
    }

    /** @return nearest monotonic deadline, or {@code -1} when empty. */
    public long nextDeadline(int maxWaitSeconds) {
        Entry<T> first = values.peekFirst();
        return first == null ? -1L : PhoneNotificationDeferralPolicy.deadline(
                first.enqueuedAtElapsed, maxWaitSeconds);
    }

    private static final class Entry<T> {
        @NonNull final T value;
        final long enqueuedAtElapsed;

        Entry(@NonNull T value, long enqueuedAtElapsed) {
            this.value = value;
            this.enqueuedAtElapsed = enqueuedAtElapsed;
        }
    }
}
