package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level release contract for HA1085 driver-panel integrations. */
public final class DriverPanelHa1085ContractTest {
    @Test
    public void longPressRecentsFavoritesAndSmartHomeUseOneShortcutModel() throws Exception {
        Path widget = widgetRoot();
        String store = read(widget.resolve("launcher/LauncherShortcutStore.java"));
        String executor = read(widget.resolve("driver/DriverPanelActionExecutor.java"));
        String overlay = read(widget.resolve("driver/DriverPanelOverlayController.java"));
        String settings = read(widget.resolve("DriverPanelSettingsActivity.java"));

        assertTrue(store.contains("RECENTS(\"recents\""));
        assertTrue(store.contains("FAVORITES(\"favorites\""));
        assertTrue(store.contains("private final Preferences.Str storage;"));
        assertTrue(store.contains("this.storage = storage;"));
        assertTrue(store.contains("forDriverFavorites("));
        assertTrue(executor.contains("case RECENTS:"));
        assertTrue(executor.contains("performGlobalRecents("));
        assertTrue(executor.contains("if (!accepted) openRecentsWithShell()"));
        assertTrue(executor.contains("\"input keyevent 187\""));
        assertTrue(executor.contains("boolean executeLong("));
        assertTrue(executor.contains("action.kind = shortcut.longKind"));
        assertTrue(executor.contains("case FAVORITES:"));
        assertTrue(overlay.contains(
                "void showFavorites(@NonNull String panelId, @Nullable View anchor)"));
        assertTrue(overlay.contains("ShortcutDrawerAdapter"));
        assertTrue(overlay.contains("favoriteWindows"));
        assertTrue(overlay.contains("compactDrawerParams("));
        assertTrue(overlay.contains("SmartHomeShortcutStatePolicy.resolveValue("));
        assertTrue(overlay.contains("addConnectorValueListener("));
        assertTrue(settings.contains("actionPicker.showLong(shortcut)"));
        assertFalse(settings.contains("Подробный климат в обычной ячейке"));
    }

    @Test
    public void favoritesPanelsAreUnlimitedAnchoredConfigurableAndAutomatable()
            throws Exception {
        Path widget = widgetRoot();
        String config = read(widget.resolve("driver/DriverFavoritesPanelConfig.java"));
        String store = read(widget.resolve("driver/DriverFavoritesPanelStore.java"));
        String shortcuts = read(widget.resolve("launcher/LauncherShortcutStore.java"));
        String settings = read(widget.resolve("DriverFavoritesSettingsActivity.java"));
        String overlay = read(widget.resolve("driver/DriverPanelOverlayController.java"));
        String scenarios = read(widget.resolve("ScenarioSettingsActivity.java"));
        String automation = read(widget.resolve("automation/AutomationStateStore.java"));

        assertFalse(store.contains("MAX_PANELS"));
        assertFalse(store.contains("MAX_ITEMS"));
        assertTrue(store.contains("values.add(value);"));
        assertTrue(shortcuts.contains("DRIVER_FAVORITES_TARGET_PREFIX = \"favorites:\""));
        assertTrue(shortcuts.contains("preferences.driverFavoritesShortcuts(panelId)"));
        assertTrue(settings.contains("без ограничения количества"));
        assertTrue(settings.contains("Видимые строки"));
        assertTrue(settings.contains("Показывать границы ячеек"));
        assertTrue(settings.contains("Цвет границы ячеек"));
        assertTrue(config.contains("visibleRows"));
        assertTrue(config.contains("borderEnabled"));
        assertTrue(config.contains("borderWidthPx"));
        assertTrue(config.contains("borderColor"));
        assertTrue(shortcuts.contains("closeFavoritePanelAfterAction"));
        assertTrue(shortcuts.contains(
                "json.optBoolean(\"closeFavoritePanelAfterAction\", false)"));
        assertTrue(settings.contains("Закрывать панель после нажатия"));

        assertTrue(overlay.contains("favoriteWindows = new LinkedHashMap<>()"));
        assertTrue(overlay.contains("anchor.getLocationOnScreen(location)"));
        assertTrue(overlay.contains("anchorCenterY - height / 2"));
        assertTrue(overlay.contains("panelOnRight ? panelX - width : panelX + physicalWidth"));
        assertTrue(overlay.contains(
                "favoritePanelBackground(context, profile, panelOnRight)"));
        assertTrue(overlay.contains("config.visibleRows * config.cellSizePx"));
        assertTrue(overlay.contains("config.borderEnabled && config.borderWidthPx > 0"));
        assertTrue(overlay.contains("if (shortcut.closeFavoritePanelAfterAction)"));

        assertTrue(scenarios.contains("result.add(new TargetOption(panel.id,"));
        assertTrue(scenarios.contains("\"Панель избранного · \" + panel.title"));
        assertTrue(automation.contains("explicitVisibility(String scope, String id)"));
        assertTrue(automation.contains("\"_visible_explicit\""));
    }

