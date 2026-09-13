/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** A fixed-size snapshot of an append-only UTF-8 log, never a scan from the beginning. */
final class BoundedUtf8Tail {
    private static final int MAX_CHARS = 64_000;
    private BoundedUtf8Tail() {}

    static String read(File file, int requestedChars) throws IOException {
        int chars = Math.max(1, Math.min(MAX_CHARS, requestedChars));
        // Four bytes cover one Unicode code point; three extra bytes allow boundary alignment.
        int budget = chars * 4 + 3;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0L, length - budget);
            input.seek(start);
            byte[] bytes = new byte[(int) (length - start)];
            int used = 0;
            while (used < bytes.length) {
                int count = input.read(bytes, used, bytes.length - used);
                if (count < 0) break; // A concurrent truncate must not turn into an unbounded read.
                used += count;
            }
            int from = 0;
            if (start > 0L) {
                while (from < used && (bytes[from] & 0xc0) == 0x80) from++;
            }
            // A concurrently appended partial UTF-8 sequence must not produce replacement glyphs.
            int end = used;
            if (end > from) {
                int lead = end - 1;
                while (lead > from && (bytes[lead] & 0xc0) == 0x80) lead--;
                int b = bytes[lead] & 0xff;
                int expected = b < 0x80 ? 1 : b < 0xe0 ? 2 : b < 0xf0 ? 3 : 4;
                if (end - lead < expected) end = lead;
            }
            String value = new String(bytes, from, end - from, StandardCharsets.UTF_8);
            if (start == 0L && value.length() <= chars) return value;
            int cut = Math.max(0, value.length() - Math.max(0, chars - 2));
            if (cut < value.length() && Character.isLowSurrogate(value.charAt(cut))) cut++;
            return chars < 2 ? "…" : "…\n" + value.substring(cut);
        }
    }
}
