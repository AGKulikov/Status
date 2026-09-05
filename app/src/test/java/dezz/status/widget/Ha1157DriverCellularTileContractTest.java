/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression guard for independently configurable, unclipped driver cellular information. */
public final class Ha1157DriverCellularTileContractTest {
    @Test public void driverEditorExposesEveryCellularPieceIndependently() throws Exception {
        String settings = source("DriverPanelSettingsActivity.java");
        String store = source("launcher/LauncherShortcutStore.java");
        String bridge = source("launcher/InformationShortcutView.java");

        assertTrue(settings.contains("Показывать шкалу сигнала"));
        assertTrue(settings.contains("Показывать название оператора"));
        assertTrue(settings.contains("Показывать тип сети (LTE/3G/5G)"));
        assertTrue(store.contains("informationPhoneCellularShowSignal"));
        assertTrue(store.contains("informationPhoneCellularShowOperator"));
        assertTrue(store.contains("informationPhoneCellularShowNetworkType"));
        assertTrue(store.contains("boolean hasCellularParts"));
        assertTrue(store.contains(
                "value.informationPhoneCellularShowOperator = value.informationShowValue"));
        assertTrue(bridge.contains("item.phoneCellularShowSignal"));
        assertTrue(bridge.contains("item.phoneCellularShowOperator"));
        assertTrue(bridge.contains("item.phoneCellularShowNetworkType"));
    }

    @Test public void wholeIconValueClusterIsCenteredAndNeverPreClipped() throws Exception {
        String panel = source("launcher/information/InformationPanelView.java");
        String policy = source("driver/DriverInformationTileLayoutPolicy.java");
        String runtime = source("driver/DriverPanelOverlayController.java");
        String settings = source("DriverPanelSettingsActivity.java");

        assertTrue(panel.contains("FrameLayout tile = new FrameLayout"));
        assertTrue(panel.contains("tile.addView(content, contentLp)"));
        assertTrue(panel.contains("PhoneCellularDisplayPolicy.resolve"));
        assertTrue(policy.contains("paint.measureText(text)"));
        assertFalse(policy.contains("Math.min(240"));
        assertTrue(runtime.contains(
                "DriverInformationTileLayoutPolicy.naturalWidth(context, shortcut, 1f)"));
        assertTrue(settings.contains(
                "DriverInformationTileLayoutPolicy.naturalWidth(this, value, .62f)"));
        assertTrue(settings.contains("new InformationShortcutView("));
    }

    @Test public void releaseIdentityMovesForward() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1157);
    }

    private static String source(String relative) throws Exception {
        Path path = root("app/src/main/java/dezz/status/widget").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path root(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) return direct;
        return Paths.get("src").resolve(relative.substring("app/src/".length()));
    }

    private static Path projectFile(String relative) {
        Path direct = Paths.get(relative);
        if (Files.isRegularFile(direct) && !Paths.get(".").toAbsolutePath().normalize()
                .endsWith("app")) return direct;
        return Paths.get("..").resolve(relative).normalize();
    }
}
