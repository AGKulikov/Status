/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression contract for popup low-battery delivery and foreground-app deferral. */
public final class Ha1217PhoneNotificationDeferralContractTest {
    @Test
    public void lowBatteryUsesConfiguredIconPopupAndNotOnlyTicker() throws Exception {
        String service = source("WidgetService.java");
        String card = source("phone/PhoneNotificationCardView.java");
        assertTrue(service.contains("showPhoneLowBatteryPopup(level)"));
        assertTrue(service.contains("|| prefs.phoneLowBatteryAlertEnabled.get()"));
        assertTrue(service.contains("PhoneNotificationAutomation.LOW_BATTERY_ICON_ID"));
        assertTrue(service.contains("OVERLAY_WITH_ICON_ID"));
        assertTrue(card.contains("PhoneNotificationLowBatteryIconFactory.create"));
    }

    @Test
    public void foregroundHoldHasEventHookOneDeadlineAndOrderedReplay() throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains("onPhoneNotificationForegroundChanged()"));
        assertTrue(service.contains("phoneNotificationDeferralDeadline"));
        assertTrue(service.contains("schedulePhoneNotificationDeferralDeadline()"));
        assertTrue(service.contains("releaseAllDeferredPhoneNotifications()"));
        assertTrue(service.contains("deferredPhoneNotifications.drainDue(now, seconds)"));
        assertTrue(service.contains("deferredPhoneNotifications.drainAll()"));
        assertTrue(service.contains("enqueuePhoneNotificationNow(delivery)"));
        assertTrue(service.contains("mainHandler.removeCallbacks(phoneNotificationDeferralDeadline)"));
    }

    @Test
    public void settingsPersistBroadInstalledAppSelectionAndMaximumWait() throws Exception {
        String prefs = source("Preferences.java");
        String settings = source("PhoneNotificationAutomationSettingsActivity.java");
        assertTrue(prefs.contains("phoneNotificationDelayInPackages"));
        assertTrue(prefs.contains("phoneNotificationDelayMaxWaitSeconds"));
        assertTrue(prefs.contains("migratePhoneNotificationDeferralIfNeeded()"));
        assertTrue(settings.contains("InstalledAppCatalog.load(this)"));
        assertTrue(settings.contains("setMultiChoiceItems"));
        assertTrue(settings.contains("boundedMaxWaitSeconds"));
        assertTrue(settings.contains("foregroundTrackingAvailable()"));
        assertTrue(settings.contains("Permissions.isAccessibilityServiceEnabled"));
        assertTrue(settings.contains("Permissions.isUsageAccessGranted"));
        assertTrue(settings.contains("showForegroundTrackingRequired()"));
    }

    @Test
    public void unknownForegroundFailsSafeUntilTheBoundedDeadline() throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains("if (lastForegroundPackage == null) return true"));
        assertTrue(service.contains("configured monotonic maximum-wait deadline"));
        assertTrue(service.contains("phoneNotificationDeferralDeadline"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get("src/main/java/dezz/status/widget").resolve(relative);
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/dezz/status/widget").resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
