package dezz.status.widget.phone;

import android.app.Application;
import android.content.pm.PackageInfo;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class PackageReplaceEarlyStartTest {
    @Test public void startupPrecedesBroadcastAndLateBroadcastDoesNotRestartBarrier() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        info.firstInstallTime = 1L;
        info.lastUpdateTime = System.currentTimeMillis() - 1L;
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);
        assertEquals(8000L, PackageReplaceBleRecoveryGate.remainingQuietMillis(app));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        PackageReplaceBleRecoveryGate.mark(app);
        assertEquals(3000L, PackageReplaceBleRecoveryGate.remainingQuietMillis(app));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3));
        assertEquals(0L, PackageReplaceBleRecoveryGate.remainingQuietMillis(app));
    }
}
