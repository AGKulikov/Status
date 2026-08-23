/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release boundary for Natro 2.2.13 and Helper 66 field-log recovery repairs. */
public final class Natro2213RecoveryExportContractTest {
    @Test public void androidExportAlwaysUsesTheSystemShareChooser() throws Exception {
        String build = read("build.gradle").replaceAll("\\s+", " ");
        String settings = read("app/src/main/java/dezz/status/widget/"
                + "PhoneConnectorSettingsActivity.java");
        String paths = read("app/src/main/res/xml/file_paths.xml");

        assertTrue(build.contains("if (version == '2.2.13') { return 208021247"));
        assertTrue(settings.contains("createConnectionJournalShareFile(text)"));
        assertTrue(settings.contains("FileProvider.getUriForFile("));
        assertTrue(settings.contains("Intent.EXTRA_STREAM, contentUri"));
        assertTrue(settings.contains("ClipData.newUri("));
        assertTrue(settings.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
        assertTrue(settings.contains("Intent.createChooser(share"));
        assertTrue(paths.contains("<cache-path name=\"exports\" path=\"exports/\""));
        assertFalse(settings.contains("REQUEST_JOURNAL_EXPORT"));
        assertFalse(settings.contains("Environment.getExternalStorageDirectory()"));
        assertFalse(settings.contains("queryIntentActivities(share"));
    }

    @Test public void killedIntegrationHostHasAlarmAndBluetoothWakeBoundaries() throws Exception {
        String watchdog = read("app/src/main/java/dezz/status/widget/WidgetServiceWatchdog.java");
        String receiver = read("app/src/main/java/dezz/status/widget/"
                + "WidgetServiceWatchdogReceiver.java");
        String bluetooth = read("app/src/main/java/dezz/status/widget/"
                + "BluetoothIntegrationWakeReceiver.java");
        String service = read("app/src/main/java/dezz/status/widget/WidgetService.java");
        String manifest = read("app/src/main/AndroidManifest.xml");

        assertTrue(watchdog.contains("DEADLINE_MS = 9_000L"));
        assertTrue(watchdog.contains("setExactAndAllowWhileIdle"));
        assertTrue(receiver.contains("requiresAutomaticIntegrationHost"));
        assertTrue(receiver.contains("WidgetServiceStarter.startIfNeededWithRetry(app)"));
        assertTrue(service.contains("SERVICE_WATCHDOG_HEARTBEAT_MS = 3_000L"));
        assertTrue(service.contains("refreshServiceWatchdog()"));
        assertTrue(service.contains("DESTROY_RECOVERY_DELAY_MS"));
        assertTrue(bluetooth.contains("ACTION_ACL_CONNECTED"));
        assertTrue(bluetooth.contains("ACTION_ACL_DISCONNECTED"));
        assertTrue(manifest.contains(".WidgetServiceWatchdogReceiver"));
        assertTrue(manifest.contains(".BluetoothIntegrationWakeReceiver"));
        assertTrue(manifest.contains("android.bluetooth.device.action.ACL_DISCONNECTED"));
    }

    @Test public void helperAutomaticallyDrainsTheLocalUnprovableRestoration() throws Exception {
        String route = read("ios/KX11-iPhone-ANCS-Helper-v66-personal/"
                + "HelperPeripheralRoute.swift");
        String journal = read("ios/KX11-iPhone-ANCS-Helper-v66-personal/"
                + "KX11ANCSHelper/ANCSConnectionJournal.swift");

        assertTrue(route.contains("permitsUnprovableLocalOnlyDrain"));
        assertTrue(route.contains("!emptyLocalOnly && !unprovableLocalOnly"));
        assertTrue(route.contains("if unprovableLocalOnly, let epoch"));
        assertTrue(route.contains("didFreezeWithoutRemoteOwner: epoch"));
        assertTrue(journal.contains("static var helperVersion: String"));
        assertTrue(journal.contains("CFBundleShortVersionString"));
        assertTrue(journal.contains("CFBundleVersion"));
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}
