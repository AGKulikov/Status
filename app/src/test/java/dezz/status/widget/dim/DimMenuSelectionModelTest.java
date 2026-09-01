/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DimMenuSelectionModelTest {
    @Test public void wrapsInBothDirectionsWhenEnabled() {
        DimMenuSelectionModel model = new DimMenuSelectionModel(3);
        assertEquals(2, model.move(-1, true));
        assertEquals(0, model.move(1, true));
    }

    @Test public void clampsAtEdgesWhenWrappingIsDisabled() {
        DimMenuSelectionModel model = new DimMenuSelectionModel(2);
        assertEquals(0, model.move(-1, false));
        assertEquals(1, model.move(1, false));
        assertEquals(1, model.move(1, false));
    }

    @Test public void shrinkingACollectionKeepsAValidSelection() {
        DimMenuSelectionModel model = new DimMenuSelectionModel(4);
        model.move(-1, true);
        assertEquals(3, model.selectedIndex());
        model.setItemCount(2);
        assertEquals(1, model.selectedIndex());
        assertTrue(model.hasSelection());
        model.setItemCount(0);
        assertEquals(0, model.selectedIndex());
        assertFalse(model.hasSelection());
    }
}
