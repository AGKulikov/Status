/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Monotonic in-memory state shared by the endpoint and future independent HUD elements. */
public final class NavigationBridgeStateStore {
    @NonNull private static String sessionId = "";
    @Nullable private static NavigationSnapshotV2 snapshot;
    @Nullable private static NavigationRouteGeometryV2 routeGeometry;

    private NavigationBridgeStateStore() {}

    public static synchronized void beginSession(@NonNull String nextSessionId) {
        String normalized = boundedSession(nextSessionId);
        if (normalized.equals(sessionId)) return;
        sessionId = normalized;
        snapshot = null;
        routeGeometry = null;
    }

    public static synchronized boolean publishSnapshot(@NonNull String sourceSession,
                                                       @NonNull NavigationSnapshotV2 next) {
        if (!sessionId.equals(boundedSession(sourceSession))) return false;
        NavigationSnapshotV2 current = snapshot;
        if (current != null && next.sequence <= current.sequence) return false;
        snapshot = next;
        if (routeGeometry != null && routeGeometry.routeEpoch != next.routeEpoch) {
            routeGeometry = null;
        }
        return true;
    }

    public static synchronized boolean publishRouteGeometry(
            @NonNull String sourceSession, @NonNull NavigationRouteGeometryV2 next) {
        if (!sessionId.equals(boundedSession(sourceSession))) return false;
        NavigationSnapshotV2 current = snapshot;
        if (current != null && next.routeEpoch != current.routeEpoch) return false;
        if (routeGeometry != null && next.routeEpoch < routeGeometry.routeEpoch) return false;
        routeGeometry = next;
        return true;
    }

    public static synchronized void endSession(@NonNull String sourceSession) {
        if (!sessionId.equals(boundedSession(sourceSession))) return;
        sessionId = "";
        snapshot = null;
        routeGeometry = null;
    }

    @NonNull public static synchronized String sessionId() { return sessionId; }
    @Nullable public static synchronized NavigationSnapshotV2 snapshot() { return snapshot; }
    @Nullable public static synchronized NavigationRouteGeometryV2 routeGeometry() {
        return routeGeometry;
    }

    @NonNull
    static String boundedSession(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < 8 || value.length() > 128 || value.indexOf('\u0000') >= 0) return "";
        return value;
    }
}
