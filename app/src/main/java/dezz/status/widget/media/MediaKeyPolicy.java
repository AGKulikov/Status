/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** MEDIA routing only; no player selection, click recognition or synthetic key pairs. */
public final class MediaKeyPolicy {
    public static final String STOCK = "android.intent.action.MEDIA_BUTTON";
    public static final String MCONFIG = "android.intent.action.IEDIA_BUTTON";
    // Same-length private input address: MConfig's always-enabled receiver cannot rebroadcast it.
    public static final String NATRO = "android.intent.action.NEDIA_BUTTON";
    public enum Route { IGNORE, AUDIO_MANAGER, STOCK_BROADCAST }

    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) {
            return size() > 128;
        }
    };

    public static Route route(String action, boolean enabled, boolean defaultSource) {
        if (!STOCK.equals(action) && !NATRO.equals(action)) return Route.IGNORE;
        if (STOCK.equals(action) && (!enabled || defaultSource)) return Route.IGNORE;
        if (enabled && !defaultSource) return Route.AUDIO_MANAGER;
        return NATRO.equals(action) ? Route.STOCK_BROADCAST : Route.IGNORE;
    }

    /** Identity excludes broadcast action: a returned MEDIA event is the same original key. */
    public synchronized boolean admit(int code, int action, int repeat, long downTime,
                                      long eventTime, int device, int scan, int source) {
        String identity = code + ":" + action + ":" + repeat + ":" + downTime + ":"
                + eventTime + ":" + device + ":" + scan + ":" + source;
        return seen.put(identity, Boolean.TRUE) == null;
    }
}
