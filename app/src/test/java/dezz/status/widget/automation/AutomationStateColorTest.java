/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AutomationStateColorTest {
    @Test public void recognizesEverySupportedFullyTransparentRuleColor() {
        assertTrue(AutomationState.isFullyTransparentColor("transparent"));
        assertTrue(AutomationState.isFullyTransparentColor(" TRANSPARENT "));
        assertTrue(AutomationState.isFullyTransparentColor("#00000000"));
        assertTrue(AutomationState.isFullyTransparentColor("#0ABC"));
    }

    @Test public void doesNotHideOpaqueOrInvalidRuleColors() {
        assertFalse(AutomationState.isFullyTransparentColor(null));
        assertFalse(AutomationState.isFullyTransparentColor(""));
        assertFalse(AutomationState.isFullyTransparentColor("#000000"));
        assertFalse(AutomationState.isFullyTransparentColor("#01000000"));
        assertFalse(AutomationState.isFullyTransparentColor("not-a-color"));
    }
}
