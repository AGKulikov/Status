/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Filesystem primitives shared by the archive validator and the recoverable restore journal. */
public final class BackupFiles {
    public static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int MAX_FILES = 20000;
    private BackupFiles() {}

    public static String safePath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.indexOf(':') >= 0)
            throw new IOException("Invalid archive path");
        String[] parts = value.split("/", -1);
        if (parts.length > 32) throw new IOException("Archive path is too deep");
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("Unsafe archive path");
        return value;
    }

    public static File child(File root, String path) throws IOException {
        File result = new File(root, safePath(path));
        String base = root.getCanonicalPath() + File.separator;
        if (!result.getCanonicalPath().startsWith(base)) throw new IOException("Path leaves archive root");
        File cursor = result;
        while (cursor != null && !cursor.equals(root)) {
            if (Files.isSymbolicLink(cursor.toPath())) throw new IOException("Symbolic links are not supported");
            cursor = cursor.getParentFile();
        }
        return result;
    }

    public static void directory(File directory) throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath()))
            throw new IOException("Cannot create private directory");
    }

    public static byte[] read(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output, limit); return output.toByteArray();
        }
    }

    public static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Archive size limit exceeded");
            output.write(buffer, 0, count);
        }
        return total;
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    public static void atomicWrite(File target, byte[] bytes) throws IOException {
        directory(target.getParentFile());
        File temporary = new File(target.getParentFile(), target.getName() + ".natro-writing");
        if (Files.isSymbolicLink(temporary.toPath())) throw new IOException("Unsafe temporary file");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes); output.getFD().sync();
        }
        move(temporary, target);
    }

    public static void atomicCopy(File source, File target) throws IOException {
        atomicCopy(source, target, ".natro-writing");
    }

    public static void atomicCopy(File source, File target, String suffix) throws IOException {
        if (!source.isFile() || Files.isSymbolicLink(source.toPath())) throw new IOException("Missing snapshot file");
        directory(target.getParentFile());
        File temporary = new File(target.getParentFile(), target.getName() + suffix);
        if (Files.isSymbolicLink(temporary.toPath())) throw new IOException("Unsafe temporary file");
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
            copy(input, output, MAX_FILE_BYTES); output.getFD().sync();
        }
        move(temporary, target);
    }

    private static void move(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParentFile());
    }

    public static void delete(File file) throws IOException {
        if (Files.deleteIfExists(file.toPath())) syncDirectory(file.getParentFile());
    }

    private static void syncDirectory(File parent) throws IOException {
        try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(
                parent.toPath(), StandardOpenOption.READ)) { directory.force(true); }
    }

    public static Map<String, File> inventory(File root) throws IOException {
        LinkedHashMap<String, File> result = new LinkedHashMap<>();
        if (!root.exists()) return result;
        walk(root, root, result, new long[]{0}); return result;
    }

    private static void walk(File root, File file, Map<String, File> out, long[] total) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Symbolic links are not supported");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Unreadable directory");
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) walk(root, child, out, total);
        } else if (file.isFile()) {
            String relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            safePath(relative);
            if (file.length() > MAX_FILE_BYTES || (total[0] += file.length()) > MAX_TOTAL_BYTES
                    || out.size() >= MAX_FILES) throw new IOException("Snapshot size limit exceeded");
            out.put(relative, file);
        } else throw new IOException("Unsupported filesystem entry");
    }

    public static void removeTree(File root) throws IOException {
        if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return;
        if (Files.isSymbolicLink(root.toPath())) { Files.delete(root.toPath()); return; }
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children == null) throw new IOException("Unreadable private staging directory");
            for (File child : children) removeTree(child);
        }
        Files.delete(root.toPath());
    }
}
