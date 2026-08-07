/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guard rails for the hardware regressions fixed after HA1130. */
public final class Ha1131RegressionContractTest {
    @Test public void launcherEditorMovesContinuouslyAndSnapsOnlyOnRelease()
            throws Exception {
        String frame = source("launcher/LauncherElementFrame.java");
        assertTrue(frame.contains("applyGeometry(lp, dx, dy, 1)"));
        assertTrue(frame.contains("applyGeometry(lp, finalDx, finalDy, snapPx)"));
        assertTrue(frame.contains("Grid snapping during every MOVE was the"));

        String proxy = source("launcher/LauncherGlobalElementProxyView.java");
        assertTrue(proxy.contains("public void refreshFromSource()"));
        assertTrue(proxy.contains("if (signature == lastVisualSignature) return"));
        assertTrue(proxy.contains("drawTextFrame(canvas, (TextView) value)"));
        assertTrue(proxy.contains(".setIncludePad(false)"));
        assertTrue(proxy.contains("postInvalidateOnAnimation()"));
        assertTrue(proxy.contains("drawViewTree(child, canvas,"));
        assertTrue(proxy.contains("sourceToScreenScaleX, sourceToScreenScaleY)"));
        assertTrue(proxy.contains("never call setTextSize while a"));
        assertFalse(proxy.contains("compensateTextScale"));
        assertFalse(proxy.contains("card.setCardBackgroundColor(Color.TRANSPARENT)"));

        String activity = source("LauncherActivity.java");
        assertTrue(activity.contains("proxy.refreshFromSource()"));
        assertFalse(between(activity, "private void refreshGlobalElementVisibility()",
                "private void applyStoredGlobalGeometry()")
                .contains("proxy.invalidate()"));
    }

    @Test public void allAppsRemainsOpaqueAndAttachedDuringStandardConfirmation()
            throws Exception {
        String launcher = source("LauncherActivity.java");
        String driver = source("driver/DriverPanelOverlayController.java");
        String uninstall = source("launcher/AppUninstallProxyActivity.java");
        assertTrue(launcher.contains("root.setBackgroundColor(Color.rgb(10, 13, 18))"));
        assertTrue(launcher.contains("setAllAppsConfirmationActive(true)"));
        assertTrue(driver.contains("root.setBackgroundColor(Color.rgb(0, 0, 0))"));
        assertTrue(driver.contains("drawer.setBackgroundColor(Color.rgb(10, 13, 18))"));
        assertTrue(driver.contains("current.setTouchable(false)"));
        assertTrue(driver.contains("AppUninstallLauncher.ACTION_FINISHED"));
        assertTrue(uninstall.contains("Intent.ACTION_UNINSTALL_PACKAGE"));
        assertTrue(uninstall.contains("Intent.EXTRA_RETURN_RESULT"));
    }

    @Test public void informationRowsHaveNoUnavoidablePaddingOrGroupGap()
            throws Exception {
        String store = source("launcher/LauncherShortcutStore.java");
        String overlay = source("driver/DriverPanelOverlayController.java");
        String information = source(
                "launcher/information/InformationPanelView.java");
        assertTrue(store.contains("public int informationPaddingLeftPx = 0"));
        assertTrue(store.contains("public int informationGroupGapPx = 0"));
        assertTrue(store.contains(".put(\"edgeToEdgeContent\", true)"));
        assertTrue(overlay.contains("boolean namedGroup"));
        assertTrue(overlay.contains("if (!namedGroup)"));
        assertTrue(overlay.contains("scroll.setPadding(0, 0, 0, 0)"));
        assertTrue(overlay.contains(
                "DriverInformationTileLayoutPolicy.naturalHeight(context, shortcut, 1f)"));
        assertFalse(overlay.contains("text + padding + dp(context, 8)"));
        assertTrue(information.contains(
                "PhoneIndicatorVisualPolicy.cellularIconTextGapPx(iconSize)"));
    }

    @Test public void favoritesSecondTapClosesAndAutoDoesNotImmediatelyReopen()
            throws Exception {
        String overlay = source("driver/DriverPanelOverlayController.java");
        assertTrue(overlay.contains("|| manuallyOpenFavorites.contains(panelId)"));
        assertTrue(overlay.contains("manuallyClosedFavorites.add(panelId)"));
        assertTrue(overlay.contains("manuallyClosedFavorites.remove(panelId)"));
        assertTrue(overlay.contains("!manuallyClosedFavorites.contains(panel.id)"));
    }

    @Test public void autoClimateKeepsScaleButNoAutomaticPictogram()
            throws Exception {
        String climate = source("driver/DriverClimateShortcutView.java");
        String automatic = between(climate, "if (automatic) {",
                "int totalBars = ClimateFanIndicatorPolicy.MANUAL_SEGMENTS");
        assertTrue(automatic.contains("ClimateFanIndicatorPolicy.AUTO_SEGMENTS"));
        assertTrue(automatic.contains("drawBars("));
        assertTrue(automatic.contains("drawAutoText("));
        assertFalse(automatic.contains("drawAirflow("));
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
