/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Release-source guard for the evidence-bounded HA1217 integrations. */
public final class Ha1217HwgpsMediaDriverContractTest {
    @Test public void hwgpsUsesOnlyExportedEvidenceAndNoPolling() throws IOException {
        String integration = source("dezz/status/widget/integration/HwgpsIntegration.java");
        String resolver = source("dezz/status/widget/integration/SystemConditionResolver.java");
        String starter = source("dezz/status/widget/WidgetServiceStarter.java");
        assertTrue(integration.contains("org.astpepper.hwgps.FindMeActivity"));
        assertTrue(integration.contains("org.astpepper.hwgps.action.FIND_ME"));
        assertTrue(integration.contains("hwgps.fix.state"));
        assertTrue(integration.contains("hwgps.fix.state.request"));
        assertTrue(integration.contains("org.astpepper.hwgps.receivers.GeodataRequestReceiver"));
        assertTrue(resolver.contains("HWGPS_ROUTE_LOST_RESOURCE"));
        assertTrue(resolver.contains("if (!scenario.enabled) continue;"));
        assertTrue(starter.contains(
                "hasConfiguredLocalScenarios(preferences.localScenariosJson.get())"));
        assertTrue(starter.contains("scenario.optBoolean(\"enabled\", true)"));
        String icons = source("dezz/status/widget/launcher/LauncherIconResolver.java");
        assertTrue(icons.contains("case \"wrong_location\": return R.drawable.ic_hwgps_find_me"));
        assertFalse(integration.contains("postDelayed"));
        assertFalse(integration.contains("startService"));
    }

    @Test public void msaverLikeNeverUsesItsPrivateBroadcast() throws IOException {
        String controller = source("dezz/status/widget/launcher/LauncherMediaController.java");
        String listener = source("dezz/status/widget/MediaNotificationListener.java");
        assertTrue(controller.contains("PlaybackState.ACTION_SET_RATING"));
        assertTrue(controller.contains("Rating.newHeartRating"));
        assertTrue(controller.contains("sendMediaNotificationLike"));
        assertTrue(listener.contains("MediaLikeActionPolicy.matchesNotificationAction"));
        assertFalse(controller.contains("PERFORM_LIKE"));
        assertFalse(listener.contains("new Intent(\"PERFORM_LIKE\")"));
    }

    @Test public void driverStyleHasStaticBaseScenarioPrecedenceAndRestoration() throws IOException {
        String policy = source("dezz/status/widget/driver/DriverPanelStylePolicy.java");
        String overlay = source("dezz/status/widget/driver/DriverPanelOverlayController.java");
        String scenarios = source("dezz/status/widget/integration/LocalScenarioController.java");
        assertTrue(policy.contains("PANEL_TARGET_ID = \"driver_panel\""));
        assertTrue(policy.contains("automation.iconTint"));
        assertTrue(overlay.contains("profile.borderColor.get()"));
        assertTrue(overlay.contains("rippleBackground(background, 14,"));
        assertTrue(scenarios.contains("case ICON_OUTLINE_WIDTH"));
    }

    @Test public void launcherStyleUsesStableScopeAndEventDrivenInvalidation()
            throws IOException {
        String contract = source("dezz/status/widget/automation/AutomationContract.java");
        String scenarios = source("dezz/status/widget/integration/LocalScenarioController.java");
        String service = source("dezz/status/widget/WidgetService.java");
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(contract.contains("SCOPE_LAUNCHER = \"launcher\""));
        assertTrue(scenarios.contains("case LAUNCHER: stateScope = "
                + "AutomationContract.SCOPE_LAUNCHER"));
        assertTrue(service.contains("AutomationPresentationListener"));
        assertTrue(service.contains("launcherAutomationState("));
        assertTrue(launcher.contains("applyLauncherAutomationStyle("));
        assertTrue(launcher.contains("addAutomationPresentationListener("));
        assertTrue(launcher.contains("binding.liveTint, binding.liveBackground"));
    }

    @Test public void driverInvalidationIsCoalescedPerScenarioBatch() throws IOException {
        String service = source("dezz/status/widget/WidgetService.java");
        int start = service.indexOf("private void onScenarioTargetsChanged(");
        int end = service.indexOf("private static final class PreparedInitialIntegrationStage",
                start);
        String callback = service.substring(start, end);
        assertTrue(callback.contains("boolean driverTargetsChanged = false"));
        assertTrue(callback.contains("driverTargetsChanged = true"));
        assertTrue(callback.contains(
                "if (driverTargetsChanged) DriverPanelService.apply(this)"));
        assertTrue(count(callback, "DriverPanelService.apply(this)") == 1);
    }

    @Test public void newIconsCarryPinnedGoogleProvenance() throws IOException {
        assertTrue(resource("ic_media_like.xml").contains(
                "Google Material Symbols Rounded @ 50f0603 (favorite"));
        assertTrue(resource("ic_hwgps_find_me.xml").contains(
                "Google Material Symbols Rounded @ 50f0603 (wrong_location"));
        assertTrue(resource("ic_driver_border_color.xml").contains(
                "Google Material Symbols Rounded @ 50f0603 (border_color"));
        assertTrue(resource("ic_driver_format_color_fill.xml").contains(
                "Google Material Symbols Rounded @ 50f0603 (format_color_fill"));
    }

    private static String source(String relative) throws IOException {
        return new String(Files.readAllBytes(project("app/src/main/java/" + relative)),
                StandardCharsets.UTF_8);
    }

    private static String resource(String file) throws IOException {
        return new String(Files.readAllBytes(project("app/src/main/res/drawable/" + file)),
                StandardCharsets.UTF_8);
    }

    private static Path project(String relative) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static int count(String source, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }
}
