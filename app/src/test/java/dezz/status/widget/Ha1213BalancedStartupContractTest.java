/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Release barriers for the visual-first, low-contention startup lane. */
public final class Ha1213BalancedStartupContractTest {
    @Test public void visualShellContainsNoControllerOrEcarxConstruction() throws Exception {
        String service = javaSource("WidgetService.java");
        String visual = between(service, "private void initializeRuntime()",
                "private void ensureMqttRuntimeGraph()");
        assertTrue(visual.contains("automationStates.beginSessionFreshnessBarrier()"));
        assertTrue(visual.contains("createOverlayView()"));
        assertFalse(visual.contains("haConfigs.loadMain()"));
        assertFalse(visual.contains("clearPhonePopupNotification()"));
        assertFalse(visual.contains("automationStates.markAllStale()"));
        assertFalse(visual.contains("new MqttController"));
        assertFalse(visual.contains("new SprutHubController"));
        assertFalse(visual.contains("new PhoneConnectorController"));
        assertFalse(visual.contains("new CarTelemetryExporter"));
        assertFalse(visual.contains("CarIntegrations.get"));

        String geometry = between(service, "private void prepareOverlayGeometryBeforeAttach()",
                "private void removeStatusOverlaySafely");
        assertFalse(geometry.contains("CarIntegrations.get"));
        assertFalse(geometry.contains(".reconfigure()"));
        assertFalse(geometry.contains("markAllStale"));

        String minimumHeight = between(service, "private int computeMinWidgetHeight(",
                "private static int textLineHeight");
        assertTrue(minimumHeight.contains("carTelemetryExporter == null ? null"));
        assertTrue(minimumHeight.contains("car == null || car.isBrickSupported"));
    }

    @Test public void overlayIsVisibleBeforeRuntimeAndOneHeavyStageRunsPerSlice()
            throws Exception {
        String service = javaSource("WidgetService.java");
        String attach = between(service, "private void createOverlayView()",
                "private void prepareOverlayGeometryBeforeAttach()");
        int add = attach.indexOf("windowManager.addView(attachmentRoot, params)");
        int deferred = attach.indexOf("scheduleInitialIntegrationStartupAfterFrame()");
        assertTrue(add >= 0 && deferred > add);
        assertTrue(attach.contains(
                "INITIAL_OVERLAY_FADE_DURATION_MS + INITIAL_OVERLAY_FALLBACK_GRACE_MS"));
        String afterFrame = between(service, "private final class DeferredIntegrationStart",
                "/** Re-evaluates TTL/stale rules");
        assertTrue(afterFrame.contains("overlay_fully_visible"));
        assertTrue(afterFrame.indexOf("notifyFirstUsefulSurface")
                < afterFrame.indexOf("runInitialIntegrationStartup"));

        String stages = between(service, "private void runNextInitialIntegrationStage()",
                "private void finishInitialIntegrationStartup()");
        assertOrdered(stages,
                "runCachedStateFreshnessBarrier()",
                "phone presence",
                "\"phone\"",
                "status surface runtime",
                "car telemetry",
                "\"MQTT\"",
                "\"Home Assistant\"",
                "\"Sprut.hub\"",
                "visual scenarios",
                "intent scenarios");
        assertTrue(stages.contains("mainHandler.postDelayed(initialIntegrationStageRunner,"));
        String barrier = between(service, "private void runCachedStateFreshnessBarrier()",
                "private void clearRetainedPhonePopupStateForStartup(long");
        assertTrue(barrier.contains("startupStateWorker.execute"));
        assertTrue(barrier.contains("startupStateBarrierInFlight = true"));
        assertTrue(barrier.contains("startupStateBarrierInFlight = false"));
        assertTrue(barrier.contains("haConfigs.loadMain(loadedMainJson)"));
        assertTrue(barrier.contains("configuredMainBricksJson = immutableMainJson"));
        assertTrue(barrier.contains("automationStates.markAllStaleIf"));
        assertTrue(barrier.contains("ownsStartupState(ownerToken)"));
    }

