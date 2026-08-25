/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24 18:18 road journals. */
public final class Natro231YandexColdStartContractTest {
    @Test public void exactSessionAndBackgroundBootstrapSupersedeOemProcessInventory()
            throws Exception {
        String build = read("build.gradle");
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(build.contains("if (version == '2.3.1')"));
        assertTrue(build.contains("return 208021255"));
        assertTrue(command.contains("route=exact_session_play"));
        assertTrue(command.contains("ActivityManager process inventory"));
        assertTrue(command.contains("isUsablePlaySession"));
        assertFalse(command.contains("verified_exact_session_media_button"));
        assertFalse(command.contains("controller.dispatchMediaButtonEvent"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));

        assertTrue(controller.contains("YANDEX_MAX_ATTEMPTS = 24"));
        assertTrue(controller.contains("YANDEX_SESSION_POLL_MS = 5_000L"));
        assertTrue(controller.contains("YANDEX_RECEIVER_GRACE_MS = 10_000L"));
        assertTrue(controller.contains(
                "requestYandexBrowserBootstrap = yandexBootRecovery"));
        assertTrue(controller.contains("KEY_YANDEX_BROWSER_BOOTSTRAP_REQUESTED"));
        assertTrue(controller.contains("KEY_YANDEX_RECEIVER_COMMAND_SENT"));
        assertTrue(controller.contains("KEY_YANDEX_SESSION_PLAY_ATTEMPTED"));
        assertTrue(command.contains("Result.BROWSER_BOOTSTRAP"));
        assertTrue(command.contains("Result.WAITING_FOR_SESSION"));
        assertTrue(command.contains("browser=\" + browser"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));

        assertTrue(browser.contains("bootstrap_scheduled"));
        assertFalse(browser.contains("service_prewarm"));
        assertTrue(browser.contains("playBrowserSession"));
        assertTrue(browser.contains("finishOrDispatch(\"connection_failed\")"));
        assertTrue(browser.contains("warmup_connected"));
        assertFalse(browser.contains("finish(\"play_dispatched\""));
    }

    @Test public void duplicateLifecycleDoesNotRewriteOriginalCaptureTimestamp()
            throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String coalesced = between(controller,
                "if (previousToken != 0L && MediaAutoResumeLifecyclePolicy.shouldCoalesce(",
                "MediaPlaybackHistoryStore.Snapshot history");

        assertTrue(coalesced.contains("KEY_LAST_LIFECYCLE_ELAPSED"));
        assertFalse(coalesced.contains("putLong(KEY_CAPTURE_ELAPSED, now)"));
        assertTrue(controller.contains("totalSinceCaptureMs="));
    }

    @Test public void helper69RemainsTheRequiredUnchangedAncsCompanion()
            throws Exception {
        String helper = read("ios/KX11-iPhone-ANCS-Helper-v69-personal/"
                + "KX11ANCSHelper/NatroLiveActivityManager.swift");
        String client = read("ios/KX11-iPhone-ANCS-Helper-v69-personal/"
                + "CarRemoteProtocolV1.swift");

        assertTrue(helper.contains("if becameConnected {"));
        assertTrue(helper.contains("private var creatingActivities = false"));
        assertFalse(helper.contains("becameConnected || runningCount == 0"));
        assertTrue(client.contains("withTimeInterval: 5, repeats: true"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
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
