/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

/** Synthetic fixtures: names, ids, vendor, model, and serial do not identify a real installation. */
final class SprutFixtures {
    private SprutFixtures() {}

    static final String ROOMS = """
            {
              "jsonrpc":"2.0",
              "id":7,
              "result":{"room":{"list":{"rooms":[
                {"id":10,"name":"Zone A","order":0,"type":"ROOM","visible":true},
                {"id":"11","name":"Zone B","order":1,"visible":false}
              ]}}}
            }
            """;

    static final String ACCESSORIES = """
            {
              "result":{"accessory":{"list":{"accessories":[
                {
                  "id":101,
                  "roomId":10,
                  "name":"Entry controller",
                  "manufacturer":"Vendor A",
                  "model":"Model X",
                  "serial":"SERIAL-REDACTED-1",
                  "firmware":"1.0",
                  "online":true,
                  "virtual":false,
                  "services":[{
                    "aId":101,
                    "sId":201,
                    "name":"Entry",
                    "type":"GarageDoorOpener",
                    "visible":true,
                    "characteristics":[{
                      "aId":101,
                      "sId":201,
                      "cId":301,
                      "control":{
                        "name":"Door state",
                        "type":"CurrentDoorState",
                        "format":"uint8",
                        "unit":"none",
                        "read":true,
                        "write":true,
                        "events":true,
                        "value":{"intValue":1},
                        "validValues":[
                          {"value":{"intValue":0},"key":"open","name":"Open"},
                          {"value":{"intValue":1},"key":"closed","name":"Closed"}
                        ]
                      }
                    }]
                  }]
                },
                {
                  "id":102,
                  "roomId":11,
                  "name":"Climate probe",
                  "manufacturer":"Vendor B",
                  "model":"Model Y",
                  "serial":"SERIAL-REDACTED-2",
                  "online":true,
                  "services":[{
                    "sId":202,
                    "name":"Water temperature",
                    "type":"TemperatureSensor",
                    "characteristics":[{
                      "cId":302,
                      "name":"Temperature",
                      "type":"CurrentTemperature",
                      "format":"float",
                      "unit":"celsius",
                      "read":true,
                      "events":true,
                      "value":{"doubleValue":54.25}
                    }]
                  }]
                },
                {
                  "id":103,
                  "name":"Test relay",
                  "manufacturer":"Vendor C",
                  "model":"Model Z",
                  "serial":"SERIAL-REDACTED-3",
                  "online":false,
                  "services":[{
                    "sId":203,
                    "name":"Relay",
                    "type":"Switch",
                    "characteristics":[{
                      "cId":303,
                      "control":{
                        "name":"Power",
                        "type":"On",
                        "read":true,
                        "write":true,
                        "events":true,
                        "value":{"boolValue":false}
                      }
                    }]
                  }]
                }
              ]}}}
            }
            """;

    static final String COVER_OPENING_EVENT = """
            {
              "event":{"characteristic":{
                "event":"EVENT_UPDATE",
                "characteristics":[{
                  "aId":101,"sId":201,"cId":301,
                  "control":{"value":{"intValue":2}}
                }]
              }}
            }
            """;
}
