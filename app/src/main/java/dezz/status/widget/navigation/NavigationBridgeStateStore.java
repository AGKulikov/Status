/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.graphics.Bitmap;

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
    @Nullable private static ManeuverArtwork maneuverArtwork;
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
            maneuverArtwork = null;
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
            if (!next.routeActive || next.maneuverIdentity.isEmpty()) {
                maneuverArtwork = null;
            } else if (maneuverArtwork != null
                    && maneuverArtwork.sequence <= next.sequence
                    && !maneuverArtwork.identity.equals(next.maneuverIdentity)) {
                maneuverArtwork = null;
            }
            if (routeGeometry != null && routeGeometry.routeEpoch != next.routeEpoch) {
                routeGeometry = null;
            }
        }
        notifyListeners();
        return true;
    }

    /** Stores one exact stock icon only when it can be joined to the same maneuver snapshot. */
    public static boolean publishManeuverArtwork(
            @NonNull String sourceSession, long sequence,
            @NonNull String maneuverIdentity, @NonNull Bitmap artwork) {
        String identity = boundedIdentity(maneuverIdentity);
        if (sequence <= 0L || identity.isEmpty() || artwork.isRecycled()
                || artwork.getWidth() <= 0 || artwork.getHeight() <= 0
                || artwork.getWidth() > 256 || artwork.getHeight() > 256
                || (long) artwork.getWidth() * artwork.getHeight() > 65_536L) {
            return false;
        }
        synchronized (NavigationBridgeStateStore.class) {
            if (!sessionId.equals(boundedSession(sourceSession))) return false;
            NavigationSnapshotV2 current = snapshot;
            ManeuverArtwork existing = maneuverArtwork;
            if (existing != null && sequence < existing.sequence) return false;
            if (current != null && sequence <= current.sequence
                    && (!current.routeActive || !identity.equals(current.maneuverIdentity))) {
                return false;
            }
            maneuverArtwork = new ManeuverArtwork(sequence, identity, artwork);
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
            maneuverArtwork = null;
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

    /** Returns artwork only for the exact atomic snapshot frame which introduced it. */
    @Nullable public static synchronized Bitmap maneuverArtworkFor(
            @NonNull NavigationSnapshotV2 value) {
        ManeuverArtwork current = maneuverArtwork;
        if (current == null || !value.routeActive || value.maneuverIdentity.isEmpty()
                || current.sequence > value.sequence
                || !current.identity.equals(value.maneuverIdentity)
                || current.bitmap.isRecycled()) return null;
        return current.bitmap;
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

    @NonNull
    private static String boundedIdentity(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 512 || value.indexOf('\u0000') >= 0) return "";
        return value;
    }

    private static final class ManeuverArtwork {
        final long sequence;
        @NonNull final String identity;
        @NonNull final Bitmap bitmap;

        ManeuverArtwork(long sequence, @NonNull String identity, @NonNull Bitmap bitmap) {
            this.sequence = sequence;
            this.identity = identity;
            this.bitmap = bitmap;
        }
    }
}
