/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RecentFiles {
    private static final int PREVIEW_SIZE = 4;
    static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    static final long WINDOW = 31 * DAY;

    public enum Category {
        IMAGE("Изображения", "jpg jpeg png gif bmp wbmp webp heic tif tiff"),
        VIDEO("Видео", "mp4 m4v f4v mkv avi mov webm 3gp 3gpp 3g2 flv ts mts m2ts mpg mpeg "
                + "wmv vob asf asx rm rmvb ogv trp divx swf"),
        MUSIC("Музыка", "mp3 m4a aac flac ogg x-ogg oga wav wma amr mid midi rmi qcp ra ram "
                + "aif aifc aiff m3u ape ac3 awb mp2 mpga 3gpa 3ga imy"),
        APK("APK", "apk"),
        WORD("Word", "doc docx docm odt"),
        EXCEL("Excel", "xla xlc xlm xls xlt xlsx xlsm xlsb ods"),
        POWERPOINT("PowerPoint", "pptx ppt ppx pps ppsm odp"),
        PDF("PDF", "pdf"),
        TXT("TXT", "txt"),
        ARCHIVE("Архивы", "zip rar 7z tar gz gzip bz2 cab jar esi"),
        OTHER("Другие документы", "chm epub umd fb2 azw azw1 azw3 azw4 prc ebk ebk2 ebk3 mobi "
                + "html htm mht et ett wps wpt dps dpt xml text ini properties prop conf classpath "
                + "project php js rss jsp asp aspx java vb vbs checksum log");

        public final String label;
        private final String extensions;
        Category(String label, String extensions) {
            this.label = label;
            this.extensions = extensions;
        }

        private static final Map<String, Category> BY_EXTENSION = new HashMap<>();
        static {
            for (Category category : values())
                for (String extension : category.extensions.split(" ")) BY_EXTENSION.put(extension, category);
        }
        static Category forName(String lowerName) {
            int dot = lowerName.lastIndexOf('.');
            return dot < 0 ? null : BY_EXTENSION.get(lowerName.substring(dot + 1));
        }
    }

    public static EnumSet<Category> defaultCategories() {
        return EnumSet.complementOf(EnumSet.of(Category.TXT, Category.OTHER));
    }

    public static final class Entry {
        public final File root;
        public final FileEntry file;
        public final long time;
        private Entry(File root, String relative, String name, BasicFileAttributes attrs) {
            this.root = root;
            time = attrs.lastModifiedTime().toMillis();
            file = new FileEntry(relative, name, false, attrs.size(), time);
        }
    }

    private static final Comparator<Entry> NEWEST = Comparator
            .comparingLong((Entry entry) -> entry.time).reversed()
            .thenComparing(entry -> entry.file.path);

    public static final class Group {
        public final File root;
        public final String parent;
        public final Category category;
        /** Age of the start of this relative minute, hour or day interval. */
        public final long bucket;
        private final List<Entry> newest = new ArrayList<>(PREVIEW_SIZE);
        public final List<Entry> preview = Collections.unmodifiableList(newest);
        public int count;

        private Group(Entry entry, Category category, long bucket) {
            root = entry.root;
            parent = FilePaths.parent(entry.file.path);
            this.category = category;
            this.bucket = bucket;
        }
        public long latestTime() { return newest.get(0).time; }

        private void add(Entry entry) {
            count++;
            int index = Collections.binarySearch(newest, entry, NEWEST);
            if (index < 0) index = -index - 1;
            if (index >= PREVIEW_SIZE) return;
            if (newest.size() == PREVIEW_SIZE) newest.remove(PREVIEW_SIZE - 1);
            newest.add(index, entry);
        }
    }

    public final List<Group> groups = new ArrayList<>();
    public boolean incomplete;
    private final EnumSet<Category> categories;
    private final long now;
    private final boolean showHidden;
    private final String query;

    private RecentFiles(boolean showHidden, Set<Category> categories, String filter, long now) {
        this.showHidden = showHidden;
        this.categories = EnumSet.noneOf(Category.class);
        this.categories.addAll(categories);
        this.query = filter.trim().toLowerCase(Locale.ROOT);
        this.now = now;
    }

    public static RecentFiles scan(List<File> volumes, boolean showHidden, Set<Category> categories,
                                   String nameFilter) throws IOException {
        return scan(volumes, showHidden, categories, nameFilter, System.currentTimeMillis());
    }

    static RecentFiles scan(List<File> volumes, boolean showHidden, Set<Category> categories,
                            String nameFilter, long now) throws IOException {
        RecentFiles result = new RecentFiles(showHidden, categories, nameFilter, now);
        checkCancelled();
        if (categories.isEmpty()) return result;
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
            if (roots.stream().noneMatch(parent -> !root.equals(parent) && root.startsWith(parent)))
                result.scanVolume(root);
        }
        result.groups.sort(Comparator.comparingLong(Group::latestTime).reversed()
                .thenComparing(group -> group.root.getPath()).thenComparing(group -> group.parent)
                .thenComparing(group -> group.category));
        return result;
    }

    private void scanVolume(Path root) throws IOException {
        File volume = root.toFile();
        // A directory can be revisited after one of its child directories. Drop its lookup
        // table at postVisitDirectory; only the four previews and count survive in groups.
        Map<Path, Map<Long, EnumMap<Category, Group>>> active = new HashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    checkCancelled();
                    if (!root.equals(dir) && skipDirectory(root, dir)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                        throws IOException {
                    checkCancelled();
                    if (root.equals(path)) {
                        incomplete = true;
                        return FileVisitResult.CONTINUE;
                    }
                    Category category = includedCategory(path, attrs);
                    if (category == null) return FileVisitResult.CONTINUE;
                    Entry entry = entry(volume, root, path, attrs);
                    if (entry != null) {
                        long bucket = timeBucket(now - entry.time);
                        EnumMap<Category, Group> interval = active
                                .computeIfAbsent(path.getParent(), ignored -> new HashMap<>())
                                .computeIfAbsent(bucket, ignored -> new EnumMap<>(Category.class));
                        Group group = interval.get(category);
                        if (group == null) {
                            group = new Group(entry, category, bucket);
                            interval.put(category, group);
                            groups.add(group);
                        }
                        group.add(entry);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path path, IOException failure)
                        throws IOException {
                    checkCancelled();
                    incomplete = true;
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure)
                        throws IOException {
                    checkCancelled();
                    active.remove(path);
                    if (failure != null) incomplete = true;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException | SecurityException unavailable) {
            incomplete = true;
        }
    }

    /** Reopen only the chosen directory. Apply the same time origin, file filter and category. */
    public List<Entry> members(Group group) throws IOException {
        checkCancelled();
        Path root = group.root.toPath();
        Path directory = root.resolve(group.parent.substring(1));
        if (!directory.toRealPath().equals(directory)) throw new IOException("Исходная папка перемещена");
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                checkCancelled();
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException removed) {
                    continue;
                }
                if (includedCategory(path, attrs) != group.category
                        || timeBucket(now - attrs.lastModifiedTime().toMillis()) != group.bucket) continue;
                Entry entry = entry(group.root, root, path, attrs);
                if (entry != null) result.add(entry);
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        result.sort(NEWEST);
        return result;
    }

    private Category includedCategory(Path path, BasicFileAttributes attrs) {
        long modified = attrs.lastModifiedTime().toMillis();
        if (!attrs.isRegularFile() || modified <= now - WINDOW || modified > now) return null;
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if ((!showHidden && lower.startsWith(".")) || !lower.contains(query)) return null;
        Category category = Category.forName(lower);
        return categories.contains(category) ? category : null;
    }

    private static Entry entry(File volume, Path root, Path path, BasicFileAttributes attrs) {
        try {
            String relative = FilePaths.normalize("/" + root.relativize(path));
            return new Entry(volume, relative, path.getFileName().toString(), attrs);
        } catch (IOException invalidName) {
            return null;
        }
    }

    private static long timeBucket(long age) {
        long unit = age < HOUR ? MINUTE : age < DAY ? HOUR : DAY;
        return age / unit * unit;
    }

    private boolean skipDirectory(Path root, Path dir) {
        String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
        if ((!showHidden && name.startsWith(".")) || name.equals(".thumbnails")
                || name.equals(".estrongs") || name.equals(".wbadcache")) return true;
        String relative = root.relativize(dir).toString().toLowerCase(Locale.ROOT);
        if (relative.equals("android/data") || relative.equals("android/obb")
                || relative.equals("sina/weibo/.log") || relative.equals("tencent/mobileqq/qbiz")
                || relative.equals("tencent/mobileqq/data")) return true;
        if (!relative.startsWith("tencent/micromsg/")) return false;
        int accountEnd = relative.indexOf('/', "tencent/micromsg/".length());
        if (accountEnd < 0) return false;
        String child = relative.substring(accountEnd + 1);
        return child.equals("voice2") || child.equals("image2") || child.equals("sns")
                || child.equals("sfs") || child.equals("avatar");
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
}
