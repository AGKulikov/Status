/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public final class SprutProtocolAdapterTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompatibleRoomSnapshotInsteadOfPublishingEmptyCatalog() {
        SprutProtocolAdapter.parseRoomListSnapshot(new JSONObject());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompatibleAccessorySnapshotInsteadOfPublishingEmptyCatalog() {
        SprutProtocolAdapter.parseAccessoryListSnapshot(new JSONObject());
    }

    @Test public void parsesRoomAccessoryServiceAndCharacteristicSnapshots() throws Exception {
        SprutCatalog catalog = SprutProtocolAdapter.parseCatalog(
                SprutFixtures.ROOMS, SprutFixtures.ACCESSORIES);

        assertEquals(2, catalog.rooms().size());
        assertEquals("Zone A", catalog.findRoom(10).name());
        assertEquals(3, catalog.accessories().size());
        assertEquals(3, catalog.services().size());
        assertEquals(3, catalog.characteristics().size());

        SprutCatalog.Accessory entry = catalog.findAccessory(101);
        assertNotNull(entry);
        assertEquals(Long.valueOf(10), entry.roomId());
        assertEquals("Zone A", catalog.roomNameFor(entry));
        assertTrue(entry.online());

        SprutCatalog.Characteristic door = catalog.find(new SprutPath(101, 201, 301));
        assertNotNull(door);
        assertEquals("CurrentDoorState", door.type());
        assertEquals("uint8", door.format());
        assertEquals("none", door.unit());
        assertTrue(door.readable());
        assertTrue(door.writable());
        assertTrue(door.events());
        assertEquals(1, door.currentValue());
        assertEquals(SprutCatalog.ValueType.INTEGER, door.valueType());
        assertEquals(2, door.validValues().size());

        SprutCatalog.Characteristic temperature =
                catalog.find(new SprutPath(102, 202, 302));
        assertNotNull(temperature);
        assertEquals(54.25d, ((Number) temperature.currentValue()).doubleValue(), 0.0001d);
        assertFalse(temperature.writable());
        assertNull(catalog.find(new SprutPath(999, 999, 999)));
    }

    @Test public void parsesAndAppliesEventUpdate() throws Exception {
        SprutCatalog catalog = SprutProtocolAdapter.parseCatalog(
                SprutFixtures.ROOMS, SprutFixtures.ACCESSORIES);
        JSONObject event = new JSONObject(SprutFixtures.COVER_OPENING_EVENT);

        List<SprutProtocolAdapter.EventUpdate> updates =
                SprutProtocolAdapter.parseEventUpdates(event);
        assertEquals(1, updates.size());
        assertEquals(new SprutPath(101, 201, 301), updates.get(0).path());
        assertEquals(2, updates.get(0).value());
        assertEquals(1, SprutProtocolAdapter.applyEventUpdate(catalog, event));
        assertEquals(2, catalog.find(new SprutPath(101, 201, 301)).currentValue());
    }

    @Test public void convertsEverySupportedTypedValue() throws Exception {
        assertEquals(true, SprutProtocolAdapter.typedValue(
                new JSONObject("{\"boolValue\":true}")));
        assertEquals(7, SprutProtocolAdapter.typedValue(
                new JSONObject("{\"intValue\":7}")));
        assertEquals(8L, SprutProtocolAdapter.typedValue(
                new JSONObject("{\"longValue\":8}")));
        assertEquals(2.5d, (Double) SprutProtocolAdapter.typedValue(
                new JSONObject("{\"doubleValue\":2.5}")), 0.0001d);
        assertEquals(1.5f, (Float) SprutProtocolAdapter.typedValue(
                new JSONObject("{\"floatValue\":1.5}")), 0.0001f);
        assertEquals("value", SprutProtocolAdapter.typedValue(
                new JSONObject("{\"stringValue\":\"value\"}")));
    }

    @Test public void buildsAuthListAndTypedUpdateParams() throws Exception {
        JSONObject auth = SprutProtocolAdapter.buildAuthParams();
        assertEquals(0, auth.getJSONObject("account").getJSONObject("auth")
                .getJSONArray("params").length());
        assertEquals("user@example.invalid", SprutProtocolAdapter
                .buildAuthAnswerParams("user@example.invalid")
                .getJSONObject("account").getJSONObject("answer").getString("data"));

        assertNotNull(SprutProtocolAdapter.buildRoomListParams()
                .getJSONObject("room").getJSONObject("list"));
        assertEquals(SprutProtocolAdapter.EXPAND_PLUS,
                SprutProtocolAdapter.buildAccessoryListParams("services+characteristics")
                        .getJSONObject("accessory").getJSONObject("list").getString("expand"));
        assertEquals(SprutProtocolAdapter.EXPAND_COMMA,
                SprutProtocolAdapter.buildAccessoryListParams("services,characteristics")
                        .getJSONObject("accessory").getJSONObject("list").getString("expand"));

        JSONObject update = SprutProtocolAdapter.buildCharacteristicUpdateParams(
                new SprutPath(101, 201, 301), 3, SprutCatalog.ValueType.INTEGER)
                .getJSONObject("characteristic").getJSONObject("update");
        assertEquals(101, update.getLong("aId"));
        assertEquals(201, update.getLong("sId"));
        assertEquals(301, update.getLong("cId"));
        assertEquals(3, update.getJSONObject("control").getJSONObject("value")
                .getInt("intValue"));
    }
}
