package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class Ha1136RegressionContractTest {
    @Test public void favoritesRepeatTapUsesOneLogicalTogglePath() throws Exception {
        String source = javaSource("driver/DriverPanelOverlayController.java");
        assertTrue(source.contains("FAVORITES_TOGGLE_DEBOUNCE_MS = 350L"));
        assertTrue(source.contains("lastFavoriteToggleAt.get(panelId)"));
        assertTrue(source.contains("manuallyOpenFavorites.contains(panelId)"));
        assertTrue(source.contains("private void executeShortcut("));
        assertTrue(source.contains("LauncherShortcutStore.isDriverFavoritesTarget("));
        assertTrue(source.contains("showFavorites(LauncherShortcutStore.driverFavoritesPanelId("));
    }

    @Test public void informationRowsGetRealHeightInsteadOfInvisibleWeightedSpace()
            throws Exception {
        String source = javaSource("driver/DriverPanelOverlayController.java");
        assertTrue(source.contains("Math.max(1, rowHeight)"));
        assertTrue(source.contains("ViewGroup.LayoutParams.MATCH_PARENT, topHeight"));
        assertTrue(source.contains("ViewGroup.LayoutParams.MATCH_PARENT, bottomHeight"));
        assertTrue(source.contains("ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f"));
        assertTrue(source.contains("dp(context, shortcut.informationIconSizePx) + padding"));
    }

    @Test public void phonePopupHasTwoEditableLayoutsWithoutCrossOverlayIdCollision()
            throws Exception {
        String automation = javaSource("phone/PhoneNotificationAutomation.java");
        String store = javaSource("popup/PopupItemConfigStore.java");
        String settings = javaSource("PhoneNotificationAutomationSettingsActivity.java");
        assertTrue(automation.contains("OVERLAY_WITH_ICON_ID = \"phone_notifications_icon\""));
        assertTrue(automation.contains("APPLICATION_ICON_ITEM_ID"));
        assertTrue(automation.contains("iconSize = dynamicAppIcon ? 72 : 0"));
        assertTrue(store.contains("config.overlayId + '\\u0000' + config.automationId"));
        assertTrue(settings.contains("Настроить первое уведомление · без иконки"));
        assertTrue(settings.contains("Настроить повторные · с иконкой"));
    }

    @Test public void appCatalogIsSavedBeforeAsyncVectorDownloadAndSurvivesUpdates()
            throws Exception {
        String source = javaSource("phone/PhoneAppIconStore.java");
        int save = source.indexOf("saveCatalogLocked();");
        int enqueue = source.indexOf("worker.execute(() -> downloadIcon");
        assertTrue(save >= 0);
        assertTrue(enqueue > save);
        assertTrue(source.contains("StatusWidget/ANCS-icons"));
        assertTrue(source.contains("simple-icons@16/icons/"));
        assertTrue(source.contains("new Download(bytes, \"svg\")"));
        assertTrue(source.contains("itunes.apple.com/lookup?bundleId="));
    }

    @Test public void bluetoothUsesOneOutlineOrFilledVectorAndResetsPerAncsSession()
            throws Exception {
        String widget = javaSource("WidgetService.java");
        String outline = resource("drawable/ic_status_bt_phone_outline.xml");
        String solid = resource("drawable/ic_status_bt_phone_solid.xml");
        assertTrue(widget.contains("PhoneBluetoothIndicatorPolicy.resolve("));
        assertTrue(widget.contains("phoneNotificationDeliveryConfirmed = true"));
        assertTrue(widget.contains("if (!phoneAncsReady)"));
        assertTrue(widget.contains("phoneNotificationDeliveryConfirmed = false"));
        assertTrue(outline.contains("android:fillColor=\"#00000000\""));
        assertTrue(outline.contains("android:strokeWidth=\"1.1\""));
        assertTrue(solid.contains("android:fillColor=\"@android:color/white\""));
        assertFalse(solid.contains("android:strokeWidth"));
    }

    @Test public void mediaTimelineUsesOnlyOriginalRenderedTitleWidth() throws Exception {
        String widget = javaSource("WidgetService.java");
        String layout = resource("layout/overlay_status_widget.xml");
        assertTrue(widget.contains("getMarqueeSourceText()"));
        assertTrue(widget.contains("MediaProgressWidthPolicy.width("));
        assertFalse(widget.contains("mediaTitleRow.getWidth()"));
        assertTrue(layout.contains("android:id=\"@+id/mediaProgressBar\""));
        assertTrue(layout.contains("android:layout_width=\"wrap_content\""));
    }

    @Test public void systemStatusBarOptionChangesAndroidPolicyInsteadOfDrawingMask()
            throws Exception {
        String policy = javaSource("launcher/EcarxSystemStatusBarPolicy.java");
        assertTrue(policy.contains("KEY = \"policy_control\""));
        assertTrue(policy.contains("STATUS_RULE = \"immersive.status=*\""));
        assertTrue(policy.contains("Settings.Global.putString"));
        assertTrue(policy.contains("settings put global policy_control"));
        assertFalse(policy.contains("WindowManager.addView"));
    }

    private static String javaSource(String relative) throws Exception {
        return read(root("app/src/main/java/dezz/status/widget")
                .resolve(relative));
    }

    private static String resource(String relative) throws Exception {
        return read(root("app/src/main/res").resolve(relative));
    }

    private static Path root(String relative) {
        Path result = Paths.get(relative);
        if (Files.isDirectory(result)) return result;
        return Paths.get("src").resolve(relative.substring("app/src/".length()));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
