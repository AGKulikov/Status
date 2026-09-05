/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Fixed-size control frames shared by the two v2 BLE topologies.
 *
 * <p>Every frame is exactly 20 bytes and therefore fits the default ATT MTU of 23 without a
 * platform-specific MTU request.  Transport encryption and the exact current GATT owner provide
 * link authority; these frames provide protocol, role, installation, and switch-epoch binding.
 * They are deliberately not described as an application cryptographic handshake.</p>
 */
public final class IphoneBleControlProtocolV2 {
    public static final int FRAME_BYTES = 20;
    public static final int PAYLOAD_BYTES = 16;

    public static final int FLAG_TELEMETRY = 1;
    public static final int FLAG_ANCS = 1 << 1;
    private static final int PEER_PROOF_FLAGS = FLAG_TELEMETRY | FLAG_ANCS;

    private static final int OP_PEER_PROOF = 'H';
    private static final int OP_ROLE_CLOSE = 'C';
    private static final int OP_ROLE_CLOSE_ACK = 'A';

    public enum Type {
        PEER_PROOF,
        ROLE_CLOSE,
        ROLE_CLOSE_ACK
    }

    public static final class Frame {
        public final Type type;
        public final IphoneBleMode mode;
        public final int flags;
        private final byte[] payload;

        private Frame(Type type, IphoneBleMode mode, int flags, byte[] payload) {
            this.type = Objects.requireNonNull(type, "type");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.flags = flags;
            this.payload = payload.clone();
        }

        /** Stable installation UUID for PEER_PROOF, or random switch token for CLOSE/ACK. */
        public byte[] payload() {
            return payload.clone();
        }

        public boolean payloadEquals(byte[] expected) {
            return expected != null && expected.length == PAYLOAD_BYTES
                    && MessageDigest.isEqual(payload, expected);
        }

        public boolean telemetrySupported() {
            return type == Type.PEER_PROOF && (flags & FLAG_TELEMETRY) != 0;
        }

        public boolean ancsSupported() {
            return type == Type.PEER_PROOF && (flags & FLAG_ANCS) != 0;
        }
    }

    private IphoneBleControlProtocolV2() {
    }

    public static byte[] encodePeerProof(IphoneBleMode mode, UUID installationId,
                                         boolean telemetrySupported,
                                         boolean ancsSupported) {
        Objects.requireNonNull(installationId, "installationId");
        int flags = (telemetrySupported ? FLAG_TELEMETRY : 0)
                | (ancsSupported ? FLAG_ANCS : 0);
        return encode(Type.PEER_PROOF, mode, flags, uuidBytes(installationId));
    }

    /** Requests confirmed teardown of the current route and names the desired opposite route. */
    public static byte[] encodeRoleClose(IphoneBleMode targetMode, byte[] switchToken) {
        return encode(Type.ROLE_CLOSE, targetMode, 0, requirePayload(switchToken));
    }

    /** Acknowledges the exact target mode and token from a previously accepted ROLE_CLOSE. */
    public static byte[] encodeRoleCloseAck(IphoneBleMode targetMode, byte[] switchToken) {
        return encode(Type.ROLE_CLOSE_ACK, targetMode, 0, requirePayload(switchToken));
    }

    /** Creates a non-zero 128-bit switch token. The token is identity, not a logged secret. */
    public static byte[] newSwitchToken(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] token = new byte[PAYLOAD_BYTES];
        do {
            random.nextBytes(token);
        } while (allZero(token));
        return token;
    }

    /** Strict decoder: unknown versions, roles, flags, lengths, and zero identities fail closed. */
    public static Frame decode(byte[] bytes) {
        if (bytes == null || bytes.length != FRAME_BYTES) return null;
        Type type = type(bytes[0] & 0xff);
        if (type == null || (bytes[1] & 0xff) != IphoneBleProtocolV2.VERSION) return null;
        IphoneBleMode mode = IphoneBleMode.fromWireId(bytes[2] & 0xff);
        if (mode == null) return null;
        int flags = bytes[3] & 0xff;
        if (type == Type.PEER_PROOF) {
            if ((flags & ~PEER_PROOF_FLAGS) != 0 || (flags & FLAG_ANCS) == 0) return null;
        } else if (flags != 0) {
            return null;
        }
        byte[] payload = Arrays.copyOfRange(bytes, 4, FRAME_BYTES);
        if (allZero(payload)) return null;
        return new Frame(type, mode, flags, payload);
    }

    public static UUID installationUuid(Frame frame) {
        if (frame == null || frame.type != Type.PEER_PROOF) return null;
        ByteBuffer buffer = ByteBuffer.wrap(frame.payload);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] encode(Type type, IphoneBleMode mode, int flags, byte[] payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        byte[] exact = requirePayload(payload);
        if (type != Type.PEER_PROOF && flags != 0) {
            throw new IllegalArgumentException("control flags must be zero");
        }
        if (type == Type.PEER_PROOF
                && ((flags & ~PEER_PROOF_FLAGS) != 0 || (flags & FLAG_ANCS) == 0)) {
            throw new IllegalArgumentException("peer proof must advertise ANCS and known flags");
        }
        ByteBuffer frame = ByteBuffer.allocate(FRAME_BYTES);
        frame.put((byte) opcode(type));
        frame.put((byte) IphoneBleProtocolV2.VERSION);
        frame.put((byte) mode.wireId);
        frame.put((byte) flags);
        frame.put(exact);
        return frame.array();
    }

    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer bytes = ByteBuffer.allocate(PAYLOAD_BYTES);
        bytes.putLong(uuid.getMostSignificantBits());
        bytes.putLong(uuid.getLeastSignificantBits());
        return requirePayload(bytes.array());
    }

    private static byte[] requirePayload(byte[] payload) {
        if (payload == null || payload.length != PAYLOAD_BYTES || allZero(payload)) {
            throw new IllegalArgumentException("payload must be a non-zero 16-byte value");
        }
        return payload.clone();
    }

    private static boolean allZero(byte[] bytes) {
        int combined = 0;
        for (byte value : bytes) combined |= value & 0xff;
        return combined == 0;
    }

    private static int opcode(Type type) {
        switch (type) {
            case PEER_PROOF: return OP_PEER_PROOF;
            case ROLE_CLOSE: return OP_ROLE_CLOSE;
            case ROLE_CLOSE_ACK: return OP_ROLE_CLOSE_ACK;
            default: throw new AssertionError(type);
        }
    }

    private static Type type(int opcode) {
        if (opcode == OP_PEER_PROOF) return Type.PEER_PROOF;
        if (opcode == OP_ROLE_CLOSE) return Type.ROLE_CLOSE;
        if (opcode == OP_ROLE_CLOSE_ACK) return Type.ROLE_CLOSE_ACK;
        return null;
    }
}