    @Test public void coldIntentCommandsKeepOriginalDeadlineUntilControllersAreReady()
            throws Exception {
        String service = javaSource("WidgetService.java");
        String start = between(service, "public int onStartCommand(@Nullable Intent intent",
                "private void createOverlayView()");
        assertTrue(start.contains("enqueueIntentScenarioCommand(intent)"));
        assertTrue(start.contains("pendingIntentScenarioCommands.size()"));
        assertTrue(start.contains("MAX_PENDING_INTENT_SCENARIO_COMMANDS"));
        assertTrue(start.contains("new Intent(command)"));
        assertTrue(start.contains("drainPendingIntentScenarioCommands()"));
        assertTrue(start.contains("ScenarioTriggerReceiver.EXTRA_DEADLINE_ELAPSED"));
        assertTrue(start.contains("command.getLongExtra"));
        assertFalse(start.contains("IntentScenarioController.deadlineAfter"));
    }

    @Test public void bootDeadlineIsAdaptiveAndVisibleHomeKeepsDurableAlarmFallback()
            throws Exception {
        String policy = javaSource("StartupLoadPolicy.java");
        assertTrue(policy.contains("COLD_BOOT_RUNTIME_TARGET_ELAPSED_MS = 4_500L"));
        assertTrue(policy.contains("BOOT_EVENT_SETTLE_MS = 1_000L"));
        assertTrue(policy.contains("QUICK_BOOT_QUIET_MS = 1_500L"));
        assertFalse(policy.contains("LOCKED_BOOT_QUIET_MS = 12_000L"));
        assertFalse(policy.contains("BOOT_COMPLETED_QUIET_MS = 10_000L"));

        String coordinator = javaSource("StartupWorkCoordinator.java");
        assertTrue(coordinator.contains("coalescingActiveBootLane"));
        assertTrue(coordinator.contains("StartupLoadPolicy.isBootLifecycle(trigger)"));
        assertTrue(coordinator.contains("dispatchPendingIntegrationHostIfDue"));
        assertTrue(coordinator.contains("durable AlarmManager copy is still pending"));
        assertTrue(coordinator.contains("retainedMediaNotBefore"));
        assertTrue(coordinator.contains("coalescingActiveBootLane && retainedHost"));
        assertTrue(coordinator.contains("coalescingActiveBootLane && retainedClimate"));
        assertTrue(coordinator.contains("|| coalescingActiveBootLane"));
        assertTrue(coordinator.contains("retainedPhaseNotBefore"));

        String launcher = javaSource("LauncherActivity.java");
        String stop = between(launcher, "protected void onStop()",
                "private void scheduleDeferredLauncherRuntimeStart()");
        assertTrue(stop.contains("ensureIntegrationHostScheduledAfter"));
        assertTrue(stop.contains("requiresAutomaticIntegrationHost(preferences)"));
        assertFalse(stop.contains("startIfNeededAutomatically"));
    }

    @Test public void startupTimelineIsLogcatOnlyAndCoversFirstUsefulSurface() throws Exception {
        String trace = javaSource("StartupPerformanceTrace.java");
        assertTrue(trace.contains("SystemClock.elapsedRealtime()"));
        assertTrue(trace.contains("Log.i(TAG"));
        assertFalse(trace.contains("java.io"));
        assertFalse(trace.contains("SharedPreferences"));
        assertFalse(trace.contains("File("));

        String application = javaSource("StatusWidgetApplication.java");
        String receiver = javaSource("BootReceiver.java");
        String launcher = javaSource("LauncherActivity.java");
        String settings = javaSource("MainActivity.java");
        String service = javaSource("WidgetService.java");
        assertTrue(application.contains("StartupPerformanceTrace.beginProcess"));
        assertTrue(application.contains("firstUsefulSurfaceSeen"));
        assertTrue(application.contains("SURFACE_RUNTIME_GRACE_MS = 1_500L"));
        assertTrue(application.contains("Math.max(surfaceDelay, coordinatorDelay)"));
        assertTrue(application.contains("resumeSurfaceOwnedInitialization"));
        assertTrue(receiver.contains("resumeSurfaceOwnedInitialization(context)"));
        assertTrue(launcher.contains("launcher_first_draw"));
        assertTrue(settings.contains("addOnDrawListener"));
        assertTrue(settings.contains("notifyFirstUsefulSurface(MainActivity.this)"));
        assertTrue(service.contains("widget_foreground_promoted"));
        assertTrue(service.contains("overlay_fully_visible"));
        assertTrue(service.contains("integrations_ready"));
    }

