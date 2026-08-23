/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source contract for the opt-in, exact-player boot resume flow. */
public final class MediaAutoResumeContractTest {
    @Test
    public void bootResumeIsOptInBoundedAndNeverRunsForAnAppUpdate() throws IOException {
        String boot = source("dezz/status/widget/BootReceiver.java");
        String controller = source(
                "dezz/status/widget/launcher/MediaAutoResumeController.java");
        String preferences = source("dezz/status/widget/Preferences.java");

        assertTrue(preferences.contains(
                "\"launcherMediaAutoResumeEnabled\", false"));
        assertTrue(controller.contains(
                "if (!preferences.launcherMediaAutoResumeEnabled.get())"));
        assertTrue(controller.contains("private static final int MAX_ATTEMPTS = 8"));
        assertTrue(controller.contains("KEY_TARGET_PACKAGE"));
        assertTrue(controller.contains("KEY_BOOT_TOKEN"));
        assertTrue(controller.contains("MediaPlaybackTargetPolicy.shouldAutoResume("));
        assertTrue(controller.contains("setExactAndAllowWhileIdle"));
        assertTrue(controller.contains("canScheduleExactAlarms"));
        assertTrue(controller.contains("KEY_TARGET_ELAPSED"));
        assertTrue(controller.contains("onPlaybackObservation"));
        assertTrue(controller.contains("ScheduledExecutorService EXACT_TIMER"));
        assertTrue(controller.contains("scheduleInProcess(app, bootToken, attempt, delayMillis)"));
        assertTrue(controller.contains("150L, 250L, 400L, 700L"));
        assertTrue(preferences.contains("\"launcherMediaFixedPlayerEnabled\", false"));
        assertTrue(preferences.contains("\"launcherMediaFixedPlayerPackage\", \"\""));
        assertTrue(controller.contains(
                "if (!MediaAutoResumeLifecyclePolicy.isLifecycleAction(action)) return"));
        assertFalse(controller.contains("ACTION_MY_PACKAGE_REPLACED"));
        assertTrue(boot.contains("MediaAutoResumeController.scheduleAfterBoot(context)"));
    }

    @Test
    public void commandTargetsOnlyTheRememberedPlayerAndUsesIdempotentPlay()
            throws IOException {
        String command = source("dezz/status/widget/launcher/MediaResumeCommand.java");

        assertTrue(command.contains("target.equals(controller.getPackageName())"));
        assertTrue(command.contains(
                "new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(target)"));
        assertTrue(command.contains("KeyEvent.KEYCODE_MEDIA_PLAY"));
        assertTrue(command.contains("Intent.FLAG_INCLUDE_STOPPED_PACKAGES"));
        assertFalse(command.contains("KEYCODE_MEDIA_PLAY_PAUSE"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));
    }

    @Test
    public void lifecycleSnapshotIsFrozenBeforeTheSharedStartupLane()
            throws IOException {
        String boot = source("dezz/status/widget/BootReceiver.java");
        String controller = source(
                "dezz/status/widget/launcher/MediaAutoResumeController.java");

        assertTrue(boot.contains(
                "MediaAutoResumeController.armAtReceiverBoundaryAsync(receiverContext, receivedAction)"));
        assertTrue(boot.indexOf("armAtReceiverBoundaryAsync(receiverContext, receivedAction)")
                < boot.indexOf("STARTUP_LANE.execute"));
        assertTrue(boot.contains("StartupWorkCoordinator.PHASE_MEDIA_PLAN"));
        assertTrue(controller.contains("MediaPlaybackHistoryStore.read(app)"));
        assertTrue(controller.contains("KEY_CAPTURE_HISTORY_PACKAGE"));
        assertTrue(controller.contains("KEY_CAPTURE_HISTORY_WAS_PLAYING"));
        assertTrue(controller.contains("KEY_PLAN_ANCHOR_ELAPSED"));
        assertTrue(controller.contains("planAnchorElapsed + Math.max"));
        assertTrue(controller.contains(
                "targetElapsed - SystemClock.elapsedRealtime()"));
        assertTrue(controller.contains(
                "state.getLong(KEY_CAPTURE_TOKEN, Long.MIN_VALUE) != bootToken"));
        assertTrue(controller.contains("MediaAutoResumeLifecyclePolicy.shouldCoalesce("));
        assertFalse(controller.contains("previousPlanConsumed"));

        int capture = controller.indexOf("public static long captureBootHistorySnapshot");
        int delayed = controller.indexOf("public static void scheduleAfterBoot");
        assertTrue(capture >= 0 && delayed > capture);
        assertFalse(controller.substring(capture, delayed).contains("new Preferences"));
    }

    @Test
    public void historyIsCapturedFromEveryMediaIngressWithoutPerSecondWrites()
            throws IOException {
        String history = source(
                "dezz/status/widget/launcher/MediaPlaybackHistoryStore.java");
        String launcher = source(
                "dezz/status/widget/launcher/LauncherMediaController.java");
        String repository = source(
                "dezz/status/widget/launcher/MediaBroadcastRepository.java");
        String status = source("dezz/status/widget/WidgetService.java");

        assertTrue(history.contains("playing == preferences.getBoolean(KEY_WAS_PLAYING"));
        assertTrue(history.contains("createDeviceProtectedStorageContext()"));
        assertTrue(launcher.contains("MediaPlaybackHistoryStore.record("));
        assertTrue(repository.contains("MediaPlaybackHistoryStore.record("));
        assertTrue(status.contains("MediaPlaybackHistoryStore.record("));
    }

    @Test
    public void internalAlarmReceiverIsNotExternallyCallable() throws IOException {
        String manifest = mainFile("AndroidManifest.xml");
        String receiver = source(
                "dezz/status/widget/launcher/MediaAutoResumeReceiver.java");

        assertTrue(manifest.contains(
                "android:name=\".launcher.MediaAutoResumeReceiver\""));
        assertTrue(receiver.contains(
                "MediaAutoResumeController.ACTION_RESUME.equals(intent.getAction())"));
        int receiverStart = manifest.indexOf(
                "android:name=\".launcher.MediaAutoResumeReceiver\"");
        int receiverEnd = manifest.indexOf("/>", receiverStart);
        assertTrue(receiverStart >= 0 && receiverEnd > receiverStart);
        assertTrue(manifest.substring(receiverStart, receiverEnd)
                .contains("android:exported=\"false\""));
    }

    private static String source(String relative) throws IOException {
        return mainFile(Paths.get("java", relative).toString());
    }

    private static String mainFile(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", relative);
        Path fromApp = Paths.get("src", "main", relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
