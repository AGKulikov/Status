/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaKeyLogRecordTest {
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
