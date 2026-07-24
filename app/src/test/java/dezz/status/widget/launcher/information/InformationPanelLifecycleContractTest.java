/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract for subscriptions that cannot be exercised by local Android JVM stubs. */
public final class InformationPanelLifecycleContractTest {
    @Test public void panelSubscribesWithInitialSnapshotAndReleasesEveryListener()
            throws IOException {
        String source = source("dezz/status/widget/launcher/information/InformationPanelView.java");
        assertTrue(source.contains("current.addConnectorValueListener(listener)"));
        assertTrue(source.contains("current.removeConnectorValueListener(listener)"));
        assertTrue(source.contains("carIntegration.subscribeTelemetry(ids, vehicleListener)"));
        assertTrue(source.contains("carIntegration.unsubscribeTelemetry(vehicleListener)"));
        assertTrue(source.contains("protected void onDetachedFromWindow()"));
        assertTrue(source.contains("handler.removeCallbacks(tick)"));
    }

    @Test public void queuedConnectorCallbackCannotCrossAStopOrReconnectBoundary()
            throws IOException {
        String source = source("dezz/status/widget/launcher/information/InformationPanelView.java");
        assertTrue(source.contains("private int connectorGeneration;"));
        assertTrue(source.contains("final int generation = connectorGeneration;"));
        assertTrue(source.contains("generation != connectorGeneration"));
        assertTrue(source.contains("subscribedService != capturedService"));
        int invalidate = source.indexOf("connectorGeneration++;",
                source.indexOf("private void disconnectConnectorService()"));
        int remove = source.indexOf("removeConnectorValueListener", invalidate);
        int clear = source.indexOf("connectorValues.clear()", invalidate);
        assertTrue(invalidate >= 0);
        assertTrue(remove > invalidate);
        assertTrue(clear > invalidate);
    }

    @Test public void panelHasOneSchedulerOwnerAndNoCommandClickPath() throws IOException {
        String source = source("dezz/status/widget/launcher/information/InformationPanelView.java");
        assertTrue(source.contains("This runnable is the sole owner of scheduling"));
        assertTrue(source.contains("subscribedService == null ? SERVICE_RETRY_MS : TICK_MS"));
        assertFalse(source.contains("setOnClickListener"));
        assertFalse(source.contains("ActionBinding"));
        assertFalse(source.contains("execute"));
    }

    @Test public void launcherOwnsIndependentFrameVisibilityAndLifecycle() throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        String layout = source("dezz/status/widget/launcher/LauncherLayoutStore.java");
        assertTrue(layout.contains("public static final String INFORMATION = \"information\""));
        assertTrue(launcher.contains("LauncherLayoutStore.INFORMATION, \"Информация\""));
        assertTrue(launcher.contains("informationPanel.start()"));
        assertTrue(launcher.contains("informationPanel.stop()"));
        assertTrue(launcher.contains("preferences.launcherInformationVisible.get()"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
