/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneCellularDisplayPolicyTest {
    @Test public void allSelectedPiecesHaveStableOrder() {
        PhoneCellularDisplayPolicy.Presentation value = PhoneCellularDisplayPolicy.resolve(
                72, "  beeline  ", " LTE ", true, true, true);

        assertEquals("LTE · beeline", value.text);
        assertTrue(value.known);
        assertTrue(value.active);
    }

    @Test public void operatorAndNetworkTypeCanBeHiddenIndependently() {
        assertEquals("LTE", PhoneCellularDisplayPolicy.resolve(
                72, "beeline", "LTE", true, false, true).text);
        assertEquals("beeline", PhoneCellularDisplayPolicy.resolve(
                72, "beeline", "LTE", true, true, false).text);
        assertEquals("", PhoneCellularDisplayPolicy.resolve(
                72, "beeline", "LTE", true, false, false).text);
    }

    @Test public void signalOnlyRemainsKnownWithoutInventingText() {
        PhoneCellularDisplayPolicy.Presentation value = PhoneCellularDisplayPolicy.resolve(
                0, "", "", true, false, false);

        assertEquals("", value.text);
        assertTrue(value.known);
        assertFalse(value.active);
    }

    @Test public void selectingNoComponentsProducesNoState() {
        PhoneCellularDisplayPolicy.Presentation value = PhoneCellularDisplayPolicy.resolve(
                72, "beeline", "LTE", false, false, false);

        assertEquals("", value.text);
        assertFalse(value.known);
        assertFalse(value.active);
    }
}
