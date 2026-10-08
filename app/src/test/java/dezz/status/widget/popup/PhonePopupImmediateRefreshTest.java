package dezz.status.widget.popup;

import android.app.Application;
import android.view.View;
import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.phone.PhoneNotificationAutomation;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class PhonePopupImmediateRefreshTest {
    @Test public void newCardRendersWithoutTheGeneralServiceRefreshQueue() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Preferences prefs = new Preferences(app);
        PhoneNotificationAutomation.ensureConfigured(prefs);
        AutomationStateStore states = new AutomationStateStore(app);
        PopupOverlayManager manager = new PopupOverlayManager(app, prefs, states, null, id -> null);
        manager.applyPreferences();
        for (String overlay : new String[]{PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID,
                PhoneNotificationAutomation.OVERLAY_ID, PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID}) {
            for (String id : new String[]{PhoneNotificationAutomation.OVERLAY_ID,
                    PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID})
                states.apply(AutomationContract.SCOPE_OVERLAY, id,
                        new JSONObject().put("visible", id.equals(overlay)).put("fresh", true));
            states.apply(AutomationContract.SCOPE_POPUP, PhoneNotificationAutomation.TEXT_AUTOMATION_ID,
                    new JSONObject().put("text", "Notification " + overlay).put("visible", true).put("fresh", true));
            // Intentionally do not dispatch onStateChanged: the global UI lane can be deferred.
            manager.refreshPhonePresentation();
            java.lang.reflect.Field field = PopupOverlayManager.class.getDeclaredField("controllers");
            field.setAccessible(true);
            Object controller = ((java.util.Map<?,?>)field.get(manager)).get(overlay);
            java.lang.reflect.Field card = PopupOverlayController.class.getDeclaredField("phoneNotificationCard");
            card.setAccessible(true);
            assertNotNull("Selected layout must be rendered immediately", card.get(controller));
            java.lang.reflect.Field attached = PopupOverlayController.class.getDeclaredField("rootAdded");
            attached.setAccessible(true);
            assertTrue(attached.getBoolean(controller));
        }
        manager.destroy();
    }
}
