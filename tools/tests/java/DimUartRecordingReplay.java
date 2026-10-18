/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class DimUartRecordingReplay {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]), root = new File(base, "records"), exports = new File(base, "exports");
        DimUartRecording record = new DimUartRecording(root, "test=true\n", 7);
        byte[] prefix = {0, 1, 2, 3}; record.append(prefix);
        // Export the durable prefix without close: simulates recovery after process death.
        File zip = DimUartRecording.exportAll(root, exports);
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry raw = z.stream().filter(e -> e.getName().endsWith("/uart.log")).findFirst().get();
            check(Arrays.equals(z.getInputStream(raw).readAllBytes(), prefix));
            check(z.stream().noneMatch(e -> e.getName().endsWith("/terminal.txt")));
            check(z.getEntry("manifest.tsv") != null);
        }
        try { record.append(new byte[]{4, 5, 6, 7, 8}); throw new AssertionError(); }
        catch (IOException expected) { check(expected.getMessage().equals("recording_size_limit")); }
        record.finish("size_limit"); record.close();
        check(Files.readAllBytes(new File(DimUartRecording.sessions(root)[0], "uart.log").toPath()).length == 7);
        try { record.append(prefix); throw new AssertionError(); } catch (IOException expected) {}
        try (DimUartRecording next = new DimUartRecording(root, "second=true\n", 8)) { next.append(prefix); }
        zip = DimUartRecording.exportAll(root, exports);
        try (ZipFile z = new ZipFile(zip)) {
            check(z.stream().filter(e -> e.getName().endsWith("/uart.log")).count() == 2);
        }
        // A link cannot import arbitrary files into the private evidence ZIP.
        File latest = DimUartRecording.sessions(root)[0];
        Path log = new File(latest, "uart.log").toPath(); Files.delete(log);
        Files.createSymbolicLink(log, zip.toPath());
        try { DimUartRecording.exportAll(root, exports); throw new AssertionError(); }
        catch (IOException expected) { check(expected.getMessage().equals("recording_invalid_file")); }
        check(Arrays.stream(exports.listFiles()).noneMatch(f -> f.getName().endsWith(".part")));
        DimUartRecording.deleteAll(root); check(DimUartRecording.sessions(root).length == 0);
        check(zip.isFile());
        System.out.println("PASS durable prefix / interruption / quota / terminal / multi-session / symlink / cleanup");
    }
}
