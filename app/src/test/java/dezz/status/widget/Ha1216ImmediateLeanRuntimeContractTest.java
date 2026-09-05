/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Release barriers for immediate startup without permanent high-frequency polling. */
public final class Ha1216ImmediateLeanRuntimeContractTest {
    @Test public void bootReceiverQueuesHeavyPhasesOffMainAtBackgroundPriority()
            throws Exception {
        String receiver = source("BootReceiver.java");
        assertTrue(receiver.contains("goAsync()"));
        assertTrue(receiver.contains("STARTUP_LANE.execute"));
        assertTrue(receiver.contains("THREAD_PRIORITY_BACKGROUND"));
        assertTrue(receiver.contains("pending.finish()"));
        assertTrue(receiver.contains("new ThreadPoolExecutor(\n                0, 1"));
        assertTrue(receiver.contains("STARTUP_QUEUE_CAPACITY = 32"));
        assertTrue(receiver.contains(
                "new ArrayBlockingQueue<>(STARTUP_QUEUE_CAPACITY)"));
        assertFalse(receiver.contains("new LinkedBlockingQueue"));
        String receive = between(receiver, "public void onReceive(Context context, Intent intent)",
                "private void handleReceive(Context context, Intent intent)");
        int rejection = receive.indexOf("catch (RejectedExecutionException rejected)");
        int finish = receive.indexOf("pending.finish();", rejection);
        int diagnostic = receive.indexOf("Log.e(TAG, \"Startup queue full; rejected action=\"",
                rejection);
        assertTrue(rejection >= 0 && finish > rejection && diagnostic > finish);
        assertTrue(receiver.contains("allowCoreThreadTimeOut(true)"));
        assertTrue(receiver.contains("revalidateAutomaticVisualSurfaceAfterQuickBoot"));
        assertFalse(receiver.contains("enterAutomaticLifecycleQuiet("));
    }

    @Test public void integrationStagesStartNowButRealFailuresKeepBoundedBackoff()
            throws Exception {
        String service = source("WidgetService.java");
        String initialize = between(service, "private void initializeRuntime()",
                "private void ensureMqttRuntimeGraph()");
        assertTrue(initialize.contains("new Preferences(this, false)"));
        assertTrue(initialize.contains("runInitialIntegrationStartup()"));

        String runner = between(service, "private void runNextInitialIntegrationStage()",
                "private void submitInitialIntegrationWorkerStage(int stage)");
        assertTrue(runner.contains("submitInitialIntegrationWorkerStage(initialIntegrationStage)"));
        assertFalse(runner.contains(".reconfigure()"));
        assertFalse(runner.contains("applyPreferences(false)"));

        String submission = between(service,
                "private void submitInitialIntegrationWorkerStage(int stage)",
                "/** Main-thread owner/stage fence");
        assertTrue(submission.contains("startupStateWorker.execute"));
        assertTrue(submission.contains("catch (RuntimeException failure)"));
        assertTrue(submission.contains("workerResult = failedInitialIntegrationStage("));
        assertTrue(submission.contains("ownsStartupState(ownerToken)"));
        assertTrue(submission.contains("pendingInitialIntegrationStage.compareAndSet"));
        assertTrue(submission.contains(
                "mainHandler.post(() -> completeInitialIntegrationWorkerStage"));
        assertFalse(submission.contains("postDelayed"));

        String publication = between(service,
                "private void completeInitialIntegrationWorkerStage(",
                "private void advanceInitialIntegrationStage(");
        assertTrue(publication.contains("startupStateOwnerToken != ownerToken"));
        assertTrue(publication.contains("prepared.stage != stage"));
        assertTrue(publication.contains("prepared::publish"));
        assertTrue(publication.contains("automaticRuntimeParked || automaticLifecycleQuiet"));
        assertTrue(publication.contains("mainHandler.post(initialIntegrationStageRunner)"));

        String advance = between(service, "private void advanceInitialIntegrationStage(",
                "private void discardPreparedInitialIntegrationStage(");
        assertTrue(advance.contains("mainHandler.post(initialIntegrationStageRunner)"));
        assertTrue(advance.contains("INITIAL_INTEGRATION_RETRY_MS"));
        assertFalse(advance.contains("INITIAL_INTEGRATION_STAGE_MS"));
        assertTrue(service.contains("THREAD_PRIORITY_BACKGROUND"));
        int oneLane = service.indexOf("Executors.newSingleThreadExecutor");
        assertTrue(oneLane >= 0);
        assertTrue(service.indexOf("Executors.newSingleThreadExecutor", oneLane + 1) < 0);
    }

