/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;
public class LocalImageFilesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void listsFoldersAndImagesOnlyWithoutRecursing()throws Exception {
        File root=temp.newFolder("volume"),folder=new File(root,"Pictures");assertTrue(folder.mkdir());
        Files.write(new File(root,"b.JPEG").toPath(),new byte[0]);Files.write(new File(root,"a.png").toPath(),new byte[0]);
        Files.write(new File(root,"notes.txt").toPath(),new byte[0]);Files.write(new File(root,".hidden.png").toPath(),new byte[0]);
        Files.write(new File(folder,"nested.png").toPath(),new byte[0]);
        List<File> entries=LocalImageFiles.list(root,root);
        assertEquals(3,entries.size());assertEquals("Pictures",entries.get(0).getName());assertEquals("a.png",entries.get(1).getName());
    }
    @Test public void refusesParentAndEscapingSymlink()throws Exception {
        File root=temp.newFolder("root"),outside=temp.newFolder("root-other");
        assertFalse(LocalImageFiles.within(root,outside));
        Files.createSymbolicLink(new File(root,"escape").toPath(),outside.toPath());
        assertTrue(LocalImageFiles.list(root,root).isEmpty());
        try{LocalImageFiles.list(root,outside);fail();}catch(IOException expected){}
    }
}
