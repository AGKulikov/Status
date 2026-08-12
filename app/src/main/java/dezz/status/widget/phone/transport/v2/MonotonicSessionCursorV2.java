/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Positive per-route-epoch cursor which fails closed instead of reusing a token after wrap. */
public final class MonotonicSessionCursorV2 {
    private long next;

    public MonotonicSessionCursorV2() {
        this(1L);
    }

    MonotonicSessionCursorV2(long first) {
        if (first <= 0L) throw new IllegalArgumentException("first must be positive");
        this.next = first;
    }

    public long next() {
        if (next == Long.MAX_VALUE) {
            throw new IllegalStateException("session cursor exhausted; fresh route epoch required");
        }
        return next++;
    }
}
