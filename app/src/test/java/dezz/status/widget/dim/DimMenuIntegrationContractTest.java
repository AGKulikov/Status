/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract for the optional ECARX-backed driver-display menu. */
public final class DimMenuIntegrationContractTest {
    @Test public void steeringBridgeAndOverlayStayOptionalAndTouchFree() throws Exception {
        Path root = projectRoot();
        String vendor = read(root.resolve("app/src/main/java/dezz/status/widget/dim/"
                + "DimMenuVendorBridge.java"));
        String window = read(root.resolve("app/src/main/java/dezz/status/widget/dim/"
                + "DimMenuOverlayWindow.java"));
        String controller = read(root.resolve("app/src/main/java/dezz/status/widget/dim/"
                + "DimMenuOverlayController.java"));
        String panel = read(root.resolve("app/src/main/java/dezz/status/widget/dim/"
                + "DimMenuPanelView.java"));

        assertTrue(vendor.contains("ECARX_KEY_DIMSCROLLUP_EVENT"));
        assertTrue(vendor.contains("ECARX_KEY_DIMSCROLLDOWN_EVENT"));
        assertTrue(vendor.contains("ECARX_KEY_DIMCONFIRM_EVENT"));
        assertTrue(vendor.contains("getDimMenuInteraction"));
        assertTrue(vendor.contains("RETRY_DELAYS_MS"));
        assertTrue(window.contains("createDisplayContext(display)"));
        assertTrue(window.contains("FLAG_NOT_TOUCHABLE"));
        assertTrue(window.contains("TYPE_APPLICATION_OVERLAY"));
        assertTrue(controller.contains("DimMenuConflictPolicy.reason"));
        assertFalse(controller.contains("InstrumentPanelActivity.isActive()"));
        assertTrue(controller.contains("getForegroundPackageOnDisplay(config.displayId)"));
        assertTrue(controller.contains("UsageEvents.Event.MOVE_TO_FOREGROUND"));
        assertTrue(controller.contains("addConnectorValueListener(smartHomeValueListener)"));
        assertTrue(controller.contains("SmartHomeShortcutStatePolicy.resolveValue("));
        assertTrue(controller.contains("state.present ? state.valueLabel : \"Нет данных\""));
        assertTrue(panel.contains("statuses.get(item.id)"));
        assertTrue(panel.contains("statusLeft - contentLeft"));
        assertTrue(panel.contains("config.mnaviStyle"));
        assertTrue(panel.contains("dp(6)"));
        assertTrue(panel.contains("MNAVI_BACKGROUND = 0x00FFFFFF"));
        assertTrue(panel.contains("PorterDuff.Mode.SRC"));
        assertTrue(panel.contains("MNAVI_SELECTED = 0xFF197BC5"));
        assertTrue(panel.contains("MNAVI_MUTED_TEXT = 0xFF6C7984"));
    }

    @Test public void settingsAndRuntimeSupportRoutesHomeCallsAndBootRestore() throws Exception {
        Path root = projectRoot();
        String settings = read(root.resolve("app/src/main/java/dezz/status/widget/"
                + "DimMenuPanelSettingsActivity.java"));
        String executor = read(root.resolve("app/src/main/java/dezz/status/widget/driver/"
                + "DriverPanelActionExecutor.java"));
        String launcher = read(root.resolve("app/src/main/java/dezz/status/widget/"
                + "LauncherActivity.java"));
        String launcherStore = read(root.resolve("app/src/main/java/dezz/status/widget/launcher/"
                + "LauncherShortcutStore.java"));
        String boot = read(root.resolve("app/src/main/java/dezz/status/widget/BootReceiver.java"));
        String manifest = read(root.resolve("app/src/main/AndroidManifest.xml"));

        assertTrue(settings.contains("маршруты, приложения, функции автомобиля, умный дом"));
        assertTrue(settings.contains("Размер заголовка"));
        assertTrue(settings.contains("Внутренний отступ"));
        assertTrue(settings.contains("Оформление списка как в mNavi"));
        assertTrue(settings.contains("#6C7984"));
        assertTrue(settings.contains("#197BC5"));
        assertTrue(settings.contains("Параметры ниже применяются только после отключения"));
        assertTrue(settings.contains("Меню открывается поверх панели приборов Natro"));
        assertTrue(launcherStore.contains("Kind { APP, BUILTIN, RULE, PHONE"));
        assertTrue(executor.contains("Intent.ACTION_CALL : Intent.ACTION_DIAL"));
        assertTrue(launcher.contains("shortcut.kind == LauncherShortcutStore.Kind.PHONE"));
        assertTrue(launcher.contains("executePhoneCall(shortcut.target)"));
        assertTrue(boot.contains("DimMenuPanelService.reconcileAutomatic"));
        assertTrue(manifest.contains(".DimMenuPanelSettingsActivity"));
        assertTrue(manifest.contains(".dim.DimMenuPanelService"));
    }

    private static Path projectRoot() {
        return Files.isRegularFile(Paths.get("app", "src", "main", "AndroidManifest.xml"))
                ? Paths.get("") : Paths.get("..");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
