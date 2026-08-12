/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

/** One platform operation emitted by a pure route reducer. */
public final class BleRouteEffect {
    public enum Type {
        START_SCAN,
        STOP_SCAN,
        CONNECT_SELECTED_BOND,
        CONNECT_GATT,
        REASSERT_SAME_GATT,
        CLOSE_GATT,
        DISCOVER_SERVICES,
        READ_PEER_PROOF,
        SUBSCRIBE_TELEMETRY,
        SUBSCRIBE_ROUTE_CONTROL,
        SUBSCRIBE_GATT_SERVICE_CHANGED,
        SUBSCRIBE_ANCS_NOTIFICATION_SOURCE,
        SUBSCRIBE_ANCS_DATA_SOURCE,
        ARM_ANCS_PARSER,
        RESET_SESSION_STATE,
        OPEN_GATT_SERVER,
        ADD_V2_SERVER_SERVICE,
        CLOSE_GATT_SERVER,
        START_ADVERTISING,
        STOP_ADVERTISING,
        BIND_INBOUND_PEER,
        DISCONNECT_INBOUND_PEER,
        OBSERVE_REVERSE_CLIENT,
        CLOSE_REVERSE_CLIENT,
        DISCOVER_ANCS,
        ARM_DEADLINE,
        CANCEL_DEADLINE,
        ARM_RETRY,
        REPORT_READY,
        REPORT_DOWN,
        REPORT_ERROR,
        REPORT_HELPER_ID_LEARNED,
        REPORT_LOCAL_TERMINAL
    }

    public final Type type;
    public final BleRouteToken token;
    /** Relative timeout/delay. Zero means the effect has no timer parameter. */
    public final long delayMillis;
    public final String detail;
    public final IphoneBleAdvertisement advertisement;

    private BleRouteEffect(Type type, BleRouteToken token, long delayMillis,
                           String detail, IphoneBleAdvertisement advertisement) {
        this.type = Objects.requireNonNull(type, "type");
        this.token = token;
        if (delayMillis < 0L) throw new IllegalArgumentException("negative delay");
        this.delayMillis = delayMillis;
        this.detail = detail == null ? "" : detail;
        this.advertisement = advertisement;
    }

    public static BleRouteEffect operation(Type type, BleRouteToken token, String detail) {
        return new BleRouteEffect(type, Objects.requireNonNull(token, "token"), 0L,
                detail, null);
    }

    public static BleRouteEffect deadline(BleRouteToken token, long delayMillis) {
        return new BleRouteEffect(Type.ARM_DEADLINE, Objects.requireNonNull(token, "token"),
                delayMillis, "", null);
    }

    public static BleRouteEffect retry(BleRouteToken token, long delayMillis, String detail) {
        return new BleRouteEffect(Type.ARM_RETRY, Objects.requireNonNull(token, "token"),
                delayMillis, detail, null);
    }

    public static BleRouteEffect advertise(BleRouteToken token,
                                           IphoneBleAdvertisement advertisement) {
        return new BleRouteEffect(Type.START_ADVERTISING,
                Objects.requireNonNull(token, "token"), 0L, "",
                Objects.requireNonNull(advertisement, "advertisement"));
    }
}
