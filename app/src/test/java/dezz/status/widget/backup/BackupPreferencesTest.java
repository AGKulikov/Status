/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.app.Application;
import android.content.*;
import dezz.status.widget.media.RuntimePreferenceWriter;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class BackupPreferencesTest {
    @Test public void barrierFlushesNewStoresAndHoldsLateCommitsUntilReleased()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        SharedPreferences store=BackupPreferences.open(app,"new-namespace",0);
        store.edit().putLong("future",Long.MAX_VALUE).apply();
        CountDownLatch started=new CountDownLatch(1),finished=new CountDownLatch(1);
        Thread writer;
        try(AutoCloseable barrier=BackupPreferences.freezeAndFlush()){
            assertEquals(Long.MAX_VALUE,BackupPreferencesXml.read(new java.io.File(app.getDataDir(),"shared_prefs/new-namespace.xml")).get("future"));
            writer=new Thread(()->{started.countDown();store.edit().putInt("late",7).commit();finished.countDown();});writer.start();
            assertTrue(started.await(2,TimeUnit.SECONDS));assertFalse(finished.await(100,TimeUnit.MILLISECONDS));
            assertFalse(store.contains("late"));
        }
        assertTrue(finished.await(2,TimeUnit.SECONDS));writer.join();assertEquals(7,store.getInt("late",0));
    }
    @Test public void acceptedRuntimeWritesAreDrainedAndPostBoundaryWritesResumeAfterFailure()throws Exception{
        SharedPreferences store=BackupPreferences.open(RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext(),"runtime-freeze",0);
        RuntimePreferenceWriter.put(store,"selected",1,"future",Long.MAX_VALUE);
        try(AutoCloseable runtime=RuntimePreferenceWriter.freezeForBackup();AutoCloseable barrier=BackupPreferences.freezeAndFlush()){
            assertEquals(1,store.getInt("selected",0));assertEquals(Long.MAX_VALUE,store.getLong("future",0));
            RuntimePreferenceWriter.put(store,"selected",2);assertEquals(1,store.getInt("selected",0));
        }
        try(AutoCloseable runtime=RuntimePreferenceWriter.freezeForBackup()){
            assertEquals(2,store.getInt("selected",0));assertEquals(Long.MAX_VALUE,store.getLong("future",0));
        }
    }
}
