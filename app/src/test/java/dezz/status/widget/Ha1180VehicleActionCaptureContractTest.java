/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression contract recovered from the signed HA1180 APK. */
public final class Ha1180VehicleActionCaptureContractTest {
    @Test public void climateUsesTheStockBinderOperationInsteadOfTouchInjection()
            throws Exception {
        String client = source("climate/StockHvacPopupClient.java");
        String driverService = source("driver/DriverPanelService.java");
        String driver = source("driver/DriverPanelOverlayController.java");

        assertTrue(client.contains("ecarx.hvac.app.HvacAppService"));
        assertTrue(client.contains("ecarx.hvac.app.IOpenHvacAidlInterface"));
        assertTrue(client.contains("TRANSACTION_OPEN_HVAC_MAIN = 1"));
        assertTrue(client.contains("data.writeInterfaceToken(INTERFACE_DESCRIPTOR)"));
        assertTrue(client.contains("binder.transact(TRANSACTION_OPEN_HVAC_MAIN"));
        assertTrue(driverService.contains("StockHvacPopupClient.openMainPopup"));
        String trigger = between(driver, "private void triggerStockClimate()",
                "private void executeShortcut(");
        assertTrue(trigger.contains("DriverPanelService.triggerStockClimate(appContext)"));
        assertFalse(trigger.contains("performTap"));
        assertFalse(trigger.contains("input tap"));
    }

    @Test public void recorderSubscribesToKx11AdasSignalsOnlyWhileRecording()
            throws Exception {
        String fallback = geely("car/EcarxSignalFallback.java");
        String integration = geely("car/GeelyCarIntegration.java");
        String recorder = source("diagnostics/ActionRecorder.java");

        assertTrue(fallback.contains("boolean needsAdasRecorder"));
        assertTrue(fallback.contains("EcarxAdasSignalCatalog.propertyIds()"));
        assertTrue(fallback.contains("listener.onAdasSignal"));
        assertTrue(integration.contains("ECARX_ADAS_CAPTURE_REQUESTED"));
        assertTrue(integration.contains("ECARX_ADAS_SIGNAL_CHANGE"));
        assertTrue(integration.contains("\"write_enabled\", false"));
        assertTrue(integration.contains("ActionRecorder.addRecordingListener"));
        assertTrue(integration.contains("ActionRecorder.removeRecordingListener"));
        assertTrue(recorder.contains("CopyOnWriteArraySet"));
    }

    @Test public void accessibilityObservesTheRealStockClimateWindowAndDisplay()
            throws Exception {
        String accessibility = source("WidgetAccessibilityService.java");
        assertTrue(accessibility.contains("STOCK_HVAC_WINDOW_OBSERVED"));
        assertTrue(accessibility.contains("StockHvacPopupClient.isStockHvacWindow"));
        assertTrue(accessibility.contains("accessibilityDisplayId(event)"));
        assertTrue(accessibility.contains("ACCESSIBILITY_CAPTURE_READY"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String geely(String relative) throws Exception {
        return project("app/src/geely/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
