/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutomationContractTest {
    @Test public void acceptsStableIds() {
        assertEquals("builtin.time", AutomationContract.requireSafeId("builtin.time"));
        assertEquals("vezd_1", AutomationContract.requireSafeId(" vezd_1 "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTopicInjection() {
        AutomationContract.requireSafeId("popup/door");
    }

    @Test public void parsesHaBooleans() {
        assertTrue(AutomationContract.parseBoolean("on"));
        assertTrue(AutomationContract.parseBoolean("visible"));
        assertFalse(AutomationContract.parseBoolean("off"));
    }
}
