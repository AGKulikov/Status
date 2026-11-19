/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static dezz.status.widget.files.RecentFiles.*;
import static org.junit.Assert.*;

public class RecentFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final long NOW = 1_800_000_000_000L;

    private Path make(Path root, String path, long age) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(NOW - age));
        return file;
    }

    private RecentFiles scan(Path root) throws Exception {
        return RecentFiles.scan(Collections.singletonList(root.toFile()), false, defaultCategories(), "", NOW);
    }

    @Test public void twoHundredThirtyNewerServiceFilesCannotDisplaceUnopenedApk() throws Exception {
        Path root = temp.newFolder("device").toPath();
        make(root, "Download/Natro.apk", 10 * MINUTE);
        String[] extensions = {".db", ".json", ".log"};
        for (int i = 0; i < 230; i++) make(root, "ServiceData/entry-" + i + extensions[i % 3], MINUTE);
        RecentFiles result = scan(root);
        assertFalse(result.incomplete);
        assertEquals(1, result.groups.size());
        Group group = result.groups.get(0);
        assertEquals(Category.APK, group.category);
        assertEquals(1, group.count);
        assertEquals("/Download/Natro.apk", group.preview.get(0).file.path);
    }

    @Test public void keepsFullGroupAndFourNewestPreviewsPlusOtherSources() throws Exception {
        Path root = temp.newFolder("device").toPath();
        for (int i = 0; i < 220; i++) make(root, "Pictures/Screenshots/" + i + ".png", 1500 - i);
        make(root, "Download/Natro.apk", 2000);
        RecentFiles result = scan(root);
        assertEquals(2, result.groups.size());
        Group photos = result.groups.get(0);
        assertEquals(Category.IMAGE, photos.category);
        assertEquals("/Pictures/Screenshots", photos.parent);
        assertEquals(220, photos.count);
        assertEquals(Arrays.asList("219.png", "218.png", "217.png", "216.png"), names(photos.preview));
        List<Entry> members = result.members(photos);
        assertEquals(220, members.size());
        assertEquals("0.png", members.get(219).file.name);
        Files.delete(root.resolve("Pictures/Screenshots/216.png"));
        assertEquals(219, result.members(photos).size());
        assertEquals(Category.APK, result.groups.get(1).category);
    }

    @Test public void exactlyEightScreenshotsAreOneCardAndQueryReachesBeyondPreview() throws Exception {
        Path root = temp.newFolder("device").toPath();
        for (int i = 0; i < 8; i++) make(root, "Pictures/Screenshots/test-" + i + ".PNG", i * 1000);
        RecentFiles full = scan(root);
        assertEquals(1, full.groups.size());
        assertEquals(8, full.groups.get(0).count);
        assertEquals(4, full.groups.get(0).preview.size());
        RecentFiles searched = RecentFiles.scan(Collections.singletonList(root.toFile()), false,
                defaultCategories(), "TEST-7", NOW);
        assertEquals(1, searched.groups.get(0).count);
        assertEquals(Collections.singletonList("test-7.PNG"), names(searched.members(searched.groups.get(0))));
    }

    @Test public void usesModificationTimeWithin31DaysEvenAfterReadOrFreshCreation() throws Exception {
        Path root = temp.newFolder("device").toPath();
        Files.readAllBytes(make(root, "read-today.pdf", 40 * DAY));
        make(root, "exact-cutoff.pdf", WINDOW);
        make(root, "just-inside.pdf", WINDOW - 1);
        make(root, "present.pdf", 0);
        make(root, "future.pdf", -1);
        RecentFiles result = scan(root);
        assertEquals(Arrays.asList("present.pdf", "just-inside.pdf"),
                result.groups.stream().map(group -> group.preview.get(0).file.name).collect(Collectors.toList()));
    }

    @Test public void splitsMinutesThenHoursThenDaysAndPreservesTypesFoldersAndVolumes() throws Exception {
        Path device = temp.newFolder("device").toPath(), usb = temp.newFolder("usb").toPath();
        long[] ages = {0, MINUTE - 1, MINUTE, HOUR - 1, HOUR, HOUR + MINUTE, DAY - 1, DAY, DAY + HOUR};
        for (int i = 0; i < ages.length; i++) make(device, "Download/file-" + i + ".pdf", ages[i]);
        make(device, "Download/update.apk", 1);
        make(device, "Documents/file-0.pdf", 2);
        make(usb, "Download/file-0.pdf", 3);
        List<File> volumes = Arrays.asList(device.toFile(), usb.toFile(), device.resolve("Download").toFile());
        RecentFiles result = RecentFiles.scan(volumes, false, defaultCategories(), "", NOW);
        assertEquals(9, result.groups.size());
        List<Group> source = result.groups.stream().filter(g -> g.root.equals(device.toFile())
                && g.parent.equals("/Download") && g.category == Category.PDF).collect(Collectors.toList());
        assertEquals(Arrays.asList(0L, MINUTE, 59 * MINUTE, HOUR, 23 * HOUR, DAY),
                source.stream().map(group -> group.bucket).collect(Collectors.toList()));
        assertEquals(Arrays.asList(2, 1, 1, 2, 1, 2),
                source.stream().map(group -> group.count).collect(Collectors.toList()));
    }

    @Test public void optionalTypesOnlyAdmitRecognisedDocumentsAndRespectEmptySelection() throws Exception {
        Path root = temp.newFolder("device").toPath();
        String[] filenames = {"file.JPG", "file.mp4", "file.mp3", "file.APK", "file.docx", "file.xlsx",
                "file.pptx", "file.pdf", "file.txt", "file.tar.gz", "file.epub", "state.json", "trace.log", "icon.svg"};
        for (String name : filenames) make(root, name, 100);
        assertEquals(9, scan(root).groups.size());
        List<File> volumes = Collections.singletonList(root.toFile());
        RecentFiles optional = RecentFiles.scan(volumes, false, EnumSet.of(Category.TXT, Category.OTHER), "", NOW);
        assertEquals(2, optional.groups.size());
        assertEquals(Arrays.asList("file.txt", "file.epub"),
                optional.groups.stream().map(group -> group.preview.get(0).file.name).collect(Collectors.toList()));
        assertEquals(Arrays.asList("file.epub", "trace.log"), names(optional.members(optional.groups.get(1))));
        assertTrue(RecentFiles.scan(volumes, false, EnumSet.noneOf(Category.class), "", NOW).groups.isEmpty());
    }

    @Test public void skipsPrivateAndThumbnailTreesRegardlessOfHiddenFilesOption() throws Exception {
        Path root = temp.newFolder("device").toPath(), other = temp.newFolder("outside").toPath();
        String[] paths = {"Pictures/.thumbnails/tiny.jpg", "ANDROID/data/app/tiny.jpg", "Android/obb/app/tiny.jpg",
                ".estrongs/tiny.jpg", "Tencent/MobileQQ/data/tiny.jpg", "tencent/MicroMsg/account/image2/tiny.jpg",
                "public/camera.jpg", ".private/my.jpg", "Android/media/chat/saved.jpg", "Download/unfinished.apk.part"};
        for (String path : paths) make(root, path, MINUTE);
        Files.createSymbolicLink(root.resolve("external"), other);
        Files.createSymbolicLink(root.resolve("link.jpg"), make(other, "outside.jpg", 5));
        assertEquals(2, scan(root).groups.size());
        RecentFiles all = RecentFiles.scan(Collections.singletonList(root.toFile()), true, defaultCategories(), "", NOW);
        assertEquals(3, all.groups.size());
        assertTrue(all.groups.stream().allMatch(group -> group.count == 1));
    }

    @Test public void incompleteVolumeDoesNotInvalidateAnotherVolumeAndCancelStopsScan() throws Exception {
        Path root = temp.newFolder("device").toPath();
        make(root, "file.pdf", MINUTE);
        RecentFiles result = RecentFiles.scan(Arrays.asList(temp.getRoot().toPath().resolve("gone").toFile(), root.toFile()),
                false, defaultCategories(), "", NOW);
        assertTrue(result.incomplete);
        assertEquals(1, result.groups.size());
        Thread.currentThread().interrupt();
        try {
            scan(root);
            fail("Interrupted scan returned a result");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static List<String> names(List<Entry> entries) {
        return entries.stream().map(entry -> entry.file.name).collect(Collectors.toList());
    }
}
