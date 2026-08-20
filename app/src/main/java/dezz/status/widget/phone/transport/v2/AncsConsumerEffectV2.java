/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Arrays;

/** Platform-neutral output from {@link AncsConsumerCoreV2}. */
public final class AncsConsumerEffectV2 {
    public enum Type {
        WRITE_CONTROL_POINT,
        ARM_REQUEST_DEADLINE,
        CANCEL_REQUEST_DEADLINE,
        ARM_REPLAY_QUIET,
        CANCEL_REPLAY_QUIET,
        NOTIFICATION_EVENT,
        NOTIFICATION,
        APP_NAME,
        REPLAY_CHECKPOINT,
        REPLAY_SUMMARY,
        QUEUE_DROPPED,
        MALFORMED_SOURCE,
        TERMINATE_SESSION
    }

    public final Type type;
    public final AncsRequestTokenV2 request;
    public final byte[] value;
    public final IphoneNotificationEventV2 event;
    public final IphoneNotificationV2 notification;
    public final IphoneAppNameV2 appName;
    public final long count;
    public final long generation;
    public final String detail;

    private AncsConsumerEffectV2(Type type, AncsRequestTokenV2 request, byte[] value,
                                 IphoneNotificationEventV2 event,
                                 IphoneNotificationV2 notification,
                                 IphoneAppNameV2 appName, long count, long generation,
                                 String detail) {
        this.type = type;
        this.request = request;
        this.value = value == null ? new byte[0] : Arrays.copyOf(value, value.length);
        this.event = event;
        this.notification = notification;
        this.appName = appName;
        this.count = count;
        this.generation = generation;
        this.detail = detail == null ? "" : detail;
    }

    static AncsConsumerEffectV2 request(Type type, AncsRequestTokenV2 request,
                                        byte[] value, String detail) {
        return new AncsConsumerEffectV2(type, request, value, null, null, null,
                0L, 0L, detail);
    }

    static AncsConsumerEffectV2 event(IphoneNotificationEventV2 event) {
        return new AncsConsumerEffectV2(Type.NOTIFICATION_EVENT, null, null, event,
                null, null, 0L, 0L, "realtime ANCS source event");
    }

    static AncsConsumerEffectV2 notification(IphoneNotificationV2 notification) {
        return new AncsConsumerEffectV2(Type.NOTIFICATION, null, null, null,
                notification, null, 0L, 0L, "complete ANCS attributes");
    }

    static AncsConsumerEffectV2 appName(IphoneAppNameV2 appName) {
        return new AncsConsumerEffectV2(Type.APP_NAME, null, null, null,
                null, appName, 0L, 0L, "complete ANCS app display name");
    }

    static AncsConsumerEffectV2 count(Type type, long count, long generation,
                                      String detail) {
        return new AncsConsumerEffectV2(type, null, null, null, null, null,
                count, generation, detail);
    }

    static AncsConsumerEffectV2 simple(Type type, String detail) {
        return count(type, 0L, 0L, detail);
    }
}
