/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.update;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.SigningInfo;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,shadows={NatroUpdaterAndroid9Test.FrameworkSigningInfo.class,NatroUpdaterAndroid9Test.JvmFileDataSource.class})
public class MultiAppUpdaterTest {
    private static final String BASE="https://github.com/AGKulikov/Status/releases/download/test/";
    private static final String CERT="6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75";
    private Application app;
    private JSONArray releases;
    private final Map<String,String> responses=new HashMap<>();
    private final class Fake extends NatroUpdater {
        Fake(UpdateTarget target){super(app,target,Runnable::run);}
        @Override String fetch(String url,int limit)throws Exception {
            if(url.startsWith("https://api.github.com/"))return releases.toString();
            if(!responses.containsKey(url))throw new IOException("test network failure");
            return responses.get(url);
        }
        @Override void transfer(JSONObject selected,File destination)throws Exception {Files.write(destination.toPath(),new byte[]{0});}
    }
    @Before public void setUp()throws Exception {
        app=RuntimeEnvironment.getApplication();releases=new JSONArray();
        for(UpdateTarget target:UpdateTarget.values())install(target,10);
    }
    private void install(UpdateTarget target,long version)throws Exception {
        String fixture=System.getenv("NATRO_UPDATER_FIXTURE_APK");assertNotNull(fixture);
        PackageInfo info=app.getPackageManager().getPackageArchiveInfo(fixture,NatroUpdater.SIGNATURE_FLAGS);assertNotNull(info.signingInfo);
        info.packageName=target.packageName;info.applicationInfo.packageName=target.packageName;
        info.applicationInfo.sourceDir=null;info.applicationInfo.publicSourceDir=null;info.setLongVersionCode(version);
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);
    }
    private JSONObject manifest(UpdateTarget target,long version)throws Exception {
        return new JSONObject().put("schema",1).put("channel","stable").put("package",target.packageName)
                .put("versionCode",version).put("versionName","test "+version).put("size",3)
                .put("sha256","0".repeat(64)).put("certificateSha256",CERT).put("url",BASE+target.name()+".apk");
    }
    private void publish(UpdateTarget target,long version)throws Exception {
        String url=BASE+target.manifestName;
        responses.put(url,manifest(target,version).toString());
        releases.put(new JSONObject().put("assets",new JSONArray().put(new JSONObject().put("name",target.manifestName).put("browser_download_url",url))));
    }
    @Test public void eachAppSelectsOnlyItsOwnManifest()throws Exception {
        for(UpdateTarget target:UpdateTarget.values())publish(target,20+target.ordinal());
        for(UpdateTarget target:UpdateTarget.values()){
            Fake updater=new Fake(target);updater.check();JSONObject state=updater.status();
            assertEquals("available",state.getString("state"));assertEquals("test "+(20+target.ordinal()),state.getString("version"));assertTrue(state.getBoolean("canUpdate"));
        }
    }
    @Test public void companionErrorDoesNotChangeNatroResult()throws Exception {
        publish(UpdateTarget.NATRO,20);publish(UpdateTarget.MUSIC,20);responses.remove(BASE+UpdateTarget.MUSIC.manifestName);
        Fake natro=new Fake(UpdateTarget.NATRO),music=new Fake(UpdateTarget.MUSIC);natro.check();music.check();
        assertEquals("available",natro.status().getString("state"));assertEquals("error",music.status().getString("state"));
    }
    @Test public void unpublishedDoesNotMeanLatestAndDraftIsIgnored()throws Exception {
        Fake updater=new Fake(UpdateTarget.NAVIGATOR);updater.check();assertEquals("unpublished",updater.status().getString("state"));
        publish(UpdateTarget.NAVIGATOR,20);releases.getJSONObject(0).put("draft",true);updater.check();assertFalse(updater.status().getBoolean("canUpdate"));
    }
    @Test public void sameOrOlderVersionIsNotOffered()throws Exception {
        publish(UpdateTarget.NAVIGATOR,10);Fake updater=new Fake(UpdateTarget.NAVIGATOR);updater.check();
        assertEquals("current",updater.status().getString("state"));assertFalse(updater.status().getBoolean("canUpdate"));
    }
    @Test public void realInstalledVersionClosesOfferAfterReturningFromInstaller()throws Exception {
        publish(UpdateTarget.MUSIC,20);Fake updater=new Fake(UpdateTarget.MUSIC);updater.check();install(UpdateTarget.MUSIC,20);
        assertFalse(updater.status().getBoolean("canUpdate"));assertEquals("current",updater.status().getString("state"));
    }
    @Test public void wrongPackageSignerForeignReleaseAndOversizeAreRefused()throws Exception {
        for(UpdateTarget target:UpdateTarget.values()){
            Fake updater=new Fake(target);
            for(String field:new String[]{"package","certificateSha256","url","size"}){
                JSONObject value=manifest(target,20);
                if(field.equals("size"))value.put(field,target.maxApkBytes+1);
                else value.put(field,field.equals("url")?"https://github.com/AGKulikov/Status/releases/download/other/app.apk":"wrong");
                try{updater.validateManifest(value,BASE+target.manifestName);fail(field);}catch(IOException expected){}
            }
        }
    }
    @Test public void navigatorAccepts150MiBButMusicDoesNot()throws Exception {
        JSONObject nav=manifest(UpdateTarget.NAVIGATOR,20).put("size",150L*1024*1024);
        new Fake(UpdateTarget.NAVIGATOR).validateManifest(nav,BASE+UpdateTarget.NAVIGATOR.manifestName);
        JSONObject music=manifest(UpdateTarget.MUSIC,20).put("size",150L*1024*1024);
        try{new Fake(UpdateTarget.MUSIC).validateManifest(music,BASE+UpdateTarget.MUSIC.manifestName);fail();}catch(IOException expected){}
    }
    @Test public void incompleteDownloadIsRemovedAndSameButtonCanRetry()throws Exception {
        publish(UpdateTarget.MUSIC,20);Fake updater=new Fake(UpdateTarget.MUSIC);updater.check();updater.download();
        assertEquals("error",updater.status().getString("state"));assertTrue(updater.status().getBoolean("canUpdate"));assertFalse(updater.status().getBoolean("canInstall"));
        assertEquals(0,new File(app.getCacheDir(),"updates").list().length);
        updater.download();assertEquals("error",updater.status().getString("state"));
    }
    @Test(timeout=5000) public void installVerificationDoesNotBlockStatusPolling()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        NatroUpdater updater=new NatroUpdater(app,UpdateTarget.NATRO,Runnable::run){@Override void verify(File file,JSONObject manifest)throws Exception{entered.countDown();release.await();throw new IOException("test complete");}};
        ReflectionHelpers.setField(updater,"apk",new File(app.getCacheDir(),"fixture.apk"));ReflectionHelpers.setField(updater,"candidate",manifest(UpdateTarget.NATRO,20));
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try{
            threads.submit(()->{try{updater.installationIntent();}catch(Exception ignored){}});assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertNotNull(threads.submit(()->updater.status()).get(1,TimeUnit.SECONDS));
        }finally{release.countDown();threads.shutdownNow();}
    }
}
