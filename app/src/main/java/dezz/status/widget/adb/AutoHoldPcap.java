/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import java.util.function.Consumer;

/** Bounded PCAP/Ethernet/VLAN/IPv4/UDP reader; unknown/truncated input never means false. */
public final class AutoHoldPcap {
    private final Consumer<Boolean> state;
    private byte[] pending = new byte[24];
    private int used, phase;
    private boolean little;
    public AutoHoldPcap(Consumer<Boolean> state) { this.state = state; }
    public void accept(byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length) {
            int size = Math.min(pending.length - used, bytes.length - offset);
            System.arraycopy(bytes, offset, pending, used, size); used += size; offset += size;
            if (used != pending.length) continue;
            if (phase == 0) {
                int magic = be32(pending, 0);
                little = magic == 0xd4c3b2a1 || magic == 0x4d3cb2a1;
                if (!little && magic != 0xa1b2c3d4 && magic != 0xa1b23c4d) throw new IllegalArgumentException("Нет заголовка PCAP");
                if (read32(pending, 20) != 1) throw new IllegalArgumentException("Ожидался Ethernet PCAP");
                pending = new byte[16]; phase = 1;
            } else if (phase == 1) {
                int length = read32(pending, 8);
                if (length < 1 || length > 65535) throw new IllegalArgumentException("Некорректный размер PCAP");
                pending = new byte[length]; phase = 2;
            } else {
                Boolean value = packet(pending); if (value != null) state.accept(value);
                pending = new byte[16]; phase = 1;
            }
            used = 0;
        }
    }
    static Boolean packet(byte[] data) {
        if (data.length < 14) return null;
        int type = u16(data, 12), ip = 14;
        while (type == 0x8100 || type == 0x88a8) {
            if (ip + 4 > data.length) return null;
            type = u16(data, ip + 2); ip += 4;
        }
        if (type != 0x800 || ip + 20 > data.length || (data[ip] & 0xf0) != 0x40 || (data[ip + 9] & 255) != 17) return null;
        int header = (data[ip] & 15) * 4, udp = ip + header;
        if (header < 20 || udp + 143 > data.length || u16(data, ip + 2) < header + 143
                || (u16(data, ip + 6) & 0x3fff) != 0) return null;
        if (u16(data, udp) != 50500 || u16(data, udp + 2) != 50335 || u16(data, udp + 4) < 143
                || u16(data, udp + 8) != 0x26 || u16(data, udp + 10) != 0x20) return null;
        return (data[udp + 142] & 0x40) != 0;
    }
    private static int u16(byte[] data, int offset) { return (data[offset] & 255) << 8 | data[offset + 1] & 255; }
    private static int be32(byte[] data, int offset) { return u16(data, offset) << 16 | u16(data, offset + 2); }
    private int read32(byte[] data, int offset) { int value = be32(data, offset); return little ? Integer.reverseBytes(value) : value; }
}
