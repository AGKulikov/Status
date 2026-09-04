/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class InstrumentPanelContractTest {
    @Test public void nativeDashboardRoundTripsEveryEditableField() throws Exception {
        InstrumentPanelConfig config = InstrumentPanelConfig.defaults();
        config.displayId = 2;
        config.transparentBackground = true;
        config.backgroundBottomColor = "#FF25384D";
        config.blackZonePercent = 61;
        config.defaultStyle = InstrumentStyleFamily.AEROWAVE;
        InstrumentElementConfig first = config.elements.get(0);
        first.responseMillis = 82;
        first.opacityPercent = 77;
        first.style = InstrumentStyleFamily.CONTINUUM;
        first.options.put("showValue", false);

        InstrumentPanelConfig restored = InstrumentPanelConfig.fromJson(config.toJson());

        assertEquals(1920, InstrumentPanelConfig.DESIGN_WIDTH);
        assertEquals(720, InstrumentPanelConfig.DESIGN_HEIGHT);
        assertEquals(2, restored.displayId);
        assertTrue(restored.transparentBackground);
        assertEquals("#FF25384D", restored.backgroundBottomColor);
        assertEquals(61, restored.blackZonePercent);
        assertEquals(InstrumentStyleFamily.AEROWAVE, restored.defaultStyle);
        assertEquals(config.elements.size(), restored.elements.size());
        assertEquals(82, restored.elements.get(0).responseMillis);
        assertEquals(77, restored.elements.get(0).opacityPercent);
        assertEquals(InstrumentStyleFamily.CONTINUUM,
                restored.elements.get(0).style);
        assertFalse(restored.elements.get(0).options.optBoolean("showValue", true));
        assertNotNull(restored.elements.stream()
                .filter(value -> value.type == InstrumentElementType.NAV_MAP)
                .findFirst().orElse(null));
        assertEquals(5, InstrumentStyleFamily.values().length);
        assertEquals(5, InstrumentPanelPreset.values().length);
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
        assertTrue(InstrumentElementType.TRAFFIC_JAM.usesNavigationState());

        config.backgroundBottomColor = "not-a-color";
        config.normalize();
        assertEquals("#FF16283D", config.backgroundBottomColor);
    }

    @Test public void fiveApprovedPresetsAreModularAndOldSchemaMigrates() throws Exception {
        for (InstrumentPanelPreset preset : InstrumentPanelPreset.values()) {
            InstrumentPanelConfig config = preset.create();
            assertEquals(preset.id, config.presetId);
            assertNotNull(config.elements.stream()
                    .filter(value -> value.type == InstrumentElementType.NAV_MAP)
                    .findFirst().orElse(null));
            assertNotNull(config.elements.stream()
                    .filter(value -> value.type == InstrumentElementType.NAVIGATION_INFO)
                    .findFirst().orElse(null));
            assertNotNull(config.elements.stream()
                    .filter(value -> value.type == InstrumentElementType.INFO_BLOCK)
                    .findFirst().orElse(null));
        }

        InstrumentPanelConfig migrated = InstrumentPanelConfig.fromJson(new JSONObject()
                .put("schema", 1).put("displayId", 7)
                .put("defaultStyle", "M_SPORT_ARCS"));
        assertEquals(7, migrated.displayId);
        assertEquals(InstrumentPanelPreset.SLATE_HORIZON.id, migrated.presetId);
        assertEquals(InstrumentStyleFamily.SLATE_HORIZON, migrated.defaultStyle);
    }

    @Test public void fiveApprovedPresetsKeepTheirReferenceSilhouettes() throws Exception {
        InstrumentPanelConfig slate = InstrumentPanelPreset.SLATE_HORIZON.create();
        assertEquals(6, element(slate, "map").x);
        assertEquals(36, element(slate, "map").width);
        assertFalse(element(slate, "tachometer").options.optBoolean("showValue", true));
        assertTrue(element(slate, "speedometer").options.optBoolean("showValue", false));

        InstrumentPanelConfig glacier = InstrumentPanelPreset.GLACIER_MAP.create();
        assertEquals("HORIZONTAL_RULER",
                element(glacier, "tachometer").options.optString("presentation"));
        assertEquals(44, element(glacier, "map").width);

        InstrumentPanelConfig aerowave = InstrumentPanelPreset.AEROWAVE.create();
        assertEquals(135d, element(aerowave, "tachometer").options
                .optDouble("arcStartDegrees"), 0d);
        assertEquals(225d, element(aerowave, "speedometer").options
                .optDouble("arcStartDegrees"), 0d);
        assertFalse(element(aerowave, "tachometer").options
                .optBoolean("showNeedle", true));
        assertFalse(element(aerowave, "speedometer").options
                .optBoolean("showNeedle", true));

        InstrumentPanelConfig steel = InstrumentPanelPreset.STEEL_VECTOR.create();
        assertEquals("VERTICAL_RULER",
                element(steel, "tachometer").options.optString("presentation"));
        assertTrue(element(steel, "tachometer").height
                > element(steel, "tachometer").width);

        InstrumentPanelConfig continuum = InstrumentPanelPreset.CONTINUUM.create();
        assertFalse(element(continuum, "tachometer").options
                .optBoolean("showNeedle", true));
        assertFalse(element(continuum, "tachometer").options
                .optBoolean("showValue", true));
        assertFalse(element(continuum, "information").enabled);
        assertTrue(element(continuum, "information").x
                <= element(continuum, "speedometer").x);
    }

    @Test public void generic257PresetMigratesOnceToApprovedGeometry() throws Exception {
        InstrumentPanelConfig old = InstrumentPanelPreset.STEEL_VECTOR.create();
        old.presetLayoutRevision = 1;
        InstrumentElementConfig oldMap = element(old, "map");
        oldMap.x = 13;
        oldMap.width = 22;
        element(old, "speedometer").enabled = false;
        InstrumentElementConfig customNavigation = element(old, "navigation");
        customNavigation.x = 31;
        customNavigation.y = 2;
        customNavigation.zIndex = 71;
        JSONObject raw = old.toJson().put("presetLayoutRevision", 1);

        InstrumentPanelConfig upgraded = InstrumentPanelConfig.fromJson(raw);

        assertEquals(InstrumentPanelPreset.LAYOUT_REVISION,
                upgraded.presetLayoutRevision);
        assertEquals(14, element(upgraded, "map").x);
        assertEquals(18, element(upgraded, "map").width);
        assertFalse(element(upgraded, "speedometer").enabled);
        assertEquals("VERTICAL_RULER",
                element(upgraded, "tachometer").options.optString("presentation"));
        assertEquals(31, element(upgraded, "navigation").x);
        assertEquals(2, element(upgraded, "navigation").y);
        assertEquals(71, element(upgraded, "navigation").zIndex);
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
        String activity = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentPanelActivity.java"));
        String endpoint = read(root.resolve("app/src/main/java/dezz/status/widget/navigation/"
                + "NavigationHudEndpointService.java"));
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
        assertTrue(panel.contains("replaceLeaseIfReady()"));
        assertTrue(panel.contains("Keep the producer lease until the View/Surface"));
        assertFalse(panel.contains("else revokeLease();"));
        assertTrue(panel.contains("ensureClusterEndpointStarted(getContext())"));
        assertTrue(panel.contains("scheduleColdLeaseRetry()"));
        assertTrue(panel.contains("COLD_LEASE_FAST_RETRY_MS = 150L"));
        assertTrue(panel.contains("COLD_LEASE_SLOW_RETRY_MS = 1_000L"));
        assertFalse(panel.contains("|| !attached || !windowVisible"));
        assertTrue(activity.contains("ensureClusterEndpointStarted(this)"));
        assertTrue(endpoint.contains("ACTION_KEEP_CLUSTER_ENDPOINT"));
        assertTrue(endpoint.contains("stopClusterEndpointIfIdle()"));
        assertFalse(panel.contains("Bitmap.createBitmap"));
        assertTrue(renderer.contains("staticLayerDirty"));
        assertTrue(renderer.contains("Choreographer.FrameCallback"));
        assertTrue(renderer.contains(
                "telemetry.acquire(telemetryListener, config.telemetryMetricIds())"));
        assertTrue(renderer.contains("if (animating) scheduleFrame()"));
        assertTrue(renderer.contains("telemetryWakePosted.compareAndSet(false, true)"));
    }

    @Test public void editorExposesPresetsModulesAndIndependentGradient() throws Exception {
        Path root = projectRoot();
        String settings = read(root.resolve("app/src/main/java/dezz/status/widget/"
                + "InstrumentPanelSettingsActivity.java"));
        String renderer = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentClusterView.java"));

        assertTrue(settings.contains("5 вариантов"));
        assertTrue(settings.contains("Модули"));
        assertTrue(settings.contains("Фон"));
        assertTrue(settings.contains("setMultiChoiceItems"));
        assertTrue(settings.contains("Цифровое значение внутри"));
        assertTrue(settings.contains("Нижний цвет градиента"));
        assertTrue(settings.contains("Чисто чёрная зона"));
        assertTrue(settings.contains("store.switchPreset(presets[which], config)"));
        String store = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentPanelStore.java"));
        assertTrue(store.contains("KEY_ACTIVE_PRESET"));
        assertTrue(store.contains("KEY_PRESET_PREFIX + preset.id"));
        assertTrue(store.contains("save(current)"));
        assertTrue(store.contains("readConfig(profileKey(target))"));
        assertTrue(renderer.contains("new LinearGradient("));
        assertTrue(renderer.contains("config.backgroundBottomColor"));
        assertTrue(renderer.contains("config.blackZonePercent"));
        assertTrue(renderer.contains("NavigationBridgeStateStore.addListener"));
        assertTrue(renderer.contains("if (navigation == null) return"));
    }

    @Test public void trafficJamIsASeparateAutoHidingCanvasModule() throws Exception {
        InstrumentElementConfig value = new InstrumentElementConfig(
                "traffic_jam", InstrumentElementType.TRAFFIC_JAM,
                InstrumentStyleFamily.SLATE_HORIZON);
        value.options.put("faceColor", "#FF123456");
        value.options.put("textSizeSp", 41);
        value.normalize(48, 18);
        InstrumentElementConfig restored = InstrumentElementConfig.fromJson(
                value.toJson(), 48, 18);

        assertEquals(InstrumentElementType.TRAFFIC_JAM, restored.type);
        assertEquals("#FF123456", restored.options.optString("faceColor"));
        assertEquals(41, restored.options.optInt("textSizeSp"));

        Path root = projectRoot();
        String renderer = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentClusterView.java"));
        String settings = read(root.resolve("app/src/main/java/dezz/status/widget/"
                + "InstrumentPanelSettingsActivity.java"));
        assertTrue(renderer.contains("drawTrafficJamForecast"));
        assertTrue(renderer.contains("navigation.trafficJamDurationSeconds >= 0"));
        assertTrue(renderer.contains("if (!available && !editorMode) return"));
        assertTrue(renderer.contains("Paint.SUBPIXEL_TEXT_FLAG"));
        assertTrue(settings.contains("TrafficJamControls"));
        assertTrue(settings.contains("прогноз текущей пробки"));
    }

    @Test public void routeSummaryUsesTheOriginalSignAndHasPerElementLayoutControls()
            throws Exception {
        Path root = projectRoot();
        String settings = read(root.resolve("app/src/main/java/dezz/status/widget/"
                + "InstrumentPanelSettingsActivity.java"));
        String renderer = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentClusterView.java"));
        String preset = read(root.resolve("app/src/main/java/dezz/status/widget/instrument/"
                + "InstrumentPanelPreset.java"));
        String repository = read(root.resolve("app/src/main/java/dezz/status/widget/launcher/"
                + "NavigationDataRepository.java"));

        assertTrue(repository.contains("readFreshManeuverImage"));
        assertTrue(renderer.contains("navigationManeuverImage"));
        assertTrue(renderer.contains("NavigationDataRepository.ACTION_UPDATED"));
        assertTrue(renderer.contains("navigationGraphicReceiver"));
        assertTrue(renderer.contains("drawSourceBitmap(canvas, navigationManeuverImage"));
        assertFalse(renderer.contains("drawManeuverIcon("));
        assertTrue(settings.contains("Исходный знак Навигатора"));
        assertTrue(settings.contains("ScrollView scroll = new ScrollView(this)"));
        assertTrue(settings.contains("Размер исходного знака"));
        assertTrue(settings.contains("Оставшееся расстояние"));
        assertTrue(settings.contains("Время прибытия"));
        assertTrue(settings.contains("Оставшееся время"));
        assertTrue(settings.contains("Карточка и внутренние отступы"));
        assertTrue(preset.contains("distanceTextSizeSp"));
        assertTrue(preset.contains("arrivalTextSizeSp"));
        assertTrue(preset.contains("durationTextSizeSp"));
        assertTrue(preset.contains("contentPaddingLeftPx"));
        assertTrue(preset.contains("maneuverIconPaddingLeftPx"));
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
        assertTrue(launcher.contains("ensureClusterEndpointStarted(app)"));
        assertTrue(launcher.contains("MAX_NAVIGATOR_READY_RETRIES = 60"));
        assertTrue(launcher.contains("NAVIGATOR_READY_RETRY_MS = 500L"));
        assertTrue(launcher.contains("onExternalLaunchPrepared("));
        assertTrue(launcher.contains("waiting for Navigator DIM launcher"));
        assertTrue(launcher.contains(
                "Navigator external launcher unavailable after bounded readiness wait"));
        assertTrue(launcher.indexOf("ensureClusterEndpointStarted(app)")
                < launcher.indexOf("LAUNCH_PENDING.compareAndSet(false, true)"));
        assertTrue(launcher.contains("FORCE_STOP_COMMAND"));
        assertTrue(launcher.contains("PrivilegedShell.get(app)"));
        assertTrue(endpoint.contains("CAP_EXTERNAL_INSTRUMENT_LAUNCHER"));
        assertTrue(endpoint.contains("MSG_PREPARE_INSTRUMENT_PANEL_LAUNCH"));
        assertTrue(client.contains("CAP_EXTERNAL_INSTRUMENT_LAUNCHER"));
        assertTrue(client.contains("ActivityOptions.makeBasic()"));
        assertTrue(client.contains("setComponent(new ComponentName(NATRO_PACKAGE"));
        assertTrue(client.contains("reconnectAfterInstrumentLaunch"));
        assertTrue(client.contains("binder.isBinderAlive() && binder.pingBinder()"));
        assertTrue(client.contains("retryMs = MIN_RETRY_MS"));
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

    private static InstrumentElementConfig element(InstrumentPanelConfig config, String id) {
        return config.elements.stream().filter(value -> id.equals(value.id))
                .findFirst().orElseThrow(() -> new AssertionError("Missing element " + id));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
