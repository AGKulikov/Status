/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class PhoneWidgetServiceIntegrationContractTest {
    @Test
    public void serviceOwnsPhoneTransportAndPresenceExporter() throws Exception {
        String source = readService();
        assertTrue(source.contains("private PhoneConnectorController phoneController;"));
        assertTrue(source.contains(
                "private PhoneSprutPresenceExporter phonePresenceExporter;"));
        assertTrue(source.contains("new PhoneConnectorController(this, prefs, connectorValues,"));
        assertTrue(source.contains(
                "exporter.onPhoneConnectionChanged(connected)"));
    }

    @Test
    public void sprutCallbacksReconcilePhonePresence() throws Exception {
        String source = readService();
        assertTrue(source.contains(
                "phonePresenceExporter.onSprutConnectionChanged(state)"));
        assertTrue(source.contains("phonePresenceExporter.onSprutCatalogChanged()"));
        assertTrue(source.contains(
                "phonePresenceExporter.onSprutCharacteristicChanged(path)"));
    }

    @Test
    public void exactDeviceBoundaryLoadsBeforePhoneReconfigure() throws Exception {
        String source = readService();
        int method = source.indexOf("private void reconfigureIntegrationControllers()");
        int presence = source.indexOf("phonePresenceExporter.reconfigure()", method);
        int phone = source.indexOf("phoneController.reconfigure()", method);
        assertTrue(method >= 0);
        assertTrue(presence > method);
        assertTrue(phone > presence);
    }

    @Test
    public void settingsCanRequestOneFreshDirectAncsHandshake() throws Exception {
        String source = readService();
        assertTrue(source.contains("public boolean reconnectPhoneForDiagnostics()"));
        assertTrue(source.contains(
                "return controller != null && controller.reconnectForDiagnostics()"));
    }

    @Test
    public void phoneSubmitsOffBeforeSprutShutdown() throws Exception {
        String source = readService();
        int destroy = source.indexOf("public void onDestroy()");
        int phone = source.indexOf("phoneController::stop", destroy);
        int presence = source.indexOf("phonePresenceExporter::stop", destroy);
        int sprut = source.indexOf("sprutController::stop", destroy);
        assertTrue(destroy >= 0);
        assertTrue(phone > destroy);
        assertTrue(presence > phone);
        assertTrue(sprut > presence);
    }

    @Test
    public void phoneOnlyConnectorColdStartsAndSurvivesBootAsAStickyHost() throws Exception {
        String starter = readWidgetSource("WidgetServiceStarter.java");
        String bootstrap = readWidgetSource("AppRuntimeBootstrap.java");
        String service = readService();
        String settings = readWidgetSource("PhoneConnectorSettingsActivity.java");
        String boot = readWidgetSource("BootReceiver.java");
        String manifest = readMainSource("AndroidManifest.xml");

        assertTrue(starter.contains("preferences.phoneConnectorEnabled.get()"));
        assertTrue(starter.contains("requiresIntegrationHost(preferences)"));
        assertTrue(starter.contains("requiresHeadlessHost(preferences)"));
        assertTrue(bootstrap.contains(
                "WidgetServiceStarter.requiresIntegrationHost(preferences)"));
        assertTrue(bootstrap.contains(
                "WidgetServiceStarter.requiresHeadlessHost(preferences)"));
        assertTrue(count(service,
                "WidgetServiceStarter.requiresHeadlessHost(prefs)") >= 4);
        assertTrue(service.contains(
                "WidgetServiceStarter.requiresIntegrationHost(prefs)"));
        assertTrue(service.contains("else if (headlessHostRequired)"));
        assertTrue(service.contains("runInitialIntegrationStartup()"));
        assertTrue(service.contains("return START_STICKY;"));
        assertTrue(settings.contains("WidgetServiceStarter.startIfNeeded(this)"));

        assertTrue(boot.contains("WidgetServiceStarter.startIfNeededWithRetry(context)"));
        assertTrue(boot.contains("WidgetServiceStarter.retryFromAlarm(context"));
        assertTrue(starter.contains(
                "RETRY_DELAYS_MS = {2_000L, 5_000L, 15_000L}"));
        assertTrue(starter.contains("scheduleRetry(app, retryAttempt)"));

        int serviceEntry = manifest.indexOf("android:name=\".WidgetService\"");
        int serviceEnd = manifest.indexOf("/>", serviceEntry);
        assertTrue(serviceEntry >= 0 && serviceEnd > serviceEntry);
        String widgetServiceEntry = manifest.substring(serviceEntry, serviceEnd);
        assertTrue(widgetServiceEntry.contains("android:directBootAware=\"true\""));
        assertTrue(widgetServiceEntry.contains("android:stopWithTask=\"false\""));
    }

    private static String readService() throws Exception {
        return readWidgetSource("WidgetService.java");
    }

    private static String readWidgetSource(String name) throws Exception {
        Path path = Paths.get("src/main/java/dezz/status/widget").resolve(name);
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/dezz/status/widget").resolve(name);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String readMainSource(String name) throws Exception {
        Path path = Paths.get("src/main").resolve(name);
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main").resolve(name);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
