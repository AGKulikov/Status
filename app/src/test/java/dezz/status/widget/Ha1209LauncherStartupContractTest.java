/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source/order contract for the atomic HOME frame, staged boot and Android-9 ATT wrappers. */
public final class Ha1209LauncherStartupContractTest {
    @Test public void sourcePanelsAreHiddenBeforeAttachAndOnlyScopedEditorMayRevealOne()
            throws Exception {
        String launcher = javaSource("LauncherActivity.java");
        String add = between(launcher, "private void addPanel(",
                "/** One optional integration");
        int alpha = add.indexOf("frame.setAlpha(0f);");
        int touch = add.indexOf("frame.setContentTouchBlocked(true);");
        int attach = add.indexOf("workspace.addView(frame, lp);");
        assertTrue(alpha >= 0 && touch > alpha && attach > touch);

        String suppress = between(launcher, "private void suppressSourcePanels()",
                "private String activeContentEditorPanelId()");
        assertTrue(suppress.contains("editorOwnsPanel ? 1f : 0f"));
        assertTrue(suppress.contains("setContentTouchBlocked(!editorOwnsPanel)"));
        String visibility = between(launcher, "private void refreshGlobalElementVisibility()",
                "private void applyStoredGlobalGeometry()");
        assertTrue(visibility.contains("sourceEditorOwnsElement"));
        assertTrue(visibility.contains("!sourceEditorOwnsElement"));

        String editor = between(launcher, "private void applyRequestedHomeEditor",
                "private void handleStagedOrHomeNavigation");
        assertTrue(editor.contains("setMediaContentEditMode(true)"));
        assertTrue(editor.contains("setNavigationContentEditMode(true)"));
        assertTrue(editor.contains("setActionsContentEditMode(true)"));
    }

    @Test public void backdropAsyncAppsAndReboundSourcesCannotExposeLegacyLayout()
            throws Exception {
        String launcher = javaSource("LauncherActivity.java");
        String layout = between(launcher, "workspace.addOnLayoutChangeListener",
                "@Override\n    protected void onNewIntent");
        assertTrue(layout.indexOf("scheduleInitialLauncherBackdrops();") >= 0);
        assertFalse(layout.contains("syncLauncherBackdrops();"));
        assertTrue(layout.indexOf("scheduleInitialLauncherBackdrops();")
                < layout.indexOf("initializePanels();"));

        String backdropLoad = between(launcher,
                "private void scheduleInitialLauncherBackdrops()",
                "/** Keeps every decorative HOME surface");
        assertTrue(backdropLoad.contains("launcherWorker.execute"));
        assertTrue(backdropLoad.contains("loaded.load(width, height)"));
        assertTrue(backdropLoad.contains("launcherFirstDrawCompleted"));
        assertTrue(backdropLoad.indexOf("navigationUiHandler.post")
                < backdropLoad.indexOf("syncLauncherBackdrops();"));

        String favorites = between(launcher, "private void refreshFavorites()",
                "private void launchApp");
        int adapter = favorites.indexOf("favoritesGrid.setAdapter");
        assertTrue(adapter >= 0 && favorites.indexOf(
                "refreshGlobalElementsAfterWidgetChange()", adapter) > adapter);
        assertTrue(launcher.contains("removeGlobalElement(staleId)"));
        assertTrue(launcher.contains("proxy.onSourceRebound()"));
        String proxy = javaSource("launcher/LauncherGlobalElementProxyView.java");
        assertTrue(proxy.contains("public void dispose()"));
        assertTrue(proxy.contains("public void onSourceRebound()"));
    }

    @Test public void rootHomeConsumesBackWithoutPackageManagerRace() throws Exception {
        String launcher = javaSource("LauncherActivity.java");
        String back = between(launcher, "public void onBackPressed()",
                "private void configureWindow");
        assertTrue(back.contains("!isTaskRoot() || !homeRootInvocation"));
        assertTrue(back.contains("super.onBackPressed();"));
        assertFalse(back.contains("isSelectedHome"));
        assertTrue(launcher.contains("private static boolean isHomeInvocation"));
        assertTrue(launcher.contains("Intent.CATEGORY_HOME"));
    }

