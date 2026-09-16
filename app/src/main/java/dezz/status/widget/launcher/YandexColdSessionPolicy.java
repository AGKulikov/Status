/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

final class YandexColdSessionPolicy {
    private YandexColdSessionPolicy() {}
    static boolean mayPlayUnready(boolean exactBootstrapRequested, boolean sessionPlayAttempted) {
        return exactBootstrapRequested && !sessionPlayAttempted;
    }
}
