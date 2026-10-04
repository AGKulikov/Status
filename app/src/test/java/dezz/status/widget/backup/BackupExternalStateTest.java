/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.app.Application;
import java.io.*;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class BackupExternalStateTest {
    @Test public void originalsAreArchivedWithoutWritingToTheSystemAndMissingIsExplicit()throws Exception{
        File root=RuntimeEnvironment.getApplication().getCacheDir(),source=new File(root,"system-fixture.bin"),snapshot=new File(root,"external-snapshot");
        BackupFiles.atomicWrite(source,new byte[]{1,2,3});
        JSONObject record=BackupExternalState.capture(source,true,snapshot,"fixture.bin");
        assertEquals("readable",record.getString("status"));assertEquals(BackupFiles.sha256(source),record.getString("sha256"));
        BackupExternalState.validate(snapshot,new JSONArray().put(record));
        assertArrayEquals(new byte[]{1,2,3},BackupFiles.read(source,10));
        assertEquals("missing",BackupExternalState.capture(new File(root,"absent"),true,snapshot,"absent.bin").getString("status"));
        File link=new File(root,"link");Files.createSymbolicLink(link.toPath(),source.toPath());
        assertEquals("not-regular",BackupExternalState.capture(link,true,snapshot,"link.bin").getString("status"));
        BackupFiles.atomicWrite(BackupFiles.child(snapshot,record.getString("archived")),new byte[]{9});
        try{BackupExternalState.validate(snapshot,new JSONArray().put(record));fail();}catch(IOException expected){}
    }
}
