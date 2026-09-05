/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

/** Exact single in-flight ANCS Control Point transaction. */
public final class AncsRequestTokenV2 {
    public enum Kind { NOTIFICATION_ATTRIBUTES, APP_DISPLAY_NAME }

    public final AncsSessionTokenV2 session;
    public final long requestId;
    public final Kind kind;

    public AncsRequestTokenV2(AncsSessionTokenV2 session, long requestId, Kind kind) {
        this.session = Objects.requireNonNull(session, "session");
        if (requestId <= 0L) throw new IllegalArgumentException("requestId must be positive");
        this.requestId = requestId;
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AncsRequestTokenV2)) return false;
        AncsRequestTokenV2 that = (AncsRequestTokenV2) other;
        return requestId == that.requestId && kind == that.kind
                && session.equals(that.session);
    }

    @Override public int hashCode() {
        return Objects.hash(session, requestId, kind);
    }

    @Override public String toString() {
        return session + "/" + kind + "-" + requestId;
    }
}
