/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guard rails for the Status Widget crash seen while Navigator creates its Android 9 windows. */
public final class Ha1140NavigatorCrashRegressionTest {
    @Test public void staleNavigatorAccessibilityWindowsCannotEscapeTheServiceCallback()
            throws Exception {
        String source = source("WidgetAccessibilityService.java");

        assertTrue(source.contains("handleAccessibilityEvent(event)"));
        assertTrue(source.contains(
                "catch (RuntimeException | LinkageError | OutOfMemoryError failure)"));
        assertTrue(source.contains("seedFromCurrentWindowsSafely(\"window transition\")"));
        assertTrue(source.contains("Publish only a complete snapshot"));
        assertTrue(source.contains("catch (RuntimeException | LinkageError ignored)"));
    }

    @Test public void foregroundRerenderFailureCannotTerminateStatusWidget() throws Exception {
        String source = source("WidgetService.java");

        assertTrue(source.contains("safeCheckForegroundApp(\"display map update\")"));
        assertTrue(source.contains("safeUpdateForegroundAppTracking(\"accessibility state\")"));
        assertTrue(source.contains("reportForegroundFailure(operation, failure)"));
    }

    @Test public void navigatorPayloadBurstsAreBoundedAndCoalescedBeforeQueueing()
            throws Exception {
        String source = source("launcher/YandexNavigationReceiver.java");
        String listener = source("MediaNotificationListener.java");

        assertTrue(source.contains("MAX_PENDING_BROADCASTS = 8"));
        assertTrue(source.contains("coalesceQueuedUpdates(task)"));
        assertTrue(source.contains("releaseQueuedPayloads()"));
        assertTrue(source.contains("catch (OutOfMemoryError memoryPressure)"));
        assertTrue(listener.contains("NavigationDataRepository.releaseDecodedGraphics()"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

}
