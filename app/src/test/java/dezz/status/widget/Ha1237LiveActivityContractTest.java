/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release boundary for Helper 55 Live Activity, demo and exact ANCS App Intents. */
public final class Ha1237LiveActivityContractTest {
    @Test public void revisionTwoKeepsVersionNameAndMovesAndroidCodeForward()
            throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1237);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        assertTrue(build.contains("if (version == '2.2.4') { return 208021238"));

        String manifest = project("release-manifests/HA1237.md");
        assertTrue(manifest.contains("Android version: `2.2.4`"));
        assertTrue(manifest.contains("Android version code: `208021237`"));
        assertTrue(manifest.contains("Release tag: `natro-v2.2.4-r2`"));
        assertTrue(manifest.contains("Helper: build `55`, marketing `55.0`"));
    }

    @Test public void companionStatesStayOutsideTheFrozenCommandCatalog() throws Exception {
        String controller = project("app/src/main/java/dezz/status/widget/phone/"
                + "CarRemoteControllerV1.java");
        String phone = project("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v55/CarRemoteProtocolV1.swift");

        assertTrue(controller.contains("STATE_CABIN_TEMPERATURE = 0xfc"));
        assertTrue(controller.contains("STATE_OUTDOOR_TEMPERATURE = 0xfd"));
        assertTrue(controller.contains("STATE_ANCS_CONNECTED = 0xfe"));
        assertTrue(controller.contains("car.subscribeTelemetry"));
        assertTrue(controller.contains("car.unsubscribeTelemetry"));
        assertTrue(phone.contains("carRemote.setAncsReady(value)"));
        assertTrue(helper.contains("case CompanionStateID.ancsConnected"));
        assertTrue(helper.contains("case CompanionStateID.cabinTemperature"));
        assertTrue(helper.contains("case CompanionStateID.outdoorTemperature"));
        assertFalse(helper.contains("controls.append(CompanionStateID"));
    }

    @Test public void demoNeverForgesTheRealShortcutsBoolean() throws Exception {
        String manager = project("ios/KX11-iPhone-ANCS-Helper-v55/KX11ANCSHelper/"
                + "NatroLiveActivityManager.swift");
        String shortcuts = project("ios/KX11-iPhone-ANCS-Helper-v55/KX11ANCSHelper/"
                + "NatroShortcuts.swift");
        String settings = project("ios/KX11-iPhone-ANCS-Helper-v55/KX11ANCSHelper/"
                + "LiveActivitySettingsViewController.swift");

        assertTrue(manager.contains(
                "var isANCSConnected: Bool { remote?.ancsConnected == true }"));
        assertTrue(manager.contains("status: \"ДЕМО · ANCS подключён\""));
        assertTrue(manager.contains("isDemo: true"));
        assertTrue(manager.contains("ensureRunning(reason: \"ДЕМО\", force: true)"));
        assertTrue(shortcuts.contains("struct NatroANCSStatusIntent: AppIntent"));
        assertTrue(shortcuts.contains("struct NatroWaitForANCSIntent: AppIntent"));
        assertTrue(shortcuts.contains("ReturnsValue<Bool>"));
        assertTrue(settings.contains("Демо без автомобиля"));
        assertTrue(settings.contains("реальный ANCS не подменяется"));
    }

    @Test public void projectEmbedsOneInteractiveLiveActivityExtension() throws Exception {
        String project = project("ios/KX11-iPhone-ANCS-Helper-v55/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        String widget = project("ios/KX11-iPhone-ANCS-Helper-v55/"
                + "NatroLiveActivityExtension/NatroLiveActivityWidget.swift");

        assertTrue(project.contains("name = NatroLiveActivityExtension;"));
        assertTrue(project.contains(
                "NatroLiveActivityExtension.appex in Embed App Extensions"));
        assertTrue(project.contains("IPHONEOS_DEPLOYMENT_TARGET = 16.2;"));
        assertTrue(project.contains("Monjaro.png in Resources"));
        assertTrue(widget.contains(
                "ActivityConfiguration(for: NatroLiveActivityAttributes.self)"));
        assertTrue(widget.contains("Button(intent: NatroLiveControlIntent"));
        assertTrue(widget.contains("DynamicIslandExpandedRegion(.bottom)"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path file = current.resolve(relative);
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
