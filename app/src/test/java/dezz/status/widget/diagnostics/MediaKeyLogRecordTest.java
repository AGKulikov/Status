/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaKeyLogRecordTest {
    @Test public void virtualKeysAndSystemStagesRemainDistinctFromMissingFields() {
        String header = "09-14 18:02:31.848  812  934 D MediaSessionService: ";
        String event = "KeyEvent { action=ACTION_DOWN, keyCode=KEYCODE_MEDIA_NEXT, "
                + "repeatCount=0, eventTime=802256, downTime=802256, deviceId=-1 }";
        MediaKeyLogRecord entry = MediaKeyLogRecord.parse(header
                + "dispatchMediaKeyEvent, pkg=private.package pid=735, uid=10027, asSystem=false, event=" + event);
        assertNotNull(entry);
        assertEquals("system_entry", entry.stage);
        assertTrue(entry.deviceIdPresent);
        assertEquals(-1, entry.deviceId);
        assertEquals(812, entry.sourcePid); assertEquals(934, entry.sourceTid);
        assertEquals(735, entry.callerPid); assertEquals(10027, entry.callerUid);
        assertEquals(802256L, entry.eventTime);
        assertEquals("session_send", MediaKeyLogRecord.parse(header + "Sending " + event + " to private.session").stage);
        assertEquals("listener_forward", MediaKeyLogRecord.parse(header + "Send " + event + " to the media key listener").stage);
        assertEquals("listener_timeout", MediaKeyLogRecord.parse(header + "The media key listener is timed-out for " + event).stage);
        assertEquals("broadcast_send", MediaKeyLogRecord.parse(header + "Sending " + event + " to the last known PendingIntent private").stage);
        MediaKeyLogRecord incomplete = MediaKeyLogRecord.parse(header + "keyCode=87 action=0");
        assertFalse(incomplete.deviceIdPresent);
        assertEquals("key_record", incomplete.stage);
    }

    @Test public void standardSessionKeyCarriesOriginalUptimeWithoutCopyingMessage() {
        MediaKeyLogRecord record = MediaKeyLogRecord.parse("09-14 07:17:20.521  812  934 D MediaSessionService: "
                + "Sending media key KeyEvent { action=ACTION_DOWN, keyCode=KEYCODE_MEDIA_NEXT, "
                + "repeatCount=0, eventTime=601203, downTime=601200, deviceId=4 } to private.player");
        assertNotNull(record);
        assertEquals(87, record.keyCode);
        assertEquals(0, record.action);
        assertEquals(601203L, record.eventTime);
        assertEquals(601200L, record.downTime);
        assertEquals(4, record.deviceId);
        assertEquals("MediaSessionService", record.tag);
        assertEquals("09-14 07:17:20.521", record.timestamp);
    }

    @Test public void partialNumericInputDoesNotInventMissingTimesOrDevice() {
        MediaKeyLogRecord record = MediaKeyLogRecord.parse("09-14 07:17:20.521  812  934 I XSFInputService: keyCode=88 action=1");
        assertNotNull(record);
        assertEquals(88, record.keyCode);
        assertEquals(1, record.action);
        assertEquals(-1L, record.eventTime);
        assertEquals(-1L, record.downTime);
        assertEquals(-1, record.deviceId);
    }

    @Test public void unrelatedMessagesAndMalformedEventsAreExcluded() {
        String prefix = "09-14 07:17:20.521  812  934 I ";
        assertNull(MediaKeyLogRecord.parse(prefix + "PrivateApp: keyCode=87 action=0"));
        assertNull(MediaKeyLogRecord.parse(prefix + "AudioService: private track metadata"));
        assertNull(MediaKeyLogRecord.parse(prefix + "InputService: keyCode=29 action=0"));
        assertNull(MediaKeyLogRecord.parse(prefix + "InputService: keyCode=87"));
        assertNull(MediaKeyLogRecord.parse(prefix + "InputService: keyCode=870 action=0"));
        assertNull(MediaKeyLogRecord.parse("keyCode=87 action=0"));
        assertNull(MediaKeyLogRecord.parse(null));
        MediaKeyLogRecord record = MediaKeyLogRecord.parse(prefix + "AudioService: keyCode=87 action=0 eventTime=9999999999999999999999999");
        assertNotNull(record);
        assertEquals(-1L, record.eventTime);
    }
}