    @Test public void quickBootParksAnUnfinishedGraphUntilExactHostResume() throws Exception {
        String service = javaSource("WidgetService.java");
        String next = between(service, "private void runNextInitialIntegrationStage()",
                "private void runCachedStateFreshnessBarrier()");
        assertTrue(next.contains("if (automaticRuntimeParked || automaticLifecycleQuiet)"));
        assertTrue(next.contains("removeCallbacks(initialIntegrationStageRunner)"));
        String enter = between(service, "private void enterAutomaticLifecycleQuietOnMain(",
                "public void resumeAutomaticLifecycleIntegrationsAfterQuiet()");
        assertTrue(enter.contains("cancelDeferredIntegrationStart()"));
        assertTrue(enter.contains("removeCallbacks(initialIntegrationStageRunner)"));
        String resume = between(service,
                "public void resumeAutomaticLifecycleIntegrationsAfterQuiet()",
                "/**\n     * Re-opens only Keystore-backed transports");
        assertTrue(resume.contains("if (initialIntegrationStartupInProgress)"));
        assertTrue(resume.contains("initialIntegrationStage = 1"));
        assertTrue(resume.contains("if (!startupStateBarrierInFlight)"));
        assertTrue(resume.contains("mainHandler.post(initialIntegrationStageRunner)"));
    }

    @Test public void staleOverlayCallbacksCannotStartTheReplacementGraph() throws Exception {
        String service = javaSource("WidgetService.java");
        assertTrue(service.contains("private int overlayAttachGeneration"));
        assertTrue(service.contains("private int overlayVisibleGeneration = -1"));
        String complete = between(service, "private void completeInitialOverlayVisibility(",
                "/**\n     * Applies only values");
        assertTrue(complete.contains("isCurrentOverlayAttachment(generation, root)"));
        assertTrue(complete.contains("overlayVisibleGeneration == generation"));
        String remove = between(service, "private void removeStatusOverlaySafely(",
                "/**\n     * Stops every listener");
        assertTrue(remove.contains("overlayAttachGeneration++"));
        assertTrue(remove.contains("root.animate().cancel()"));
        assertTrue(remove.contains("cancelDeferredIntegrationStart()"));
    }

    @Test public void multiObjectRuntimeBundlesPublishOnlyAfterCompleteConstruction()
            throws Exception {
        String service = javaSource("WidgetService.java");
        String sprut = between(service, "private void ensureSprutRuntimeGraph()",
                "private void ensurePhoneRuntimeGraph()");
        assertTrue(sprut.contains("SprutHubController nextController"));
        assertTrue(sprut.contains("PhoneSprutPresenceExporter nextPresence"));
        assertTrue(sprut.contains("PhoneSprutPresenceExporter nextAncsPresence"));
        assertTrue(sprut.indexOf("sprutController = nextController")
                > sprut.indexOf("nextAncsPresence"));
        assertTrue(sprut.contains("nextController.stop()"));

        String car = between(service, "private void ensureCarRuntimeGraph()",
                "private void ensureHomeAssistantRuntimeGraph()");
        assertTrue(car.contains("CarTelemetryExporter nextExporter"));
        assertTrue(car.indexOf("carTelemetryExporter = nextExporter")
                > car.indexOf("setAvailabilityChangedListener"));
        assertTrue(car.contains("nextExporter.stop()"));
    }

    @Test public void deferredHomeWorkStartsOnlyAfterDrawAndHasADurableNoDrawFallback()
            throws Exception {
        String launcher = javaSource("LauncherActivity.java");
        String start = between(launcher, "private void startDeferredHomeWorkAfterFirstDraw()",
                "private boolean launcherRuntimeStageReached");
        assertTrue(start.contains("launcherFirstDrawCompleted"));
        assertTrue(start.contains("registerNavigationReceiver()"));
        assertTrue(start.contains("globalElementRefresh"));
        assertTrue(start.contains("startVisibleSurfaceImmediatelyAutomatically"));

        String onStart = between(launcher, "protected void onStart()",
                "protected void onStop()");
        assertFalse(onStart.contains("registerNavigationReceiver()"));
        assertFalse(onStart.contains("startIfNeededAutomatically"));
        assertFalse(onStart.contains("navigationUiHandler.post(globalElementRefresh)"));
        assertTrue(onStart.contains("!launcherFirstDrawCompleted"));
        String panelGate = between(launcher, "private final Runnable allowPanelInitialization",
                "private final Runnable panelInitializationStep");
        assertTrue(panelGate.contains("!launcherFirstDrawCompleted"));
        assertTrue(start.contains("postDelayed(allowPanelInitialization"));
        String stop = between(launcher, "protected void onStop()",
                "private void scheduleDeferredLauncherRuntimeStart()");
        assertTrue(stop.contains("ensureIntegrationHostScheduledAfter"));
        assertFalse(stop.contains("startIfNeededAutomatically"));
    }

