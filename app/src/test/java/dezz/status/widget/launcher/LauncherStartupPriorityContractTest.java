/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the cold-HOME critical path on the single Android 9 background lane. */
public final class LauncherStartupPriorityContractTest {
    @Test public void optionalVehicleRuntimeCannotQueueAheadOfRequiredGeometry()
            throws Exception {
        String source = launcherSource();
        String create = between(source, "protected void onCreate(",
                "protected void onNewIntent(");
        assertTrue(create.contains("startLauncherBootstrapNow()"));
        assertFalse(create.contains("startLauncherCarRuntimeAsync()"));

        String deferred = between(source,
                "private final Runnable deferredLauncherRuntimeStep",
                "@Override\n    protected void onCreate(");
        assertTrue(deferred.contains("case 2:"));
        assertTrue(deferred.indexOf("startLauncherCarRuntimeAsync()")
                > deferred.indexOf("case 2:"));
        assertTrue(deferred.contains("reloadAppCatalogAsync(false)"));
        assertFalse(deferred.contains("reloadAppCatalogAsync(true)"));
    }

    @Test public void visibleApplicationAndActionPanelsPrecedeOptionalPanels()
            throws Exception {
        String source = launcherSource();
        String stages = between(source, "private void continuePanelInitialization()",
                "private void makePanelTransparent(");
        assertOrdered(stages,
                "addPanelSafely(LauncherLayoutStore.APPS",
                "addPanelSafely(LauncherLayoutStore.ACTIONS",
                "addPanelSafely(LauncherLayoutStore.MEDIA",
                "addPanelSafely(LauncherLayoutStore.CLOCK",
                "addPanelSafely(LauncherLayoutStore.NAVIGATION");
        assertFalse(stages.contains("postDelayed"));
    }

    @Test public void catalogStartsAtGeometryReadinessAndPublishesAtomically()
            throws Exception {
        String source = launcherSource();
        String geometry = between(source, "private void finishPanelGeometryLoad(",
                "private void continuePanelInitialization()");
        assertOrdered(geometry,
                "appCatalog = new AppCatalog(getApplicationContext())",
                "reloadAppCatalogAsync(true)",
                "scheduleInitialLauncherBackdrops()",
                "navigationUiHandler.post(panelInitializationStep)");

        String reload = between(source, "private void reloadAppCatalogAsync(boolean force)",
                "private void applyLauncherPreferences()");
        assertTrue(reload.contains("if (!activityStarted || appCatalogLoadInFlight"));
        assertTrue(reload.contains("launcherWorker.execute"));
        assertTrue(reload.contains("loaded.reload()"));
        assertTrue(reload.contains("lastAppCatalogLoadElapsed > 0L"));
        assertOrdered(reload,
                "navigationUiHandler.post",
                "appCatalog = loaded",
                "refreshFavorites()",
                "refreshAllAppsDrawerContents()");
        // PackageManager/icon work must never be added incrementally to the live adapter.
        assertFalse(reload.contains("appCatalog.reload()"));

        String backdrops = between(source, "private void scheduleInitialLauncherBackdrops()",
                "/** Keeps every decorative HOME surface");
        assertTrue(backdrops.contains("!panelGeometryReady && !panelsInitialized"));
        assertTrue(backdrops.contains("++initialBackdropLoadGeneration"));
        assertTrue(backdrops.contains("generation != initialBackdropLoadGeneration"));

        String resume = between(source, "protected void onResume()",
                "protected void onPause()");
        assertTrue(resume.contains("reloadAppCatalogAsync(false)"));
    }

    @Test public void physicalTimingHasComparableReadyMarkers() throws Exception {
        String source = launcherSource();
        assertTrue(source.contains("launcher_actions_ready"));
        assertTrue(source.contains("launcher_app_catalog_ready"));
        assertTrue(source.contains("SystemClock.elapsedRealtime() - loadStartedElapsed"));
    }

    private static void assertOrdered(String source, String... markers) {
        int position = -1;
        for (String marker : markers) {
            int next = source.indexOf(marker, position + 1);
            assertTrue("Missing/out-of-order marker: " + marker, next > position);
            position = next;
        }
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String launcherSource() throws Exception {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status",
                "widget", "LauncherActivity.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "LauncherActivity.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
