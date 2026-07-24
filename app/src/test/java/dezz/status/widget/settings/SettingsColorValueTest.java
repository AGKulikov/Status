/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SettingsColorValueTest {
    @Test
    public void sixDigitColorRoundTripsWithoutAddingAlpha() {
        SettingsColorValue value = SettingsColorValue.tryParse("#35b7ff");

        assertEquals(SettingsColorValue.Kind.COLOR, value.kind());
        assertEquals(0xFF35B7FF, value.argb());
        assertFalse(value.hasExplicitAlpha());
        assertEquals("#35B7FF", value.serialize());
    }

    @Test
    public void eightDigitColorRoundTripsWithAlpha() {
        SettingsColorValue value = SettingsColorValue.tryParse("#8035b7ff");

        assertEquals(SettingsColorValue.Kind.COLOR, value.kind());
        assertEquals(0x8035B7FF, value.argb());
        assertTrue(value.hasExplicitAlpha());
        assertEquals("#8035B7FF", value.serialize());
    }

    @Test
    public void semanticValuesRemainDistinct() {
        assertEquals(SettingsColorValue.Kind.TRANSPARENT,
                SettingsColorValue.tryParse("TRANSPARENT").kind());
        assertEquals("transparent", SettingsColorValue.tryParse("transparent").serialize());
        assertEquals(SettingsColorValue.Kind.NONE, SettingsColorValue.tryParse("none").kind());
        assertEquals("none", SettingsColorValue.tryParse("NONE").serialize());
        assertEquals(SettingsColorValue.Kind.INHERIT, SettingsColorValue.tryParse(null).kind());
        assertNull(SettingsColorValue.tryParse(null).serialize());
    }

    @Test
    public void alphaIsEmittedWhenNeeded() {
        assertEquals("#00FFFFFF", SettingsColorValue.serializeColor(0x00FFFFFF, false));
        assertEquals("#FFFFFFFF", SettingsColorValue.serializeColor(0xFFFFFFFF, true));
        assertEquals("#FFFFFF", SettingsColorValue.serializeColor(0xFFFFFFFF, false));
    }

    @Test
    public void malformedPersistedValueDoesNotBecomeAColor() {
        assertNull(SettingsColorValue.tryParse(""));
        assertNull(SettingsColorValue.tryParse("#12345"));
        assertNull(SettingsColorValue.tryParse("#GGFFFFFF"));
        assertNull(SettingsColorValue.tryParse("red"));
    }
}
