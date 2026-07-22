/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public final class PopupOverlayConfigTest {
    @Test
    public void roundTripKeepsIndependentGeometry() throws Exception {
        PopupOverlayConfig source = PopupOverlayConfig.create("home_gate", "Ворота", 3);
        source.enabled = false;
        source.width = 780;
        source.height = 340;
        source.rows = 1;
        source.columns = 3;
        source.x = -42;
        source.y = 515;
        source.positionLocked = true;
        source.backgroundColor = "transparent";

        PopupOverlayConfig restored = PopupOverlayConfig.fromJson(source.toJson(), 0);

        assertEquals("home_gate", restored.id);
        assertEquals("Ворота", restored.name);
        assertFalse(restored.enabled);
        assertEquals(780, restored.width);
        assertEquals(340, restored.height);
        assertEquals(1, restored.rows);
        assertEquals(3, restored.columns);
        assertEquals(-42, restored.x);
        assertEquals(515, restored.y);
        assertTrue(restored.positionLocked);
        assertEquals("transparent", restored.backgroundColor);
    }

    @Test
    public void oldPopupTileMigratesToDefaultOverlay() throws Exception {
        PopupItemConfig item = PopupItemConfig.fromJson(
                new JSONObject().put("id", "gate").put("automationId", "gate"), 0);

        assertEquals(PopupItemConfig.DEFAULT_OVERLAY_ID, item.overlayId);
    }

    @Test
    public void settingsChangePreservesPositionSavedByDragController() {
        PopupOverlayConfig staleEditor = PopupOverlayConfig.create("gate", "Ворота", 0);
        staleEditor.x = 100;
        staleEditor.y = 200;
        staleEditor.positionLocked = true;

        PopupOverlayConfig dragged = PopupOverlayConfig.create("gate", "Ворота", 0);
        dragged.x = 731;
        dragged.y = 419;
        dragged.positionLocked = false;

        PopupOverlayConfigStore.preserveStoredPositions(
                Collections.singletonList(staleEditor), Collections.singletonList(dragged));

        assertEquals(731, staleEditor.x);
        assertEquals(419, staleEditor.y);
        assertTrue(staleEditor.positionLocked);
    }
}
