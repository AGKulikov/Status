/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guard rails for the HA1145 fixes requested on the KX11 head unit. */
public final class Ha1145RegressionContractTest {
    @Test public void informationRowGapAndAncsGateReachTheActualRuntimeRow() throws Exception {
        String settings = source("DriverPanelSettingsActivity.java");
        String store = source("launcher/LauncherShortcutStore.java");
        String runtime = source("driver/DriverPanelOverlayController.java");
        String service = source("WidgetService.java");

        assertTrue(settings.contains("Расстояние между информационными иконками"));
        assertTrue(settings.contains("value -> value.informationGroupGapPx = selected"));
        assertTrue(settings.contains("seek.setProgress(0)"));
        assertTrue(settings.contains("Показывать ряд только при подключении ANCS"));
        assertTrue(store.contains("public boolean informationGroupAncsOnly = false"));
        assertTrue(store.contains(".put(\"informationGroupAncsOnly\""));
        assertTrue(runtime.contains("shortcut.informationGroupAncsOnly && !ancsReady"));
        assertTrue(runtime.contains("int internalGap = dp(context,"
                + " rowStyle.informationGroupGapPx)"));
        assertTrue(service.contains("previousAncsReady != phoneAncsReady"));
        assertTrue(service.contains("DriverPanelService.apply(this)"));
    }

    @Test public void cellularBrickOwnsBothOperatorAndSignal() throws Exception {
        String widget = source("WidgetService.java");
        String layout = resource("layout/overlay_status_widget.xml");
        String catalog = source("launcher/information/StatusBarInformationCatalog.java");

        assertTrue(layout.contains("@+id/phoneCellularContainer"));
        assertTrue(layout.contains("@+id/phoneCellularStatusIcon"));
        assertTrue(layout.contains("@+id/phoneCellularOperatorText"));
        assertTrue(widget.contains("phoneText(\"network.operator\")"));
        assertTrue(widget.contains("binding.phoneCellularOperatorText.setText(operator)"));
        assertTrue(widget.contains("signal != null || !operator.isEmpty()"));
        assertTrue(catalog.contains("Оператор и сигнал iPhone"));

        String store = source("launcher/LauncherShortcutStore.java");
        assertTrue(store.contains("migrateCombinedPhoneCellularRows()"));
        assertTrue(store.contains("PHONE_OPERATOR_RESOURCE.equals(binding.resourceId)"));
        assertTrue(store.contains("group.equals(candidate.informationGroup.trim())"));
    }

    @Test public void missingScenarioSourceOpensThePickerInsteadOfLeakingAnIdField()
            throws Exception {
        String scenarios = source("ScenarioSettingsActivity.java");
        assertTrue(scenarios.contains("Выберите источник условия"));
        assertTrue(scenarios.contains("views.showSourcePicker()"));
        assertFalse(scenarios.contains("Укажите ID ресурса"));
    }

    @Test public void ancsEscalationAutomatesTheSuccessfulManualRadioRecovery()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String policy = source("phone/AncsAdapterRecoveryPolicy.java");
        String transport = source("phone/transport/IphoneAncsTransport.java");

        assertTrue(policy.contains("ESCALATION_DELAY_MS = 30_000L"));
        assertTrue(policy.contains("RESET_COOLDOWN_MS = 5L * 60L * 1_000L"));
        assertTrue(controller.contains("ancsWasReadyThisSession"));
        assertTrue(controller.contains("adapter.disable()"));
        assertTrue(controller.contains("adapter.enable()"));
        assertTrue(controller.contains("handleAdapterRecoveryState(token, state)"));
        assertTrue(transport.contains("refreshGattCache(previous)"));
    }

    @Test public void smartHomeNamesAndBluetoothFollowOneRenderedColour() throws Exception {
        String popup = source("popup/PopupOverlayController.java");
        String preview = source("VisualBrickEditorActivity.java");
        String bluetooth = source("phone/PhoneBluetoothIndicatorPolicy.java");
        String widget = source("WidgetService.java");

        assertTrue(popup.contains("SmartHomeTileColorPolicy.contentColor("));
        assertTrue(popup.contains("renderedTitleColor"));
        assertTrue(preview.contains("SmartHomeTileColorPolicy.contentColor("));
        assertTrue(bluetooth.contains("PHONE_MONO"));
        assertFalse(bluetooth.contains("PHONE_OUTLINE"));
        assertFalse(bluetooth.contains("PHONE_SOLID"));
        assertTrue(widget.contains("binding.bluetoothStatusIcon.setOutlineWidth(0)"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("java", "dezz", "status", "widget").resolve(relative));
    }

    private static String resource(String relative) throws Exception {
        return read(Paths.get("res").resolve(relative));
    }

    private static String read(Path relative) throws Exception {
        Path root = Paths.get("app", "src", "main").resolve(relative);
        Path app = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
