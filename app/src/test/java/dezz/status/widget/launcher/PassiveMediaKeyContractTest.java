/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** MConfig/Android owns physical keys. Natro only observes available broadcasts and feedback. */
public final class PassiveMediaKeyContractTest {
    @Test public void noAccessibilityInterceptionOrReplacementRouter() throws Exception {
        String service = read("app/src/main/java/dezz/status/widget/WidgetAccessibilityService.java");
        String xml = read("app/src/main/res/xml/widget_accessibility_service.xml");
        assertFalse(service.contains("onKeyEvent("));
        assertFalse(service.contains("SteeringMediaKeyRouter"));
        assertFalse(xml.contains("flagRequestFilterKeyEvents"));
        assertFalse(xml.contains("canRequestFilterKeyEvents"));
        assertFalse(Files.exists(root().resolve("app/src/main/java/dezz/status/widget/launcher/SteeringMediaKeyRouter.java")));
    }

    @Test public void observationIsReadOnlyBoundedAndExplicitAboutCoverage() throws Exception {
        String observer = read("app/src/main/java/dezz/status/widget/diagnostics/SteeringKeyDiagnostics.java");
        assertTrue(observer.contains("android.intent.action.IEDIA_BUTTON"));
        assertTrue(observer.contains("Intent.ACTION_MEDIA_BUTTON"));
        assertTrue(observer.contains("filter.setPriority(-1_000)"));
        assertTrue(observer.contains("media-key-observer"));
        assertTrue(observer.contains("media-session-observer"));
        assertTrue(observer.contains("MAX_SESSIONS = 16"));
        assertTrue(observer.contains("++rateCount > 32"));
        assertTrue(observer.contains("ActionRecorder.recordAsync("));
        assertTrue(observer.contains("DiagnosticJournal.infoAsync("));
        assertTrue(observer.contains("notification_access_required"));
        assertTrue(observer.contains("association=temporal_only"));
        assertTrue(observer.contains("physical_origin=unverified"));
        assertTrue(observer.contains("command_delivery=unobserved, audio_output=unobserved"));
        for (String forbidden : new String[]{"getTransportControls(", "dispatchMediaKeyEvent(",
                "sendBroadcast(", "sendOrderedBroadcast(", "abortBroadcast(", "setResultCode(",
                "goAsync(", "new MediaSession(", "getDescription(", "getBitmap("}) {
            assertFalse(forbidden, observer.contains(forbidden));
        }
        String input = between(observer, "@Override public void onReceive(", "private SteeringKeyDiagnostics(");
        assertFalse(input.contains("getActiveSessions("));
        assertFalse(input.contains("getPlaybackState("));
        assertTrue(observer.contains("unregisterReceiver(receiver)"));
        assertTrue(observer.contains("unregisterCallback(watch.callback)"));
        String systemLog = read("app/src/main/java/dezz/status/widget/diagnostics/MediaKeySystemLog.java");
        assertTrue(systemLog.contains("checkSelfPermission(\"android.permission.READ_LOGS\")"));
        assertTrue(systemLog.contains("read_logs_not_granted"));
        assertTrue(systemLog.contains("media-key-system-log"));
        assertTrue(systemLog.contains("MediaKeyLogRecord.parse(line)"));
        assertTrue(systemLog.contains("*:S"));
        assertTrue(observer.contains("record.deviceIdPresent"));
        assertTrue(observer.contains("event_age_ms="));
        assertFalse(observer.contains("log_delivery_ms="));
        assertTrue(observer.contains("after_system_sequence="));
        assertTrue(observer.contains("systemObservation.clear()"));
        for (String forbidden : new String[]{"getevent", "Runtime.getRuntime(", "grantRuntimePermission(",
                "sendBroadcast(", "dispatchMediaKeyEvent(", "su\",", "settings put"}) {
            assertFalse(forbidden, systemLog.contains(forbidden));
        }
    }

    @Test public void everyOrdinaryJournalProducerIsSeparatedFromDiskLock() throws Exception {
        String journal = read("app/src/main/java/dezz/status/widget/diagnostics/DiagnosticJournal.java");
        String record = between(journal, "public static void record(@NonNull Level", "public static void infoAsync");
        String early = between(journal, "public static void recordEarly(", "private static void finishEarlyEntriesLocked");
        assertTrue(record.contains("enqueueLocked("));
        assertTrue(early.contains("enqueueLocked("));
        assertFalse(record.contains("appendLocked("));
        assertFalse(early.contains("appendLocked("));
        assertFalse(record.contains("DISK_LOCK"));
        assertFalse(journal.contains("CallerRunsPolicy"));
        assertTrue(journal.contains("new ArrayBlockingQueue<>(128)"));
        assertTrue(journal.contains("status-journal-writer"));
    }

    @Test public void knownMainLooperBlockersStayOutsideTheInputLane() throws Exception {
        String boot = read("app/src/geely/java/dezz/status/widget/car/HudModeFallbackBootReceiver.java");
        String driver = read("app/src/main/java/dezz/status/widget/driver/DriverPanelOverlayController.java");
        String loader = between(driver, "private AppDrawerData loadAppDrawerData()",
                "private void registerDrawerPackageReceiver()");
        String adapter = driver.substring(driver.indexOf("private static final class AppsAdapter"));
        String getView = between(adapter, "public View getView(int position, View convertView, ViewGroup parent)",
                "private final class ShortcutDrawerAdapter");
        assertTrue(boot.contains("putLong(KEY_NOT_BEFORE"));
        assertTrue(boot.contains(".apply()"));
        assertFalse(boot.contains(".commit()"));
        assertTrue(loader.contains("LauncherAppCatalog.loadIcon(appContext, app)"));
        assertTrue(loader.contains("AppDrawerUninstallPolicy.canUninstall("));
        assertFalse(getView.contains("LauncherAppCatalog.loadIcon"));
        assertFalse(getView.contains("AppDrawerUninstallPolicy.canUninstall"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError(start);
        return source.substring(from, to);
    }
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(root().resolve(path)), StandardCharsets.UTF_8);
    }
    private static Path root() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}
