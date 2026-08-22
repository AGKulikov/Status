/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import org.junit.Test;

/** Release boundary for Natro 2.2.5 and Helper 57 companion/payload repairs. */
public final class Ha1239CompanionPayloadPassengerContractTest {
    @Test public void publicVersionAdvancesForThisRelease() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1239);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        assertTrue(build.contains("return '2.2.5'"));
        assertTrue(build.contains("if (version == '2.2.5') { return 208021239"));
        assertTrue(build.contains("if (version == '2.2.4') { return 208021238"));

        String manifest = project("release-manifests/HA1239.md");
        assertTrue(manifest.contains("Android version: `2.2.5`"));
        assertTrue(manifest.contains("Android version code: `208021239`"));
        assertTrue(manifest.contains("Release tag: `natro-v2.2.5`"));
        assertTrue(manifest.contains("Helper: build `57`, marketing `57.0`"));
    }

    @Test public void liveActivityStateIsCompactAndCarAssetIsSmall() throws Exception {
        String shared = helper("NatroLiveActivityShared.swift");
        String manager = helper("KX11ANCSHelper/NatroLiveActivityManager.swift");
        String widget = helper("NatroLiveActivityExtension/NatroLiveActivityWidget.swift");
        int encoderStart = shared.indexOf("func encode(to encoder: Encoder) throws");
        int encoderEnd = shared.indexOf("var isDemo: Bool", encoderStart);
        String encoder = shared.substring(encoderStart, encoderEnd);

        assertTrue(shared.contains("statusCode: UInt8"));
        assertTrue(shared.contains("values: [Int32]"));
        assertTrue(shared.contains("valueFlags: [UInt8]"));
        assertTrue(shared.contains("Array(values.prefix(4))"));
        assertFalse(encoder.contains("activeControlIDs"));
        assertFalse(encoder.contains("availableControlIDs"));
        assertFalse(encoder.contains("controlSnapshots"));
        assertTrue(manager.contains("maximumEncodedStateBytes = 3_500"));
        assertTrue(manager.contains("replaceActivities(reason: \"обновление Helper 57\")"));
        assertTrue(widget.contains("Image(\"Monjaro\")"));
        assertTrue(widget.contains("control.compactValue(snapshot?.value ?? 0, active: active)"));
        assertTrue(shared.contains("func compactValue(_ value: Int32, active: Bool)"));
        assertTrue(Files.size(projectPath("ios/KX11-iPhone-ANCS-Helper-v57/"
                + "NatroLiveActivityExtension/Monjaro.png")) < 80_000L);
    }

    @Test public void firstLaunchDemoAndContextualControlsAreFunctional() throws Exception {
        String runtime = helper("HelperSwitchRuntimeCoordinator.swift");
        String manager = helper("KX11ANCSHelper/NatroLiveActivityManager.swift");
        String ui = helper("KX11ANCSHelper/CarControlUI.swift");

        assertTrue(runtime.contains(
                "persisted.reducerPhase == .failed || persisted.reducerPhase == .closed"));
        assertTrue(runtime.contains("legacy-настройка перенесена в основной Route A"));
        assertTrue(manager.contains("private func applyDemo("));
        assertTrue(manager.contains("demoValues[controlID] = 1"));
        assertTrue(manager.contains("DispatchQueue.main.asyncAfter(deadline: .now() + 0.8)"));
        assertTrue(ui.contains("VehicleControlSectionViewController"));
        assertTrue(ui.contains("case .levels, .options:"));
        assertTrue(ui.contains("case .range:"));
        assertTrue(ui.contains("manager.send(controlID: definition.id, operation: .set"));
        assertFalse(ui.contains("contentStack.addArrangedSubview(makeControlCard"));
    }

    @Test public void passengerControlsAreFiniteCapabilityGatedAndHaveNoMassage()
            throws Exception {
        String passenger = project("app/src/geely/java/dezz/status/widget/car/"
                + "GeelyPassengerControlIntegration.java");
        String registry = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "CarRemoteControlRegistryV1.java");
        String helperProtocol = helper("CarRemoteProtocolV1.swift");
        String all = (passenger + registry + helperProtocol).toLowerCase(Locale.ROOT);

        for (String marker : new String[]{"getPA_ReadLightFrontLeft", "CB_ReadLightFrontLeft",
                "CB_ReadLightAllOnSwitch", "getPA_Fragra_LvlReqSts", "CB_Fragra_LvlReq"}) {
            assertTrue(marker, passenger.contains(marker));
        }
        for (int id = 60; id <= 65; id++) {
            assertTrue("missing C5 id " + id, registry.contains("add(wire, control, " + id + ","));
        }
        assertTrue(helperProtocol.contains("optionalV57IDs: Set<UInt8> = [60, 61, 62, 63, 64, 65]"));
        assertFalse(all.contains("massage"));
        assertFalse(all.contains("массаж"));
    }

    @Test public void headUnitUsesContextualControlCenterAndBothJournalsAreExpanded()
            throws Exception {
        String activity = project("app/src/main/java/dezz/status/widget/VehicleControlActivity.java");
        String catalog = project("app/src/main/java/dezz/status/widget/settings/"
                + "SettingsDestinationCatalog.java");
        String androidJournal = project("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectionJournal.java");
        String routeA = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String helperJournal = helper("KX11ANCSHelper/ANCSConnectionJournal.swift");

        assertTrue(activity.contains("controls.setColumnCount(2)"));
        assertTrue(activity.contains("chooseOption"));
        assertTrue(activity.contains("chooseRange"));
        assertTrue(activity.contains("Убедитесь, что рядом с механизмом"));
        assertTrue(catalog.contains("vehicle_control"));
        assertTrue(catalog.contains("dezz.status.widget.VehicleControlActivity"));
        assertTrue(androidJournal.contains("MAX_LINES = 1_600"));
        assertTrue(androidJournal.contains("private static final String SESSION"));
        assertTrue(routeA.contains("journalTransition"));
        assertTrue(routeA.contains("effects="));
        assertTrue(helperJournal.contains("private let maximumLines = 1_600"));
        assertTrue(helperJournal.contains("sessionID"));
        assertTrue(helperJournal.contains("func exportURL() throws -> URL"));
    }

    private static String helper(String relative) throws Exception {
        return project("ios/KX11-iPhone-ANCS-Helper-v57/" + relative);
    }

    private static String project(String relative) throws Exception {
        return new String(Files.readAllBytes(projectPath(relative)), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String relative) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path file = current.resolve(relative);
            if (Files.isRegularFile(file)) return file;
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