    @Test public void lifecyclePhasesOwnGenerationAndDispatchImmediatelyWithFallback()
            throws Exception {
        String receiver = javaSource("BootReceiver.java");
        String coordinator = javaSource("StartupWorkCoordinator.java");
        String application = javaSource("StatusWidgetApplication.java");
        assertTrue(application.contains("StartupWorkCoordinator.clearLegacyStartupDeferrals(this)"));
        assertTrue(coordinator.contains("Settings.Global.BOOT_COUNT"));
        assertTrue(coordinator.contains("KEY_QUIET_UNTIL_ELAPSED"));
        assertTrue(coordinator.contains("EXTRA_GENERATION"));
        assertTrue(coordinator.contains("generationKey(phase)"));
        assertTrue(coordinator.contains("notBeforeKey(phase)"));
        assertTrue(coordinator.contains("Ignoring stale startup phase"));
        assertTrue(coordinator.contains("dispatchPhaseNowWithFallback"));
        assertTrue(coordinator.contains("app.sendBroadcast(phaseIntent("));
        assertTrue(coordinator.contains("PHASE_DELIVERY_FALLBACK_MS"));
        assertTrue(coordinator.contains("cancelPhaseFallback(app, phase)"));
        assertFalse(coordinator.contains("remainingStartupLaneMillis"));
        assertTrue(receiver.contains("StartupWorkCoordinator.generation(intent)"));
        assertTrue(receiver.contains("deferPhaseIfNeeded(context, phase, generation)"));
        assertTrue(receiver.contains("markPhaseCompleted(context, phase, generation)"));
        assertFalse(receiver.contains("CATEGORY_HOME"));
        assertFalse(coordinator.contains("CATEGORY_HOME"));
    }

    @Test public void launcherAndSettingsDoNotWaitForAnArtificialStartupLane() throws Exception {
        String bootstrap = javaSource("AppRuntimeBootstrap.java");
        assertFalse(bootstrap.contains("automaticReconcileDelayMillis"));
        assertTrue(bootstrap.contains("WidgetServiceStarter.startIfNeeded(appContext)"));
        assertTrue(bootstrap.contains("ClimatePanelService.apply(appContext)"));

        String coordinator = javaSource("StartupWorkCoordinator.java");
        assertFalse(coordinator.contains("launcherRuntimeDelayMillis"));
        assertFalse(coordinator.contains("automaticReconcileDelayMillis"));
        assertFalse(coordinator.contains("startupInitializationDelayMillis"));

        String launcher = javaSource("LauncherActivity.java");
        String panelStep = between(launcher, "private void continuePanelInitialization()",
                "private void makePanelTransparent");
        assertFalse(panelStep.contains("launcherPanelDelayMillis"));
        String runtimeStep = between(launcher,
                "private final Runnable deferredLauncherRuntimeStep",
                "@Override\n    protected void onCreate");
        assertFalse(runtimeStep.contains("launcherRuntimeDelayMillis("));
        assertFalse(runtimeStep.contains("DEFERRED_LAUNCHER_STAGE_MS"));
        assertTrue(runtimeStep.contains("navigationUiHandler.post(this)"));

        String fallback = project("app/src/geely/java/dezz/status/widget/car/"
                + "HudModeFallbackBootReceiver.java");
        assertTrue(fallback.contains("ACTION_QUICKBOOT_POWERON"));
        assertTrue(fallback.contains("KEY_NOT_BEFORE"));
        assertTrue(fallback.contains("StartupWorkCoordinator.hudFallbackDelayMillis()"));
    }

