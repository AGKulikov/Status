/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Release barriers for immediate visual admission with a separately parked runtime graph. */
public final class Ha1214ImmediateSurfaceRuntimeContractTest {
    @Test public void immediateVisualPathAvoidsPreferenceMigrations() throws Exception {
        String starter = source("WidgetServiceStarter.java");
        String attempt = between(starter, "private static boolean attemptStart(",
                "static boolean requiresIntegrationHost(@NonNull Preferences");
        assertTrue(attempt.contains("boolean runtimeParked ="));
        assertTrue(attempt.contains("ensureIntegrationHostScheduled(app)"));
        assertTrue(attempt.contains("canStartVisualSurfaceWhileRuntimeParked("));
        assertTrue(attempt.contains("isStatusWidgetEnabledForVisualBootstrap(app)"));
        assertTrue(attempt.contains("setAction(ACTION_START_VISIBLE_SURFACE)"));
        int visual = attempt.indexOf("if (allowVisualSurfaceDuringQuiet)");
        int fullPreferences = attempt.indexOf("Preferences preferences = new Preferences(app, false)");
        assertTrue(visual >= 0 && fullPreferences > visual);

        String policy = between(starter,
                "static boolean canStartVisualSurfaceWhileRuntimeParked(",
                "static boolean requiresHeadlessHost(@NonNull Preferences");
        assertTrue(policy.contains("return widgetEnabled && overlayPermissions"));
        assertFalse(policy.contains("requiresHeadlessHost"));
    }

    @Test public void unlockedBootAdmitsVisualSurfaceBeforeCoordinatorTransaction()
            throws Exception {
        String receiver = source("BootReceiver.java");
        String lifecycle = between(receiver,
                "if (Intent.ACTION_BOOT_COMPLETED.equals(action)",
                "private static boolean restoreStatusWidget(");
        int visual = lifecycle.indexOf(
                "startVisibleSurfaceImmediatelyWithRetry(context)");
        int coordinator = lifecycle.indexOf("scheduleForLifecycle(context, action)");
        assertTrue(visual >= 0 && coordinator > visual);
        String visualAdmission = between(lifecycle,
                "// Admit the tiny visual surface",
                "if (ACTION_QUICKBOOT_POWERON.equals(action)) {");
        assertTrue(visualAdmission.contains("Intent.ACTION_BOOT_COMPLETED.equals(action)"));
        assertTrue(visualAdmission.contains("ACTION_QUICKBOOT_POWERON.equals(action)"));
        assertTrue(visualAdmission.contains("Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)"));
        assertFalse(visualAdmission.contains("Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)"));
        assertTrue(lifecycle.contains("ACTION_QUICKBOOT_POWERON.equals(action)"));
        assertFalse(lifecycle.contains("enterAutomaticLifecycleQuiet("));
        assertTrue(lifecycle.contains("revalidateAutomaticVisualSurfaceAfterQuickBoot"));
    }

    @Test public void serviceParksBeforeInitializationAndVisibleFrameCannotOpenBarrier()
            throws Exception {
        String service = source("WidgetService.java");
        String start = between(service,
                "public int onStartCommand(@Nullable Intent intent",
                "private void enqueueIntentScenarioCommand");
        int parked = start.indexOf("automaticRuntimeParked = (visualSurfaceOnly || stickyVisualSurface)");
        int initialize = start.indexOf("initializeRuntime();");
        assertTrue(parked >= 0 && initialize > parked);
        assertTrue(start.contains("deferredStickyRestart && !stickyVisualSurface"));
        assertTrue(start.contains("isStatusWidgetEnabledForVisualBootstrap(this)"));

        String visible = between(service, "private final class DeferredIntegrationStart",
                "/** Re-evaluates TTL/stale rules");
        assertTrue(visible.contains("shouldParkAutomaticRuntime(WidgetService.this)"));
        assertTrue(visible.contains("automaticRuntimeParked = true"));
        assertTrue(visible.contains("automaticHostReleaseAfterVisible"));
        assertTrue(visible.indexOf("automaticHostReleaseAfterVisible")
                < visible.indexOf("resumeAutomaticLifecycleIntegrationsAfterQuiet()"));

        String initial = between(service, "private void runInitialIntegrationStartup()",
                "private void runCachedStateFreshnessBarrier()");
        assertTrue(initial.contains("automaticRuntimeParked || automaticLifecycleQuiet"));
        assertTrue(initial.contains("if (automaticRuntimeParked || automaticLifecycleQuiet)"));

        String initializeRuntime = between(service, "private void initializeRuntime()",
                "private void ensureMqttRuntimeGraph()");
        assertTrue(initializeRuntime.contains("new Preferences(this, false)"));
        assertTrue(initializeRuntime.contains(
                "if (!automaticRuntimeParked && !automaticLifecycleQuiet)"));
        String barrier = between(service, "private void runCachedStateFreshnessBarrier()",
                "private boolean ownsStartupState(");
        assertTrue(barrier.contains("startupStateWorker.execute"));
        assertTrue(barrier.contains("prefs.completeDeferredStartupMigrations()"));
    }

