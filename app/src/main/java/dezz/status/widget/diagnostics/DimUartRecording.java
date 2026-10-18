/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.security.*;

/** Append-only evidence. A running marker on disk never starts a new capture. */
public final class DimUartRecording implements AutoCloseable {
    public static final long LIMIT = 64L * 1024 * 1024;
    private final File directory;
    private final long limit;
    private FileOutputStream output;
    private long bytes;
    private boolean closed;

    public DimUartRecording(File root, String metadata) throws IOException {
        this(root, metadata, LIMIT);
    }
    DimUartRecording(File root, String metadata, long limit) throws IOException {
        this.limit = limit;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("recording_directory");
        directory = new File(root, "session-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("recording_session");
        write("session.txt", "schema=1\nstart_wall_ms=" + System.currentTimeMillis()
                + "\nsource=existing_logcat_linoverlvds\nlimit_bytes=" + limit
                + "\nreconnect=false\nvehicle_writes=false\n" + metadata);
        write("state.txt", "running_or_interrupted\n");
        output = new FileOutputStream(new File(directory, "uart.log"));
    }
    public synchronized void append(byte[] chunk) throws IOException {
        if (closed) throw new IOException("recording_closed");
        int count = (int) Math.min(chunk.length, limit - bytes);
        if (count > 0) {
            output.write(chunk, 0, count);
            output.getFD().sync(); // Each accepted prefix is durable; no RAM-only tail.
            bytes += count;
        }
        if (bytes >= limit) throw new IOException("recording_size_limit");
    }
    public synchronized void finish(String reason) throws IOException {
        if (closed) return;
        closed = true;
        try { output.close(); }
        finally { write("terminal.txt", "reason=" + reason + "\nbytes=" + bytes
                + "\nend_wall_ms=" + System.currentTimeMillis() + "\n"); }
    }
    @Override public void close() throws IOException { finish("interrupted"); }
    private void write(String name, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
    public static File[] sessions(File root) {
        File[] files = root.listFiles(f -> f.isDirectory()
                && f.getName().matches("session-[0-9]+-[a-f0-9-]{36}") && regularPath(f));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }
    private static boolean regularPath(File file) {
        try { return file.getCanonicalFile().equals(file.getAbsoluteFile()); }
        catch (IOException failure) { return false; }
    }
    /** Export only while stopped. No recovery action changes the source files. */
    public static File exportAll(File root, File exports) throws IOException {
        File[] sessions = sessions(root);
        if (sessions.length == 0) throw new IOException("no_recording");
        if (!exports.isDirectory() && !exports.mkdirs()) throw new IOException("export_directory");
        File partial = File.createTempFile("Natro-dim-uart-", ".part", exports);
        File target = new File(exports, partial.getName().replace(".part", ".zip"));
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
                StringBuilder manifest = new StringBuilder("path\tbytes\tsha256\n");
                for (File session : sessions) {
                for (String name : new String[]{"session.txt", "state.txt", "terminal.txt", "uart.log"}) {
                    File file = new File(session, name);
                    if (!file.isFile()) continue;
                    if (!regularPath(file) || file.length() > LIMIT) throw new IOException("recording_invalid_file");
                    zip.putNextEntry(new ZipEntry(session.getName() + "/" + name));
                    MessageDigest digest;
                    try { digest = MessageDigest.getInstance("SHA-256"); }
                    catch (NoSuchAlgorithmException failure) { throw new IOException(failure); }
                    long count = 0;
                    try (InputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            count += n;
                            if (count > LIMIT) throw new IOException("recording_size_changed");
                            zip.write(buffer, 0, n); digest.update(buffer, 0, n);
                        }
                    }
                    zip.closeEntry();
                    manifest.append(session.getName()).append('/').append(name).append('\t').append(count).append('\t');
                    for (byte b : digest.digest()) manifest.append(String.format(Locale.ROOT, "%02x", b & 255));
                    manifest.append('\n');
                }
                }
                zip.putNextEntry(new ZipEntry("manifest.tsv"));
                zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("recovery.txt"));
                zip.write(("No automatic resume. Missing/incomplete terminal.txt means interruption.\n"
                        + "Logcat history may precede start; absence of records is not absence of UART traffic.\n"
                        + "This capture does not prove visible DIM pixels or a successful vehicle command.\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (!partial.renameTo(target)) throw new IOException("export_publish");
            File[] old = exports.listFiles(f -> f.isFile() && regularPath(f)
                    && f.getName().matches("Natro-dim-uart-[0-9]+\\.zip") && !f.equals(target));
            if (old != null) {
                Arrays.sort(old, Comparator.comparingLong(File::lastModified).reversed());
                for (int i = 1; i < old.length; i++) old[i].delete();
            }
            return target;
        } finally { partial.delete(); }
    }
    public static void deleteAll(File root) throws IOException {
        for (File session : sessions(root)) {
            for (String name : new String[]{"session.txt", "state.txt", "terminal.txt", "uart.log"}) {
                File file = new File(session, name);
                if (file.exists() && !file.delete()) throw new IOException("recording_delete");
            }
            if (!session.delete()) throw new IOException("recording_delete");
        }
    }
}