    @Test public void visibleHomeUsesExactHostHandoffButHiddenHomeKeepsAlarmOwnership()
            throws Exception {
        String launcher = javaSource("LauncherActivity.java");
        String handoff = between(launcher,
                "private final Runnable visibleIntegrationHostHandoff",
                "private boolean launcherFirstDrawCompleted");
        assertTrue(handoff.contains("pendingIntegrationHostDelayMillis"));
        assertTrue(handoff.contains("dispatchPendingIntegrationHostIfDue"));
        String stop = between(launcher, "protected void onStop()",
                "/** Starts migration immediately");
        assertTrue(stop.contains("removeCallbacks(visibleIntegrationHostHandoff)"));

        String coordinator = javaSource("StartupWorkCoordinator.java");
        String dispatch = between(coordinator,
                "static boolean dispatchPendingIntegrationHostIfDue",
                "public static void ensureIntegrationHostScheduled");
        assertTrue(dispatch.contains("KEY_HOST_PHASE_PENDING"));
        assertTrue(dispatch.contains("KEY_HOST_PHASE_GENERATION"));
        assertTrue(dispatch.contains("isUserUnlocked(app)"));
        assertTrue(dispatch.contains("new Intent(app, BootReceiver.class)"));
        assertTrue(dispatch.contains("app.sendBroadcast(phase)"));
        assertTrue(dispatch.contains("durable AlarmManager copy is still pending"));
    }

    @Test public void connectorsUnlockAndQuickBootSurfacesStaySerialized() throws Exception {
        String service = javaSource("WidgetService.java");
        String initial = between(service, "private void runInitialIntegrationStartup()",
                "private void scheduleInitialIntegrationStartupAfterFrame");
        assertTrue(initial.contains("runNextInitialIntegrationStage"));
        assertTrue(initial.contains("mainHandler.post(initialIntegrationStageRunner)"));
        assertFalse(initial.contains("reconfigureIntegrationControllers();"));
        assertFalse(initial.contains("INITIAL_INTEGRATION_STAGE_MS"));
        assertFalse(initial.contains("INITIAL_HUD_AFTER_DRIVER_MS"));
        assertTrue(service.contains("integrationReconfigurePending"));
        assertTrue(service.contains("schedulePendingIntegrationReconfigure()"));

        String unlock = between(service,
                "public void reconfigureCredentialBackedIntegrationsAfterUnlock()",
                "/** Queues a fresh direct handshake");
        assertTrue(unlock.contains("MQTT unlock"));
        assertTrue(unlock.contains("Home Assistant unlock"));
        assertTrue(unlock.contains("Sprut.hub unlock"));
        assertFalse(unlock.contains("DriverPanelService"));
        assertFalse(unlock.contains("HudPresentationService"));

        String survivor = between(service,
                "public void reconcileAutomaticLifecycleSurfaces()",
                "/**\n     * Re-opens only Keystore-backed transports");
        assertTrue(survivor.contains("DriverPanelService.apply(this)"));
        assertTrue(survivor.contains("prefs.hudPanelAutostart.get()"));
        assertFalse(survivor.contains("reconfigureIntegrationControllers"));
        String receiver = javaSource("BootReceiver.java");
        assertTrue(receiver.contains("hasSurfaceReconcilePending"));
        assertTrue(receiver.contains("hasCredentialRefreshPending"));
        assertTrue(receiver.contains("acknowledgeHostRequests"));
        assertTrue(receiver.indexOf("acknowledgeHostRequests")
                > receiver.indexOf("restoreStatusWidget"));
    }

    @Test public void mediaHistoryIsFrozenNowButExecutedInItsLaterRelativeLane()
            throws Exception {
        String receiver = javaSource("BootReceiver.java");
        String media = javaSource("launcher/MediaAutoResumeController.java");
        assertTrue(receiver.contains("captureBootHistorySnapshot(context, action)"));
        assertTrue(receiver.contains("PHASE_MEDIA_PLAN"));
        String capture = between(media, "public static long captureBootHistorySnapshot",
                "/** Called from the delayed media lane");
        assertTrue(capture.contains("MediaPlaybackHistoryStore.read(app)"));
        assertFalse(capture.contains("new Preferences"));
        assertTrue(capture.contains("KEY_CAPTURE_TOKEN"));
        String plan = between(media, "public static void scheduleAfterBoot",
                "static void execute");
        assertTrue(plan.contains("KEY_CAPTURE_HISTORY_PACKAGE"));
        assertTrue(plan.contains("planAnchorElapsed + Math.max"));
        assertTrue(plan.contains("targetElapsed - SystemClock.elapsedRealtime()"));
        assertTrue(media.contains("state.getLong(KEY_CAPTURE_TOKEN, Long.MIN_VALUE) != bootToken"));
    }

