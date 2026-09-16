package dezz.status.widget.hud;

import org.junit.Test;
import static org.junit.Assert.*;

public class HudOwnerReconcilePolicyTest {
    @Test public void integrationRetriesPreserveAHealthyOwner() {
        for (int n = 0; n < 50; n++)
            assertTrue(HudOwnerReconcilePolicy.retain(true, true, true, 5_000 + n, false));
    }
    @Test public void coldAttachAndDuplicateQuickBootAreNotInvalidations() {
        assertTrue(HudOwnerReconcilePolicy.retain(true, true, false, 10, false));
        assertTrue(HudOwnerReconcilePolicy.retain(true, true, true, 1800, true));
    }
    @Test public void invalidationAndRealQuickBootRebuildOnlyTheOwner() {
        assertFalse(HudOwnerReconcilePolicy.retain(false, true, true, 10, false));
        assertFalse(HudOwnerReconcilePolicy.retain(true, false, false, 10, false));
        assertFalse(HudOwnerReconcilePolicy.retain(true, true, false, 3000, false));
        assertFalse(HudOwnerReconcilePolicy.retain(true, true, true, 3000, true));
    }
}
