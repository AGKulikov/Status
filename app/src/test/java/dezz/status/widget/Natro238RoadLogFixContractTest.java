/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-25 package-update, media and PAS road captures. */
public final class Natro238RoadLogFixContractTest {
    @Test public void releaseIdentityAndHelperStayInstallCompatible() throws Exception {
        String build = read("build.gradle");
        String workflow = read(
                ".github/workflows/verify-natro-2.3.8-helper69-personal.yml");
        String manifest = read(
                "release-manifests/NATRO-2.3.8-HELPER69-PERSONAL.md");
        assertTrue(build.contains("if (version == '2.3.8')"));
        assertTrue(build.contains("return 208021262"));
        assertTrue(workflow.contains("VERSION_NAME: '2.3.8'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021262'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest assembleGeelyRelease"));
        assertTrue(workflow.contains("verify-v69-personal-contract.sh"));
        assertTrue(manifest.contains("Helper 69 is intentionally unchanged"));
        assertTrue(manifest.contains("6e9855aedc008bbdd8a7fbf3f490be07"
                + "f964b7ac658a837a1592647a08365c75"));
    }

    @Test public void yandexUsesForegroundReceiverThenExactTokenFallback() throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");
        assertTrue(command.contains("Intent.FLAG_RECEIVER_FOREGROUND"));
        assertTrue(command.contains("route=exact_foreground_receiver"));
        assertTrue(command.contains("browser=deferred"));
        assertFalse(command.contains("exact_receiver_browser_race"));
        assertFalse(controller.contains("YandexMusicBrowserStarter.requestWarmup(app)"));
        assertTrue(controller.contains("YANDEX_RECEIVER_GRACE_MS = 10_000L"));
        assertTrue(browser.contains("browser.getSessionToken()"));
        assertTrue(browser.contains("playBrowserSession"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));
        assertFalse(browser.contains("startActivity("));
    }

    @Test public void packageReplacementDelaysOnlyThePhoneTransport() throws Exception {
        String boot = read("app/src/main/java/dezz/status/widget/BootReceiver.java");
        String gate = read("app/src/main/java/dezz/status/widget/phone/"
                + "PackageReplaceBleRecoveryGate.java");
        String controller = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        assertTrue(gate.contains("QUIET_MS = 8_000L"));
        assertTrue(gate.contains("nowElapsed < markedAtElapsed"));
        assertTrue(boot.contains("PackageReplaceBleRecoveryGate.mark(receiverContext)"));
        assertTrue(boot.indexOf("PackageReplaceBleRecoveryGate.mark(receiverContext)")
                < boot.indexOf("startVisibleSurfaceImmediatelyWithRetry(context)"));
        assertTrue(controller.contains("worker.postDelayed(start, packageReplaceQuietMs)"));
    }

    @Test public void persistentPhoneJournalIsNotReadFromColdFlashAtStartup() throws Exception {
        String journal = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectionJournal.java");
        String initialize = between(journal, "public static void initialize(",
                "public static void append(");
        assertFalse(initialize.contains("FileInputStream"));
        assertFalse(initialize.contains("readLines("));
        assertTrue(journal.contains("merged.addAll(readLines(file))"));
    }

    @Test public void parkingWindowAndCameraVisibilityOwnThePauseEdgeWithoutDeadlineBypass() throws Exception {
        String widget = read("app/src/main/java/dezz/status/widget/WidgetService.java");
        assertTrue(widget.contains("boolean active = phoneVehicleOverlayActive || phoneParkingWindowActive"));
        assertTrue(widget.contains("this::onEcarxParkingWindowStateChanged"));
        assertFalse(widget.contains("phoneExternalOverlayDeadlineBypass"));
        assertTrue(widget.contains("boolean changed = phoneExternalOverlayActive != active"));
        assertTrue(widget.contains("syncPhoneNotificationExternalOverlayPause()"));
        assertTrue(widget.contains("if (changed) onPhoneNotificationForegroundChanged()"));
        assertTrue(widget.contains("DiagnosticJournal.debug(\"navigator-window\","));
        assertFalse(widget.contains("DiagnosticJournal.warn(\"navigator-window\",\n"
                + "                        \"windowed vendor confirmation lease expired\"") );
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
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
