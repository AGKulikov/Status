/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fixed ATT20 vehicle-control codec carried only by the authenticated v2 GATT owner.
 *
 * <p>The wire never contains an ECARX function id or zone.  {@code controlId} selects one entry
 * from {@link CarRemoteControlRegistryV1}; unknown entries fail closed.  The final CRC rejects
 * torn/misrouted callbacks, while the session sequence and transaction id provide replay and
 * duplicate fences above encrypted ATT.</p>
 */
public final class IphoneCarRemoteProtocolV1 {
    public static final int FRAME_BYTES = 20;
    public static final int VERSION = 1;
    public static final int MAGIC = 0x4e; // Natro car remote.

    public static final int FLAG_AVAILABLE = 1;
    public static final int FLAG_KNOWN = 1 << 1;
    public static final int FLAG_ACTIVE = 1 << 2;
    public static final int FLAG_MECHANICAL = 1 << 3;
    public static final int FLAG_REQUIRES_CONFIRMATION = 1 << 4;
    public static final int FLAG_MORE = 1 << 5;
    public static final int FLAG_CONFIRMED = 1 << 6;
    public static final int FLAG_ERROR = 1 << 7;

    public enum Type {
        HELLO(1), CATALOG(2), STATE(3), COMMAND(4), RESULT(5), SYNC_COMPLETE(6);
        public final int wire;
        Type(int wire) { this.wire = wire; }
        static Type fromWire(int value) {
            for (Type type : values()) if (type.wire == value) return type;
            return null;
        }
    }

    public enum Operation {
        NONE(0), SET(1), TOGGLE(2), CYCLE(3), ACTIVATE(4);
        public final int wire;
        Operation(int wire) { this.wire = wire; }
        public static Operation fromWire(int value) {
            for (Operation operation : values()) if (operation.wire == value) return operation;
            return null;
        }
    }

    public enum Result {
        OK(0), REJECTED(1), BUSY(2), UNSUPPORTED(3), STALE(4), INVALID(5), TIMEOUT(6);
        public final int wire;
        Result(int wire) { this.wire = wire; }
        public static Result fromWire(int value) {
            for (Result result : values()) if (result.wire == value) return result;
            return null;
        }
    }

    public static final class Frame {
        public final Type type;
        public final int controlId;
        /** Operation for COMMAND, result code for RESULT, descriptor kind for CATALOG. */
        public final int code;
        public final int flags;
        public final int transactionId;
        public final long sequence;
        /** Exact signed value; registry scale converts ranges without losing ECARX enum bits. */
        public final int value;
        /** Command acceptance window in 100 ms units; zero on all non-command frames. */
        public final int maxAgeDeciseconds;

        public Frame(Type type, int controlId, int code, int flags, int transactionId,
                     long sequence, int value, int maxAgeDeciseconds) {
            if (type == null) throw new NullPointerException("type");
            if ((controlId & ~0xff) != 0 || (code & ~0xff) != 0 || (flags & ~0xff) != 0
                    || (transactionId & ~0xffff) != 0
                    || (sequence & ~0xffff_ffffL) != 0
                    || (maxAgeDeciseconds & ~0xffff) != 0) {
                throw new IllegalArgumentException("car remote frame field out of range");
            }
            this.type = type;
            this.controlId = controlId;
            this.code = code;
            this.flags = flags;
            this.transactionId = transactionId;
            this.sequence = sequence;
            this.value = value;
            this.maxAgeDeciseconds = maxAgeDeciseconds;
        }
    }

    private IphoneCarRemoteProtocolV1() { }

    public static byte[] encode(Frame frame) {
        ByteBuffer buffer = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) MAGIC);
        buffer.put((byte) VERSION);
        buffer.put((byte) frame.type.wire);
        buffer.put((byte) frame.controlId);
        buffer.put((byte) frame.code);
        buffer.put((byte) frame.flags);
        buffer.putShort((short) frame.transactionId);
        buffer.putInt((int) frame.sequence);
        buffer.putInt(frame.value);
        buffer.putShort((short) frame.maxAgeDeciseconds);
        byte[] bytes = buffer.array();
        int crc = crc16(bytes, 0, FRAME_BYTES - 2);
        bytes[18] = (byte) crc;
        bytes[19] = (byte) (crc >>> 8);
        return bytes;
    }

    public static Frame decode(byte[] bytes) {
        if (bytes == null || bytes.length != FRAME_BYTES
                || (bytes[0] & 0xff) != MAGIC || (bytes[1] & 0xff) != VERSION) return null;
        int expected = (bytes[18] & 0xff) | (bytes[19] & 0xff) << 8;
        if (crc16(bytes, 0, FRAME_BYTES - 2) != expected) return null;
        Type type = Type.fromWire(bytes[2] & 0xff);
        if (type == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(3);
        int controlId = buffer.get() & 0xff;
        int code = buffer.get() & 0xff;
        int flags = buffer.get() & 0xff;
        int transactionId = buffer.getShort() & 0xffff;
        long sequence = buffer.getInt() & 0xffff_ffffL;
        int value = buffer.getInt();
        int maxAge = buffer.getShort() & 0xffff;
        if (sequence == 0L) return null;
        switch (type) {
            case HELLO:
            case SYNC_COMPLETE:
                if (controlId != 0 || code != 0 || flags != 0 || transactionId != 0
                        || value != 0 || maxAge != 0) return null;
                break;
            case CATALOG:
                int catalogFlags = FLAG_AVAILABLE | FLAG_MECHANICAL
                        | FLAG_REQUIRES_CONFIRMATION | FLAG_MORE;
                if (controlId == 0 || code < 1 || code > 5 || (flags & ~catalogFlags) != 0
                        || transactionId != 0 || value != 0 || maxAge != 0) return null;
                break;
            case STATE:
                int stateFlags = FLAG_AVAILABLE | FLAG_KNOWN | FLAG_ACTIVE
                        | FLAG_MECHANICAL | FLAG_REQUIRES_CONFIRMATION;
                if (controlId == 0 || code != 0 || (flags & ~stateFlags) != 0
                        || transactionId != 0 || maxAge != 0) return null;
                break;
            case COMMAND:
                if (controlId == 0 || transactionId == 0 || Operation.fromWire(code) == null
                        || code == Operation.NONE.wire || (flags & ~FLAG_CONFIRMED) != 0
                        || maxAge < 1 || maxAge > 50) return null;
                break;
            case RESULT:
                if (controlId == 0 || transactionId == 0 || Result.fromWire(code) == null
                        || (code == Result.OK.wire ? flags != 0 : flags != FLAG_ERROR)
                        || maxAge != 0) return null;
                break;
            default:
                return null;
        }
        try {
            return new Frame(type, controlId, code, flags, transactionId,
                    sequence, value, maxAge);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xffff. */
    public static int crc16(byte[] bytes, int offset, int length) {
        int crc = 0xffff;
        for (int index = offset; index < offset + length; index++) {
            crc ^= (bytes[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
