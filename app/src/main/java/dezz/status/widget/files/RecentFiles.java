/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** A bounded view of actual files on mounted shared storage. All scanning runs off the UI thread. */
public final class RecentFiles {
    public static final int LIMIT = 200;

    public static final class Entry {
        public final File root;
        public final FileEntry file;
        public final long time;
        Entry(Path root, Path path, BasicFileAttributes attrs, long time) throws IOException {
            this.root = root.toFile();
            String relative = FilePaths.normalize("/" + root.relativize(path));
            file = new FileEntry(relative, path.getFileName().toString(), false,
                    attrs.size(), attrs.lastModifiedTime().toMillis());
            this.time = time;
        }
    }

    public final List<Entry> entries = new ArrayList<>();
    public boolean incomplete;
    private static final Comparator<Entry> NEWEST = Comparator
            .comparingLong((Entry entry) -> entry.time).reversed()
            .thenComparing(entry -> entry.root.getPath()).thenComparing(entry -> entry.file.path);

    public static RecentFiles scan(List<File> volumes, boolean showHidden, Map<String, Long> activity)
            throws IOException {
        RecentFiles result = new RecentFiles();
        PriorityQueue<Entry> best = new PriorityQueue<>(LIMIT, NEWEST.reversed());
        Set<Path> roots = new LinkedHashSet<>();
        for (File volume : volumes) {
            try {
                roots.add(volume.getCanonicalFile().toPath());
            } catch (IOException | SecurityException inaccessible) {
                result.incomplete = true;
            }
        }
        for (Path root : roots) {
            checkCancelled();
            // A mounted volume may also be exposed under a second or nested path.
            if (roots.stream().anyMatch(parent -> !root.equals(parent) && root.startsWith(parent))) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        checkCancelled();
                        if (!dir.equals(root) && ((!showHidden && dir.getFileName().toString().startsWith("."))
                                || dir.equals(root.resolve("Android/data")) || dir.equals(root.resolve("Android/obb"))))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                            throws IOException {
                        checkCancelled();
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!attrs.isRegularFile() || (!showHidden && name.startsWith("."))
                                || name.endsWith(".part") || name.endsWith(".crdownload"))
                            return FileVisitResult.CONTINUE;
                        long time = Math.max(attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis());
                        time = Math.max(time, activity.getOrDefault(path.toString(), 0L));
                        if (best.size() == LIMIT && time < best.element().time) return FileVisitResult.CONTINUE;
                        try {
                            Entry entry = new Entry(root, path, attrs, time);
                            if (best.size() < LIMIT || NEWEST.compare(entry, best.element()) < 0) {
                                if (best.size() == LIMIT) best.remove();
                                best.add(entry);
                            }
                        } catch (IOException unsupportedName) {
                            // The manager cannot operate on this path. Continue with its siblings.
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFileFailed(Path path, IOException failure)
                            throws IOException {
                        checkCancelled();
                        result.incomplete = true;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult postVisitDirectory(Path path, IOException failure)
                            throws IOException {
                        checkCancelled();
                        if (failure != null) result.incomplete = true;
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (InterruptedIOException cancelled) {
                throw cancelled;
            } catch (IOException | SecurityException unavailableVolume) {
                result.incomplete = true;
            }
        }
        result.entries.addAll(best);
        result.entries.sort(NEWEST);
        return result;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
}
