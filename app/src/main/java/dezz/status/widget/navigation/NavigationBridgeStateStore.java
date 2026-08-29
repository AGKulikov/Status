/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Monotonic in-memory state shared by the endpoint and future independent HUD elements. */
public final class NavigationBridgeStateStore {
    public interface Listener { void onNavigationBridgeStateChanged(); }

    @NonNull private static String sessionId = "";
    @Nullable private static NavigationSnapshotV2 snapshot;
    @Nullable private static NavigationRouteGeometryV2 routeGeometry;
    @NonNull private static final Set<Listener> listeners = new HashSet<>();
    @NonNull private static volatile Listener[] listenerSnapshot = new Listener[0];

    private NavigationBridgeStateStore() {}

    public static void beginSession(@NonNull String nextSessionId) {
        String normalized = boundedSession(nextSessionId);
        synchronized (NavigationBridgeStateStore.class) {
            if (normalized.equals(sessionId)) return;
            sessionId = normalized;
            snapshot = null;
            routeGeometry = null;
        }
        notifyListeners();
    }

    public static boolean publishSnapshot(@NonNull String sourceSession,
                                          @NonNull NavigationSnapshotV2 next) {
        synchronized (NavigationBridgeStateStore.class) {
            if (!sessionId.equals(boundedSession(sourceSession))) return false;
            NavigationSnapshotV2 current = snapshot;
            if (current != null && next.sequence <= current.sequence) return false;
            snapshot = next;
            if (routeGeometry != null && routeGeometry.routeEpoch != next.routeEpoch) {
                routeGeometry = null;
            }
        }
        notifyListeners();
        return true;
    }

    public static boolean publishRouteGeometry(
            @NonNull String sourceSession, @NonNull NavigationRouteGeometryV2 next) {
        synchronized (NavigationBridgeStateStore.class) {
            if (!sessionId.equals(boundedSession(sourceSession))) return false;
            NavigationSnapshotV2 current = snapshot;
            if (current != null && next.routeEpoch != current.routeEpoch) return false;
            if (routeGeometry != null && next.routeEpoch < routeGeometry.routeEpoch) return false;
            routeGeometry = next;
        }
        notifyListeners();
        return true;
    }

    public static void endSession(@NonNull String sourceSession) {
        synchronized (NavigationBridgeStateStore.class) {
            if (!sessionId.equals(boundedSession(sourceSession))) return;
            sessionId = "";
            snapshot = null;
            routeGeometry = null;
        }
        notifyListeners();
    }

    public static synchronized void addListener(@NonNull Listener listener) {
        if (listeners.add(listener)) rebuildListenerSnapshotLocked();
    }

    public static synchronized void removeListener(@NonNull Listener listener) {
        if (listeners.remove(listener)) rebuildListenerSnapshotLocked();
    }

    @NonNull public static synchronized String sessionId() { return sessionId; }
    @Nullable public static synchronized NavigationSnapshotV2 snapshot() { return snapshot; }
    @Nullable public static synchronized NavigationRouteGeometryV2 routeGeometry() {
        return routeGeometry;
    }

    private static void notifyListeners() {
        for (Listener listener : listenerSnapshot) {
            try { listener.onNavigationBridgeStateChanged(); }
            catch (RuntimeException ignored) {}
        }
    }

    /** Listener lifecycle is rare; state publication is hot and remains allocation-free. */
    private static void rebuildListenerSnapshotLocked() {
        listenerSnapshot = listeners.toArray(new Listener[0]);
    }

    @NonNull
    static String boundedSession(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < 8 || value.length() > 128 || value.indexOf('\u0000') >= 0) return "";
        return value;
    }
}
