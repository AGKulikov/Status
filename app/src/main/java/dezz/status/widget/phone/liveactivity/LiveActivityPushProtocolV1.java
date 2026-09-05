/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.liveactivity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small authenticated-C5 sideband used only to provision ActivityKit push tokens and layout.
 * It deliberately has a different magic from the vehicle command protocol, so a malformed token
 * chunk can never be interpreted as a car command.
 */
public final class LiveActivityPushProtocolV1 {
    public static final int FRAME_BYTES = 20;
    public static final int VERSION = 1;
    public static final int MAGIC = 0x50;
    public static final int PAYLOAD_BYTES = 8;
    public static final int MAX_CHUNKS = 32;
    public static final int MAX_MESSAGE_BYTES = PAYLOAD_BYTES * MAX_CHUNKS;

    public static final int TYPE_PUSH_TO_START_TOKEN = 1;
    public static final int TYPE_CONFIGURATION = 2;
    public static final int TYPE_ACTIVITY_PUSH_TOKEN = 3;
    public static final int TYPE_ACTIVITY_ENDED = 4;

    private static final long ASSEMBLY_TIMEOUT_MS = 10_000L;

    public static final class Frame {
        public final int type;
        public final int messageId;
        public final int chunkIndex;
        public final int chunkCount;
        @NonNull public final byte[] payload;

        public Frame(int type, int messageId, int chunkIndex, int chunkCount,
                     @NonNull byte[] payload) {
            if (!validType(type) || (messageId & ~0xffff) != 0 || messageId == 0
                    || chunkCount < 1 || chunkCount > MAX_CHUNKS
                    || chunkIndex < 0 || chunkIndex >= chunkCount
                    || payload.length < 1 || payload.length > PAYLOAD_BYTES
                    || (chunkIndex + 1 < chunkCount && payload.length != PAYLOAD_BYTES)) {
                throw new IllegalArgumentException("live activity push frame field out of range");
            }
            this.type = type;
            this.messageId = messageId;
            this.chunkIndex = chunkIndex;
            this.chunkCount = chunkCount;
            this.payload = payload.clone();
        }
    }

    public static final class Message {
        public final int type;
        @NonNull public final byte[] payload;

        Message(int type, @NonNull byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static final class Assembly {
        final int type;
        final int messageId;
        final byte[][] chunks;
        long touchedElapsed;
        int received;

        Assembly(Frame frame, long now) {
            type = frame.type;
            messageId = frame.messageId;
            chunks = new byte[frame.chunkCount][];
            touchedElapsed = now;
        }
    }

    public static final class Reassembler {
        private final Map<Integer, Assembly> assemblies = new LinkedHashMap<>();

        @Nullable
        public synchronized Message accept(@Nullable byte[] raw) {
            Frame frame = decode(raw);
            if (frame == null) return null;
            long now = System.nanoTime() / 1_000_000L;
            assemblies.entrySet().removeIf(entry ->
                    now - entry.getValue().touchedElapsed > ASSEMBLY_TIMEOUT_MS);
            int key = frame.type << 16 | frame.messageId;
            Assembly assembly = assemblies.get(key);
            if (assembly == null || assembly.chunks.length != frame.chunkCount) {
                assembly = new Assembly(frame, now);
                assemblies.put(key, assembly);
            }
            assembly.touchedElapsed = now;
            byte[] previous = assembly.chunks[frame.chunkIndex];
            if (previous != null) {
                if (!Arrays.equals(previous, frame.payload)) assemblies.remove(key);
                return null;
            }
            assembly.chunks[frame.chunkIndex] = frame.payload;
            assembly.received++;
            if (assembly.received != assembly.chunks.length) return null;
            ByteArrayOutputStream joined = new ByteArrayOutputStream(MAX_MESSAGE_BYTES);
            for (byte[] chunk : assembly.chunks) {
                if (chunk == null) return null;
                joined.write(chunk, 0, chunk.length);
            }
            assemblies.remove(key);
            return new Message(assembly.type, joined.toByteArray());
        }

        public synchronized void clear() {
            assemblies.clear();
        }
    }

    private LiveActivityPushProtocolV1() { }

    @NonNull
    public static byte[] encode(@NonNull Frame frame) {
        byte[] bytes = new byte[FRAME_BYTES];
        bytes[0] = (byte) MAGIC;
        bytes[1] = (byte) VERSION;
        bytes[2] = (byte) frame.type;
        bytes[3] = 0;
        bytes[4] = (byte) frame.messageId;
        bytes[5] = (byte) (frame.messageId >>> 8);
        bytes[6] = (byte) frame.chunkIndex;
        bytes[7] = (byte) frame.chunkCount;
        bytes[8] = (byte) frame.payload.length;
        bytes[9] = 0;
        System.arraycopy(frame.payload, 0, bytes, 10, frame.payload.length);
        int checksum = crc16(bytes, 0, 18);
        bytes[18] = (byte) checksum;
        bytes[19] = (byte) (checksum >>> 8);
        return bytes;
    }

    @Nullable
    public static Frame decode(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length != FRAME_BYTES
                || (bytes[0] & 0xff) != MAGIC || (bytes[1] & 0xff) != VERSION
                || bytes[3] != 0 || bytes[9] != 0) return null;
        int expected = (bytes[18] & 0xff) | (bytes[19] & 0xff) << 8;
        if (crc16(bytes, 0, 18) != expected) return null;
        int type = bytes[2] & 0xff;
        int messageId = (bytes[4] & 0xff) | (bytes[5] & 0xff) << 8;
        int chunkIndex = bytes[6] & 0xff;
        int chunkCount = bytes[7] & 0xff;
        int length = bytes[8] & 0xff;
        if (!validType(type) || messageId == 0 || chunkCount < 1
                || chunkCount > MAX_CHUNKS || chunkIndex >= chunkCount
                || length < 1 || length > PAYLOAD_BYTES
                || (chunkIndex + 1 < chunkCount && length != PAYLOAD_BYTES)) return null;
        for (int index = 10 + length; index < 18; index++) {
            if (bytes[index] != 0) return null;
        }
        try {
            return new Frame(type, messageId, chunkIndex, chunkCount,
                    Arrays.copyOfRange(bytes, 10, 10 + length));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean validType(int type) {
        return type >= TYPE_PUSH_TO_START_TOKEN && type <= TYPE_ACTIVITY_ENDED;
    }

    /** CRC-16/CCITT-FALSE, identical to the existing C5 codec. */
    public static int crc16(@NonNull byte[] bytes, int offset, int length) {
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
