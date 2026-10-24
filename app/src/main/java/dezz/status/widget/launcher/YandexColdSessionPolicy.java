/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

final class YandexColdSessionPolicy {
    private YandexColdSessionPolicy() {}
    static boolean retryReceiver(boolean sent, boolean grace, boolean browser, boolean session, int attempts) {
        return sent && !grace && !browser && !session && attempts < 5;
    }
    /** The browser owns the one unready PLAY until it supplies its exact token or times out. */
    static boolean unreadyPlayReserved(boolean sessionPlayAttempted, boolean browserRequested) {
        return sessionPlayAttempted || browserRequested;
    }
    static boolean mayPlayUnready(boolean exactBootstrapRequested, boolean sessionPlayAttempted) {
        return exactBootstrapRequested && !sessionPlayAttempted;
    }
}
