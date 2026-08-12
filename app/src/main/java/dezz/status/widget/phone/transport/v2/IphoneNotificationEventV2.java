/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** One decoded ANCS Notification Source event before any Control Point attribute request. */
public final class IphoneNotificationEventV2 {
    public static final int ADDED = 0;
    public static final int MODIFIED = 1;
    public static final int REMOVED = 2;
    public static final int FLAG_PRE_EXISTING = 0x04;

    public final int eventId;
    public final int flags;
    public final int categoryId;
    public final int categoryCount;
    public final long uid;
    public final long observedAtElapsedMillis;

    public IphoneNotificationEventV2(int eventId, int flags, int categoryId,
                                     int categoryCount, long uid,
                                     long observedAtElapsedMillis) {
        if (eventId < ADDED || eventId > REMOVED) {
            throw new IllegalArgumentException("unknown ANCS eventId");
        }
        if ((flags & ~0xff) != 0 || categoryId < 0 || categoryId > 0xff
                || categoryCount < 0 || categoryCount > 0xff) {
            throw new IllegalArgumentException("ANCS byte field out of range");
        }
        if (observedAtElapsedMillis < 0L) {
            throw new IllegalArgumentException("negative observation time");
        }
        this.eventId = eventId;
        this.flags = flags;
        this.categoryId = categoryId;
        this.categoryCount = categoryCount;
        this.uid = uid;
        this.observedAtElapsedMillis = observedAtElapsedMillis;
    }

    public boolean isPreExisting() {
        return (flags & FLAG_PRE_EXISTING) != 0;
    }
}
