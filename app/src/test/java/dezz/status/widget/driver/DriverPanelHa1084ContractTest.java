package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level release contract for HA1084 driver-panel integrations. */
public final class DriverPanelHa1084ContractTest {
    @Test
    public void longPressRecentsFavoritesAndSmartHomeUseOneShortcutModel() throws Exception {
        Path widget = widgetRoot();
        String store = read(widget.resolve("launcher/LauncherShortcutStore.java"));
        String executor = read(widget.resolve("driver/DriverPanelActionExecutor.java"));
        String overlay = read(widget.resolve("driver/DriverPanelOverlayController.java"));
        String settings = read(widget.resolve("DriverPanelSettingsActivity.java"));

        assertTrue(store.contains("RECENTS(\"recents\""));
        assertTrue(store.contains("FAVORITES(\"favorites\""));
        assertTrue(store.contains("driverFavoritesShortcutsJson"));
        assertTrue(store.contains("forDriverFavorites("));
        assertTrue(executor.contains("case RECENTS:"));
        assertTrue(executor.contains("performGlobalRecents("));
        assertTrue(executor.contains("if (!accepted) openRecentsWithShell()"));
        assertTrue(executor.contains("\"input keyevent 187\""));
        assertTrue(executor.contains("boolean executeLong("));
        assertTrue(executor.contains("action.kind = shortcut.longKind"));
        assertTrue(executor.contains("case FAVORITES:"));
        assertTrue(overlay.contains("void showFavorites()"));
        assertTrue(overlay.contains("ShortcutDrawerAdapter"));
        assertTrue(overlay.contains("SmartHomeShortcutStatePolicy.resolveValue("));
        assertTrue(overlay.contains("addConnectorValueListener("));
        assertTrue(settings.contains("actionPicker.showLong(shortcut)"));
        assertTrue(settings.contains("Расширенная информация"));
    }

    @Test
    public void scenariosCanHideOrDisableDriverItems() throws Exception {
        Path widget = widgetRoot();
        String scope = read(widget.resolve("scenario/TargetScope.java"));
        String contract = read(widget.resolve("automation/AutomationContract.java"));
        String editor = read(widget.resolve("ScenarioSettingsActivity.java"));
        String overlay = read(widget.resolve("driver/DriverPanelOverlayController.java"));
        String service = read(widget.resolve("WidgetService.java"));

        assertTrue(scope.contains("DRIVER"));
        assertTrue(contract.contains("SCOPE_DRIVER = \"driver\""));
        assertTrue(editor.contains("\"Кнопка панели водителя\""));
        assertTrue(editor.contains("case DRIVER:"));
        assertTrue(overlay.contains("driverShortcutVisible(shortcut.id, true)"));
        assertTrue(overlay.contains("driverShortcutActionEnabled(shortcut.id, true)"));
        assertTrue(overlay.contains("refreshFavoritesDrawer()"));
        assertTrue(service.contains("effectiveActionEnabled("));
        assertTrue(service.contains("DriverPanelService.apply(this)"));
    }

    @Test
    public void runtimeAllAppsIsSharedAndExcludesSystemPackages() throws Exception {
        Path widget = widgetRoot();
        String catalog = read(widget.resolve("launcher/LauncherAppCatalog.java"));
        String launcher = read(widget.resolve("LauncherActivity.java"));
        String overlay = read(widget.resolve("driver/DriverPanelOverlayController.java"));
        String preferences = read(widget.resolve("Preferences.java"));
        String intentRules = read(widget.resolve("IntentScenarioSettingsActivity.java"));
        String homeIcons = read(widget.resolve("LauncherShortcutSettingsActivity.java"));

        assertTrue(catalog.contains("ApplicationInfo.FLAG_SYSTEM"));
        assertTrue(catalog.contains("ApplicationInfo.FLAG_UPDATED_SYSTEM_APP"));
        assertTrue(catalog.contains("loadVisible("));
        assertTrue(catalog.contains("loadIncludingSystem("));
        assertTrue(launcher.contains("appCatalog.allVisible()"));
        assertTrue(launcher.contains("LauncherAppCatalog.loadIncludingSystem(context)"));
        assertTrue(launcher.contains("if (!app.systemApp"));
        assertTrue(overlay.contains("LauncherAppCatalog.loadVisible("));
        assertTrue(preferences.contains("launcherAllAppsHiddenComponents"));
        assertTrue(preferences.contains("launcherAllAppsIconScalePercent"));
        assertFalse(overlay.contains("PanelElementConfigStore.APPS_GRID"));
        assertTrue(intentRules.contains(
                "LauncherShortcutStore.forDriverPanel(prefs, prefs.driverPanelOld)"));
        assertTrue(intentRules.contains(
                "LauncherShortcutStore.forDriverPanel(prefs, prefs.driverPanelNew)"));
        assertTrue(intentRules.contains("LauncherShortcutStore.forDriverFavorites(prefs)"));
        assertTrue(homeIcons.contains("InstalledAppCatalog.load(this)"));
        assertTrue(homeIcons.contains("app.system"));
    }

