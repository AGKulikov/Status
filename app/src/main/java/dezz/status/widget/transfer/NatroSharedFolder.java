/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;

/** Read-only LAN access to direct regular children; export is invoked only by local UI. */
public final class NatroSharedFolder {
    public static final int MAX_ENTRIES = 1000;
    public static final long MAX_DOWNLOAD = 256L * 1024 * 1024;
    public static final class Item {
        public final String id, name;
        public final long size, modified;
        Item(String id, File file) {
            this.id = id; name = file.getName(); size = file.length(); modified = file.lastModified();
        }
    }
    private final File directory;
    private final Map<String, String> names = new HashMap<>();
    public NatroSharedFolder(File directory) { this.directory = directory.getAbsoluteFile(); }
    private File root() throws IOException {
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Папка Natro не должна быть ссылкой");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Нет доступа к Загрузки/Natro. Разрешите доступ к файлам на магнитоле.");
        if (!directory.canRead()) throw new IOException("Нет доступа к Загрузки/Natro");
        return directory.getCanonicalFile();
    }
    public synchronized List<Item> list() throws IOException {
        File root = root(); List<Item> result = new ArrayList<>();
        Map<String, String> previous = new HashMap<>();
        names.forEach((id, name) -> previous.put(name, id));
        Map<String, String> next = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.toPath())) {
            int visited = 0;
            for (Path path : entries) {
                if (++visited > MAX_ENTRIES) throw new IOException("В папке больше 1000 объектов. Уменьшите их количество на магнитоле.");
                String name = path.getFileName().toString();
                if (name.startsWith(".") || name.endsWith(".part")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = previous.getOrDefault(name, UUID.randomUUID().toString());
                next.put(id, name); result.add(new Item(id, path.toFile()));
            }
        }
        names.clear(); names.putAll(next);
        result.sort(Comparator.comparingLong((Item item) -> item.modified).reversed().thenComparing(item -> item.name));
        return result;
    }
    public synchronized Download open(String id) throws IOException {
        String name = names.get(id);
        if (name == null) throw new FileNotFoundException("Файл не найден. Обновите список.");
        File root = root(); Path path = new File(root, name).toPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException("Файл удалён или заменён ссылкой");
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            long size = channel.size();
            if (size > MAX_DOWNLOAD) throw new IOException("Лимит скачивания — 256 МиБ на файл");
            return new Download(name, size, Channels.newInputStream(channel));
        } catch (Exception error) { channel.close(); throw error; }
    }
    public File exportDump(File source) throws IOException {
        File root = root();
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Дамп отсутствует");
        Path partial = Files.createTempFile(root.toPath(), ".Natro-dump-", ".part");
        Path target = root.toPath().resolve((source.getName().startsWith("Natro-vehicle-")?"Natro-vehicle-":"Natro-system-") + UUID.randomUUID() + ".zip");
        try {
            long expected = source.length(), copied = 0;
            try (InputStream input = Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Сохранение отменено");
                    copied += count;
                    if (copied > expected) throw new IOException("Дамп изменился при сохранении");
                    output.write(buffer, 0, count);
                }
            }
            if (copied != expected) throw new IOException("Дамп изменился при сохранении");
            Files.move(partial, target); // No REPLACE_EXISTING: never overwrite user files.
            return target.toFile();
        } finally { Files.deleteIfExists(partial); }
    }
    public static final class Download implements AutoCloseable {
        public final String name;
        public final long size;
        private final InputStream input;
        Download(String name, long size, InputStream input) { this.name = name; this.size = size; this.input = input; }
        public void writeTo(OutputStream output) throws IOException {
            byte[] buffer = new byte[65536]; long remaining = size;
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Скачивание отменено");
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException("Файл изменился во время скачивания");
                output.write(buffer, 0, count); remaining -= count;
            }
        }
        @Override public void close() throws IOException { input.close(); }
    }
}
