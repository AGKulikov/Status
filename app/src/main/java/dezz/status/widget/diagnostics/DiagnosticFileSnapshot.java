/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** A bounded, complete-line snapshot. Never waits on the live writer's monitor. */
public final class DiagnosticFileSnapshot {
    public final String text;
    public final long sourceBytes;
    public final int partialTailBytes;
    public final boolean truncated;
    private DiagnosticFileSnapshot(String text, long sourceBytes, int partial, boolean truncated) {
        this.text = text; this.sourceBytes = sourceBytes; partialTailBytes = partial; this.truncated = truncated;
    }
    public static DiagnosticFileSnapshot read(File file, int maximumBytes) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length(), start = Math.max(0, size - maximumBytes);
            input.seek(start);
            byte[] bytes = new byte[(int) (size - start)];
            int length = 0, got;
            while (length < bytes.length && (got = input.read(bytes, length, bytes.length - length)) > 0) length += got;
            int first = 0, end = length;
            if (start > 0) { while (first < length && bytes[first] != '\n') first++; first = Math.min(length, first + 1); }
            while (end > first && bytes[end - 1] != '\n') end--;
            return new DiagnosticFileSnapshot(new String(bytes, first, end - first, StandardCharsets.UTF_8),
                    size, length - end, start > 0);
        }
    }
}
