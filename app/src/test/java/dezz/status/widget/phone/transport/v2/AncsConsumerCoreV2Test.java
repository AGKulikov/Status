/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AncsConsumerCoreV2Test {
    @Test public void parserBuffersRealtimeUntilBothCccdsAndSuppressesReplayBoundedly() {
        AncsConsumerCoreV2 core = new AncsConsumerCoreV2();
        AncsSessionTokenV2 session = session(1L);
        core.begin(session);

        List<AncsConsumerEffectV2> replay = core.notificationSource(
                session, replayPacket(255), 100L);
        assertEquals(8, count(replay, AncsConsumerEffectV2.Type.REPLAY_CHECKPOINT));
        assertEquals(0, count(replay, AncsConsumerEffectV2.Type.NOTIFICATION_EVENT));
        assertEquals(0, count(replay, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        AncsConsumerEffectV2 latestTimer = last(
                replay, AncsConsumerEffectV2.Type.ARM_REPLAY_QUIET);
        assertNotNull(latestTimer);
        List<AncsConsumerEffectV2> summary = core.replayQuiet(
                session, latestTimer.generation);
        assertEquals(1, count(summary, AncsConsumerEffectV2.Type.REPLAY_SUMMARY));
        assertEquals(255L, summary.get(0).count);

        List<AncsConsumerEffectV2> early = core.notificationSource(
                session, event(0, 0, 4, 1, 0x11223344L), 200L);
        assertEquals(1, count(early, AncsConsumerEffectV2.Type.NOTIFICATION_EVENT));
        assertEquals(0, count(early, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));

        List<AncsConsumerEffectV2> ready = core.subscriptionsReady(session);
        assertEquals(1, count(ready, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        assertEquals(1, count(ready, AncsConsumerEffectV2.Type.ARM_REQUEST_DEADLINE));
    }

    @Test public void fragmentedResponseMayBeatWriteCallbackButDeliveryWaitsForBoth() {
        AncsConsumerCoreV2 core = readyCore(2L);
        AncsSessionTokenV2 session = session(2L);
        List<AncsConsumerEffectV2> source = core.notificationSource(
                session, event(0, 0, 4, 1, 7L), 300L);
        AncsRequestTokenV2 request = last(
                source, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        byte[] response = notificationResponse(7L, "com.example.chat", "Alice", "",
                "Hello", "20260812T001122");

        assertEquals(0, count(core.dataSource(session,
                Arrays.copyOfRange(response, 0, 9)),
                AncsConsumerEffectV2.Type.NOTIFICATION));
        assertEquals(0, count(core.dataSource(session,
                Arrays.copyOfRange(response, 9, response.length)),
                AncsConsumerEffectV2.Type.NOTIFICATION));

        List<AncsConsumerEffectV2> completed =
                core.controlPointWriteResult(request, true);
        assertEquals(1, count(completed, AncsConsumerEffectV2.Type.NOTIFICATION));
        assertEquals(1, count(completed, AncsConsumerEffectV2.Type.CANCEL_REQUEST_DEADLINE));
        assertEquals(1, count(completed, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        IphoneNotificationV2 notification = last(
                completed, AncsConsumerEffectV2.Type.NOTIFICATION).notification;
        assertEquals("Alice", notification.title);
        assertEquals("Hello", notification.message);
        assertEquals("", notification.appName);

        AncsRequestTokenV2 appRequest = last(
                completed, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        assertEquals(AncsRequestTokenV2.Kind.APP_DISPLAY_NAME, appRequest.kind);
        assertEquals(0, count(core.controlPointWriteResult(appRequest, true),
                AncsConsumerEffectV2.Type.APP_NAME));
        List<AncsConsumerEffectV2> app = core.dataSource(
                session, appResponse("com.example.chat", "Chat"));
        assertEquals(1, count(app, AncsConsumerEffectV2.Type.APP_NAME));
        assertEquals(300L, last(app, AncsConsumerEffectV2.Type.APP_NAME)
                .appName.observedAtElapsedMillis);
    }

    @Test public void notificationRequestsStaySerializedAndAppNamesAreLowerPriority() {
        AncsConsumerCoreV2 core = readyCore(3L);
        AncsSessionTokenV2 session = session(3L);
        List<AncsConsumerEffectV2> first = core.notificationSource(session,
                concat(event(0, 0, 6, 1, 10L), event(0, 0, 6, 2, 11L)), 400L);
        assertEquals(1, count(first, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        AncsRequestTokenV2 request10 = last(
                first, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;

        core.controlPointWriteResult(request10, true);
        List<AncsConsumerEffectV2> done10 = core.dataSource(session,
                notificationResponse(10L, "mail", "One", "", "M1", "D"));
        AncsRequestTokenV2 request11 = last(
                done10, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        assertEquals(AncsRequestTokenV2.Kind.NOTIFICATION_ATTRIBUTES, request11.kind);

        core.controlPointWriteResult(request11, true);
        List<AncsConsumerEffectV2> done11 = core.dataSource(session,
                notificationResponse(11L, "mail", "Two", "", "M2", "D"));
        AncsRequestTokenV2 app = last(
                done11, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        assertEquals(AncsRequestTokenV2.Kind.APP_DISPLAY_NAME, app.kind);
    }

    @Test public void removalSuppressesCurrentAttributesWithoutDesynchronizingStream() {
        AncsConsumerCoreV2 core = readyCore(4L);
        AncsSessionTokenV2 session = session(4L);
        List<AncsConsumerEffectV2> source = core.notificationSource(
                session, event(0, 0, 4, 1, 44L), 500L);
        AncsRequestTokenV2 request = last(
                source, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        List<AncsConsumerEffectV2> removed = core.notificationSource(
                session, event(2, 0, 4, 0, 44L), 501L);
        assertEquals(1, count(removed, AncsConsumerEffectV2.Type.NOTIFICATION_EVENT));

        core.controlPointWriteResult(request, true);
        List<AncsConsumerEffectV2> drained = core.dataSource(session,
                notificationResponse(44L, "chat", "Gone", "", "Gone", "D"));
        assertEquals(0, count(drained, AncsConsumerEffectV2.Type.NOTIFICATION));
        assertEquals(0, count(drained, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        assertTrue(core.isReady(session));
    }

    @Test public void removalBeforeDataSourceSubscriptionPurgesEarlyRealtimeUid() {
        AncsConsumerCoreV2 core = new AncsConsumerCoreV2();
        AncsSessionTokenV2 session = session(41L);
        core.begin(session);

        List<AncsConsumerEffectV2> added = core.notificationSource(
                session, event(0, 0, 4, 1, 441L), 510L);
        assertEquals(1, count(added, AncsConsumerEffectV2.Type.NOTIFICATION_EVENT));
        assertEquals(0, count(added, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));

        List<AncsConsumerEffectV2> removed = core.notificationSource(
                session, event(2, 0, 4, 0, 441L), 511L);
        assertEquals(1, count(removed, AncsConsumerEffectV2.Type.NOTIFICATION_EVENT));

        List<AncsConsumerEffectV2> ready = core.subscriptionsReady(session);
        assertEquals(0, count(ready, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        assertTrue(core.isReady(session));
    }

    @Test public void malformedOrTimedOutDataStreamTerminatesExactSession() {
        AncsConsumerCoreV2 core = readyCore(5L);
        AncsSessionTokenV2 session = session(5L);
        List<AncsConsumerEffectV2> source = core.notificationSource(
                session, event(0, 0, 1, 1, 55L), 600L);
        AncsRequestTokenV2 request = last(
                source, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        List<AncsConsumerEffectV2> malformed = core.dataSource(
                session, new byte[] {1, 0, 0, 0, 0});
        assertEquals(1, count(malformed, AncsConsumerEffectV2.Type.TERMINATE_SESSION));
        assertFalse(core.isActive(session));
        assertEquals(0, core.requestDeadline(request).size());

        AncsConsumerCoreV2 timeoutCore = readyCore(6L);
        AncsSessionTokenV2 timeoutSession = session(6L);
        AncsRequestTokenV2 timeout = last(timeoutCore.notificationSource(
                timeoutSession, event(0, 0, 1, 1, 66L), 700L),
                AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        assertEquals(1, count(timeoutCore.requestDeadline(timeout),
                AncsConsumerEffectV2.Type.TERMINATE_SESSION));
        assertFalse(timeoutCore.isActive(timeoutSession));
    }

    @Test public void staleOwnerAndOldRequestCallbacksCannotAdvanceReplacementSession() {
        AncsConsumerCoreV2 core = readyCore(7L);
        AncsSessionTokenV2 oldSession = session(7L);
        AncsRequestTokenV2 oldRequest = last(core.notificationSource(oldSession,
                event(0, 0, 1, 1, 77L), 800L),
                AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;

        AncsSessionTokenV2 replacement = session(8L);
        core.begin(replacement);
        core.subscriptionsReady(replacement);
        assertEquals(0, core.controlPointWriteResult(oldRequest, true).size());
        assertEquals(0, core.dataSource(oldSession,
                notificationResponse(77L, "x", "x", "", "x", "D")).size());
        assertTrue(core.isReady(replacement));
    }

    @Test public void realtimeQueueOverflowIsExplicitAndNeverCreatesParallelWrites() {
        AncsConsumerCoreV2 core = readyCore(9L);
        AncsSessionTokenV2 session = session(9L);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        for (int uid = 1; uid <= 66; uid++) {
            byte[] event = event(0, 0, 4, 1, uid);
            packet.write(event, 0, event.length);
        }
        List<AncsConsumerEffectV2> effects = core.notificationSource(
                session, packet.toByteArray(), 900L);
        assertEquals(2, count(effects, AncsConsumerEffectV2.Type.QUEUE_DROPPED));
        assertEquals(1, count(effects, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
    }

    @Test public void requestCursorExhaustionTerminatesInsteadOfReusingOne() {
        AncsConsumerCoreV2 core = new AncsConsumerCoreV2(Long.MAX_VALUE - 1L);
        AncsSessionTokenV2 session = session(91L);
        core.begin(session);
        core.subscriptionsReady(session);

        List<AncsConsumerEffectV2> first = core.notificationSource(
                session, event(0, 0, 4, 1, 901L), 901L);
        AncsRequestTokenV2 request = last(
                first, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT).request;
        assertEquals(Long.MAX_VALUE - 1L, request.requestId);
        core.notificationSource(session, event(0, 0, 4, 1, 902L), 902L);
        core.controlPointWriteResult(request, true);
        List<AncsConsumerEffectV2> completed = core.dataSource(session,
                notificationResponse(901L, "", "Final", "", "Final", "D"));
        assertEquals(1, count(completed, AncsConsumerEffectV2.Type.TERMINATE_SESSION));
        assertEquals(0, count(completed, AncsConsumerEffectV2.Type.WRITE_CONTROL_POINT));
        assertFalse(core.isActive(session));
    }

    private static AncsConsumerCoreV2 readyCore(long id) {
        AncsConsumerCoreV2 core = new AncsConsumerCoreV2();
        core.begin(session(id));
        core.subscriptionsReady(session(id));
        return core;
    }

    private static AncsSessionTokenV2 session(long id) {
        return new AncsSessionTokenV2(new BleRouteEpoch(id, 1L), id, id);
    }

    private static byte[] replayPacket(int count) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(count * 8);
        for (int index = 0; index < count; index++) {
            byte[] event = event(0, 0x04, 4, 1, index + 1L);
            output.write(event, 0, event.length);
        }
        return output.toByteArray();
    }

    private static byte[] event(int eventId, int flags, int category, int categoryCount,
                                long uid) {
        return new byte[] {(byte) eventId, (byte) flags, (byte) category,
                (byte) categoryCount, (byte) uid, (byte) (uid >>> 8),
                (byte) (uid >>> 16), (byte) (uid >>> 24)};
    }

    private static byte[] notificationResponse(long uid, String app, String title,
                                                String subtitle, String message, String date) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        writeUid(output, uid);
        tuple(output, 0, app);
        tuple(output, 1, title);
        tuple(output, 2, subtitle);
        tuple(output, 3, message);
        tuple(output, 5, date);
        return output.toByteArray();
    }

    private static byte[] appResponse(String appIdentifier, String appName) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(1);
        byte[] app = appIdentifier.getBytes(StandardCharsets.UTF_8);
        output.write(app, 0, app.length);
        output.write(0);
        tuple(output, 0, appName);
        return output.toByteArray();
    }

    private static void tuple(ByteArrayOutputStream output, int id, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(id);
        output.write(bytes.length & 0xff);
        output.write(bytes.length >>> 8 & 0xff);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeUid(ByteArrayOutputStream output, long uid) {
        output.write((int) uid & 0xff);
        output.write((int) (uid >>> 8) & 0xff);
        output.write((int) (uid >>> 16) & 0xff);
        output.write((int) (uid >>> 24) & 0xff);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] value = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, value, first.length, second.length);
        return value;
    }

    private static int count(List<AncsConsumerEffectV2> effects,
                             AncsConsumerEffectV2.Type type) {
        int count = 0;
        for (AncsConsumerEffectV2 effect : effects) if (effect.type == type) count++;
        return count;
    }

    private static AncsConsumerEffectV2 last(List<AncsConsumerEffectV2> effects,
                                             AncsConsumerEffectV2.Type type) {
        AncsConsumerEffectV2 result = null;
        for (AncsConsumerEffectV2 effect : effects) if (effect.type == type) result = effect;
        return result;
    }
}
