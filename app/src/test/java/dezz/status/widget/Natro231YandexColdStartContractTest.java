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
    @Test public void stateNoneSessionIsActionableAndGetsAnExactVerifiedFallback()
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
        String usability = between(command, "private static boolean isUsablePlaySession(",
                "/** Dispatches only to the already resolved exact MediaSession");
        assertTrue(usability.contains("return explicitPlay;"));
        assertFalse(usability.contains("playbackState != PlaybackState.STATE_NONE"));
        assertTrue(command.contains("stateNoneAccepted="));
        assertTrue(command.contains("verified_exact_session_media_button"));
        assertTrue(command.contains("controller.dispatchMediaButtonEvent"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));

        assertTrue(controller.contains("YANDEX_MAX_ATTEMPTS = 20"));
        assertTrue(controller.contains("YANDEX_SESSION_POLL_MS = 5_000L"));
        assertTrue(controller.contains("KEY_YANDEX_BROWSER_BOOTSTRAP_REQUESTED"));
        assertTrue(controller.contains("KEY_YANDEX_SESSION_PLAY_ATTEMPTED"));
        assertTrue(command.contains("Result.WAITING_FOR_SESSION"));
        assertTrue(command.contains("browser=\" + browser"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));

        assertTrue(browser.contains("bootstrap_scheduled"));
        assertTrue(browser.contains("play_request_sent_unverified"));
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
