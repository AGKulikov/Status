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

    private static String readService() throws Exception {
        Path path = Paths.get("src/main/java/dezz/status/widget/WidgetService.java");
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/dezz/status/widget/WidgetService.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
