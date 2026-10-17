/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.update;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Fault injection into PackageInfo; actual archive verification is in the API-28 suite. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class NatroSignerPolicyTest {
    @Test public void realPublicStableCertificateIsAccepted() throws Exception {
        NatroUpdater.requireStableSigner(info(stable()),"Скачанный APK");
    }
    @Test public void missingSigningInfoHasDistinctError() throws Exception {
        refused(new PackageInfo(),"Скачанный APK","не вернул сертификат");
    }
    @Test public void missingCurrentSignersAreRejected() throws Exception {
        refused(info((Signature[])null),"Скачанный APK","не вернул сертификат");
    }
    @Test public void emptyAndNullSignersAreRejected() throws Exception {
        refused(info(),"Скачанный APK","не вернул сертификат");
        refused(info((Signature)null),"Скачанный APK","не вернул сертификат");
    }
    @Test public void multipleSignersAreRejectedEvenWhenOneIsStable() throws Exception {
        refused(info(stable(),other()),"Скачанный APK","один сертификат");
    }
    @Test public void wrongDownloadedSignerIsRejected() throws Exception {
        refused(info(other()),"Скачанный APK","не совпадает");
    }
    @Test public void wrongInstalledSignerIsRejected() throws Exception {
        refused(info(other()),"Установленная Natro","не совпадает");
    }
    @Test public void legacyCertificateCannotOverrideMissingCurrentSigner() throws Exception {
        PackageInfo value=new PackageInfo();value.signatures=new Signature[]{stable()};
        refused(value,"Скачанный APK","не вернул сертификат");
    }
    @Test public void legacyAndPastCertificateCannotOverrideWrongCurrentSigner() throws Exception {
        PackageInfo value=info(other());value.signatures=new Signature[]{stable()};
        Shadows.shadowOf(value.signingInfo).setPastSigningCertificates(new Signature[]{stable()});
        refused(value,"Скачанный APK","не совпадает");
    }
    private static PackageInfo info(Signature... signatures){
        PackageInfo value=new PackageInfo();
        value.signingInfo=ReflectionHelpers.newInstance(SigningInfo.class);
        Shadows.shadowOf(value.signingInfo).setSignatures(signatures);return value;
    }
    private static Signature stable()throws Exception {
        try(InputStream input=NatroSignerPolicyTest.class.getResourceAsStream("/update/stable-certificate.pem")){
            assertNotNull(input);return new Signature(CertificateFactory.getInstance("X.509").generateCertificate(input).getEncoded());
        }
    }
    private static Signature other(){return new Signature(new byte[]{1,2,3});}
    private static void refused(PackageInfo value,String label,String reason)throws Exception {
        try{NatroUpdater.requireStableSigner(value,label);fail("Must reject");}
        catch(IOException error){assertTrue(error.getMessage(),error.getMessage().startsWith(label+":"));assertTrue(error.getMessage(),error.getMessage().contains(reason));}
    }
}
