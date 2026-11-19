/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RecentFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path make(Path root, String path) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }

    @Test public void includesUnopenedDownloadsAndSameNamesAcrossVolumesInTimeOrder() throws Exception {
        Path device = temp.newFolder("device").toPath(), usb = temp.newFolder("usb").toPath();
        Path download = make(device, "Download/Natro.apk"), opened = make(usb, "share/Natro.apk");
        Path deleted = make(device, "missing.pdf");
        Files.delete(deleted);
        Files.createSymbolicLink(device.resolve("outside"), usb);
        make(device, ".hidden/private.txt");
        make(device, "Android/data/sample/cache.db");
        make(device, "Download/unfinished.apk.part");
        long now = System.currentTimeMillis();
        Map<String, Long> times = new HashMap<>();
        times.put(opened.toString(), now + 60000);
        times.put(deleted.toString(), now + 70000);
        List<File> roots = Arrays.asList(device.toFile(), usb.toFile(), device.resolve("Download").toFile());
        RecentFiles list = RecentFiles.scan(roots, false, times);
        assertFalse(list.incomplete);
        assertEquals(2, list.entries.size());
        assertEquals(usb.toFile(), list.entries.get(0).root);
        assertEquals("/share/Natro.apk", list.entries.get(0).file.path);
        assertEquals("/Download/Natro.apk", list.entries.get(1).file.path);
        Files.delete(download);
        assertEquals(1, RecentFiles.scan(roots, false, times).entries.size());
    }

    @Test public void keepsGlobalNewestAndReportsAnUnmountedRootWithoutDroppingRemainingFiles() throws Exception {
        Path root = temp.newFolder("volume").toPath();
        Map<String, Long> times = new HashMap<>();
        for (int i = 0; i < RecentFiles.LIMIT + 5; i++)
            times.put(make(root, String.valueOf(i)).toString(), 4000000000000L + i);
        RecentFiles found = RecentFiles.scan(Arrays.asList(root.toFile(), temp.getRoot().toPath().resolve("gone").toFile()), false, times);
        assertTrue(found.incomplete);
        List<String> names = found.entries.stream().map(e -> e.file.name).collect(Collectors.toList());
        assertEquals(RecentFiles.LIMIT, names.size());
        assertEquals(String.valueOf(RecentFiles.LIMIT + 4), names.get(0));
        assertEquals("5", names.get(names.size() - 1));
    }

    @Test public void leavingTheSectionCancelsScan() throws Exception {
        Thread.currentThread().interrupt();
        try {
            RecentFiles.scan(Collections.singletonList(temp.getRoot()), false, Collections.emptyMap());
            fail("Scan should be interrupted");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
