/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** Pure generation token; callers serialize access together with the cache it protects. */
final class NavigationCacheEpoch {
    private long generation;

    long capture() {
        return generation;
    }

    boolean isCurrent(long token) {
        return token == generation;
    }

    void invalidate() {
        generation++;
    }
}
