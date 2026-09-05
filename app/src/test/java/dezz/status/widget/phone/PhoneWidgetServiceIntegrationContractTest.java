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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneWidgetServiceIntegrationContractTest {
    @Test
    public void serviceOwnsPhoneTransportAndPresenceExporter() throws Exception {
        String source = readService();
        assertTrue(source.contains("private volatile PhoneConnectorController phoneController;"));
        assertTrue(source.contains(
                "private volatile PhoneSprutPresenceExporter phonePresenceExporter;"));
        assertTrue(source.contains(
                "private volatile PhoneSprutPresenceExporter phoneAncsPresenceExporter;"));
        assertTrue(source.contains("new PhoneConnectorController(this, prefs, connectorValues,"));
        assertTrue(source.contains(
                "exporter.onPhoneConnectionChanged(connected)"));
        assertTrue(source.contains("PhoneSprutPresenceExporter.Signal.ANCS"));
        assertTrue(source.contains("onAncsConnectionChanged(boolean connected)"));

        String dispatch = between(source,
                "private PreparedInitialIntegrationStage prepareInitialIntegrationWorkerStage(",
                "private PreparedInitialIntegrationStage preparePhonePresenceStage(");
        assertTrue(dispatch.contains("case 1:"));
        assertTrue(dispatch.contains("return preparePhoneStage(stage)"));
        assertTrue(dispatch.contains("case 2:"));
        assertTrue(dispatch.contains("return preparePhonePresenceStage(stage)"));

        String presence = between(source,
                "private PreparedInitialIntegrationStage preparePhonePresenceStage(",
                "private PreparedInitialIntegrationStage preparePhoneStage(");
        int phonePresenceReady = presence.indexOf("prepared.phonePresence.reconfigure()");
        int ancsPresenceReady = presence.indexOf("prepared.ancsPresence.reconfigure()");
        int presencePublication = presence.indexOf("publishSprutRuntimeGraph(prepared)");
        assertTrue(phonePresenceReady >= 0);
        assertTrue(ancsPresenceReady > phonePresenceReady);
        assertTrue(presencePublication > ancsPresenceReady);
        assertTrue(presence.contains("() -> discardSprutRuntimeGraph(prepared)"));

        String phone = between(source,
                "private PreparedInitialIntegrationStage preparePhoneStage(",
                "private PreparedInitialIntegrationStage prepareStatusSurfaceStage(");
        int phonePrepared = phone.indexOf("next.reconfigure()");
        int phonePublished = phone.indexOf("phoneController = prepared");
        assertTrue(phonePrepared >= 0);
        assertTrue(phonePublished > phonePrepared);
        assertTrue(phone.contains("prepared::stop"));

        String submission = between(source,
                "private void submitInitialIntegrationWorkerStage(",
                "private void completeInitialIntegrationWorkerStage(");
        assertTrue(submission.contains("final long ownerToken = startupStateOwnerToken"));
        assertTrue(submission.contains("if (!ownsStartupState(ownerToken))"));
        assertTrue(submission.contains(
                "pendingInitialIntegrationStage.compareAndSet(null, prepared)"));
        assertTrue(submission.contains("discardPreparedInitialIntegrationStage(prepared)"));

        String completion = between(source,
                "private void completeInitialIntegrationWorkerStage(",
                "private void advanceInitialIntegrationStage(");
        assertTrue(completion.contains("!ownsStartupState(ownerToken)"));
        assertTrue(completion.contains("startupStateOwnerToken != ownerToken"));
        assertTrue(completion.contains("prepared.stage != stage"));
        assertTrue(completion.contains("prepared::publish"));
        assertTrue(completion.contains("discardPreparedInitialIntegrationStage(prepared)"));

        String destroy = between(source, "public void onDestroy()", "public IBinder onBind(");
        int invalidateOwner = destroy.indexOf("startupStateOwnerToken = 0L");
        int takeUnpublished = destroy.indexOf(
                "pendingInitialIntegrationStage.getAndSet(null)");
        int discardUnpublished = destroy.indexOf(
                "discardPreparedInitialIntegrationStage(unpublished)");
        assertTrue(invalidateOwner >= 0);
        assertTrue(takeUnpublished > invalidateOwner);
        assertTrue(discardUnpublished > takeUnpublished);
    }

    @Test
    public void sprutCallbacksReconcilePhonePresence() throws Exception {
        String source = readService();
        assertTrue(source.contains(
                "phonePresenceExporter.onSprutConnectionChanged(state)"));
        assertTrue(source.contains("phonePresenceExporter.onSprutCatalogChanged()"));
        assertTrue(source.contains(
                "phonePresenceExporter.onSprutCharacteristicChanged(path)"));
        assertTrue(source.contains(
                "phoneAncsPresenceExporter.onSprutConnectionChanged(state)"));
        assertTrue(source.contains("phoneAncsPresenceExporter.onSprutCatalogChanged()"));
        assertTrue(source.contains(
                "phoneAncsPresenceExporter.onSprutCharacteristicChanged(path)"));
    }

    @Test
    public void exactDeviceBoundaryLoadsBeforePhoneReconfigure() throws Exception {
        String source = readService();
        int method = source.indexOf("private void reconfigureIntegrationControllers()");
        int presence = source.indexOf("phonePresenceExporter.reconfigure()", method);
        int phone = source.indexOf("phoneController.reconfigure()", method);
        int ancsPresence = source.indexOf("phoneAncsPresenceExporter.reconfigure()", method);
        assertTrue(method >= 0);
        assertTrue(presence > method);
        assertTrue(ancsPresence > presence);
        assertTrue(phone > ancsPresence);
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
        int ancsPresence = source.indexOf("phoneAncsPresenceExporter::stop", destroy);
        int sprut = source.indexOf("sprutController::stop", destroy);
        assertTrue(destroy >= 0);
        assertTrue(phone > destroy);
        assertTrue(presence > phone);
        assertTrue(ancsPresence > presence);
        assertTrue(sprut > ancsPresence);
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
        assertTrue(starter.contains(
                "scheduleRetry(app, retryAttempt, allowVisualSurfaceDuringQuiet)"));
        assertTrue(starter.contains("EXTRA_VISUAL_SURFACE_ONLY"));

        int serviceEntry = manifest.indexOf("android:name=\".WidgetService\"");
        int serviceEnd = manifest.indexOf("/>", serviceEntry);
        assertTrue(serviceEntry >= 0 && serviceEnd > serviceEntry);
        String widgetServiceEntry = manifest.substring(serviceEntry, serviceEnd);
        assertTrue(widgetServiceEntry.contains("android:directBootAware=\"true\""));
        assertTrue(widgetServiceEntry.contains("android:stopWithTask=\"false\""));
    }

    @Test
    public void oneRegistryListenerProjectsSelectablePhoneStatusValues() throws Exception {
        String source = readService();

        assertTrue(source.contains("connectorValues.addListener(phoneStatusListener)"));
        assertTrue(source.contains("connectorValues.removeListener(phoneStatusListener)"));
        assertTrue(source.contains("value.connectorType != ConnectorType.PHONE"));
        assertTrue(source.contains("renderPhoneStatusBricks()"));
        assertTrue(source.contains("PhoneStatusBarPolicy.parseIds("));
        assertTrue(source.contains("PhoneStatusBarPolicy.display("));
    }

    @Test
    public void latestRealTimeNotificationTemporarilyReusesMediaWithoutPlaybackControl()
            throws Exception {
        String source = readService();
        int renderStart = source.indexOf("private void renderPhoneStatusNotification()");
        int renderEnd = source.indexOf("private void updateMediaInfo()", renderStart);
        String render = source.substring(renderStart, renderEnd);
        int expiryStart = source.indexOf("private final Runnable phoneNotificationExpiry");
        int expiryEnd = source.indexOf("private final Runnable crossSourceRuleRefresh",
                expiryStart);
        String expiry = source.substring(expiryStart, expiryEnd);

        assertTrue(source.contains("\"notifications.latest\".equals(value.resourceId)"));
        assertTrue(source.contains("observedPhoneNotificationKeys"));
        assertTrue(source.contains("rememberPhoneNotificationItems(notificationItems)"));
        assertTrue(source.contains("prefs.phoneStatusBarNotificationsEnabled.get()"));
        assertTrue(source.contains("prefs.phoneStatusBarNotificationSeconds.get()"));
        assertTrue(source.contains("Math.max(1, Math.min(120,"));
        assertTrue(render.contains("binding.mediaStateIcon.setVisibility(View.GONE)"));
        assertTrue(render.contains("binding.mediaDurationText.setVisibility(View.GONE)"));
        assertTrue(render.contains("binding.mediaProgressBar.setVisibility(View.GONE)"));
        assertTrue(render.contains("binding.mediaTitleText.setMarqueeEnabled(true)"));
        assertTrue(expiry.contains("clearPhoneStatusNotification(true)"));
        assertTrue(expiry.contains("updateMediaInfo();"));
        assertFalse(render.contains("getTransportControls"));
        assertFalse(render.contains(".pause("));
        assertFalse(render.contains(".stop("));
    }

    @Test
    public void phoneTickerHonorsMediaHideRulesAndDoesNotLeakToOtherSurfaces()
            throws Exception {
        String source = readService();
        String visibility = between(source, "private void applyBrickVisibility(",
                "private void applyBrickTarget(");
        String render = between(source, "private void renderPhoneStatusNotification()",
                "private void updateMediaInfo()");
        String popup = between(source,
                "private PopupOverlayController.BuiltinValue popupBuiltinValue(",
                "private static PopupOverlayController.BuiltinValue popupTextValue(");
        String snapshot = between(source,
                "public StatusBrickSnapshot statusBrickSnapshot(",
                "public List<ConnectorValue> connectorValueSnapshot()");

        assertTrue(visibility.contains("!isRemotelyVisible(BrickType.MEDIA)"));
        assertTrue(visibility.contains("isBrickHiddenByApp(BrickType.MEDIA)"));
        assertFalse(render.contains("mediaContainer.setVisibility(View.VISIBLE)"));
        assertFalse(popup.contains("activePhoneNotificationText()"));
        assertFalse(snapshot.contains("activePhoneNotificationText()"));
    }

    @Test
    public void clearingTickerStopsItsMarqueeEvenWhenMediaWasRemoved() throws Exception {
        String source = readService();
        String clear = between(source, "private void clearPhoneStatusNotification(",
                "private String activePhoneNotificationText()");

        assertTrue(clear.contains("binding.mediaAppText.setMarqueeText(\"\")"));
        assertTrue(clear.contains("binding.mediaTitleText.setMarqueeText(\"\")"));
        assertTrue(clear.contains(
                "binding.mediaTitleText.setMarqueeEnabled(prefs.media.marqueeEnabled.get())"));
    }

    @Test
    public void lowBatteryWarningsUseTwoPersistentLatchesAndSharedDeliveryRouting()
            throws Exception {
        String source = readService();
        String evaluate = between(source, "private void handlePhoneLowBatteryAlert(",
                "private void updatePhoneNotificationFieldStates(");
        String disconnect = between(source, "private void postPhoneValuesChanged(",
                "private void rememberPhoneNotificationItems(");
        String render = between(source, "private void renderPhoneStatusNotification()",
                "private void updateMediaInfo()");

        assertTrue(source.contains(
                "prefs.phoneLowBatteryAlertLatched.get()"));
        assertTrue(source.contains(
                "prefs.phoneLowBatteryAlertLatched2.get()"));
        assertTrue(evaluate.contains("PhoneLowBatteryAlertPolicy.evaluate("));
        assertFalse(evaluate.contains("prefs.phoneLowBatteryAlertLatched.set(true)"));
        assertFalse(evaluate.contains("prefs.phoneLowBatteryAlertLatched2.set(true)"));
        assertTrue(source.contains("markPhoneLowBatteryAlertPresented"));
        assertTrue(source.contains("phoneLowBatteryAlertPending"));
        assertTrue(source.contains("prefs.phoneLowBatteryAlertLatched.set(true)"));
        assertTrue(source.contains("prefs.phoneLowBatteryAlertLatched2.set(true)"));
        assertTrue(evaluate.contains("enqueuePhoneLowBatteryAlert(level,"));
        assertTrue(source.contains("QueuedPhoneNotification.lowBattery(level, color, stage)"));
        assertTrue(source.contains("phoneNotificationAllowedByLockState()"));
        assertTrue(source.contains("phoneNotificationBlockedByForeground()"));
        assertTrue(source.contains("prefs.phoneStatusBarNotificationsEnabled.get()"));
        assertTrue(source.contains("prefs.phonePopupNotificationsEnabled.get()"));
        assertFalse(disconnect.contains("phoneLowBatteryAlertLatched = false"));
        assertTrue(source.contains("activePhoneBatteryAlertText"));
        assertTrue(render.contains("activePhoneBatteryAlertColor"));
        assertTrue(render.contains("prefs.phoneStatusBarNotificationColor.get()"));
        assertTrue(render.contains("AutomationState.parseColor(configuredColor, defaultColor)"));
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

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to < 0 || to <= from) {
            throw new AssertionError("Missing source range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
