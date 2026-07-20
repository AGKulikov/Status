/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

public final class HaEntityMapperTest {
    @Test public void entityDetachesAndFreezesNestedAttributes() throws Exception {
        JSONObject source = new JSONArray(HaApiFixtures.SNAPSHOT).getJSONObject(0);
        HaEntity entity = HaEntity.fromJson(source);
        source.getJSONObject("attributes").getJSONObject("nested").put("value", 99);

        assertEquals("sensor.fixture_temperature", entity.entityId());
        assertEquals("sensor", entity.domain());
        assertEquals(7, HaEntityMapper.rawValue(entity, "attributes.nested.value"));
        assertEquals(2, HaEntityMapper.rawValue(entity, "samples.1"));
        assertNull(HaEntityMapper.rawValue(entity, "samples.10"));

        boolean immutable = false;
        try {
            entity.attributes().put("new", true);
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        assertTrue(immutable);
        assertTrue(entity.attribute("nested") instanceof Map<?, ?>);
    }

    @Test public void suggestsCoverBooleanTemperatureAndRawWithoutColors() throws Exception {
        HaEntity cover = HaEntity.fromJson(new JSONObject("""
                {"entity_id":"cover.fixture_entry","state":"opening","attributes":{},
                 "last_updated":"2026-01-01T10:00:00Z"}
                """));
        HaEntityMapper.Presentation coverValue = HaEntityMapper.suggestedPresentation(cover);
        assertEquals("Открывается", coverValue.text());
        assertEquals(HaEntityMapper.PresentationKind.COVER, coverValue.kind());
        assertEquals("mdi:garage", coverValue.suggestedIcon());

        HaEntity contact = HaEntity.fromJson(new JSONObject("""
                {"entity_id":"binary_sensor.fixture_door","state":"on",
                 "attributes":{"device_class":"door"},"last_updated":"2026-01-01T10:00:00Z"}
                """));
        HaEntityMapper.Presentation contactValue = HaEntityMapper.suggestedPresentation(contact);
        assertEquals("Открыто", contactValue.text());
        assertEquals(HaEntityMapper.PresentationKind.BOOLEAN, contactValue.kind());

        HaEntity temperature = HaEntityCatalog.fromStates(new JSONArray(HaApiFixtures.SNAPSHOT))
                .find("sensor.fixture_temperature");
        HaEntityMapper.Presentation temperatureValue =
                HaEntityMapper.suggestedPresentation(temperature);
        assertEquals("20.5 °C", temperatureValue.text());
        assertEquals(HaEntityMapper.PresentationKind.TEMPERATURE, temperatureValue.kind());

        HaEntity raw = HaEntity.fromJson(new JSONObject("""
                {"entity_id":"sensor.fixture_mode","state":"eco","attributes":{},
                 "last_updated":"2026-01-01T10:00:00Z"}
                """));
        assertEquals(HaEntityMapper.PresentationKind.RAW,
                HaEntityMapper.suggestedPresentation(raw).kind());
        assertTrue(HaEntityMapper.suggestedPresentation(raw).available());

        HaEntity unavailable = HaEntity.fromJson(new JSONObject("""
                {"entity_id":"sensor.fixture_missing","state":"unavailable","attributes":{},
                 "last_updated":"2026-01-01T10:00:00Z"}
                """));
        assertEquals("Недоступно", HaEntityMapper.suggestedPresentation(unavailable).text());
        assertFalse(HaEntityMapper.suggestedPresentation(unavailable).available());
    }
}
