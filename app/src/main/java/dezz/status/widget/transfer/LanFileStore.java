/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
/** Incoming files live only inside this directory; external names never resolve a path. */
public final class LanFileStore {
    public static final class Item {
        public final String id, name; public final File file;
        Item(File file) { this.file = file; id = file.getName().substring(0, 36); name = file.getName().substring(37); }
    }
    private final File directory;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final Set<String> active = new HashSet<>();
    public LanFileStore(File directory) throws IOException {
        this.directory = directory.getCanonicalFile();
        if (!this.directory.isDirectory() && !this.directory.mkdirs()) throw new IOException("Не удалось создать папку входящих файлов");
        // Only this store's unfinished uploads, never completed or user-named files.
        File[] leftovers = this.directory.listFiles();
        if (leftovers != null) for (File file : leftovers)
            if (file.getName().matches("\\." + UUID_PATTERN + "\\.part")
                    && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !file.delete())
                throw new IOException("Не удалось убрать незавершённую передачу");
    }
    public String path() { return directory.getAbsolutePath(); }
    public synchronized List<Item> list() {
        List<Item> result = new ArrayList<>(); File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && file.getName().matches(UUID_PATTERN + "_.+") && !active.contains(file.getName())) result.add(new Item(file));
        result.sort((a, b) -> Long.compare(b.file.lastModified(), a.file.lastModified())); return result;
    }
    public Item find(String id) { if (id == null || !id.matches(UUID_PATTERN)) return null; for (Item item : list()) if (item.id.equals(id)) return item; return null; }
    public Item receive(String name, long length, InputStream input) throws IOException {
        if (length < 0 || length > LanHttpServer.MAX_UPLOAD) throw new IOException("Лимит файла — 256 МиБ");
        File target;
        synchronized (this) {
            List<Item> files = list(); long total = files.stream().mapToLong(item -> item.file.length()).sum();
            if (files.size() + active.size() >= 100 || total + length + active.size() * LanHttpServer.MAX_UPLOAD > 1024L * 1024 * 1024)
                throw new IOException("Папка входящих заполнена (100 файлов / 1 ГиБ)");
            target = new File(directory, UUID.randomUUID() + "_" + safeName(name)); active.add(target.getName());
        }
        File partial = new File(directory, "." + target.getName().substring(0, 36) + ".part");
        boolean complete = false, created = false;
        try {
          if (!partial.createNewFile()) throw new IOException("Временный файл уже существует");
          created = true;
          try (OutputStream output = new FileOutputStream(partial)) {
            byte[] bytes = new byte[65536]; long remaining = length;
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Передача отменена");
                int count = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
                if (count < 0) throw new EOFException("Передача оборвана; файл не сохранён");
                output.write(bytes, 0, count); remaining -= count;
            }
            output.flush();
          }
          if (!partial.renameTo(target)) throw new IOException("Не удалось завершить сохранение файла");
          complete = true; return new Item(target);
        } finally {
            synchronized (this) { active.remove(target.getName()); }
            if (created && !complete) partial.delete(); // Retried by the next store startup if necessary.
        }
    }
    public static String safeName(String raw) {
        String result = raw == null ? "file" : raw.replaceAll("[\\\\/\\p{Cntrl}]", "_").replaceAll("^\\.+", "_");
        result = result.trim(); if (result.isEmpty()) result = "file";
        // Android filesystems limit a path component in bytes, not UTF-16 characters.
        StringBuilder bounded = new StringBuilder(); int bytes = 0;
        for (int offset = 0; offset < result.length();) {
            int point = result.codePointAt(offset);
            String character = new String(Character.toChars(point));
            int count = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + count > 180) break;
            bounded.append(character); bytes += count; offset += Character.charCount(point);
        }
        return bounded.toString();
    }
}
