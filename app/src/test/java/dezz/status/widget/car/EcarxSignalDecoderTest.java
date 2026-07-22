/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EcarxSignalDecoderTest {
    @Test public void selectorSignalMatchesObservedEcarxLeverEncoding() {
        assertEquals(Integer.valueOf(2_097_712),
                EcarxSignalDecoder.selectorToAdaptGear(0));
        assertEquals(Integer.valueOf(2_097_728),
                EcarxSignalDecoder.selectorToAdaptGear(1));
        assertEquals(Integer.valueOf(2_097_680),
                EcarxSignalDecoder.selectorToAdaptGear(2));
        assertEquals(Integer.valueOf(2_097_696),
                EcarxSignalDecoder.selectorToAdaptGear(3));
        assertEquals(Integer.valueOf(2_097_696),
                EcarxSignalDecoder.selectorToAdaptGear(4));
        assertNull(EcarxSignalDecoder.selectorToAdaptGear(253));
    }

    @Test public void actualGearAcceptsRawAndAdaptApiValues() {
        assertEquals(1, EcarxSignalDecoder.normalizeActualGear(1));
        assertEquals(10, EcarxSignalDecoder.normalizeActualGear(10));
        assertEquals(1, EcarxSignalDecoder.normalizeActualGear(2_097_665));
        assertEquals(10, EcarxSignalDecoder.normalizeActualGear(2_097_674));
        assertEquals(0, EcarxSignalDecoder.normalizeActualGear(0));
        assertEquals(0, EcarxSignalDecoder.normalizeActualGear(255));
    }

    @Test public void pnrWinAndDriveComposesAutomaticOrManualRatio() {
        assertEquals(Integer.valueOf(2_097_712),
                EcarxSignalDecoder.composeAdaptGear(0, 7, true));
        assertEquals(Integer.valueOf(2_097_728),
                EcarxSignalDecoder.composeAdaptGear(1, 7, true));
        assertEquals(Integer.valueOf(2_097_680),
                EcarxSignalDecoder.composeAdaptGear(2, 7, true));
        assertEquals(Integer.valueOf(2_097_671),
                EcarxSignalDecoder.composeAdaptGear(3, 7, false));
        assertEquals(Integer.valueOf(-10_007),
                EcarxSignalDecoder.composeAdaptGear(4, 7, true));
        assertEquals(Integer.valueOf(2_097_696),
                EcarxSignalDecoder.composeAdaptGear(3, 255, false));
        assertNull(EcarxSignalDecoder.composeAdaptGear(null, 2, false));
    }

    @Test public void displayTextPreservesLeverAndAddsMhubStyleRatioPrefix() {
        assertEquals("P", EcarxSignalDecoder.gearDisplayName(2_097_712));
        assertEquals("R", EcarxSignalDecoder.gearDisplayName(2_097_728));
        assertEquals("N", EcarxSignalDecoder.gearDisplayName(2_097_680));
        assertEquals("D", EcarxSignalDecoder.gearDisplayName(2_097_696));
        assertEquals("D1", EcarxSignalDecoder.gearDisplayName(2_097_665));
        assertEquals("D10", EcarxSignalDecoder.gearDisplayName(2_097_674));
        assertEquals("M1", EcarxSignalDecoder.gearDisplayName(-10_001));
        assertEquals("M10", EcarxSignalDecoder.gearDisplayName(-10_010));
        assertNull(EcarxSignalDecoder.gearDisplayName(123));
    }

    @Test public void manualModeRejectsVendorSentinels() {
        assertFalse(EcarxSignalDecoder.isManualModeValue(0));
        assertTrue(EcarxSignalDecoder.isManualModeValue(1));
        assertTrue(EcarxSignalDecoder.isManualModeValue(252));
        assertFalse(EcarxSignalDecoder.isManualModeValue(253));
        assertFalse(EcarxSignalDecoder.isManualModeValue(254));
        assertFalse(EcarxSignalDecoder.isManualModeValue(255));
    }

    @Test public void highBeamAcceptsSteadyDomainAndRejectsUnknowns() {
        assertEquals(0, EcarxSignalDecoder.normalizeHighBeam(0));
        assertEquals(1, EcarxSignalDecoder.normalizeHighBeam(1));
        assertEquals(1, EcarxSignalDecoder.normalizeHighBeam(253));
        assertEquals(-1, EcarxSignalDecoder.normalizeHighBeam(-1));
        assertEquals(-1, EcarxSignalDecoder.normalizeHighBeam(254));
        assertEquals(-1, EcarxSignalDecoder.normalizeHighBeam(255));
    }

    @Test public void propertyNameDiscoveryAvoidsFlashAutoAndLeverSignals() {
        assertTrue(EcarxSignalDecoder.isHighBeamPropertyName("ExtrLtgStsHiBeam"));
        assertTrue(EcarxSignalDecoder.isHighBeamPropertyName("HiBeamActv"));
        assertFalse(EcarxSignalDecoder.isHighBeamPropertyName("AutoHiBeamSts"));
        assertFalse(EcarxSignalDecoder.isHighBeamPropertyName("HiBeamFlash"));

        assertTrue(EcarxSignalDecoder.isManualModePropertyName("TrnsmShftMod"));
        assertTrue(EcarxSignalDecoder.isManualModePropertyName("TipTronic"));
        assertFalse(EcarxSignalDecoder.isManualModePropertyName("GearLvrPosn"));
        assertFalse(EcarxSignalDecoder.isManualModePropertyName("ShiftPaddle"));
    }

    @Test public void wrapperValuesAreDecodedWithoutVendorTypes() {
        assertEquals(Integer.valueOf(7), EcarxSignalDecoder.coerceInteger(7L));
        assertEquals(Integer.valueOf(1), EcarxSignalDecoder.coerceInteger(true));
        assertEquals(Integer.valueOf(42), EcarxSignalDecoder.coerceInteger(" 42 "));
        assertEquals(Integer.valueOf(9),
                EcarxSignalDecoder.coerceInteger(new ValueWrapper(new DataWrapper(9))));
        assertNull(EcarxSignalDecoder.coerceInteger(new Object()));
    }

    public static final class ValueWrapper {
        private final Object value;
        ValueWrapper(Object value) { this.value = value; }
        public Object getValue() { return value; }
    }

    public static final class DataWrapper {
        private final int data;
        DataWrapper(int data) { this.data = data; }
        public int getData() { return data; }
    }
}
