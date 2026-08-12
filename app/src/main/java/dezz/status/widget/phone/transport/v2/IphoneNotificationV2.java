/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Complete ANCS notification detached from the legacy codec's nested output type. */
public final class IphoneNotificationV2 {
    public final int eventId;
    public final int flags;
    public final int categoryId;
    public final int categoryCount;
    public final long uid;
    public final String appIdentifier;
    public final String appName;
    public final String title;
    public final String subtitle;
    public final String message;
    public final String date;
    public final long observedAtElapsedMillis;

    public IphoneNotificationV2(int eventId, int flags, int categoryId, int categoryCount,
                                long uid, String appIdentifier, String appName,
                                String title, String subtitle, String message, String date,
                                long observedAtElapsedMillis) {
        // Reuse one validation vocabulary for source and completed-attribute delivery.
        new IphoneNotificationEventV2(eventId, flags, categoryId, categoryCount, uid,
                observedAtElapsedMillis);
        this.eventId = eventId;
        this.flags = flags;
        this.categoryId = categoryId;
        this.categoryCount = categoryCount;
        this.uid = uid;
        this.appIdentifier = safe(appIdentifier);
        this.appName = safe(appName);
        this.title = safe(title);
        this.subtitle = safe(subtitle);
        this.message = safe(message);
        this.date = safe(date);
        this.observedAtElapsedMillis = observedAtElapsedMillis;
    }

    public boolean isPreExisting() {
        return (flags & IphoneNotificationEventV2.FLAG_PRE_EXISTING) != 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
