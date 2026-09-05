/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PhoneNotificationDeferralPolicyTest {
    @Test
    public void blockerRequiresEnabledExactForegroundPackage() {
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(
                "com.ecarx.camera", "ru.yandex.yandexnavi"));
        assertTrue(PhoneNotificationDeferralPolicy.isBlocking(
                true, selected, "com.ecarx.camera"));
        assertFalse(PhoneNotificationDeferralPolicy.isBlocking(
                false, selected, "com.ecarx.camera"));
        assertFalse(PhoneNotificationDeferralPolicy.isBlocking(
                true, selected, "com.ecarx.camera.preview"));
        assertFalse(PhoneNotificationDeferralPolicy.isBlocking(true, selected, null));
    }

    @Test
    public void maximumWaitIsBoundedAndDeadlineUsesMonotonicOrigin() {
        assertEquals(1, PhoneNotificationDeferralPolicy.boundedMaxWaitSeconds(-5));
        assertEquals(30, PhoneNotificationDeferralPolicy.boundedMaxWaitSeconds(30));
        assertEquals(600, PhoneNotificationDeferralPolicy.boundedMaxWaitSeconds(9_999));
        assertEquals(42_000L, PhoneNotificationDeferralPolicy.deadline(12_000L, 30));
        assertEquals(Long.MAX_VALUE,
                PhoneNotificationDeferralPolicy.deadline(Long.MAX_VALUE - 500L, 30));
    }
}
