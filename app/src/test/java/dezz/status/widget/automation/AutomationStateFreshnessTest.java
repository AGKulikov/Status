/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pure immutable projection used while the persisted restart barrier is delayed. */
public final class AutomationStateFreshnessTest {
    @Test public void freshCachedValueBecomesStaleWithoutLosingPresentation() throws Exception {
        AutomationState fresh = AutomationState.fromJson(new JSONObject()
                .put("text", "21.5 °C")
                .put("color", "#FF00FF00")
                .put("visible", false)
                .put("action_enabled", true)
                .put("fresh", true)
                .put("source", "ha")
                .put("updated_at", 1234L)
                .put("expires_at", 5678L));

        AutomationState stale = fresh.asStale();

        assertTrue(stale.present);
        assertFalse(stale.fresh);
        assertEquals(fresh.text, stale.text);
        assertEquals(fresh.color, stale.color);
        assertEquals(fresh.visible, stale.visible);
        assertEquals(fresh.actionEnabled, stale.actionEnabled);
        assertEquals(fresh.source, stale.source);
        assertEquals(fresh.updatedAt, stale.updatedAt);
        assertEquals(fresh.expiresAt, stale.expiresAt);
    }

    @Test public void missingAndAlreadyStaleValuesAreNotReallocated() throws Exception {
        AutomationState missing = AutomationState.missing();
        AutomationState stale = AutomationState.fromJson(
                new JSONObject().put("fresh", false));

        assertSame(missing, missing.asStale());
        assertSame(stale, stale.asStale());
    }
}
