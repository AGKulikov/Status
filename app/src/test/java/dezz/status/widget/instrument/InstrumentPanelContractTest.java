/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class InstrumentPanelContractTest {
    @Test public void nativeDashboardRoundTripsEveryEditableField() throws Exception {
        InstrumentPanelConfig config = InstrumentPanelConfig.defaults();
        config.displayId = 2;
        config.transparentBackground = true;
        config.defaultStyle = InstrumentStyleFamily.M_SPORT_ARCS;
        InstrumentElementConfig first = config.elements.get(0);
        first.responseMillis = 82;
        first.opacityPercent = 77;
        first.style = InstrumentStyleFamily.RETRO_MECHANICAL;

        InstrumentPanelConfig restored = InstrumentPanelConfig.fromJson(config.toJson());

        assertEquals(1920, InstrumentPanelConfig.DESIGN_WIDTH);
        assertEquals(720, InstrumentPanelConfig.DESIGN_HEIGHT);
        assertEquals(2, restored.displayId);
        assertTrue(restored.transparentBackground);
        assertEquals(InstrumentStyleFamily.M_SPORT_ARCS, restored.defaultStyle);
        assertEquals(config.elements.size(), restored.elements.size());
        assertEquals(82, restored.elements.get(0).responseMillis);
        assertEquals(77, restored.elements.get(0).opacityPercent);
        assertEquals(InstrumentStyleFamily.RETRO_MECHANICAL,
                restored.elements.get(0).style);
        assertNotNull(restored.elements.stream()
                .filter(value -> value.type == InstrumentElementType.NAV_MAP)
                .findFirst().orElse(null));
        assertEquals(10, InstrumentStyleFamily.values().length);
        assertEquals(6, java.util.Arrays.stream(InstrumentElementType.values())
                .filter(InstrumentElementType::isAnalogGauge).count());
        assertEquals(8, java.util.Arrays.stream(InstrumentElementType.values())
                .filter(InstrumentElementType::isDigitalGauge).count());
        assertTrue(InstrumentElementType.ANALOG_FUEL_GAUGE.isAnalogGauge());
        assertTrue(InstrumentElementType.ANALOG_BATTERY_GAUGE.isAnalogGauge());
        assertTrue(InstrumentElementType.ANALOG_COOLANT_TEMPERATURE.isAnalogGauge());
        assertFalse(InstrumentElementType.FUEL_GAUGE.isAnalogGauge());
        assertTrue(InstrumentElementType.DIGITAL_SPEEDOMETER.isDigitalGauge());
        assertTrue(InstrumentElementType.AVERAGE_CONSUMPTION.isDigitalGauge());
        assertFalse(InstrumentElementType.GEAR.isDigitalGauge());
    }

    @Test public void panelUsesOneFastChannelAndDirectNativeMapSurface() throws Exception {
        Path root = projectRoot();
        String repository = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentTelemetryRepository.java"));
        String integration = read(root.resolve("app/src/geely/java/dezz/status/widget/car/"
                + "GeelyCarIntegration.java"));
        String passengerDecorator = read(root.resolve("app/src/geely/java/dezz/status/widget/car/"
                + "GeelyPassengerControlIntegration.java"));
        String panel = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentPanelView.java"));
        String renderer = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentClusterView.java"));

        assertTrue(repository.contains("ownerMetrics"));
        assertTrue(repository.contains("reconcileSubscriptionLocked"));
        assertTrue(repository.contains("subscribeRealtimeTelemetry(next, listener)"));
        assertTrue(repository.contains("if (!changed) return"));
        assertTrue(repository.contains("volatile float speed"));
        assertFalse(repository.contains("new Handler"));
        assertTrue(integration.contains("no Handler post, TelemetryValue"));
        assertTrue(integration.contains("drainFastTelemetry"));
        assertTrue(integration.contains("realtimeTelemetrySnapshot"));
        assertTrue(integration.contains("isRealtimeSensorRecoveryDemanded"));
        assertTrue(integration.contains("reconcileRealtimeTelemetryAfterRecovery"));
        assertTrue(passengerDecorator.contains(
                "delegate.subscribeRealtimeTelemetry(ids, listener)"));
        assertTrue(passengerDecorator.contains(
                "delegate.unsubscribeRealtimeTelemetry(listener)"));
        assertTrue(panel.contains("publishClusterSurface("));
        assertTrue(panel.contains("revokeClusterSurface("));
        assertFalse(panel.contains("Bitmap.createBitmap"));
        assertTrue(renderer.contains("staticLayerDirty"));
        assertTrue(renderer.contains("Choreographer.FrameCallback"));
        assertTrue(renderer.contains(
                "telemetry.acquire(telemetryListener, config.telemetryMetricIds())"));
        assertTrue(renderer.contains("if (animating) scheduleFrame()"));
        assertTrue(renderer.contains("telemetryWakePosted.compareAndSet(false, true)"));
    }

    @Test public void launcherMirrorsVerifiedDimSequenceAndAutostarts() throws Exception {
        Path root = projectRoot();
        String launcher = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentDisplayLauncher.java"));
        String bootstrap = read(root.resolve(
                "app/src/main/java/dezz/status/widget/AppRuntimeBootstrap.java"));
        String boot = read(root.resolve("app/src/main/java/dezz/status/widget/BootReceiver.java"));
        String manifest = read(root.resolve("app/src/main/AndroidManifest.xml"));
        String store = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentPanelStore.java"));
        String activity = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentPanelActivity.java"));
        String endpoint = read(root.resolve("app/src/main/java/dezz/status/widget/navigation/"
                + "NavigationHudEndpointService.java"));
        String client = read(root.resolve("navigator-mod/src/main/java/ru/natro/navigation/"
                + "NavigationBridgeClient.java"));

        assertTrue(launcher.contains("DIM_NAVIGATION_MODE = 3"));
        assertTrue(launcher.contains("switchNaviMode"));
        assertTrue(launcher.contains("(byte) 2, (byte) 8, (byte) 8"));
        assertTrue(launcher.contains("setLaunchDisplayId(config.displayId)"));
        assertTrue(launcher.contains("setAction(Intent.ACTION_MAIN)"));
        assertTrue(launcher.contains("android.activity.windowingMode"));
        assertTrue(launcher.contains("android.activity.SplitScreenShownPosition"));
        assertTrue(launcher.contains("DIM_WAKE_TO_TASK_RESET_MS = 200L"));
        assertTrue(launcher.contains("finishStalePanelTask"));
        assertTrue(launcher.contains("prepareInstrumentPanelLaunch"));
        assertTrue(launcher.contains("FORCE_STOP_COMMAND"));
        assertTrue(launcher.contains("PrivilegedShell.get(app)"));
        assertTrue(endpoint.contains("CAP_EXTERNAL_INSTRUMENT_LAUNCHER"));
        assertTrue(endpoint.contains("MSG_PREPARE_INSTRUMENT_PANEL_LAUNCH"));
        assertTrue(client.contains("CAP_EXTERNAL_INSTRUMENT_LAUNCHER"));
        assertTrue(client.contains("ActivityOptions.makeBasic()"));
        assertTrue(client.contains("setComponent(new ComponentName(NATRO_PACKAGE"));
        assertFalse(launcher.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"));
        assertFalse(launcher.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        assertFalse(launcher.contains("Intent.FLAG_ACTIVITY_NO_ANIMATION"));
        assertTrue(launcher.contains("MAX_DISPLAY_RETRIES"));
        assertTrue(bootstrap.contains("InstrumentDisplayLauncher.reconcileAutomatic"));
        assertTrue(boot.contains("InstrumentDisplayLauncher.reconcileAutomatic"));
        assertTrue(manifest.contains(".instrument.InstrumentPanelActivity"));
        assertTrue(manifest.contains("android:exported=\"true\""));
        assertTrue(manifest.contains("android:taskAffinity=\"ru.natro.statuswidget.instrument\""));
        assertTrue(manifest.contains("eos_supports_multipages"));
        assertTrue(store.contains("createDeviceProtectedStorageContext"));
        assertTrue(store.contains("issueLaunchToken"));
        assertTrue(store.contains("consumeLaunchToken"));
        assertTrue(store.contains(".commit()"));
        assertTrue(activity.contains("store.consumeLaunchToken("));
        assertTrue(activity.contains("StatusWidgetApplication.notifyFirstUsefulSurface"));
    }

    private static Path projectRoot() {
        return Files.isRegularFile(Paths.get("app", "src", "main", "AndroidManifest.xml"))
                ? Paths.get("") : Paths.get("..");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
