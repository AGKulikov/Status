/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class SprutValueMapperTest {
    @Test public void mapsAllFiveGarageDoorStates() throws Exception {
        SprutCatalog catalog = catalog();
        SprutCatalog.Characteristic door = catalog.find(new SprutPath(101, 201, 301));
        String[] text = {"Открыто", "Закрыто", "Открывается", "Закрывается", "Остановлено"};
        String[] color = {
                SprutValueMapper.COLOR_GREEN,
                SprutValueMapper.COLOR_WHITE,
                SprutValueMapper.COLOR_ORANGE,
                SprutValueMapper.COLOR_RED,
                SprutValueMapper.COLOR_YELLOW
        };
        for (int state = 0; state < text.length; state++) {
            JSONObject event = new JSONObject("""
                    {"event":{"characteristic":{"event":"EVENT_UPDATE","characteristics":[
                      {"aId":101,"sId":201,"cId":301,"control":{"value":{"intValue":%d}}}
                    ]}}}
                    """.formatted(state));
            SprutProtocolAdapter.applyEventUpdate(catalog, event);
            SprutValueMapper.DisplayValue display = SprutValueMapper.toDisplayPayload(door, 1000L);
            assertEquals(text[state], display.text());
            assertEquals(color[state], display.color());
            assertTrue(display.visible());
            assertTrue(display.actionEnabled());
            assertEquals("garage", SprutValueMapper.iconFor(door));
        }
    }

    @Test public void mapsBooleanAndTemperaturePresets() throws Exception {
        SprutCatalog catalog = catalog();
        SprutCatalog.Characteristic relay = catalog.find(new SprutPath(103, 203, 303));
        SprutValueMapper.DisplayValue off = SprutValueMapper.toDisplayPayload(relay, 2000L);
        assertEquals("Выключено", off.text());
        assertEquals(SprutValueMapper.COLOR_WHITE, off.color());
        assertTrue(off.actionEnabled());
        assertEquals("power", SprutValueMapper.iconFor(relay));

        SprutCatalog.Characteristic temperature =
                catalog.find(new SprutPath(102, 202, 302));
        SprutValueMapper.DisplayValue value =
                SprutValueMapper.toDisplayPayload(temperature, 3000L);
        assertEquals("54.25 °C", value.text());
        assertEquals(SprutValueMapper.COLOR_WHITE, value.color());
        assertTrue(value.visible());
        assertFalse(value.actionEnabled());
        assertEquals("temperature", SprutValueMapper.iconFor(temperature));
    }

    @Test public void createsFreshSprutHubPatchWithCallerTimestamp() throws Exception {
        SprutCatalog.Characteristic door = catalog().find(new SprutPath(101, 201, 301));
        JSONObject patch = SprutValueMapper.toJsonPatch(door, 123456789L);
        assertEquals("SPRUTHUB", patch.getString("source"));
        assertTrue(patch.getBoolean("fresh"));
        assertEquals(123456789L, patch.getLong("updated_at"));
        assertEquals("Закрыто", patch.getString("text"));
    }

    private static SprutCatalog catalog() throws Exception {
        return SprutProtocolAdapter.parseCatalog(SprutFixtures.ROOMS, SprutFixtures.ACCESSORIES);
    }
}
