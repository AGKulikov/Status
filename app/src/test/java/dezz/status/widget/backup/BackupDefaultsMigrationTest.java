/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.app.Application;
import dezz.status.widget.PortableBackupDefaults;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class BackupDefaultsMigrationTest {
    @Test public void startupIconsReleasePreservesOldDefaultsAndRejectsFuture() throws Exception {
        for(int source=208021335;source<=208021346;source++) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",source).put("defaultsSchema",source)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.singletonMap("enabled",false)));
            File snapshot=new File(RuntimeEnvironment.getApplication().getCacheDir(),"313-"+source);
            File main=BackupFiles.child(snapshot,"de_prefs/example_preferences.xml");
            BackupPreferencesXml.write(main,Collections.singletonMap("enabled",true));
            BackupDefaultsMigration.materialize(snapshot,"example",metadata,208021346);
            assertEquals(Boolean.TRUE,BackupPreferencesXml.read(main).get("enabled"));
        }
        for(int invalid:new int[]{208021334,208021347}) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",invalid).put("defaultsSchema",invalid)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.emptyMap()));
            try { BackupDefaultsMigration.validate(metadata,208021346);fail(); } catch(IOException expected) {}
        }
    }
    @Test public void hudDimReleasePreservesOldDefaultsAndRejectsFuture() throws Exception {
        for(int source=208021335;source<=208021345;source++) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",source).put("defaultsSchema",source)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.singletonMap("enabled",false)));
            File snapshot=new File(RuntimeEnvironment.getApplication().getCacheDir(),"312-"+source);
            File main=BackupFiles.child(snapshot,"de_prefs/example_preferences.xml");
            BackupPreferencesXml.write(main,Collections.singletonMap("enabled",true));
            BackupDefaultsMigration.materialize(snapshot,"example",metadata,208021345);
            assertEquals(Boolean.TRUE,BackupPreferencesXml.read(main).get("enabled"));
        }
        for(int invalid:new int[]{208021334,208021346}) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",invalid).put("defaultsSchema",invalid)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.emptyMap()));
            try { BackupDefaultsMigration.validate(metadata,208021345);fail(); } catch(IOException expected) {}
        }
    }
    @Test public void diagnosticReleasePreservesOldDefaultsAndRejectsFuture() throws Exception {
        for(int source=208021335;source<=208021344;source++) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",source).put("defaultsSchema",source)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.singletonMap("enabled",false)));
            File snapshot=new File(RuntimeEnvironment.getApplication().getCacheDir(),"311-"+source);
            File main=BackupFiles.child(snapshot,"de_prefs/example_preferences.xml");
            BackupPreferencesXml.write(main,Collections.singletonMap("enabled",true));
            BackupDefaultsMigration.materialize(snapshot,"example",metadata,208021344);
            assertEquals(Boolean.TRUE,BackupPreferencesXml.read(main).get("enabled"));
        }
        for(int invalid:new int[]{208021334,208021345}) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",invalid).put("defaultsSchema",invalid)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.emptyMap()));
            try { BackupDefaultsMigration.validate(metadata,208021344);fail(); } catch(IOException expected) {}
        }
    }
    @Test public void hudVolumeReleaseAcceptsThrough309ButRejectsFuture() throws Exception {
        for (int source = 208021335; source <= 208021343; source++) {
            JSONObject metadata = new JSONObject().put("sourceVersionCode", source).put("defaultsSchema", source)
                    .put("declaredMainDefaults", BackupPreferencesXml.encode(Collections.emptyMap()));
            BackupDefaultsMigration.validate(metadata, 208021343);
        }
        JSONObject future = new JSONObject().put("sourceVersionCode", 208021344).put("defaultsSchema", 208021344)
                .put("declaredMainDefaults", BackupPreferencesXml.encode(Collections.emptyMap()));
        try { BackupDefaultsMigration.validate(future, 208021343); fail(); } catch (IOException expected) { }
    }
    @Test public void followupReleaseAcceptsReviewedVersionsAndPreservesExplicitDefaults() throws Exception {
        for (int source = 208021335; source <= 208021342; source++) {
            JSONObject metadata = new JSONObject().put("sourceVersionCode", source).put("defaultsSchema", source)
                    .put("declaredMainDefaults", BackupPreferencesXml.encode(Collections.singletonMap("enabled", false)));
            File snapshot = new File(RuntimeEnvironment.getApplication().getCacheDir(), "309-" + source);
            File main = BackupFiles.child(snapshot, "de_prefs/example_preferences.xml");
            BackupPreferencesXml.write(main, Collections.singletonMap("enabled", true));
            BackupDefaultsMigration.materialize(snapshot, "example", metadata, 208021342);
            assertEquals(Boolean.TRUE, BackupPreferencesXml.read(main).get("enabled"));
        }
        for (int invalid : new int[]{208021334, 208021343}) {
            JSONObject metadata = new JSONObject().put("sourceVersionCode", invalid).put("defaultsSchema", invalid)
                    .put("declaredMainDefaults", BackupPreferencesXml.encode(Collections.emptyMap()));
            try { BackupDefaultsMigration.validate(metadata, 208021342); fail(); } catch (IOException expected) {}
        }
    }
    @Test public void logFixReleaseAcceptsReviewedVersionsAndPreservesExplicitDefaults() throws Exception {
        for (int source = 208021335; source <= 208021341; source++) {
            JSONObject metadata = new JSONObject().put("sourceVersionCode", source).put("defaultsSchema", source)
                    .put("declaredMainDefaults", BackupPreferencesXml.encode(Collections.singletonMap("enabled", false)));
            File snapshot = new File(RuntimeEnvironment.getApplication().getCacheDir(), "308-" + source);
            File main = BackupFiles.child(snapshot, "de_prefs/example_preferences.xml");
            BackupPreferencesXml.write(main, Collections.singletonMap("enabled", true));
            BackupDefaultsMigration.materialize(snapshot, "example", metadata, 208021341);
            assertEquals(Boolean.TRUE, BackupPreferencesXml.read(main).get("enabled"));
        }
        for (int invalid : new int[]{208021334, 208021342}) {
            JSONObject metadata = new JSONObject().put("sourceVersionCode", invalid).put("defaultsSchema", invalid)
                    .put("declaredMainDefaults", BackupPreferencesXml.encode(Collections.emptyMap()));
            try { BackupDefaultsMigration.validate(metadata, 208021341); fail(); } catch (IOException expected) {}
        }
    }
    @Test public void declaredDefaultsUseRealGettersWithoutWritingOrProxyIdentityFailure()throws Exception{
        Map<String,Object> values=PortableBackupDefaults.capture(RuntimeEnvironment.getApplication());
        assertTrue(values.size()>100);assertTrue(values.containsKey("launcherLayoutJson"));
        assertFalse(new File(RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext().getDataDir(),"shared_prefs").exists());
    }
    @Test public void loggingReleaseAcceptsAllReviewedPredecessorsAndRejectsFutureSchema()throws Exception{
        for(int source:new int[]{208021335,208021336,208021337,208021338}){
            JSONObject metadata=new JSONObject().put("sourceVersionCode",source).put("defaultsSchema",source)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.singletonMap("debugModeEnabled",false)));
            BackupDefaultsMigration.validate(metadata,208021338);
        }
        JSONObject future=new JSONObject().put("sourceVersionCode",208021339).put("defaultsSchema",208021339)
                .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.emptyMap()));
        try{BackupDefaultsMigration.validate(future,208021338);fail();}catch(IOException expected){}
    }
    @Test public void oldEffectiveDefaultsAndUnknownValuesSurviveOnTheNewVersion()throws Exception{
        File snapshot=new File(RuntimeEnvironment.getApplication().getCacheDir(),"migration");
        File main=BackupFiles.child(snapshot,"de_prefs/example_preferences.xml");
        Map<String,Object> oldDefaults=new LinkedHashMap<>();oldDefaults.put("enabled",false);oldDefaults.put("size",18);oldDefaults.put("passenger_layout","old");
        Map<String,Object> written=new LinkedHashMap<>();written.put("size",26);written.put("future-key",Long.MAX_VALUE);
        BackupPreferencesXml.write(main,written);
        JSONObject metadata=new JSONObject().put("sourceVersionCode",208021335).put("defaultsSchema",208021335)
                .put("declaredMainDefaults",BackupPreferencesXml.encode(oldDefaults));
        BackupDefaultsMigration.materialize(snapshot,"example",metadata,208021337);
        Map<String,Object> restored=BackupPreferencesXml.read(main);
        assertEquals(false,restored.get("enabled"));assertEquals(26,restored.get("size"));
        assertEquals("old",restored.get("passenger_layout"));assertEquals(Long.MAX_VALUE,restored.get("future-key"));
        assertFalse(restored.containsKey("new-target-only-default"));
        for(int invalid:new int[]{208021334,208021338}){
            metadata.put("sourceVersionCode",invalid).put("defaultsSchema",invalid);
            try{BackupDefaultsMigration.materialize(snapshot,"example",metadata,208021337);fail();}catch(IOException expected){}
            assertEquals(restored,BackupPreferencesXml.read(main));
        }
    }
    @Test public void passengerStartupReleasePreservesExplicitOptInAndRejectsFuture()throws Exception {
        for(int source:new int[]{208021335,208021336,208021337,208021338,208021339}) {
            JSONObject metadata=new JSONObject().put("sourceVersionCode",source).put("defaultsSchema",source)
                    .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.emptyMap()));
            File snapshot=new File(RuntimeEnvironment.getApplication().getCacheDir(),"pass-start-"+source);
            File main=BackupFiles.child(snapshot,"de_prefs/example_preferences.xml");
            BackupPreferencesXml.write(main,Collections.singletonMap("passengerLauncherAutoStart",true));
            BackupDefaultsMigration.materialize(snapshot,"example",metadata,208021339);
            assertEquals(Boolean.TRUE,BackupPreferencesXml.read(main).get("passengerLauncherAutoStart"));
        }
        JSONObject future=new JSONObject().put("sourceVersionCode",208021340).put("defaultsSchema",208021340)
                .put("declaredMainDefaults",BackupPreferencesXml.encode(Collections.emptyMap()));
        try{BackupDefaultsMigration.validate(future,208021339);fail();}catch(IOException expected){}
    }
}