    @Test
    public void driverSmartHomeAndRulesKeepAHeadlessIntegrationHost() throws Exception {
        Path widget = widgetRoot();
        String bootstrap = read(widget.resolve("AppRuntimeBootstrap.java"));
        String starter = read(widget.resolve("WidgetServiceStarter.java"));
        String service = read(widget.resolve("WidgetService.java"));
        String receiver = read(widget.resolve(
                "automation/ScenarioTriggerReceiver.java"));
        String intentRules = read(widget.resolve("IntentScenarioSettingsActivity.java"));
        String editor = read(widget.resolve("ScenarioSettingsActivity.java"));

        assertTrue(bootstrap.contains("preferences.widgetEnabled.get()")
                && bootstrap.contains("preferences.driverPanelEnabled.get()"));
        assertTrue(starter.contains("!preferences.driverPanelEnabled.get()"));
        assertTrue(service.contains("else if (prefs.driverPanelEnabled.get())"));
        assertTrue(service.contains("runInitialIntegrationStartup()"));
        assertTrue(service.contains("detachStatusSurfaceRuntime(\"status row disabled\")"));
        assertTrue(service.contains("stopLocationTracking()"));
        assertTrue(service.contains("stopConnectivityTracking()"));
        assertTrue(service.contains("overlayAttachRetryScheduled"));
        assertTrue(service.contains("bluetoothTrackingGeneration"));
        assertTrue(service.contains("binding == null || prefs == null"
                + " || !prefs.widgetEnabled.get()"));
        assertTrue(service.contains("void ensureEnabledRuntime()"));
        assertTrue(service.contains("prefs.driverPanelEnabled.get() && !integrationsStarted"));
        assertTrue(service.contains("overlayAttachAttempts = 0;"));
        assertTrue(service.contains("if (prefs.driverPanelEnabled.get()) {\n"
                + "                ensureEnabledRuntime();"));
        assertTrue(service.contains("|| !Permissions.allPermissionsGranted(this)) return;"));
        assertTrue(service.contains("if (integrationsStarted)"));
        assertTrue(service.contains("applyPopupPreferencesSafely()"));
        assertTrue(bootstrap.contains("runningHost.ensureEnabledRuntime()"));
        assertTrue(receiver.contains("!preferences.driverPanelEnabled.get()"));
        assertTrue(editor.contains("keepDriverActionSupported()"));
        assertTrue(editor.contains("targetScope == TargetScope.DRIVER"
                + " && !isBooleanField(field)"));
        assertTrue(editor.contains("WidgetServiceStarter.startIfNeeded(this)"));
        assertTrue(intentRules.contains("DriverPanelService.apply(this)"));
    }

    @Test
    public void serviceStartCannotBypassPermissionPausedOverlayRetry() throws Exception {
        String service = read(widgetRoot().resolve("WidgetService.java"));

        int onStart = service.indexOf("public int onStartCommand");
        int create = service.indexOf("private void createOverlayView()", onStart);
        assertTrue(onStart >= 0 && create > onStart);
        String onStartBody = service.substring(onStart, create);
        assertTrue(onStartBody.contains("&& !overlayAttachRetryScheduled\n"
                + "                && Permissions.allPermissionsGranted(this)"));

        int createGuardEnd = service.indexOf("// Create the overlay view", create);
        assertTrue(createGuardEnd > create);
        String createGuard = service.substring(create, createGuardEnd);
        assertTrue(createGuard.contains("|| overlayAttachRetryScheduled\n"
                + "                || !Permissions.allPermissionsGranted(this)) return;"));
    }

    private static Path widgetRoot() {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        return Files.isDirectory(fromRoot)
                ? fromRoot : Paths.get("src", "main", "java", "dezz", "status", "widget");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