    @Test public void startupWorkerPublishesGraphsOnMainAndCleansUnpublishedOwnership()
            throws Exception {
        String service = source("WidgetService.java");
        String prepare = between(service,
                "private PreparedInitialIntegrationStage prepareInitialIntegrationWorkerStage",
                "private void runCachedStateFreshnessBarrier()");
        assertTrue(prepare.contains("next.reconfigure()"));
        assertTrue(prepare.contains("prepared.exporter.reconfigure()"));
        assertTrue(prepare.contains("prepared.controller.reconfigure()"));
        assertTrue(prepare.contains("PhoneNotificationAutomation.ensureConfigured(prefs)"));
        assertTrue(prepare.contains("loadPopupBuiltinTypes()"));
        assertTrue(prepare.contains("loadDriverInformationBrickTypes()"));
        assertTrue(prepare.contains("drainPendingIntentScenarioCommands(false)"));
        assertFalse(prepare.contains("ensurePopupOverlayManager()"));

        String intentPrepare = between(service,
                "private PreparedInitialIntegrationStage prepareIntentScenarioStage(int stage)",
                "/**\n     * Main-publication-only boundary for IntentScenarioController");
        assertTrue(intentPrepare.contains("createIntentScenarioController(dispatcher)"));
        assertTrue(intentPrepare.contains("publishIntentScenarioStage(current, false)"));
        assertTrue(intentPrepare.contains("publishIntentScenarioStage(prepared, true)"));
        assertFalse(intentPrepare.contains(".reconfigure()"));

        String intentPublication = between(service,
                "private void publishIntentScenarioStage(",
                "private PreparedInitialIntegrationStage successfulInitialIntegrationStage(");
        int publishField = intentPublication.indexOf("intentScenarioController = controller");
        int reconfigure = intentPublication.indexOf("controller.reconfigure()");
        assertTrue(publishField >= 0 && reconfigure > publishField);
        assertTrue(intentPublication.contains("intentScenarioController = null"));
        assertTrue(intentPublication.contains("controller.destroy()"));

        String sprutPrepare = between(service,
                "private PreparedInitialIntegrationStage preparePhonePresenceStage(int stage)",
                "private PreparedInitialIntegrationStage preparePhoneStage(int stage)");
        int sprutReload = sprutPrepare.lastIndexOf("prepared.ancsPresence.reconfigure()");
        int sprutPublish = sprutPrepare.lastIndexOf("publishSprutRuntimeGraph(prepared)");
        assertTrue(sprutReload >= 0 && sprutPublish > sprutReload);
        assertTrue(sprutPrepare.contains("phonePresenceExporter != currentPhone"));
        assertTrue(sprutPrepare.contains("phonePresenceExporter = next.phonePresence"));

        String carPrepare = between(service,
                "private PreparedInitialIntegrationStage prepareCarTelemetryStage(int stage)",
                "private PreparedInitialIntegrationStage prepareMqttStage(int stage)");
        int carReload = carPrepare.indexOf("prepared.exporter.reconfigure()");
        int carPublish = carPrepare.indexOf("publishCarRuntimeGraph(prepared)");
        assertTrue(carReload >= 0 && carPublish > carReload);
        assertTrue(carPrepare.contains("carTelemetryExporter != current"));
        assertFalse(carPrepare.contains("current.reconfigure()"));

        String destroy = between(service, "public void onDestroy()",
                "public IBinder onBind(Intent intent)");
        assertTrue(destroy.contains("startupStateWorker.shutdownNow()"));
        assertTrue(destroy.contains("pendingInitialIntegrationStage.getAndSet(null)"));
        assertTrue(destroy.contains("discardPreparedInitialIntegrationStage(unpublished)"));

        String drain = between(service,
                "private void drainPendingIntentScenarioCommands(boolean reloadRules)",
                "private void reconcileExplicitScenarioRuntimeOverride(");
        assertTrue(drain.contains("if (reloadRules) intentScenarioController.reconfigure()"));
    }

