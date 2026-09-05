/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public final class PhoneNotificationFilterTest {
    @Test
    public void categoriesRoundTripInCanonicalOrder() {
        Set<Integer> parsed = PhoneNotificationFilter.parseCategoryIds("11,4,4,0,99,bad");
        assertEquals(Arrays.asList(0, 4, 11), Arrays.asList(parsed.toArray()));
        assertEquals("0,4,11", PhoneNotificationFilter.serializeCategoryIds(parsed));
        assertEquals(12, PhoneNotificationFilter.categories().size());
    }

    @Test
    public void unknownFutureCategoriesUseTheOtherSwitch() {
        Set<Integer> otherOnly = Collections.singleton(0);
        assertTrue(PhoneNotificationFilter.allowsCategory(otherOnly, 0));
        assertTrue(PhoneNotificationFilter.allowsCategory(otherOnly, 42));
        assertFalse(PhoneNotificationFilter.allowsCategory(
                Collections.singleton(4), 42));
    }

    @Test
    public void appModesApplyAfterCategoryFilter() {
        Set<Integer> categories = PhoneNotificationFilter.allCategoryIds();
        Set<String> selected = PhoneNotificationFilter.parseAppKeys(
                "NET.WHATSAPP.WHATSAPP, com.apple.mobilemail");

        assertTrue(PhoneNotificationFilter.allows(
                PhoneNotificationFilter.MODE_ALL, Collections.emptySet(),
                categories, "anything", 6));
        assertTrue(PhoneNotificationFilter.allows(
                PhoneNotificationFilter.MODE_ONLY_SELECTED, selected,
                categories, "net.whatsapp.whatsapp", 4));
        assertFalse(PhoneNotificationFilter.allows(
                PhoneNotificationFilter.MODE_ONLY_SELECTED, selected,
                categories, "com.apple.mobilecal", 5));
        assertFalse(PhoneNotificationFilter.allows(
                PhoneNotificationFilter.MODE_EXCEPT_SELECTED, selected,
                categories, "com.apple.mobilemail", 6));
        assertTrue(PhoneNotificationFilter.allows(
                PhoneNotificationFilter.MODE_EXCEPT_SELECTED, selected,
                categories, "com.apple.mobilecal", 5));
        assertFalse(PhoneNotificationFilter.allows(
                PhoneNotificationFilter.MODE_ALL, Collections.emptySet(),
                Collections.singleton(4), "net.whatsapp.whatsapp", 6));
    }

    @Test
    public void malformedAppKeysCannotEnterThePersistedFilter() {
        assertEquals("com.apple.mobilemail,net.whatsapp.whatsapp",
                PhoneNotificationFilter.serializeAppKeys(Arrays.asList(
                        " net.whatsapp.WhatsApp ", "bad,key", "",
                        "com.apple.mobilemail")));
        assertEquals("", PhoneNotificationFilter.normalizeAppKey("bad key"));
        assertEquals(PhoneNotificationFilter.MODE_ALL,
                PhoneNotificationFilter.normalizeMode(99));
    }
}
