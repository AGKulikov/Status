/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;
import android.app.Application;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class RestoredSystemOperationsTest {
    @Test public void restoreKeepsEachNativeRouteUnconfirmedUntilItsExplicitVerifiedOperation()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        assertFalse(BackupMaintenance.systemOperationNeedsReview(app,"MEDIA"));
        BackupMaintenance.markRestored(app);
        assertTrue(BackupMaintenance.systemOperationNeedsReview(app,"MEDIA"));assertTrue(BackupMaintenance.systemOperationNeedsReview(app,"STAR"));
        BackupMaintenance.acknowledgePlayback(app);
        assertTrue("Playing music cannot approve native patches",BackupMaintenance.systemOperationNeedsReview(app,"MEDIA"));
        BackupMaintenance.systemOperationVerified(app,"MEDIA");
        assertFalse(BackupMaintenance.systemOperationNeedsReview(app,"MEDIA"));assertTrue(BackupMaintenance.systemOperationNeedsReview(app,"STAR"));
        BackupMaintenance.markRestored(app);
        assertTrue("A later restore cannot reuse prior approval",BackupMaintenance.systemOperationNeedsReview(app,"MEDIA"));
        BackupMaintenance.acknowledgePlayback(app);
    }
}
