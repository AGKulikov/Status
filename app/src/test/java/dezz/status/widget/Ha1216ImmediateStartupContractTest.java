/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards immediate, event-driven startup without adding another scheduler or polling loop. */
public final class Ha1216ImmediateStartupContractTest {
    @Test public void coordinatorDispatchesNowAndUsesAlarmOnlyAsFallback() throws Exception {
        String source = javaSource("StartupWorkCoordinator.java");
        String dispatch = between(source, "private static void dispatchPhaseNowWithFallback",
                "private static void schedulePhase");
        assertTrue(dispatch.contains("schedulePhase(app, phase, generation,"));
        assertTrue(dispatch.contains("PHASE_DELIVERY_FALLBACK_MS"));
        assertTrue(dispatch.contains("app.sendBroadcast(phaseIntent(app, phase, generation))"));
        assertTrue(source.contains("cancelPhaseFallback(app, phase)"));
        String prime = between(source, "static void clearLegacyStartupDeferrals",
                "public static void scheduleForLifecycle");
        assertTrue(prime.contains("edit.apply()"));
        assertFalse(prime.contains("edit.commit()"));
        assertTrue(prime.contains("StartupLoadPolicy.isNewBootGeneration"));
        assertTrue(prime.contains("clearElapsedGenerationState(edit)"));
        assertTrue(source.contains("static long remainingQuietMillis"));
        assertTrue(source.contains("static boolean shouldParkAutomaticRuntime"));
        assertFalse(source.contains("launcherRuntimeDelayMillis"));
        assertFalse(source.contains("automaticReconcileDelayMillis"));
        assertFalse(source.contains("startupInitializationDelayMillis"));
        assertFalse(source.contains("coalescingActiveBootLane"));
        assertFalse(source.contains("retainedPhaseNotBefore"));
    }

    @Test public void applicationUsesRealEventsWithoutGraceOrPolling() throws Exception {
        String source = javaSource("StatusWidgetApplication.java");
        String attempt = between(source, "private void attemptSurfaceOwnedInitialization()",
                "private void installCrashHandler");
        assertTrue(attempt.contains("if (!firstUsefulSurfaceSeen"));
        assertTrue(attempt.contains("isIntegrationRuntimeReadyForApplication"));
        assertTrue(attempt.contains("ensureUnlockedRuntimeInitialized()"));
        assertFalse(attempt.contains("postDelayed"));
        assertFalse(source.contains("SURFACE_RUNTIME_GRACE_MS"));
        assertFalse(source.contains("unlockedRuntimeRetry"));
        assertTrue(source.contains("new Preferences(this, false)"));
        assertTrue(source.contains("AsyncTask.SERIAL_EXECUTOR.execute"));
        assertTrue(source.contains("THREAD_PRIORITY_BACKGROUND"));
        assertTrue(source.contains("preferences.completeDeferredStartupMigrations()"));
    }

    @Test public void automaticAndManualBootstrapShareImmediatePath() throws Exception {
        String source = javaSource("AppRuntimeBootstrap.java");
        String run = between(source, "public static void run(", "/**\n     * Re-applies");
        assertTrue(run.contains("reconcileServices(activity, preferences)"));
        assertFalse(run.contains("automaticReconcileDelayMillis"));
        String reconcile = between(source, "static void reconcileServices(",
                "private static void tryAutoGrant");
        assertTrue(reconcile.contains("WidgetServiceStarter.startIfNeeded(appContext)"));
        assertTrue(reconcile.contains("DriverPanelService.apply(appContext)"));
        assertTrue(reconcile.contains("HudPresentationService.apply(appContext)"));
        assertTrue(reconcile.contains("ClimatePanelService.apply(appContext)"));
        assertFalse(reconcile.contains("ensureIntegrationHostScheduled"));
        assertFalse(reconcile.contains("ensureClimateScheduled"));
        assertTrue(source.contains("AsyncTask.SERIAL_EXECUTOR.execute"));
        assertTrue(source.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        assertTrue(source.contains("activity.runOnUiThread"));
    }

    @Test public void ownedStartupFilesCreateNoWorkerPoolOrBusyLoop() throws Exception {
        String combined = javaSource("StartupWorkCoordinator.java")
                + javaSource("StatusWidgetApplication.java")
                + javaSource("AppRuntimeBootstrap.java");
        assertFalse(combined.contains("Executors."));
        assertFalse(combined.contains("new Thread("));
        assertFalse(combined.contains("new HandlerThread("));
        assertFalse(combined.contains("new Timer("));
        assertFalse(combined.contains("scheduleAtFixedRate"));
        assertFalse(combined.contains("for (;;"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String javaSource(String relative) throws Exception {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", relative);
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
