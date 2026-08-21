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

/** Release boundary for Helper 56 dual Live Activity, extended controls and ANCS journals. */
public final class Ha1238DualLiveActivityControlsJournalContractTest {
    @Test public void revisionThreeKeepsInstallIdentityAndAdvancesTheCode() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1238);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        assertTrue(build.contains("return '2.2.4'"));
        assertTrue(build.contains("if (version == '2.2.4') { return 208021238"));

        String manifest = project("release-manifests/HA1238.md");
        assertTrue(manifest.contains("Android version code: `208021238`"));
        assertTrue(manifest.contains("Release tag: `natro-v2.2.4-r3`"));
        assertTrue(manifest.contains("Helper: build `56`, marketing `56.0`"));
    }

    @Test public void twoPanelsShareDemoAndExposeRealLevelState() throws Exception {
        String shared = helper("NatroLiveActivityShared.swift");
        String manager = helper("KX11ANCSHelper/NatroLiveActivityManager.swift");
        String widget = helper("NatroLiveActivityExtension/NatroLiveActivityWidget.swift");
        String dashboard = helper("KX11ANCSHelper/CarControlUI.swift");

        assertTrue(shared.contains("enum NatroLivePanel"));
        assertTrue(shared.contains("case climate"));
        assertTrue(shared.contains("case functions"));
        assertTrue(shared.contains("count: 10"));
        assertTrue(shared.contains("var isThreeStage: Bool"));
        assertTrue(manager.contains("NatroLivePanel.allCases.filter"));
        assertTrue(manager.contains("runningCount >= 2"));
        assertTrue(manager.contains("value: operation == .activate ? 1 : 0"));
        assertTrue(widget.contains("ClimateActivityView(state: state)"));
        assertTrue(widget.contains("FunctionsActivityView(state: state)"));
        assertTrue(widget.contains("private struct StageIndicator: View"));
        assertTrue(dashboard.contains("private func makeLevelStrip"));
        assertTrue(dashboard.contains("manager.isDemoMode"));
    }

    @Test public void optionalCatalogIsFiniteCapabilityGatedAndHasNoMassage() throws Exception {
        String registry = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "CarRemoteControlRegistryV1.java");
        String geely = project("app/src/geely/java/dezz/status/widget/car/"
                + "GeelyCarIntegration.java");
        String helperProtocol = helper("CarRemoteProtocolV1.swift");
        String all = (registry + geely + helperProtocol).toLowerCase(Locale.ROOT);

        assertTrue(registry.contains("climate.seat_heat_rear_left"));
        assertTrue(registry.contains("comfort.ambient_theme"));
        assertTrue(registry.contains("vehicle.window_close_driver"));
        assertTrue(geely.contains("WINDOW_POSITION_FUNCTION"));
        assertTrue(geely.contains("SUNROOF_TILT_FUNCTION"));
        assertTrue(helperProtocol.contains("optionalV56IDs"));
        assertTrue(helperProtocol.contains("requiredLegacyIDs"));
        assertFalse(all.contains("massage"));
        assertFalse(all.contains("массаж"));
    }

    @Test public void bothSidesRecordDetailedExportableAncsStages() throws Exception {
        String journal = helper("KX11ANCSHelper/ANCSConnectionJournal.swift");
        String settings = helper("KX11ANCSHelper/ViewController.swift");
        String phone = project("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");

        assertTrue(journal.contains("private let maximumLines = 800"));
        assertTrue(journal.contains("func exportURL() throws -> URL"));
        assertTrue(journal.contains("private func sanitize"));
        assertTrue(settings.contains("Показывать журнал"));
        assertTrue(settings.contains("Экспортировать журнал"));
        assertTrue(settings.contains("runtime.onDiagnosticEvent"));
        assertTrue(phone.contains("v2LifecycleStage(status.lifecycle)"));
        assertTrue(phone.contains("recovery="));
        assertTrue(phone.contains("failures="));
        assertTrue(phone.contains("PhoneConnectionJournal.append(\"ancs-trigger\""));
        assertTrue(phone.contains("presenceSink.onAncsConnectionChanged(value)"));
        assertTrue(phone.contains("carRemote.setAncsReady(value)"));
    }

    private static String helper(String relative) throws Exception {
        return project("ios/KX11-iPhone-ANCS-Helper-v56/" + relative);
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