    @Test public void automaticHostHonorsManualOnlyHudAutostart() throws Exception {
        String starter = javaSource("WidgetServiceStarter.java");
        assertTrue(starter.contains("startIfNeededAutomatically"));
        assertTrue(starter.contains("requiresAutomaticIntegrationHost"));
        assertTrue(starter.contains("hudPanelEnabled && hudPanelAutostart"));
        assertTrue(javaSource("LauncherActivity.java")
                .contains("WidgetServiceStarter.startVisibleSurfaceImmediatelyAutomatically(this)"));
    }

    @Test public void stickyServicesCannotConstructHeavyGraphsBeforeAdmission()
            throws Exception {
        String widgetStart = between(javaSource("WidgetService.java"),
                "public int onStartCommand(@Nullable Intent intent",
                "private void createOverlayView()");
        assertTrue(widgetStart.contains("shouldDeferAutomaticStickyRestart(this)"));
        assertTrue(widgetStart.indexOf("shouldDeferAutomaticStickyRestart(this)")
                < widgetStart.indexOf("initializeRuntime();"));
        assertTrue(widgetStart.contains("return START_NOT_STICKY;"));

        String driverStart = between(javaSource("driver/DriverPanelService.java"),
                "public int onStartCommand(@Nullable Intent intent",
                "public void onConfigurationChanged");
        assertTrue(driverStart.indexOf("shouldDeferAutomaticStickyRestart(this)")
                < driverStart.indexOf("initializeRuntime();"));
        String climateStart = between(javaSource("climate/ClimatePanelService.java"),
                "public int onStartCommand(@Nullable Intent intent",
                "public void onConfigurationChanged");
        assertTrue(climateStart.indexOf("shouldDeferAutomaticStickyRestart(this)")
                < climateStart.indexOf("initializeRuntime();"));
        String hudStart = between(javaSource("hud/HudPresentationService.java"),
                "public int onStartCommand(Intent intent",
                "public void onDestroy()");
        assertTrue(hudStart.indexOf("shouldDeferAutomaticStickyRestart(this)")
                < hudStart.indexOf("initializeRuntime();"));

        String fallback = project("app/src/geely/java/dezz/status/widget/car/"
                + "HudModeFallbackService.java");
        String fallbackCreate = between(fallback, "public void onCreate()",
                "public int onStartCommand(Intent intent");
        assertFalse(fallbackCreate.contains("ensureWorker()"));
        String fallbackStart = between(fallback, "public int onStartCommand(Intent intent",
                "public IBinder onBind(Intent intent)");
        assertTrue(fallbackStart.indexOf("shouldDeferAutomaticStickyRestart(this)")
                < fallbackStart.indexOf("ensureWorker();"));
        assertTrue(fallbackStart.contains("return START_NOT_STICKY;"));
    }

    @Test public void releaseIdentityAndWorkflowAdvanceTogether() throws Exception {
        String workflow = project(".github/workflows/verify-ha1216.yml");
        String manifest = project("release-manifests/HA1216.md");
        ReleaseIdentityContract.assertCurrentAtLeast(1209);
        assertTrue(workflow.contains("name: Verify HA1216 immediate lean runtime candidate"));
        assertTrue(workflow.contains("VERSION_NAME: 'v2.8.2-ha1216'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021216'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest"));
        assertTrue(manifest.contains("ru.natro.statuswidget"));
        assertTrue(manifest.contains("208021216"));
    }

    private static String javaSource(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path first = Paths.get(relative);
        Path second = Paths.get("..", relative);
        Path file = Files.isRegularFile(first) ? first : second;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) {
            throw new AssertionError("Missing source range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
