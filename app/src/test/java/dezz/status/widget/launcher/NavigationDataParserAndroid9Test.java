/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;

/** Regression coverage for the regex subset supported by Android 9. */
public class NavigationDataParserAndroid9Test {
    @Test
    public void regexFlagsExcludeAndroid9UnsupportedUnicodeCharacterClass() throws Exception {
        Field flagsField = NavigationDataParser.class.getDeclaredField("REGEX_FLAGS");
        flagsField.setAccessible(true);

        // java.util.regex.Pattern.UNICODE_CHARACTER_CLASS == 0x100; Android 9 rejects it.
        assertEquals(0, flagsField.getInt(null) & 0x100);
    }

    @Test
    public void keepsCaseInsensitiveCyrillicMatchingWithoutUnsupportedFlag() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(Collections.singletonList(
                "ВРЕМЯ ПРИБЫТИЯ: 09:27 ОСТАЛОСЬ: 10 МИН · 3,5 КМ"));

        assertEquals("09:27", parsed.arrival);
        assertEquals("10 мин", parsed.duration);
        assertEquals("3,5 км", parsed.distance);
        assertTrue(NavigationDataParser.hasRouteMarker(Collections.singletonList(
                "ВрЕмЯ ПрИбЫтИя")));
    }

    @Test
    public void normalizesUnicodeSpacesInRussianNavigationText() {
        NavigationDataParser.Parsed parsed = NavigationDataParser.parse(Collections.singletonList(
                "Время\u00a0прибытия:\u202f09:27  Осталось:\u200910\u00a0мин · 3,5\u202fкм"));

        assertEquals("09:27", parsed.arrival);
        assertEquals("10 мин", parsed.duration);
        assertEquals("3,5 км", parsed.distance);
    }
}