    @Test
    public void readOnlyInformationAndDividersAreUnlimitedAndNeverTruncateState()
            throws Exception {
        Path widget = widgetRoot();
        String store = read(widget.resolve("launcher/LauncherShortcutStore.java"));
        String information = read(widget.resolve("launcher/InformationShortcutView.java"));
        String policy = read(widget.resolve("launcher/SmartHomeShortcutStatePolicy.java"));
        String infoView = read(widget.resolve(
                "launcher/information/InformationPanelView.java"));
        String picker = read(widget.resolve(
                "launcher/information/InformationSourcePicker.java"));
        String overlay = read(widget.resolve("driver/DriverPanelOverlayController.java"));

        assertTrue(store.contains("INFO, DIVIDER"));
        assertTrue(store.contains(
                "value.kind != Kind.INFO && value.kind != Kind.DIVIDER"));
        assertTrue(store.contains("interactiveCount(shortcuts)"));
        assertTrue(information.contains("setClickable(false)"));
        assertTrue(information.contains("content.start()"));
        assertTrue(information.contains("content.stop()"));
        assertTrue(information.contains(
                "content.setFixedCellBackgroundColor(shortcut.backgroundColor)"));
        assertTrue(overlay.contains("List<LauncherShortcutStore.Shortcut> topInformation"));
        assertTrue(overlay.contains("List<LauncherShortcutStore.Shortcut> bottomInformation"));
        assertTrue(overlay.contains("? bottomInformation : topInformation).add(shortcut)"));
        assertTrue(overlay.contains("new InformationShortcutView("));
        assertTrue(settings(widget).contains("Показывать значок слева"));
        assertTrue(infoView.contains("value.setSingleLine(phoneCellular)"));
        assertTrue(infoView.contains(
                "value.setMaxLines(phoneCellular ? 1 : Integer.MAX_VALUE)"));
        assertTrue(picker.contains("\"system.bluetooth\""));
        assertTrue(picker.contains("\"system.wifi\""));
        assertTrue(infoView.contains("private Value resolveBluetooth()"));
        assertTrue(infoView.contains("private Value resolveWifi()"));
        assertTrue(policy.contains("private static String complete(String text)"));
        assertFalse(policy.contains("substring(0, 47)"));
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
        assertTrue(editor.contains("\"Панель / кнопка водителя\""));
        assertTrue(editor.contains("case DRIVER:"));
        assertTrue(overlay.contains("driverShortcutVisible(shortcut.id, true)"));
        assertTrue(overlay.contains("driverShortcutActionEnabled(shortcut.id, true)"));
        assertTrue(overlay.contains("refreshFavoriteWindows()"));
        assertTrue(overlay.contains("reconcileAutomatedFavoritePanels()"));
        assertTrue(service.contains("driverFavoritePanelVisibility("));
        assertTrue(service.contains("effectiveActionEnabled("));
        assertTrue(service.contains("DriverPanelService.apply(this)"));
    }

    @Test
    public void runtimeAllAppsIsSharedAndIncludesLaunchableSystemPackages() throws Exception {
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
        assertFalse(launcher.contains("if (!app.systemApp"));
        assertTrue(overlay.contains("LauncherAppCatalog.loadVisible("));
        assertTrue(catalog.contains("ensureDefaultSystemVisibility(context, preferences, catalog)"));
        assertTrue(catalog.contains("launcherSystemAppsDefaultApplied"));
        assertTrue(catalog.contains("isUserFacingPhone(app, defaultDialer)"));
        assertTrue(preferences.contains("launcherAllAppsHiddenComponents"));
        assertTrue(preferences.contains("launcherAllAppsIconScalePercent"));
        assertFalse(overlay.contains("PanelElementConfigStore.APPS_GRID"));
        assertTrue(intentRules.contains("prefs.activeDriverPanelProfile()"));
        assertTrue(intentRules.contains(
                "new dezz.status.widget.driver.DriverFavoritesPanelStore(prefs).load()"));
        assertTrue(intentRules.contains(
                "LauncherShortcutStore.forDriverFavorites(prefs, panel.id)"));
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

        assertTrue(bootstrap.contains(
                "WidgetServiceStarter.requiresIntegrationHost(preferences)"));
        assertTrue(bootstrap.contains(
                "WidgetServiceStarter.requiresHeadlessHost(preferences)"));
        assertTrue(starter.contains("preferences.driverPanelEnabled.get()"));
        assertTrue(starter.contains("preferences.phoneConnectorEnabled.get()"));
        assertTrue(service.contains("else if (headlessHostRequired)"));
        assertTrue(service.contains("runInitialIntegrationStartup()"));
        assertTrue(service.contains("detachStatusSurfaceRuntime(\"status row disabled\")"));
        assertTrue(service.contains("stopLocationTracking()"));
        assertTrue(service.contains("stopConnectivityTracking()"));
        assertTrue(service.contains("overlayAttachRetryScheduled"));
        assertTrue(service.contains("bluetoothTrackingGeneration"));
        assertTrue(service.contains("binding == null || prefs == null"
                + " || !prefs.widgetEnabled.get()"));
        assertTrue(service.contains("void ensureEnabledRuntime()"));
        assertTrue(service.contains(
                "WidgetServiceStarter.requiresHeadlessHost(prefs)\n"
                        + "                && !integrationsStarted"));
        assertTrue(service.contains("overlayAttachAttempts = 0;"));
        assertTrue(service.contains(
                "if (WidgetServiceStarter.requiresHeadlessHost(prefs)) {\n"
                        + "                ensureEnabledRuntime();"));
        assertTrue(service.contains("|| !Permissions.allPermissionsGranted(this)) return;"));
        assertTrue(service.contains("if (integrationsStarted)"));
        assertTrue(service.contains("applyPopupPreferencesSafely()"));
        assertTrue(bootstrap.contains("runningHost.ensureEnabledRuntime()"));
        assertTrue(receiver.contains("!preferences.driverPanelEnabled.get()"));
        assertTrue(editor.contains("keepDriverActionSupported()"));
        assertTrue(editor.contains("targetScope == TargetScope.DRIVER"));
        assertTrue(editor.contains("!isDriverFieldSupported(selectedTargetId, field)"));
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

    private static String settings(Path widget) throws Exception {
        return read(widget.resolve("DriverPanelSettingsActivity.java"));
    }
}
