/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/** Pure time-source contract for local automation conditions. */
public final class SystemConditionResolverTest {
    @Test public void ordinaryRangeIncludesStartAndExcludesEnd() {
        long tenThirty = localTime(10, 30);
        assertTrue(SystemConditionResolver.inTimeRange("0800-1200", tenThirty));
        assertFalse(SystemConditionResolver.inTimeRange("1031-1200", tenThirty));
        assertFalse(SystemConditionResolver.inTimeRange("0800-1030", tenThirty));
    }

    @Test public void overnightRangeSpansMidnightAndEqualBoundsMeanAllDay() {
        assertTrue(SystemConditionResolver.inTimeRange("2200-0600", localTime(23, 45)));
        assertTrue(SystemConditionResolver.inTimeRange("2200-0600", localTime(5, 59)));
        assertFalse(SystemConditionResolver.inTimeRange("2200-0600", localTime(12, 0)));
        assertTrue(SystemConditionResolver.inTimeRange("0800-0800", localTime(2, 0)));
    }

    @Test public void malformedRangeFailsClosedAndResourceIsCanonical() {
        assertNull(SystemConditionResolver.inTimeRange("25:00-08:00", localTime(2, 0)));
        assertNull(SystemConditionResolver.inTimeRange("bad", localTime(2, 0)));
        assertEquals("time.range:0830-2045",
                SystemConditionResolver.timeRangeResource("08:30", "20:45"));
    }

    @Test public void preciseDrSourceAndLegacyRouteNameResolveToTheSameRuntimeSignal() {
        assertTrue(SystemConditionResolver.isHwgpsDrResource("hwgps.dr_active"));
        assertTrue(SystemConditionResolver.isHwgpsDrResource("hwgps.route_lost"));
        assertFalse(SystemConditionResolver.isHwgpsDrResource("hwgps.other"));
    }

    private static long localTime(int hour, int minute) {
        Calendar value = Calendar.getInstance();
        value.clear();
        value.set(2026, Calendar.JULY, 27, hour, minute, 0);
        return value.getTimeInMillis();
    }
}
