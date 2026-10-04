/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.app.Application;
import android.content.*;
import android.net.Uri;
import dezz.status.widget.PortableBackupSecrets;
import dezz.status.widget.phone.liveactivity.ApnsCredentialStore;
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
public class BackupStorageIntegrationTest {
    @Test public void wholeArchiveRestoresTypedNamespacesResourcesAndSecretsUnderNewKeys()throws Exception{
        Context app=RuntimeEnvironment.getApplication(),device=app.createDeviceProtectedStorageContext();
        android.provider.Settings.Secure.putString(app.getContentResolver(),"android_id","fixture-installation");
        try(TestAndroidKeyStore keys=new TestAndroidKeyStore()){
            byte[] pem=TestAndroidKeyStore.apnsPem();
            new ApnsCredentialStore(app).save("TEAM123456","KEY0123456",ApnsCredentialStore.DEFAULT_TOPIC,false,pem);
            File resource=new File(app.getCacheDir(),"external-icon.bin");BackupFiles.atomicWrite(resource,new byte[]{8,7,6});
            String oldUri=Uri.fromFile(resource).toString();
            SharedPreferences main=BackupPreferences.open(device,app.getPackageName()+"_preferences",0);
            String oldSecret=PortableBackupSecrets.wrapPreference(app,"synthetic-fixture-secret");
            main.edit().putString("fixtureSecret",oldSecret).putString("customIconUri",oldUri)
                    .putString("customConfig","{\"iconUri\":\""+oldUri+"\",\"scale\":1.25}").commit();
            Map<String,Object> expected=new LinkedHashMap<>();expected.put("unknown-long",Long.MAX_VALUE);expected.put("float",-0.0f);
            expected.put("set",new HashSet<>(Arrays.asList("я","雪","")));expected.put("empty","");expected.put("disabled",false);
            BackupPreferencesXml.write(new File(device.getDataDir(),"shared_prefs/future_namespace.xml"),expected);
            BackupFiles.atomicWrite(new File(app.getFilesDir(),"custom/sub/file.bin"),new byte[]{1,2,3});
            File source=new File(app.getCacheDir(),"snapshot");BackupStorage storage=new BackupStorage(app);
            JSONObject metadata=storage.capture(source).put("navigator",new JSONObject().put("installed",false));
            ByteArrayOutputStream archive=new ByteArrayOutputStream();char[] password="test-password-only".toCharArray();
            BackupArchive.write(source,metadata,password,archive);
            byte[] bytes=archive.toByteArray();
            assertFalse(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1).contains("synthetic-fixture-secret"));
            keys.erase(); // There is no access to either source installation wrapping key.
            File imported=new File(app.getCacheDir(),"imported");
            JSONObject importedMetadata=BackupArchive.read(new ByteArrayInputStream(bytes),password,imported);
            File data=new File(imported,"data");storage.prepareRestore(data,importedMetadata);
            Map<String,Object> restored=BackupPreferencesXml.read(new File(data,"de_prefs/"+app.getPackageName()+"_preferences.xml"));
            String newSecret=(String)restored.get("fixtureSecret");assertNotEquals(oldSecret,newSecret);
            assertEquals("synthetic-fixture-secret",PortableBackupSecrets.unwrapPreference(app,newSecret));
            assertArrayEquals(pem,PortableBackupSecrets.unwrapApns(BackupFiles.read(new File(data,"ce_no_backup/live_activity_apns_p8_v1.bin"),32768)));
            assertEquals(expected,BackupPreferencesXml.read(new File(data,"de_prefs/future_namespace.xml")));
            assertNotEquals(oldUri,restored.get("customIconUri"));
            assertEquals(restored.get("customIconUri"),new JSONObject((String)restored.get("customConfig")).getString("iconUri"));
            assertEquals(1.25,new JSONObject((String)restored.get("customConfig")).getDouble("scale"),0);
            storage.transaction().apply(data,new JSONObject());
            assertArrayEquals(new byte[]{8,7,6},BackupFiles.read(new File(Uri.parse((String)restored.get("customIconUri")).getPath()),10));
            assertArrayEquals(new byte[]{1,2,3},BackupFiles.read(new File(app.getFilesDir(),"custom/sub/file.bin"),10));
            assertEquals(expected,BackupPreferencesXml.read(new File(device.getDataDir(),"shared_prefs/future_namespace.xml")));
            Arrays.fill(pem,(byte)0);Arrays.fill(password,'\0');
        }
    }
}
