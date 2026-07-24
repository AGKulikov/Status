/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

/** Synthetic states with non-production entity names and values. */
final class HaApiFixtures {
    private HaApiFixtures() {}

    static final String SNAPSHOT = """
            [
              {
                "entity_id":"sensor.fixture_temperature",
                "state":"20.5",
                "attributes":{
                  "friendly_name":"Fixture temperature",
                  "device_class":"temperature",
                  "unit_of_measurement":"°C",
                  "nested":{"value":7},
                  "samples":[1,2,3]
                },
                "last_updated":"2026-01-01T10:00:00+00:00"
              },
              {
                "entity_id":"light.fixture_lamp",
                "state":"on",
                "attributes":{"friendly_name":"Fixture lamp"},
                "last_updated":"2026-01-01T10:00:00+00:00"
              }
            ]
            """;

    static String stateEvent(String entityId, String state, String updatedAt) {
        return """
                {
                  "id":1,
                  "type":"event",
                  "event":{
                    "event_type":"state_changed",
                    "time_fired":"%3$s",
                    "data":{
                      "entity_id":"%1$s",
                      "old_state":null,
                      "new_state":{
                        "entity_id":"%1$s",
                        "state":"%2$s",
                        "attributes":{},
                        "last_updated":"%3$s"
                      }
                    }
                  }
                }
                """.formatted(entityId, state, updatedAt);
    }

    static String removalEvent(String entityId, String timeFired) {
        return """
                {
                  "id":1,
                  "type":"event",
                  "event":{
                    "event_type":"state_changed",
                    "time_fired":"%2$s",
                    "data":{"entity_id":"%1$s","old_state":null,"new_state":null}
                  }
                }
                """.formatted(entityId, timeFired);
    }
}
