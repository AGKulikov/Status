/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the Android 9 ANCS/C5 failure captured on 2026-08-21. */
public final class Ha1235AncsActiveRecoveryContractTest {
    @Test public void publicationMappingRemainsFrozenForReplay() throws Exception {
        ReleaseIdentityContract.assertCurrentAtLeast(1235);
        String build = project("build.gradle").replaceAll("\\s+", " ");
        String current = "if (version == '2.2.3') { return 208021235";
        String frozen222 = "if (version == '2.2.2') { return 208021234";
        String frozen221 = "if (version == '2.2.1') { return 208021233";
        String frozen220 = "if (version == '2.2.0') { return 208021232";
        assertTrue(build.contains(current));
        assertTrue(build.contains(frozen222));
        assertTrue(build.contains(frozen221));
        assertTrue(build.contains(frozen220));
        assertTrue(build.indexOf(current) < build.indexOf(frozen222));
    }

    @Test public void enrolledRecoveryStartsDirectThenScansOnlyAfterProvenFailure()
            throws Exception {
        String route = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String platform = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String direct = between(route, "private static BleRouteTransition<State> beginDirectConnect",
                "private static BleRouteTransition<State> retry(");
        String selected = between(platform, "private void connectSelectedBond",
                "private void connectMatchedBootstrap");

        assertTrue(direct.contains("autoConnect=false"));
        assertTrue(direct.contains("autoConnect=false"));
        assertFalse(direct.contains("passiveEnrolledRetry"));
        assertTrue(route.contains("base.consecutiveFailures >= 1"));
        assertTrue(route.contains("beginScan(base)"));
        assertTrue(route.contains("unfiltered scan; accept only stack-resolved exact saved public "));
        assertTrue(route.contains("identity + bonded facade"));
        assertTrue(selected.contains("adapter.getRemoteDevice(record.leIdentityAddress)"));
        assertTrue(selected.contains("createGattOwner(token, enrolled, false, null)"));
        assertFalse(selected.contains("passiveRetry"));
        assertFalse(selected.contains("startBootstrapScan("));
        assertTrue(platform.contains("second BluetoothGatt wrapper forbidden"));
        assertTrue(platform.contains("scan_start mode=unfiltered_enrolled_identity"));
    }

    @Test public void ancsWaitsBehindOptionalAttInsteadOfReportingFalseRejection()
            throws Exception {
        String platform = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String write = between(platform, "private void writeControlPoint",
                "private void transmitControlOnMain");
        int pending = write.indexOf("if (pendingGatt != null)");
        int defer = write.indexOf("deferredAncsRequest = request", pending);
        int rejection = write.indexOf("ancs.controlPointWriteResult(request, false)", pending);

        assertTrue(pending >= 0);
        assertTrue(write.contains("pendingGatt.type != RawOperation.WRITE_CONTROL_POINT"));
        assertTrue(write.contains("pendingGatt.type != RawOperation.WRITE_ROUTE_CONTROL"));
        assertTrue(defer > pending);
        assertTrue(rejection > defer);
        assertTrue(platform.contains("drainDeferredAncsAfterGatt()"));
    }

    @Test public void missingC5CallbackResetsOwnerWithoutOverlappingTheAttFifo()
            throws Exception {
        String platform = project("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String watchdog = between(platform, "carRemoteWriteWatchdog = () -> {",
                "main.postDelayed(carRemoteWriteWatchdog, CAR_REMOTE_WRITE_TIMEOUT_MS)");
        String completion = between(platform,
                "} else if (pending.type == RawOperation.WRITE_CAR_REMOTE)",
                "private void handleCharacteristicChanged");

        assertTrue(watchdog.contains("pendingGatt != pending || owner != exactOwner"));
        assertFalse(watchdog.contains("pendingGatt = null"));
        assertTrue(watchdog.contains("resetCurrentOwner("));
        assertTrue(watchdog.contains("carRemoteRetryNotBeforeMillis"));
        assertTrue(watchdog.contains("CAR_REMOTE_BACKOFF_MAX_MS"));
        assertTrue(completion.contains("drainDeferredAncsAfterGatt()"));
        assertTrue(completion.indexOf("drainDeferredAncsAfterGatt()")
                < completion.indexOf("scheduleCarRemoteDrain("));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) {
            throw new AssertionError("Source boundary missing: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