    @Test public void existingControllersReconfigureOnlyInsideFencedMainPublication()
            throws Exception {
        String service = source("WidgetService.java");
        assertCurrentReconfigureIsPublished(service,
                "private PreparedInitialIntegrationStage preparePhoneStage(int stage)",
                "PhoneConnectorController next = null", "phoneController != current");
        assertCurrentReconfigureIsPublished(service,
                "private PreparedInitialIntegrationStage prepareMqttStage(int stage)",
                "MqttController next = null", "mqttController != current");
        assertCurrentReconfigureIsPublished(service,
                "private PreparedInitialIntegrationStage prepareHomeAssistantStage(int stage)",
                "HaApiController next = null", "haApiController != current");
        assertCurrentReconfigureIsPublished(service,
                "private PreparedInitialIntegrationStage prepareSprutStage(int stage)",
                "private PreparedInitialIntegrationStage prepareVisualScenarioStage",
                "sprutController != current");
        assertCurrentReconfigureIsPublished(service,
                "private PreparedInitialIntegrationStage prepareVisualScenarioStage(int stage)",
                "if (current != null || actionDispatcher != null",
                "scenarioController != current || actionDispatcher != currentDispatcher");

        String visual = between(service,
                "private PreparedInitialIntegrationStage prepareVisualScenarioStage(int stage)",
                "private PreparedInitialIntegrationStage prepareIntentScenarioStage(int stage)");
        int newReconfigure = visual.lastIndexOf("prepared.controller.reconfigure()");
        int newPublish = visual.lastIndexOf("publishScenarioRuntimeGraph(prepared)");
        assertTrue(newReconfigure >= 0 && newPublish > newReconfigure);
    }

    @Test public void wifiAndGnssUseEventsAndDeadlinesInsteadOfTwoSecondPolling()
            throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains("WifiManager.RSSI_CHANGED_ACTION"));
        assertTrue(service.contains("wifiRssiReceiver"));
        assertFalse(service.contains("WIFI_SIGNAL_REFRESH_INTERVAL_MS"));
        assertFalse(service.contains("wifiSignalRefreshRunnable"));

        String gnss = between(service, "private final Runnable updateGnssStatusRunnable",
                "private final GnssStatus.Callback gnssStatusCallback");
        assertTrue(gnss.contains("SystemClock.elapsedRealtime()"));
        assertTrue(gnss.contains("GNSS_FIX_DEGRADED_AFTER_MS"));
        assertTrue(gnss.contains("GNSS_FIX_OFF_AFTER_MS"));
        assertFalse(gnss.contains("GNSS_STATUS_CHECK_INTERVAL"));
        assertFalse(gnss.contains("System.currentTimeMillis()"));
        assertTrue(service.contains("scheduleGnssFreshnessDeadline()"));
    }

    @Test public void visualAdmissionNeverRunsPreferenceMigrationsInReceiverLane()
            throws Exception {
        String starter = source("WidgetServiceStarter.java");
        String attempt = between(starter, "private static boolean attemptStart(",
                "static boolean requiresIntegrationHost(@NonNull Preferences");
        assertTrue(attempt.contains("if (allowVisualSurfaceDuringQuiet)"));
        assertTrue(attempt.contains("isStatusWidgetEnabledForVisualBootstrap(app)"));
        assertTrue(attempt.contains("new Preferences(app, false)"));
        assertFalse(attempt.contains("new Preferences(app);"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertCurrentReconfigureIsPublished(
            String source, String start, String end, String identityFence) {
        String currentBranch = between(source, start, end);
        int publication = currentBranch.indexOf(
                "return successfulInitialIntegrationStage");
        int fence = currentBranch.indexOf(identityFence);
        int reconfigure = currentBranch.indexOf("current.reconfigure()");
        assertTrue(publication >= 0 && fence > publication && reconfigure > fence);
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        String projectRelative = "app/src/main/java/dezz/status/widget/" + relative;
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(projectRelative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + projectRelative);
    }
}
