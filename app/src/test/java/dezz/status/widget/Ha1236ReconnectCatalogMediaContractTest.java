/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the two 2026-08-21 KX11 journals and Helper screenshots. */
public final class Ha1236ReconnectCatalogMediaContractTest {
    @Test public void publicationIsMonotonicAndLeaves223Frozen() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1236);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        String current = "if (version == '2.2.4') { return 208021237";
        String frozen = "if (version == '2.2.3') { return 208021235";
        assertTrue(build.contains("return '2.2.4'"));
        assertTrue(build.contains(current));
        assertTrue(build.contains(frozen));
        assertTrue(build.indexOf(current) < build.indexOf(frozen));
        String original = project("release-manifests/HA1236.md");
        assertTrue(original.contains("Android version code: `208021236`"));
        assertTrue(original.contains("Release tag: `natro-v2.2.4`"));
    }

    @Test public void c4FrameworkFailuresRetryWithoutErasingEnrollment() throws Exception {
        String source = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String transportFailure = between(source, "private void failRoutineTransport",
                "private void handleCharacteristicRead");
        String proofFailure = between(source, "private void failRoutine(String detail)",
                "private void failRoutineTransport");
        String writes = between(source, "private void handleCharacteristicWrite",
                "private void handleCharacteristicChanged");

        assertTrue(transportFailure.contains("IphoneTransportErrorV2.Kind.GATT"));
        assertTrue(transportFailure.contains("resetCurrentOwner(detail)"));
        assertFalse(transportFailure.contains("explicit re-enroll required"));
        assertTrue(proofFailure.contains("PEER_PROOF_REJECTED"));
        assertTrue(writes.contains("failRoutineTransport(\"routine C4 hello write failed"));
        assertTrue(writes.contains("failRoutineTransport(\"routine C4 confirm write failed"));
    }

    @Test public void disconnectedWrapperCanDrainAndC5BackoffIsSeconds() throws Exception {
        String source = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String freeze = between(source, "@Override public void freezeIngress",
                "@Override public void transmitControl");
        assertTrue(freeze.contains("FROZEN_NO_REMOTE_OWNER"));
        assertFalse(freeze.contains("owner == null && !scanRunning"));
        assertTrue(source.contains("CAR_REMOTE_BACKOFF_MIN_MS = 2_000L"));
        assertTrue(source.contains("CAR_REMOTE_BACKOFF_MAX_MS = 30_000L"));
    }

    @Test public void catalogIsValidProtectedAndHelperRequiresAllIds() throws Exception {
        String controller = project("app/src/main/java/dezz/status/widget/phone/"
                + "CarRemoteControllerV1.java");
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v54/CarRemoteProtocolV1.swift");
        assertTrue(controller.contains("HELLO_COALESCE_MS = 12_000L"));
        assertTrue(controller.contains("CarControlDescriptor.Kind.ACTION.ordinal() + 1"));
        assertFalse(controller.contains("descriptor == null ? 0"));
        assertTrue(controller.indexOf("Type.SYNC_COMPLETE")
                < controller.indexOf("car.subscribeControlStates",
                controller.indexOf("private void publishCatalog")));
        assertTrue(helper.contains("expectedCatalogIDs"));
        assertTrue(helper.contains("catalogTailSeen && missing.isEmpty"));
        assertTrue(helper.contains("withTimeInterval: 10"));
        assertTrue(project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "CarRemoteFrameQueueV1.java").contains("removeOldestState"));
    }

    @Test public void mediaCountdownUsesExactAlarmAndSessionReadiness() throws Exception {
        String media = project("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String boot = project("app/src/main/java/dezz/status/widget/BootReceiver.java");
        assertTrue(media.contains("setExactAndAllowWhileIdle"));
        assertTrue(media.contains("KEY_TARGET_ELAPSED"));
        assertTrue(media.contains("onPlaybackObservation"));
        assertTrue(boot.indexOf("MediaAutoResumeController.scheduleAfterBoot(context)")
                < boot.indexOf("StartupWorkCoordinator.scheduleForLifecycle(context, action)"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path file = current.resolve(relative);
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
