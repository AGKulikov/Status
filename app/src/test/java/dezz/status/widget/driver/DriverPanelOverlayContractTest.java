package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DriverPanelOverlayContractTest {
    @Test
    public void productionRailIsContinuousAndClimateTapPassesThroughInput() throws Exception {
        String root = System.getProperty("user.dir");
        Path controller = Paths.get(root, "src/main/java/dezz/status/widget/driver/"
                + "DriverPanelOverlayController.java");
        if (!Files.exists(controller)) {
            controller = Paths.get(root, "app/src/main/java/dezz/status/widget/driver/"
                    + "DriverPanelOverlayController.java");
        }
        String source = read(controller);
        assertTrue(source.contains("interactiveCount,"));
        assertTrue(source.contains("false);"));
        assertTrue(source.contains("setPanelTouchable(false)"));
        assertTrue(source.contains("FLAG_NOT_TOUCHABLE"));
        assertTrue(source.contains("performTap(target.x, target.y"));
        assertTrue(source.contains("\"input tap \" + target.x + \" \" + target.y"));
        assertFalse(source.contains("shortcuts.subList"));
        assertTrue(source.contains("windowContext(display, attachedType)"));
        assertFalse(source.contains("fullScreenParams(attachedType)"));
        assertTrue(source.contains("metrics.widthPixels - physicalWidth"));
        assertTrue(source.contains("int drawerLeft = profile.side.get() == 0 ? physicalWidth : 0"));
        assertTrue(source.contains("compactDrawerParams("));
        assertTrue(source.contains("allAppsOverlayParams("));
        assertTrue(source.contains("root.setOnClickListener(view -> dismissAllApps())"));
        assertTrue(source.contains("root.setPadding(0, geometry.contentTop, 0,"));
        assertTrue(source.contains("screenHeight - geometry.contentBottom"));
        assertTrue(source.contains("width, Math.max(1, screenHeight), type"));
        assertTrue(source.contains("params.gravity = Gravity.TOP | Gravity.LEFT;"));
        assertTrue(source.contains("DriverPanelLayoutPolicy.panelWindowX("));
        assertTrue(source.contains("params.y = 0;"));
        assertTrue(source.contains("safeOpaqueColor(profile.backgroundColor.get()"));
        assertTrue(source.contains("safeColor(raw, fallback) | 0xFF000000"));
        assertTrue(source.contains("Color.argb(0x33, 255, 255, 255)"));
        assertTrue(source.contains("background.setCornerRadii(panelCornerRadii("));
        assertTrue(source.contains("Math.max(dp(context, 20), profile.cornerRadiusPx.get())"));
        assertTrue(source.contains("DriverPanelLayoutPolicy.referencePanelWidth("));
        assertTrue(source.contains(
                "geometry.contentBottom - geometry.contentTop"));
        int fallbackStart = source.indexOf("private void fallbackStockClimateTap");
        int fallbackEnd = source.indexOf("private void dismissAllApps", fallbackStart);
        assertTrue(fallbackStart >= 0);
        assertTrue(fallbackEnd > fallbackStart);
        String fallbackSource = source.substring(fallbackStart, fallbackEnd);
        assertTrue(fallbackSource.contains("setPanelTouchable(true)"));
        assertFalse(fallbackSource.contains("detachPanel()"));
        assertFalse(fallbackSource.contains("applyPreferences()"));
        assertTrue(source.contains("PROXY_TAP_SETTLE_MS = 70L"));
        assertTrue(source.contains("}, PROXY_TAP_SETTLE_MS);"));
        assertTrue(source.contains("PROXY_TAP_WATCHDOG_MS = 15_000L"));
        assertTrue(source.contains(
                "WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED"));

        Path executor = controller.resolveSibling("DriverPanelActionExecutor.java");
        String executorSource = read(executor);
        assertTrue(executorSource.contains("case STOCK_CLIMATE:"));
        assertTrue(executorSource.contains("host.triggerStockClimate();"));
        assertFalse(executorSource.contains("ClimatePanelService"));

        Path climateView = controller.resolveSibling("DriverClimateShortcutView.java");
        String climateSource = read(climateView);
        assertTrue(climateSource.contains("fanKnown && fanActive"));
        assertTrue(climateSource.contains("if (!showFan) return;"));
        assertFalse(climateSource.contains("drawFanGlyph"));
        assertFalse(climateSource.contains("drawText(\"AUTO\""));
        assertTrue(climateSource.contains("drawBars(canvas, width * .12f"));
        assertTrue(climateSource.contains("Standard seated-person airflow pictogram"));
        assertTrue(climateSource.contains("AIRFLOW = \"climate.airflow\""));
        assertTrue(climateSource.contains("boolean expanded = detailed;"));
        assertTrue(climateSource.contains("primarySize * .58f"));
        assertTrue(climateSource.contains("AIRFLOW_WINDSHIELD"));
        assertTrue(climateSource.contains("AIRFLOW_FACE"));
        assertTrue(climateSource.contains("AIRFLOW_LEGS"));
        assertTrue(source.contains("private static boolean isExpandedClimate("));
        assertTrue(source.contains("// HA1085 keeps the detailed climate tile"));
        assertTrue(source.contains("DriverPanelLayoutPolicy.shortcutWeight(expandedClimate)"));
        assertTrue(source.contains("DriverPanelLayoutPolicy.shortcutIconHeight("));
        assertTrue(source.contains("button.setSoundEffectsEnabled(false)"));
    }

    @Test
    public void vendorWindowTypesFollowMonjaroOrderWithoutAospRangeFilter()
            throws Exception {
        String root = System.getProperty("user.dir");
        Path policy = Paths.get(root, "src/main/java/dezz/status/widget/driver/"
                + "DriverPanelWindowTypePolicy.java");
        if (!Files.exists(policy)) {
            policy = Paths.get(root, "app/src/main/java/dezz/status/widget/driver/"
                    + "DriverPanelWindowTypePolicy.java");
        }
        String source = read(policy);
        int codeNavigation = source.indexOf("\"TYPE_CODE_NAVIGATION_BAR\"");
        int navigation = source.indexOf("\"TYPE_NAVIGATION_BAR\"");
        int codeStatus = source.indexOf("\"TYPE_CODE_STATUS_BAR\"");
        int fallback = source.indexOf("values.add("
                + "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)");
        assertTrue(codeNavigation >= 0);
        assertTrue(codeNavigation < navigation);
        assertTrue(navigation < codeStatus);
        assertTrue(codeStatus < fallback);
        assertTrue(source.contains("if (value != 0) values.add(value);"));
        assertFalse(source.contains("FIRST_SYSTEM_WINDOW"));
        assertFalse(source.contains("LAST_SYSTEM_WINDOW"));
    }

    @Test
    public void legacyProfileMigratesIntoOneCurrentDriverPanel() throws Exception {
        String root = System.getProperty("user.dir");
        Path preferences = Paths.get(root, "src/main/java/dezz/status/widget/Preferences.java");
        if (!Files.exists(preferences)) {
            preferences = Paths.get(root,
                    "app/src/main/java/dezz/status/widget/Preferences.java");
        }
        String preferencesSource = read(preferences);
        assertTrue(preferencesSource.contains("\"driverPanelStyle\""));
        assertTrue(preferencesSource.contains("DriverPanelStyle.OLD.key"));
        assertTrue(preferencesSource.contains(
                "this, DriverPanelStyle.OLD, \"driverPanel\", 120"));
        assertTrue(preferencesSource.contains(
                "this, DriverPanelStyle.NEW, \"driverPanelNew\", 150"));
        assertTrue(preferencesSource.contains(
                "prefix + \"ItemGapPx\", 10"));
        assertTrue(preferencesSource.contains(
                "prefix + \"CornerRadiusPx\", 20"));
        assertTrue(preferencesSource.contains(
                "prefix + \"BackgroundColor\", \"#FF13171C\""));
        assertTrue(preferencesSource.contains(
                "public final Int driverPanelSide = driverPanelNew.side;"));
        assertTrue(preferencesSource.contains(
                "public final Str driverPanelShortcutsJson = driverPanelNew.shortcutsJson;"));
        assertTrue(preferencesSource.contains(
                "return driverPanelNew;"));
        assertTrue(preferencesSource.contains("migrateUnifiedDriverPanelIfNeeded()"));
        assertTrue(preferencesSource.contains("\"driverPanelUnifiedHa1085\""));
        assertTrue(preferencesSource.contains(
                "// A full backup made by HA1084 may still select the legacy driver profile"));

        Path settings = preferences.resolveSibling("DriverPanelSettingsActivity.java");
        String settingsSource = read(settings);
        assertFalse(settingsSource.contains("MaterialButtonToggleGroup"));
        assertFalse(settingsSource.contains("compactButton(\"Старая\")"));
        assertFalse(settingsSource.contains(
                "preferences.driverPanelStyle.set("));
        assertTrue(settingsSource.contains("AppleColorPickerDialog.Options.opaque()"));
        assertFalse(settingsSource.contains("\"Цвет и прозрачность панели\""));
        assertTrue(settingsSource.contains(
                "profile.backgroundColor.get(),"));
        assertTrue(settingsSource.contains(
                "0xFF13171C) | 0xFF000000"));
        assertTrue(settingsSource.contains("int minimumDriverRadius = dp(20);"));
        assertTrue(settingsSource.contains(
                "Math.max(minimumDriverRadius, profile.cornerRadiusPx.get())"));

        Path store = preferences.resolve("launcher/LauncherShortcutStore.java");
        if (!Files.exists(store)) {
            store = preferences.getParent().resolve(
                    "launcher/LauncherShortcutStore.java");
        }
        String storeSource = read(store);
        assertTrue(storeSource.contains(
                "preferences.activeDriverPanelProfile()"));
        assertTrue(storeSource.contains(
                "profile.shortcutsJson"));
        assertTrue(storeSource.contains(
                "value.iconColor = \"#FFE0E5F3\";"));
    }

    @Test
    public void runtimeDrawerSharesLauncherCatalogAndTileRenderer() throws Exception {
        String root = System.getProperty("user.dir");
        Path widget = Paths.get(root, "src/main/java/dezz/status/widget");
        if (!Files.exists(widget)) {
            widget = Paths.get(root, "app/src/main/java/dezz/status/widget");
        }
        String controller = read(widget.resolve(
                "driver/DriverPanelOverlayController.java"));
        String launcher = read(widget.resolve("LauncherActivity.java"));
        String catalog = read(widget.resolve(
                "launcher/LauncherAppCatalog.java"));
        String renderer = read(widget.resolve(
                "launcher/LauncherAppTileRenderer.java"));
        String settings = read(widget.resolve(
                "DriverPanelSettingsActivity.java"));

        assertTrue(controller.contains("LauncherAppCatalog.loadVisible(appContext, preferences)"));
        assertTrue(controller.contains("LauncherAppTileRenderer.render("));
        assertFalse(controller.contains("InstalledAppCatalog"));
        assertTrue(controller.contains("preferences.launcherAllAppsColumns.get()"));
        assertTrue(controller.contains("preferences.launcherAllAppsGapPx.get()"));
        assertTrue(controller.contains("preferences.launcherAllAppsIconScalePercent.get()"));
        assertTrue(controller.contains("grid.setPadding(dp(context, 16), dp(context, 16),"));
        assertTrue(controller.contains("title.setText(\"Все приложения\")"));
        assertTrue(controller.contains("FavoriteAppsConfigStore"));

        assertTrue(launcher.contains("LauncherAppCatalog.loadIncludingSystem(context)"));
        assertTrue(launcher.contains("appCatalog.allVisible()"));
        assertFalse(launcher.contains("if (!app.systemApp"));
        assertTrue(launcher.contains("LauncherAppTileRenderer.render("));
        assertTrue(catalog.contains("Intent.CATEGORY_LAUNCHER"));
        assertTrue(catalog.contains("queryIntentActivities(query, 0)"));
        assertTrue(catalog.contains("new LinkedHashSet<>()"));
        assertTrue(catalog.contains("toLowerCase(Locale.ROOT)"));
        assertTrue(catalog.contains("ApplicationInfo.FLAG_SYSTEM"));
        assertTrue(catalog.contains("ApplicationInfo.FLAG_UPDATED_SYSTEM_APP"));
        assertTrue(renderer.contains("appearance.iconSizePx * scale / 100"));
        assertTrue(renderer.contains("appearance.labelSizeSp * scale / 100f"));

        // The editor intentionally retains the broad catalog so system/non-launcher activities
        // remain assignable even though they never appear in a runtime all-apps surface.
        assertTrue(settings.contains("InstalledAppCatalog.load(this)"));
    }

    @Test
    public void panelReplacementNeverExposesCoveredOemRail() throws Exception {
        String root = System.getProperty("user.dir");
        Path controller = Paths.get(root, "src/main/java/dezz/status/widget/driver/"
                + "DriverPanelOverlayController.java");
        if (!Files.exists(controller)) {
            controller = Paths.get(root, "app/src/main/java/dezz/status/widget/driver/"
                    + "DriverPanelOverlayController.java");
        }
        String source = read(controller);
        int apply = source.indexOf("void applyPreferences()");
        int navigation = source.indexOf("boolean setNavigationHidden", apply);
        String replacement = source.substring(apply, navigation);
        assertTrue(replacement.contains("previousWindows"));
        assertTrue(replacement.contains("attachForType("));
        assertTrue(replacement.contains(
                "retireAfterFirstDraw(previousWindows, successorWindows)"));
        assertTrue(replacement.contains("panelWindows.addAll(previousWindows)"));
        assertTrue(source.contains("addOnPreDrawListener(callback[0])"));
        assertTrue(source.contains(
                "anchor.view.postOnAnimation(() -> anchor.view.postOnAnimation("));
        assertTrue(source.contains("private int drawerGeneration;"));
        assertTrue(source.contains("generation != drawerGeneration"));
        assertFalse(replacement.contains(
                "statusListener.onStatus(\"hidden\""));
    }

    @Test
    public void appCatalogEnumeratesSystemAndNonLauncherPackages() throws Exception {
        String root = System.getProperty("user.dir");
        Path catalog = Paths.get(root, "src/main/java/dezz/status/widget/launcher/"
                + "InstalledAppCatalog.java");
        if (!Files.exists(catalog)) {
            catalog = Paths.get(root, "app/src/main/java/dezz/status/widget/launcher/"
                    + "InstalledAppCatalog.java");
        }
        String source = read(catalog);
        assertTrue(source.contains("getInstalledApplications"));
        assertTrue(source.contains("FLAG_SYSTEM"));
        assertTrue(source.contains("CATEGORY_LEANBACK_LAUNCHER"));
        assertTrue(source.contains("CATEGORY_HOME"));
        assertFalse(source.contains("queryIntentActivities(query, 0)"));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
