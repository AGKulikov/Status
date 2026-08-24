/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24 08:17 road session and the Helper 68 journal storm. */
public final class Natro2219RoadLogRecoveryContractTest {
    @Test public void yandexReceiverAttemptRemainsExclusiveBeforeVerifiedFallback()
            throws Exception {
        String build = read("build.gradle");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(build.contains("if (version == '2.2.19')"));
        assertTrue(build.contains("return 208021253"));
        assertTrue(command.contains("deferredYandexPlaySession = controller"));
        assertTrue(command.contains("skipped_receiver_available"));
        assertTrue(command.contains("verified_media_browser_bootstrap"));
        String queriedReceiver = between(command,
                "for (ResolveInfo resolved : receivers)",
                "ComponentName known = knownReceiver(target)");
        assertTrue(queriedReceiver.contains("sendKey(context, receiver"));
        assertFalse(queriedReceiver.contains("requestYandexBrowserIfUseful"));
        assertTrue(command.indexOf("deferredYandexPlaySession = controller")
                < command.indexOf("PackageManager packages"));
        String sessionFallback = between(command,
                "String deferredSession = \"not_used\";",
                "String browser = requestYandexBrowserIfUseful");
        assertTrue(sessionFallback.contains("return trace(Result.SESSION_COMMAND"));
        assertTrue(sessionFallback.contains("browser=skipped_session_available"));
        assertTrue(browser.contains("CONNECTION_TIMEOUT_MS = 15_000L"));
        assertTrue(browser.contains("connect_coalesced"));
    }

    @Test public void asynchronousGattFailureRetiresTheTransportNotTheAncsParser()
            throws Exception {
        String central = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String callback = between(central,
                "clearDeferredAncsRequest(pending.ancsRequest);",
                "} else if (pending.type == RawOperation.REQUEST_TELEMETRY)");

        assertTrue(callback.contains("status == BluetoothGatt.GATT_SUCCESS"));
        assertTrue(callback.contains("IphoneTransportErrorV2.Kind.GATT"));
        assertTrue(callback.contains("resetCurrentOwner(detail)"));
        assertFalse(callback.contains("controlPointWriteResult(\n"
                + "                    pending.ancsRequest, status =="));
    }

    @Test public void c5AndLiveActivityHaveOneBoundedConnectionEdge() throws Exception {
        String remote = read("app/src/main/java/dezz/status/widget/phone/"
                + "CarRemoteControllerV1.java");
        String helper = read("ios/KX11-iPhone-ANCS-Helper-v69-personal/"
                + "KX11ANCSHelper/NatroLiveActivityManager.swift");
        String client = read("ios/KX11-iPhone-ANCS-Helper-v69-personal/"
                + "CarRemoteProtocolV1.swift");

        assertTrue(remote.contains("HELLO_COALESCE_MS = 30_000L"));
        assertTrue(helper.contains("if becameConnected {"));
        assertTrue(helper.contains("private var creatingActivities = false"));
        assertTrue(helper.contains("guard !creatingActivities else"));
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
