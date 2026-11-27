package dezz.status.widget.qnx;

import android.app.Application;
import android.content.Context;
import dezz.status.widget.backup.BackupStorage;
import java.io.IOException;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class QnxSshProfileTest {
    @Test public void savesTrustLocallyAndOmitsItFromPortableBackup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertNull(QnxSshProfile.loadOptional(context));
        assertThrows(IOException.class, () -> QnxSshProfile.load(context));
        String pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
        QnxSshProfile.save(context, new QnxSshProfile("operator", pin));
        QnxSshProfile saved = QnxSshProfile.load(context);
        assertEquals("operator", saved.user);
        assertEquals(pin, saved.pin);
        assertEquals("operator", QnxSshProfile.loadOptional(context).user);
        QnxSshProfile.useDirect(context);
        assertNull(QnxSshProfile.loadOptional(context));
        assertFalse(new BackupStorage(context).transaction().currentFiles()
                .containsKey("ce_no_backup/qnx-ssh/profile"));
    }
}
