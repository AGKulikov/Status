/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;

public final class MqttActionRequestTest {
    @Test public void legacyActionIdUsesWidgetCommandPrefixAndExactPayload() {
        ActionBinding binding = ActionBinding.legacy("toggle_gate", "{\"open\":true}");

        MqttActionRequest request = MqttActionRequest.from(
                "statuswidget/v1/car/command/", binding);

        assertEquals("statuswidget/v1/car/command/toggle_gate", request.topic);
        assertEquals("{\"open\":true}", request.payload);
    }

    @Test public void explicitTopicAndPrimitivePayloadAreNotRewritten() {
        ActionBinding binding = new ActionBinding(ConnectorType.MQTT, "default",
                "home/gate/set", ActionBinding.OPERATION_PUBLISH, "  OPEN  ");

        MqttActionRequest request = MqttActionRequest.from("ignored/command/", binding);

        assertEquals("home/gate/set", request.topic);
        assertEquals("  OPEN  ", request.payload);
    }

    @Test public void dollarTopicWithoutSlashIsExplicit() {
        ActionBinding binding = new ActionBinding(ConnectorType.MQTT, "default",
                "$direct", ActionBinding.OPERATION_PUBLISH, "1");

        assertEquals("$direct", MqttActionRequest.from("ignored/", binding).topic);
    }

    @Test public void nonPublishOperationAndWildcardTopicAreRejected() {
        assertRejected(new ActionBinding(ConnectorType.MQTT, "default", "home/gate/set",
                ActionBinding.OPERATION_SET, "open"));
        assertRejected(new ActionBinding(ConnectorType.MQTT, "default", "home/+/set",
                ActionBinding.OPERATION_PUBLISH, "open"));
    }

    private static void assertRejected(ActionBinding binding) {
        try {
            MqttActionRequest.from("statuswidget/command/", binding);
            fail("Expected MQTT action to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
