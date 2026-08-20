/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source/order contract for immediate launcher startup without poll-loop CPU churn. */
public final class Ha1216LauncherFirstFrameContractTest {
    @Test public void onCreateAttachesOpaqueShellBeforeSubmittingAllStartupCommands()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        String create = between(launcher, "protected void onCreate(",
                "protected void onNewIntent(");
        int deferredPreferences = create.indexOf("new Preferences(this, false)");
        int setContent = create.indexOf("setContentView(root);");
        int bootstrap = create.indexOf("startLauncherBootstrapNow();");
        int service = create.indexOf("navigationUiHandler.post(this::startImmediateHomeRuntime);");
        int draw = create.indexOf("addOnDrawListener");
        assertTrue(deferredPreferences >= 0 && setContent > deferredPreferences
                && bootstrap > setContent && service > bootstrap && draw > service);
        assertFalse(create.contains("new Preferences(this);"));
        assertFalse(create.contains("completeDeferredStartupMigrations"));
        assertFalse(create.contains("postDelayed"));

        String shell = between(launcher, "private View buildRoot()",
                "/** Keeps HOME content");
        assertTrue(shell.contains("buildShellBackground()"));
        assertTrue(shell.contains("new LauncherWorkspaceView(this)"));
        assertFalse(shell.contains("preferences."));
        assertFalse(shell.contains("new MaterialButton"));
    }

    @Test public void migrationAndVendorConstructionRunOnOneImmediateBackgroundLane()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        assertTrue(launcher.contains("Executors.newSingleThreadExecutor"));
        assertFalse(launcher.contains("Executors.newFixedThreadPool"));

        String bootstrap = between(launcher, "private void startLauncherBootstrapNow()",
                "private void finishLauncherBootstrap(");
        assertTrue(bootstrap.contains("launcherWorker.execute"));
        assertTrue(bootstrap.contains("preferences.completeDeferredStartupMigrations()"));
        assertFalse(bootstrap.contains("postDelayed"));

        String apply = between(launcher, "private void finishLauncherBootstrap(",
                "private void applyPendingLauncherBootstrapAfterFirstDraw()");
        assertTrue(apply.contains("!launcherFirstDrawCompleted || !activityStarted"));

        String geometry = between(launcher, "private void initializePanels()",
                "private void finishPanelGeometryLoad(");
        assertTrue(geometry.contains("launcherWorker.execute"));
        assertTrue(geometry.contains("layoutStore.load(width, height)"));
        assertFalse(geometry.contains("postDelayed"));

        String car = between(launcher, "private void startLauncherCarRuntimeAsync()",
                "private void activateLauncherCarRuntime()");
        assertTrue(car.contains("launcherWorker.execute"));
        assertTrue(car.contains("CarIntegrations.get(getApplicationContext())"));
    }

    @Test public void panelAndRuntimeStagesYieldWithoutArtificialTimers() throws Exception {
        String launcher = source("LauncherActivity.java");
        String panels = between(launcher, "private void continuePanelInitialization()",
                "private void makePanelTransparent");
        assertTrue(panels.contains("navigationUiHandler.post(panelInitializationStep)"));
        assertFalse(panels.contains("postDelayed"));

        String runtime = between(launcher,
                "private final Runnable deferredLauncherRuntimeStep",
                "protected void onCreate(");
        assertTrue(runtime.contains("navigationUiHandler.post(this)"));
        assertTrue(runtime.contains("startLauncherCarRuntimeAsync()"));
        assertFalse(runtime.contains("postDelayed"));
        assertFalse(launcher.contains("PANEL_INITIALIZATION_GRACE_MS"));
        assertFalse(launcher.contains("PANEL_INITIALIZATION_STAGE_MS"));
        assertFalse(launcher.contains("DEFERRED_LAUNCHER_STAGE_MS"));
        assertFalse(launcher.contains("launcherPanelDelayMillis"));
        assertFalse(launcher.contains("launcherRuntimeDelayMillis"));
    }

    @Test public void liveUiUsesInvalidationAndCallbacksInsteadOf500msScans()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        assertFalse(launcher.contains("SAFE_AREA_REFRESH_MS"));
        assertFalse(launcher.contains("GLOBAL_ELEMENT_REFRESH_MS"));
        assertTrue(launcher.contains("setDescendantInvalidationListener"));
        assertTrue(launcher.contains("LauncherRuntimeProbePolicy.nextDelayMillis"));

        String global = between(launcher,
                "private final Runnable globalElementRefresh",
                "private final BroadcastReceiver navigationReceiver");
        assertTrue(global.contains("refreshGlobalElementVisibility()"));
        assertFalse(global.contains("postDelayed"));
        assertFalse(global.contains("syncGlobalElements()"));

        String navigation = between(launcher, "private void scheduleNavigationRefresh()",
                "private void showNavigationImage");
        assertTrue(navigation.contains("if (navigationDynamicRefresh)"));
        assertFalse(navigation.contains("NAVIGATION_UI_REFRESH_MS"));

        String workspace = source("launcher/LauncherWorkspaceView.java");
        assertTrue(workspace.contains("onDescendantInvalidated"));
        assertTrue(workspace.contains("onDescendantInvalidated(target)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        Path module = Paths.get("src", "main", "java", "dezz", "status", "widget");
        Path base = Files.isDirectory(root) ? root : module;
        return new String(Files.readAllBytes(base.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(0, start.length()));
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
    }
}