    @Test public void acceptedHostReusesFreshRootButQuickBootCanRevalidateImmediately()
            throws Exception {
        String service = source("WidgetService.java");
        String reconcile = between(service,
                "public void reconcileAutomaticLifecycleSurfaces()",
                "private void finishAutomaticSurfaceReconcileIfReady()");
        assertTrue(reconcile.contains("if (automaticSurfaceRevalidationRequired"));
        assertTrue(reconcile.contains("boolean surfaceReady"));
        assertTrue(reconcile.contains("if (binding == null) createOverlayView()"));
        assertFalse(reconcile.contains("if (prefs.widgetEnabled.get() && binding != null)"));
        assertTrue(reconcile.contains("automaticHostReleaseAfterVisible = true"));
        assertTrue(reconcile.indexOf("automaticHostReleaseAfterVisible = true")
                < reconcile.indexOf("if (binding == null) createOverlayView()"));

        String enter = between(service, "public void enterAutomaticLifecycleQuiet()",
                "public void resumeAutomaticLifecycleIntegrationsAfterQuiet()");
        assertTrue(enter.contains("revalidateVisualSurfaceImmediately"));
        assertTrue(enter.contains("automaticSurfaceRevalidationRequired = true"));
        assertTrue(enter.contains("mainHandler.post(automaticVisualSurfaceRevalidation)"));
        String windowOnly = between(service,
                "private void revalidateStatusOverlayWindowOnly(",
                "/**\n     * Stops every listener");
        assertTrue(windowOnly.contains("removeStatusOverlaySafely(reason)"));
        assertTrue(windowOnly.contains("createOverlayView()"));
        assertFalse(windowOnly.contains("stopLocationTracking"));
        assertFalse(windowOnly.contains("CarIntegrations.get"));

        String resume = between(service,
                "public void resumeAutomaticLifecycleIntegrationsAfterQuiet()",
                "/**\n     * Re-opens only Keystore-backed transports");
        assertTrue(resume.contains("shouldParkAutomaticRuntime(this)"));
        assertTrue(resume.contains("automaticRuntimeParked = false"));
    }

    @Test public void quickBootTeardownAndApplicationRuntimeCannotFormAnotherBurst()
            throws Exception {
        String service = source("WidgetService.java");
        String teardown = between(service,
                "private void runNextAutomaticLifecycleQuietTeardown()",
                "private final Runnable automaticVisualSurfaceRevalidation");
        assertTrue(teardown.contains("switch (automaticLifecycleTeardownStage++)"));
        assertTrue(teardown.contains("mainHandler.post(automaticLifecycleQuietTeardown)"));
        assertFalse(teardown.contains("postDelayed(automaticLifecycleQuietTeardown"));

        String application = source("StatusWidgetApplication.java");
        String attempt = between(application, "private void attemptSurfaceOwnedInitialization()",
                "private void installCrashHandler");
        assertTrue(attempt.contains("WidgetService.getInstance()"));
        assertTrue(attempt.contains("!host.isIntegrationRuntimeReadyForApplication()"));
        assertTrue(service.contains("StatusWidgetApplication.resumeSurfaceOwnedInitialization(this)"));
    }

    @Test public void explicitCommandOpensVisibleParkedRuntimeWithoutOpeningHostBarrier()
            throws Exception {
        String service = source("WidgetService.java");
        String start = between(service,
                "public int onStartCommand(@Nullable Intent intent",
                "private void enqueueIntentScenarioCommand");
        assertTrue(start.contains("explicitScenarioRuntimeOverride = true"));
        assertTrue(start.contains("postDelayed(explicitScenarioRuntimeOverrideExpiry"));
        assertTrue(start.contains("mainHandler.removeCallbacks(automaticLifecycleQuietTeardown)"));
        assertTrue(start.contains("if (automaticRuntimeParked || automaticLifecycleQuiet)"));
        assertTrue(start.contains("resumeAutomaticLifecycleIntegrationsAfterQuiet()"));
        assertTrue(start.contains("else if (!initialIntegrationStartupInProgress)"));
        assertTrue(start.contains("runInitialIntegrationStartup()"));
        assertFalse(start.contains("else if (binding == null && !initialIntegrationStartupInProgress)"));
        String visible = between(service, "private final class DeferredIntegrationStart",
                "/** Re-evaluates TTL/stale rules");
        assertTrue(visible.contains("if (!explicitScenarioRuntimeOverride"));
        String resume = between(service,
                "public void resumeAutomaticLifecycleIntegrationsAfterQuiet()",
                "/**\n     * Re-opens only Keystore-backed transports");
        assertTrue(resume.contains("!explicitScenarioRuntimeOverride"));
        assertTrue(resume.contains("automaticRuntimeParked = false"));
        assertTrue(resume.contains("automaticLifecycleQuiet = false"));
        assertTrue(resume.contains("if (!startupStateBarrierInFlight)"));

        String drain = between(service,
                "private void drainPendingIntentScenarioCommands()",
                "private void reconcileExplicitScenarioRuntimeOverride(");
        assertTrue(drain.contains("reconcileExplicitScenarioRuntimeOverride(false)"));
        assertFalse(drain.contains("explicitScenarioRuntimeOverride = false"));
        String override = between(service,
                "private void reconcileExplicitScenarioRuntimeOverride(",
                "private void reconcileTemporaryScenarioHeadlessHost(");
        assertTrue(override.contains("intentScenarioController.hasPendingExecutions()"));
        assertTrue(override.contains("postDelayed(explicitScenarioRuntimeOverrideRecheck"));
        assertTrue(override.contains("shouldParkAutomaticRuntime(this)"));
        assertFalse(start.contains("openInitializationBarrierForHost"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
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
