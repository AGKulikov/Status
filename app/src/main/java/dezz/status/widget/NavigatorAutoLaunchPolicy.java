/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

/** Retry only a launch that never produced a visible Yandex surface. MAIN-thread state. */
final class NavigatorAutoLaunchPolicy {
    private NavigatorAutoLaunchPolicy() {}
    static boolean retry(long now, long started, long dispatched, int attempts,
                         boolean focused, boolean absent, long observed, long lastPresent) {
        return started >= 0 && attempts > 0 && attempts < 2 && focused && absent
                && now >= dispatched && now - dispatched >= 15_000
                && now >= started && now - started <= 40_000
                && observed >= dispatched && now >= observed && now - observed <= 3_000
                && lastPresent < started;
    }
}
