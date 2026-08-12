/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

/**
 * Exact-identity fence for framework callback objects that do not echo an operation token.
 *
 * <p>Android scan, advertising, and GATT-server callbacks can arrive after their platform owner
 * has been retired. A new attempt must never relabel such a callback with its own token. This
 * single-slot fence deliberately uses object identity rather than value equality.</p>
 */
public final class ExactCallbackAttemptFenceV2<T> {
    private T current;

    /** Claims the empty slot for one immutable callback closure. */
    public synchronized boolean begin(T exactAttempt) {
        Objects.requireNonNull(exactAttempt, "exactAttempt");
        if (current != null && current != exactAttempt) return false;
        current = exactAttempt;
        return true;
    }

    public synchronized boolean owns(T exactAttempt) {
        return exactAttempt != null && current == exactAttempt;
    }

    /** A late retirement of an old closure cannot retire the current one. */
    public synchronized boolean retire(T exactAttempt) {
        if (exactAttempt == null || current != exactAttempt) return false;
        current = null;
        return true;
    }

    public synchronized boolean isEmpty() {
        return current == null;
    }
}
