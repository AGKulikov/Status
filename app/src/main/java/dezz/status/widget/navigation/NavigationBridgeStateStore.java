/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Monotonic in-memory state shared by the endpoint and future independent HUD elements. */
public final class NavigationBridgeStateStore {
    public interface Listener { void onNavigationBridgeStateChanged(); }

    @NonNull private static String sessionId = "";
    @Nullable private static NavigationSnapshotV2 snapshot;
    @Nullable private static NavigationRouteGeometryV2 routeGeometry;
    @NonNull private static final Set<Listener> listeners = new HashSet<>();

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
        listeners.add(listener);
    }

    public static synchronized void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    @NonNull public static synchronized String sessionId() { return sessionId; }
    @Nullable public static synchronized NavigationSnapshotV2 snapshot() { return snapshot; }
    @Nullable public static synchronized NavigationRouteGeometryV2 routeGeometry() {
        return routeGeometry;
    }

    private static void notifyListeners() {
        final ArrayList<Listener> copy;
        synchronized (NavigationBridgeStateStore.class) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy) {
            try { listener.onNavigationBridgeStateChanged(); }
            catch (RuntimeException ignored) {}
        }
    }

    @NonNull
    static String boundedSession(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < 8 || value.length() > 128 || value.indexOf('\u0000') >= 0) return "";
        return value;
    }
}
