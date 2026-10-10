package dezz.status.widget.phone;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class PhoneNotificationAsyncIconTest {
    @Test(timeout=10000) public void busyIconCatalogDoesNotBlockShowingNotificationText() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        PhoneAppIconStore store = PhoneAppIconStore.get(app);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (store) {
                locked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        writer.start();
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS));
            PhoneNotificationCardView view = new PhoneNotificationCardView(app);
            long start = System.nanoTime();
            view.setPresentation(PhoneNotificationLayoutConfig.carPlay(
                    PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID),
                    new PhoneNotificationCardView.Model("Test app", "Title", "Message", "phone-app:test.app"));
            assertTrue("Showing text must not wait for the catalog writer",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 1000);
            java.lang.reflect.Field badgeField = PhoneNotificationCardView.class.getDeclaredField("badge");
            badgeField.setAccessible(true);
            Object badge = badgeField.get(view);
            java.lang.reflect.Field source = badge.getClass().getDeclaredField("sourceDrawable");
            source.setAccessible(true);
            assertNull("Pending custom icon must never flash the default icon", source.get(badge));
            // Replacing the card while the first image is blocked must also return immediately.
            view.setPresentation(PhoneNotificationLayoutConfig.carPlay(
                    PhoneNotificationAutomation.OVERLAY_ID),
                    new PhoneNotificationCardView.Model("Next app", "Next title", "Next message", null));
            assertNotNull("No icon reference still receives the normal preview/fallback", source.get(badge));
        } finally { release.countDown(); writer.join(2000); }
    }
}
