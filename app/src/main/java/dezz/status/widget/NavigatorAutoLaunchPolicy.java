/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

/** Retry only a launch that never produced a visible Yandex surface. MAIN-thread state. */
final class NavigatorAutoLaunchPolicy {
    private NavigatorAutoLaunchPolicy() {}
    /** Activity.onUserInteraction also receives key-up/media/volume events at boot. */
    static boolean navigationKeyDown(int action,int keyCode,int repeatCount) {
        if(action!=0 || repeatCount!=0) return false;
        return keyCode==3 || keyCode==4 || (keyCode>=19 && keyCode<=23)
                || keyCode==61 || keyCode==62 || keyCode==66 || keyCode==82 || keyCode==111;
    }
    static boolean retry(long now, long started, long dispatched, int attempts,
                         boolean focused, boolean absent, long observed, long lastPresent) {
        return retry(now,started,dispatched,attempts,focused,absent,observed,lastPresent,false);
    }
    static boolean retry(long now,long started,long dispatched,int attempts,
                         boolean focused,boolean absent,long observed,long lastPresent,boolean failed) {
        return started >= 0 && attempts > 0 && attempts < 2 && focused && absent
                && now >= dispatched && (failed || now - dispatched >= 30_000)
                && now >= started && now - started <= 60_000
                && observed >= dispatched && now >= observed && now - observed <= 3_000
                && lastPresent < started;
    }
}
