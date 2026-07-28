/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guard rails for the first approved unified-spec implementation slice. */
public final class Ha1132RegressionContractTest {
    @Test public void homeEditorHasNoLongPressEntryOrTechnicalFrameContours()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        String frame = source("launcher/LauncherElementFrame.java");
        String proxy = source("launcher/LauncherGlobalElementProxyView.java");

        String root = between(launcher, "private View buildRoot()",
                "private void updateLauncherSafeArea()");
        assertTrue(root.contains("workspace.setLongClickable(false)"));
        assertFalse(root.contains("workspace.setOnLongClickListener"));
        assertTrue(frame.contains("setStrokeWidth(0)"));
        assertFalse(frame.contains("setStrokeWidth(enabled ?"));
        assertTrue(proxy.contains("setLongClickable(false)"));
        assertFalse(proxy.contains("GestureDetector"));
    }

    @Test public void flatCatalogContainsConcreteWidgetsAndNeverRequiresGridSpace()
            throws Exception {
        String catalog = source("launcher/LauncherWidgetCatalog.java");
        String launcher = source("LauncherActivity.java");
        String navigation = source(
                "launcher/navigation/NavigationPanelConfig.java");

        assertTrue(catalog.contains("One flat source of every item"));
        assertTrue(catalog.contains("MediaPanelConfig.SPECS"));
        assertTrue(catalog.contains("NavigationPanelConfig.SPECS"));
        assertTrue(catalog.contains("ClimatePanelConfig.ELEMENTS"));
        assertTrue(catalog.contains("Kind.HORIZONTAL_GROUP"));
        assertTrue(launcher.contains("LauncherWidgetCatalog.available("));
        assertTrue(launcher.contains("addLauncherCatalogEntry(entries.get(which))"));
        assertFalse(between(launcher, "private void showLauncherWidgetCatalog()",
                "private boolean hasRemovedLauncherWidgets()")
                .contains("На сетке"));
        assertTrue(navigation.contains("it must never turn a"));
        assertFalse(navigation.contains("element.enabled = false;\n                    continue;"));
    }

    @Test public void freeFramesCropLegacyInsetsAndMarqueesKeepIndependentClocks()
            throws Exception {
        String proxy = source("launcher/LauncherGlobalElementProxyView.java");
        String frame = source("launcher/LauncherElementFrame.java");
        assertTrue(proxy.contains("visualContentBounds(source)"));
        assertTrue(proxy.contains("appendVisualBounds"));
        assertTrue(proxy.contains("Map<TextView, MarqueeState> marqueeStates"));
        assertTrue(proxy.contains("MarqueeState state = marqueeState(source, key)"));
        assertFalse(proxy.contains("private long marqueeStartedAtMs"));
        assertTrue(frame.contains("minimumWidthPx = 1"));
        assertTrue(frame.contains("minimumHeightPx = 1"));
    }

    @Test public void horizontalRowsAreRealFreeFramesWithZeroSpacingControls()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        String store = source("launcher/LauncherHorizontalGroupStore.java");
        String layout = source("launcher/HorizontalGroupLayout.java");
        assertTrue(launcher.contains("syncLauncherHorizontalGroups()"));
        assertTrue(launcher.contains("createLauncherHorizontalGroup()"));
        assertTrue(launcher.contains("group.gapPx = value"));
        assertTrue(launcher.contains("group.paddingBottomPx = value"));
        assertTrue(store.contains("launcherHorizontalGroupsJson"));
        assertTrue(store.contains("containsMember"));
        assertTrue(layout.contains("int safeGap = Math.max(0, gap)"));
        assertTrue(layout.contains("Text size is deliberately absent"));
    }

    @Test public void launcherSettingsAreOneCanonicalGroupWithoutLegacyPanelSection()
            throws Exception {
        String catalog = source("settings/SettingsDestinationCatalog.java");
        assertTrue(catalog.contains("HOME(\"home\", \"Лаунчер\""));
        assertTrue(catalog.contains("activity(\"panel_media\", Group.HOME"));
        assertTrue(catalog.contains("activity(\"panel_information\", Group.HOME"));
        assertTrue(catalog.contains("activity(\"panel_actions\", Group.HOME"));
        assertFalse(catalog.contains("activity(\"home_panel_content\""));
    }

    @Test public void hudRowsAreGeometryContainersWithoutImplicitSurfaceOrTextScaling()
            throws Exception {
        String canvas = source("hud/HudCanvasView.java");
        String settings = source("HudPanelSettingsActivity.java");
        String element = source("hud/HudElementConfig.java");
        String group = source("hud/HudHorizontalGroup.java");

        assertTrue(canvas.contains("case HORIZONTAL_GROUP:"));
        assertTrue(canvas.contains("private RectF groupedBounds"));
        assertTrue(canvas.contains("HorizontalGroupLayout.layout("));
        assertTrue(settings.contains("editHorizontalGroup(item)"));
        assertTrue(settings.contains("Внутренний отступ снизу, px"));
        assertTrue(settings.contains("Внешний отступ снизу, px"));
        assertTrue(settings.contains("rebuildHudHorizontalGroupMembers"));
        assertTrue(element.contains("type == HudElementType.HORIZONTAL_GROUP"));
        assertTrue(group.contains("group.options.optInt(\"gapPx\", 0)"));
        assertFalse(group.toLowerCase().contains("shadow"));
    }

    @Test public void driverAndAllAppsFixesKeepOverlaysStableAndDefaultsPrivate()
            throws Exception {
        String driver = source("driver/DriverPanelOverlayController.java");
        String uninstall = source("launcher/AppUninstallLauncher.java");
        String catalog = source("launcher/LauncherAppCatalog.java");

        assertTrue(driver.contains("|| manuallyOpenFavorites.contains(panelId)"));
        assertTrue(driver.contains("scheduleRaiseAfterExternalLaunch()"));
        assertTrue(driver.contains("ViewGroup.LayoutParams.WRAP_CONTENT, 1f"));
        assertTrue(driver.contains("shortcut.informationShowValue ? 1 : 0"));
        assertTrue(driver.contains(
                "AppUninstallLauncher.request(context, app, attachedType)"));
        assertTrue(uninstall.contains("window.setType(windowType)"));
        assertTrue(catalog.contains("ensureDefaultSystemVisibility("));
        assertTrue(catalog.contains("app.systemApp && !isUserFacingPhone"));
        assertTrue(catalog.contains("preferences.launcherSystemAppsDefaultApplied.set(true)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) {
            throw new AssertionError("Missing range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
