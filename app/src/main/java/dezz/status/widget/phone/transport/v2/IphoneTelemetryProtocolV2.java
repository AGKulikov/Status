/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Compact, deterministic Helper telemetry frame; independent of ATT MTU negotiation. */
public final class IphoneTelemetryProtocolV2 {
    public static final int FRAME_BYTES = 8;

    private static final int OPCODE = 'T';
    private static final int FLAG_BATTERY_VALID = 1;
    private static final int FLAG_EXTERNAL_POWER = 1 << 1;
    private static final int FLAG_LOCKED = 1 << 2;
    private static final int CHARGE_SHIFT = 3;
    private static final int CHARGE_MASK = 3 << CHARGE_SHIFT;
    private static final int KNOWN_FLAGS = FLAG_BATTERY_VALID | FLAG_EXTERNAL_POWER
            | FLAG_LOCKED | CHARGE_MASK;

    public enum ChargeState {
        UNKNOWN(0, "unknown"),
        DISCHARGING(1, "discharging"),
        CHARGING(2, "charging"),
        FULL(3, "full");

        final int wire;
        final String label;

        ChargeState(int wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        static ChargeState fromWire(int wire) {
            for (ChargeState value : values()) if (value.wire == wire) return value;
            return null;
        }
    }

    public enum Network {
        UNKNOWN(0, ""),
        OFFLINE(1, "offline"),
        WIFI(2, "wifi"),
        LTE(3, "lte"),
        NR5G(4, "5g"),
        CELLULAR_3G(5, "3g"),
        CELLULAR_2G(6, "2g");

        final int wire;
        final String label;

        Network(int wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        static Network fromWire(int wire) {
            for (Network value : values()) if (value.wire == wire) return value;
            return null;
        }
    }

    private IphoneTelemetryProtocolV2() {
    }

    public static byte[] encode(Integer batteryPercent, boolean externalPower,
                                ChargeState chargeState, Network network,
                                boolean locked, int sequence) {
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100)) {
            throw new IllegalArgumentException("batteryPercent out of range");
        }
        if (sequence < 0 || sequence > 0xffff) {
            throw new IllegalArgumentException("sequence out of range");
        }
        if (chargeState == null || network == null) {
            throw new NullPointerException("chargeState/network");
        }
        int flags = (batteryPercent == null ? 0 : FLAG_BATTERY_VALID)
                | (externalPower ? FLAG_EXTERNAL_POWER : 0)
                | (locked ? FLAG_LOCKED : 0)
                | chargeState.wire << CHARGE_SHIFT;
        byte[] frame = new byte[FRAME_BYTES];
        frame[0] = (byte) OPCODE;
        frame[1] = (byte) IphoneBleProtocolV2.VERSION;
        frame[2] = (byte) flags;
        frame[3] = (byte) (batteryPercent == null ? 0xff : batteryPercent);
        frame[4] = (byte) network.wire;
        frame[5] = (byte) sequence;
        frame[6] = (byte) (sequence >>> 8);
        frame[7] = crc8(frame, FRAME_BYTES - 1);
        return frame;
    }

    public static IphoneTelemetryV2 decode(byte[] frame) {
        if (frame == null || frame.length != FRAME_BYTES
                || (frame[0] & 0xff) != OPCODE
                || (frame[1] & 0xff) != IphoneBleProtocolV2.VERSION
                || frame[7] != crc8(frame, FRAME_BYTES - 1)) {
            return null;
        }
        int flags = frame[2] & 0xff;
        if ((flags & ~KNOWN_FLAGS) != 0) return null;
        boolean batteryValid = (flags & FLAG_BATTERY_VALID) != 0;
        int rawBattery = frame[3] & 0xff;
        if (batteryValid ? rawBattery > 100 : rawBattery != 0xff) return null;
        ChargeState charge = ChargeState.fromWire((flags & CHARGE_MASK) >>> CHARGE_SHIFT);
        Network network = Network.fromWire(frame[4] & 0xff);
        if (charge == null || network == null) return null;
        int sequence = (frame[5] & 0xff) | (frame[6] & 0xff) << 8;
        return new IphoneTelemetryV2(batteryValid ? rawBattery : null,
                (flags & FLAG_EXTERNAL_POWER) != 0, charge.label, network.label,
                (flags & FLAG_LOCKED) != 0, sequence);
    }

    /** CRC-8/ATM, polynomial 0x07, initial value 0. */
    static byte crc8(byte[] bytes, int length) {
        int crc = 0;
        for (int index = 0; index < length; index++) {
            crc ^= bytes[index] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? (crc << 1 ^ 0x07) : crc << 1;
                crc &= 0xff;
            }
        }
        return (byte) crc;
    }
}
