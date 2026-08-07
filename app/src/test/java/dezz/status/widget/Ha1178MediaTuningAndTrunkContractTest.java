/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression contract for user-tunable media controls and the safety-gated KX11 trunk. */
public final class Ha1178MediaTuningAndTrunkContractTest {
    @Test public void progressHasGapSettingWholeFrameGestureAndCommitOnlySeek() throws Exception {
        String config = source("launcher/media/MediaPanelConfig.java");
        String store = source("launcher/media/MediaPanelConfigStore.java");
        String settings = source("MediaPanelSettingsActivity.java");
        String panel = source("launcher/media/MediaPanelView.java");
        String controller = source("launcher/LauncherMediaController.java");

        assertTrue(config.contains("progressTimeGapDp"));
        assertTrue(store.contains("put(\"progressTimeGapDp\""));
        assertTrue(settings.contains("Отступ времени от полосы"));
        assertTrue(panel.contains("root.setOnTouchListener"));
        assertTrue(panel.contains("setOnProgressCommitted"));
        String changed = between(panel, "progress.setOnProgressChanged(",
                "progress.setOnProgressCommitted(");
        assertFalse(changed.contains("controls.seekTo"));
        assertTrue(controller.contains("selected.getTransportControls().seekTo(targetPosition)"));
    }

    @Test public void volumeThumbCanBeHiddenAndResizedProportionally() throws Exception {
        String config = source("launcher/media/MediaPanelConfig.java");
        String settings = source("MediaPanelSettingsActivity.java");
        String panel = source("launcher/media/MediaPanelView.java");

        assertTrue(config.contains("volumeThumbVisible"));
        assertTrue(config.contains("volumeThumbSizePercent"));
        assertTrue(settings.contains("Показывать кружок громкости"));
        assertTrue(settings.contains("Размер кружка"));
        assertTrue(panel.contains("height * .22f * thumbScale"));
        assertTrue(panel.contains("if (thumbVisible)"));
        assertTrue(panel.contains("thumbRadiusY * scaleY / scaleX"));
    }

    @Test public void trunkUsesExactVendorProtocolConfirmationAndLiveIcons() throws Exception {
        String geely = project("app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java");
        String safety = source("car/TrunkControlSafety.java");
        String launcher = source("LauncherActivity.java");
        String driver = source("driver/DriverPanelActionExecutor.java");

        assertTrue(geely.contains("TRUNK_FUNCTION_ID = 0x02210100"));
        assertTrue(geely.contains("TRUNK_ZONE = 0x20000000"));
        assertTrue(geely.contains("option(0, \"Закрыт\"), option(1, \"Открыт\")"));
        assertTrue(geely.indexOf("direct trunk probe is not ready")
                < geely.indexOf("source.isFunctionSupported(definition.functionId"));
        assertTrue(safety.contains("setTitle(\"Открыть багажник?\")"));
        assertTrue(safety.contains("CarControlCommand.Operation.SET, target"));
        assertTrue(launcher.contains("TrunkControlSafety.iconKey"));
        assertTrue(driver.contains("TrunkControlSafety.confirmOpeningIfNeeded"));
    }

    @Test public void releaseIdentityAdvancesToHa1178() throws Exception {
        assertTrue(rootProject("build.gradle").contains("return 'v2.8.2-ha1178'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        return source.substring(from, to);
    }
}
