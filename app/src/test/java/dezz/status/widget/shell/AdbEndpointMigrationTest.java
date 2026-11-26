package dezz.status.widget.shell;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AdbEndpointMigrationTest {
    @Test public void bothCachesRejectLegacyTelnetAndKeepAdb() {
        Context context = RuntimeEnvironment.getApplication();
        Context device = context.createDeviceProtectedStorageContext();
        SharedPreferences single = device.getSharedPreferences(context.getPackageName() + "_privileged_shell", 0);
        single.edit().putString("host", "127.0.0.1").putInt("port", 23).putString("transport", "Telnet").commit();
        ConnectionStorage cache = new ConnectionStorage(context);
        assertNull(cache.load());
        single.edit().putInt("port", 5555).putString("transport", "ADB").commit();
        assertEquals("ADB", cache.load().transport);

        device.getSharedPreferences(context.getPackageName() + "_connection", 0).edit()
                .putStringSet("endpoints", new HashSet<>(Arrays.asList("127.0.0.1|23|Telnet",
                        "127.0.0.1|5555|ADB", "127.0.0.1|5555|unknown"))).commit();
        dezz.status.widget.servicemode.ConnectionStorage service =
                new dezz.status.widget.servicemode.ConnectionStorage(context);
        assertEquals(1, service.loadAll().size());
        assertEquals("ADB", service.loadAll().get(0).transport);
    }
}
