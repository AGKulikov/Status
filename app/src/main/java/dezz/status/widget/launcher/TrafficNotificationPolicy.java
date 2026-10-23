/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/** Preserve immediate visible changes and periodic liveness without timestamp-only bursts. */
final class TrafficNotificationPolicy {
    private long lastNotified = -1L;

    synchronized boolean shouldNotify(String before, String after, long uptime) {
        if (!before.equals(after) || lastNotified < 0 || uptime < lastNotified
                || uptime - lastNotified >= 1000L) {
            lastNotified = uptime;
            return true;
        }
        return false;
    }
}