    @Test public void explicitScenarioHeadlessHostIsBoundedAndReleased() throws Exception {
        String service = javaSource("WidgetService.java");
        String stateStore = javaSource("automation/AutomationStateStore.java");
        assertTrue(service.contains("TEMPORARY_SCENARIO_HOST_MAX_MS"));
        assertTrue(service.contains("temporaryScenarioHeadlessHost"));
        String reconcile = between(service,
                "private void reconcileTemporaryScenarioHeadlessHost(",
                "private void createOverlayView()");
        assertTrue(reconcile.contains("hasPendingExecutions()"));
        assertTrue(reconcile.contains("pendingIntentScenarioCommands.clear()"));
        assertTrue(reconcile.contains("persistentSurfaceHost"));
        assertTrue(reconcile.contains("prefs.widgetEnabled.get()"));
        assertTrue(reconcile.contains("stopSelf()"));
        String arm = between(service,
                "private void armTemporaryScenarioHeadlessHostIfNeeded(",
                "private void drainPendingIntentScenarioCommands()");
        assertTrue(arm.contains("prefs.widgetEnabled.get() && overlayRuntimeAvailable"));
        assertFalse(arm.contains("|| overlayRuntimeAvailable"));
        String clearAll = between(stateStore, "public synchronized void clearAll()",
                "/**\n     * Atomically replaces");
        assertTrue(clearAll.contains("synchronized (PERSISTENCE_LOCK)"));
    }

    @Test public void quickBootAndTransientFailuresRemainSerializedBehindARealFrame()
            throws Exception {
        String service = javaSource("WidgetService.java");
        String receiver = javaSource("BootReceiver.java");
        String reconcile = between(service,
                "public void reconcileAutomaticLifecycleSurfaces()",
                "private void finishAutomaticSurfaceReconcileIfReady()");
        assertTrue(reconcile.contains("automaticSurfaceReconcilePending = true"));
        assertTrue(reconcile.indexOf("createOverlayView()")
                < reconcile.lastIndexOf("resumeAutomaticLifecycleIntegrationsAfterQuiet()"));
        assertTrue(reconcile.contains("automaticHostReleaseAfterVisible = true"));
        String restore = between(receiver, "private static boolean restoreStatusWidget(",
                "private static boolean restoreClimateSafely(");
        assertTrue(restore.contains("if (reconcileSurfaces)"));
        assertTrue(restore.contains("} else {\n                    current.resumeAutomaticLifecycle"));

        String enter = between(service, "private void enterAutomaticLifecycleQuietOnMain(",
                "public void resumeAutomaticLifecycleIntegrationsAfterQuiet()");
        assertTrue(enter.indexOf("automaticLifecycleQuiet = true")
                < enter.indexOf("mainHandler.post(automaticLifecycleQuietTeardown)"));
        assertTrue(enter.contains("removeCallbacks(initialIntegrationStageRunner)"));

        String stages = between(service, "private void runNextInitialIntegrationStage()",
                "private void runCachedStateFreshnessBarrier()");
        assertTrue(stages.contains("MAX_INITIAL_INTEGRATION_STAGE_RETRIES"));
        assertTrue(stages.contains("if (!stageSucceeded"));
        String reconfigure = between(service,
                "private void reconfigureIntegrationControllers()",
                "private boolean runIntegrationStep");
        assertTrue(reconfigure.contains("ensurePhoneRuntimeGraph()"));
        assertTrue(reconfigure.contains("ensureCarRuntimeGraph()"));
    }

    private static void assertOrdered(String source, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker, previous + 1);
            assertTrue("Missing/out-of-order marker: " + marker, current > previous);
            previous = current;
        }
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String javaSource(String relative) throws Exception {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                relative);
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget", relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
