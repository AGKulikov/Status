/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for process-death-safe iPhone ANCS background reconnection. */
public final class Ha1160ColdAncsReconnectContractTest {
    @Test public void bondedColdStartRegistersOnePersistentLeBackgroundOwner()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String connect = between(transport, "public boolean connectSavedIphone",
                "public void requestSavedPeerReconnect");
        String background = between(transport,
                "private boolean startManagedBackgroundAttach",
                "/**\n     * Explicit recovery hook");

        assertTrue(connect.contains("safeBondState(device) == BluetoothDevice.BOND_BONDED"));
        assertTrue(connect.contains("scheduleColdBackgroundAttach(device,"));
        assertTrue(background.contains(
                "target.connectGatt(context, true, gattCallback,"));
        assertTrue(background.contains("BluetoothDevice.TRANSPORT_LE"));
        assertTrue(background.contains("activeClientAutoConnect = true"));
        assertTrue(background.contains("one durable cold-start owner"));
        assertFalse(background.contains("postDelayed(connectTimeout"));
        assertFalse(background.contains("closeClientGatt(created)"));
    }

    @Test public void coldRegistrationIsSerializedAndExplicitFailuresRetryBackgroundFirst()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String schedule = between(transport, "private boolean scheduleColdBackgroundAttach",
                "private boolean startManagedBackgroundAttach");
        String retry = between(transport, "private void scheduleManagedReconnect",
                "private static boolean requiresControllerRetry");
        String explicitReconnect = between(transport,
                "public void requestSavedPeerReconnect(@NonNull String reason, boolean",
                "private void scheduleAmbiguousAclProbe");

        assertTrue(transport.contains("COLD_BACKGROUND_ATTACH_DELAY_MS = 2_500L"));
        assertTrue(schedule.contains("coldBackgroundAttachTask != null"));
        assertTrue(schedule.contains("main.postDelayed(coldBackgroundAttachTask,"));
        assertTrue(retry.contains("managedReconnectTask != null"
                + " || coldBackgroundAttachTask != null"));
        assertTrue(retry.contains("safeBondState(expected) == BluetoothDevice.BOND_BONDED"));
        assertTrue(retry.contains("startManagedBackgroundAttach(expected,"));
        assertTrue(retry.contains(": startSavedPeerScan(expected)"));
        assertTrue(explicitReconnect.contains("activeClientAutoConnect"));
        assertTrue(explicitReconnect.contains("BACKGROUND_CONNECT"));
        assertTrue(explicitReconnect.contains("Сохраняю ожидающий background GATT owner"));
        assertFalse(explicitReconnect.contains("closeClientGatt(pendingOwner)"));
    }

    @Test public void establishedOwnerSurvivesRadioLossAndServiceRestartsAfterUpdate()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String callback = between(transport,
                "private void handleIphonePeripheralConnectionState",
                "private final BluetoothGattCallback gattCallback");
        String persistent = between(transport, "private void awaitPersistentGattReconnect",
                "private boolean startSavedPeerScan");
        String receiver = source("BootReceiver.java");
        String service = source("WidgetService.java");

        assertTrue(callback.contains("if (establishedOwner)"));
        assertTrue(callback.contains("awaitPersistentGattReconnect(callbackGatt"));
        assertTrue(persistent.contains("expected.connect()"));
        assertFalse(persistent.contains("closeClientGatt(expected)"));
        assertTrue(receiver.contains("Intent.ACTION_MY_PACKAGE_REPLACED"));
        assertTrue(service.contains("startForeground(NOTIFICATION_ID, createNotification())"));
        assertTrue(service.contains("return START_STICKY"));
    }

    @Test public void releaseIdentityIsHa1160() throws Exception {
        String build = project("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1166'"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("app/src/main/java/dezz/status/widget").resolve(relative),
                Paths.get("src/main/java/dezz/status/widget").resolve(relative));
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        if (Files.isRegularFile(Paths.get("..", "settings.gradle"))
                && Files.isRegularFile(parent)) return text(parent);
        return text(Files.isRegularFile(direct) ? direct : parent);
    }

    private static String read(Path root, Path app) throws Exception {
        return text(Files.isRegularFile(root) ? root : app);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
