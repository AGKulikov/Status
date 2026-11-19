/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class StockManeuverPackageTest {
    private static void install(PackageManager pm, String name, int uid, long code, String signer) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.setLongVersionCode(code);
        info.signatures = new Signature[]{new Signature(signer)};
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.uid = uid;
        info.applicationInfo.packageName = name;
        Shadows.shadowOf(pm).installPackage(info);
    }

    @Test public void installedRepackUsesSameArtworkOnlyWithProjectSignature() throws Exception {
        String host = "test.natro.host", navigator = "ru.yandex.yandexnavi";
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        install(pm, host, 22101, 1, "1234");
        for (long code : new long[]{739564630L, 739564631L, 739564632L, 739564633L}) {
            install(pm, navigator, 22102, code, "1234");
            assertEquals(code != 739564633L, StockManeuverResources.compatiblePackage(
                    pm.getPackageInfo(navigator, 0), pm, host));
        }
        install(pm, navigator, 22102, 739564632L, "5678");
        assertFalse(StockManeuverResources.compatiblePackage(pm.getPackageInfo(navigator, 0), pm, host));
    }
}
