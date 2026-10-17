/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.update;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real published APK, real API-28 archive parser and certificate verifier.
 * CI downloads the immutable 3.2.5 release fixture and verifies its SHA first.
 * Only the installed-package database is simulated; no network/installer/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,
        shadows=NatroUpdaterAndroid9Test.FrameworkSigningInfo.class)
public class NatroUpdaterAndroid9Test {
    // Robolectric's default ShadowSigningInfo ignores constructor certificates.
    // Call the actual Android implementation for this parser regression.
    @Implements(value=SigningInfo.class,minSdk=28)
    public static class FrameworkSigningInfo {}

    private static final String SHA="d3fed86ca73c76d1ff10eb3a89a22a731abc50df33ab1782e0986aa164a2daf6";
    private Application app;
    private File fixture;
    private NatroUpdater updater;
    private JSONObject manifest;

    @Before public void setUp() throws Exception {
        app=RuntimeEnvironment.getApplication();
        String path=System.getenv("NATRO_UPDATER_FIXTURE_APK");
        assertNotNull("Set NATRO_UPDATER_FIXTURE_APK to the published signed 3.2.5 APK",path);
        fixture=new File(path);
        assertTrue(fixture.isFile());
        assertEquals(SHA,hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture.toPath()))));
        manifest=new JSONObject().put("size",fixture.length()).put("sha256",SHA)
                .put("versionCode",208021358L).put("versionName","3.2.5");
        updater=new NatroUpdater(app);
    }

    @Test public void android9RequiresBothFlagsToCollectCurrentCertificate() throws Exception {
        PackageManager pm=app.getPackageManager();
        PackageInfo original=pm.getPackageArchiveInfo(fixture.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotNull(original);
        assertNull("Original Android 9 path does not collect certificates",original.signingInfo);
        PackageInfo fixed=pm.getPackageArchiveInfo(fixture.getAbsolutePath(),NatroUpdater.SIGNATURE_FLAGS);
        assertNotNull(fixed);
        assertNotNull(fixed.signingInfo);
        NatroUpdater.requireStableSigner(fixed,"Скачанный APK");
    }

    @Test public void actualPublishedApkPassesCompleteVerification() throws Exception {
        installPreviousVersion(208021357L);
        updater.verify(fixture,manifest);
    }

    @Test public void incorrectHashStillFailsBeforeSignatureCheck() throws Exception {
        installPreviousVersion(208021357L);
        manifest.put("sha256","0".repeat(64));
        expectFailure(()->updater.verify(fixture,manifest),"SHA-256");
    }

    @Test public void sameVersionStillCannotBeInstalledAsUpdate() throws Exception {
        installPreviousVersion(208021358L);
        expectFailure(()->updater.verify(fixture,manifest),"версия");
    }

    @Test public void installActionRechecksPreparedBytes() throws Exception {
        installPreviousVersion(208021357L);
        updater.verify(fixture,manifest);
        File changed=new File(app.getCacheDir(),"changed-update.apk");
        byte[] bytes=Files.readAllBytes(fixture.toPath());bytes[bytes.length/2]^=1;
        Files.write(changed.toPath(),bytes);
        ReflectionHelpers.setField(updater,"apk",changed);
        ReflectionHelpers.setField(updater,"candidate",manifest);
        expectFailure(()->updater.installationIntent(),"SHA-256");
    }

    private void installPreviousVersion(long version) {
        PackageInfo current=app.getPackageManager().getPackageArchiveInfo(fixture.getAbsolutePath(),NatroUpdater.SIGNATURE_FLAGS);
        assertNotNull(current);
        assertEquals(app.getPackageName(),current.packageName);
        current.setLongVersionCode(version);
        // Do not associate fixture.sourceDir with an installed package: archive
        // lookups must keep using the actual Android parser, not a shadow cache.
        current.applicationInfo.sourceDir=null;
        current.applicationInfo.publicSourceDir=null;
        Shadows.shadowOf(app.getPackageManager()).installPackage(current);
    }
    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation,String fragment)throws Exception {
        try {operation.run();fail("Expected refusal: "+fragment);}
        catch(IOException error){assertTrue(error.getMessage(),error.getMessage().contains(fragment));}
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
