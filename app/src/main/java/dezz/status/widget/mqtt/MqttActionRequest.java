/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.SourceBinding;

/** Pure, validated MQTT publish request derived from a connector-neutral action binding. */
final class MqttActionRequest {
    private static final Pattern LEGACY_ACTION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    final String topic;
    final String payload;

    private MqttActionRequest(String topic, String payload) {
        this.topic = topic;
        this.payload = payload;
    }

    /**
     * A legacy safe id is resolved below the widget command prefix. A resource containing a
     * topic separator (or beginning with {@code $}) is an explicit broker topic and is published
     * unchanged. This preserves old action ids while allowing direct Sprut/HA MQTT topics.
     */
    static MqttActionRequest from(String commandPrefix, ActionBinding binding) {
        if (binding == null || binding.connectorType != ConnectorType.MQTT
                || !binding.isBound()) {
            throw new IllegalArgumentException("Not a bound MQTT action");
        }
        if (!SourceBinding.DEFAULT_CONNECTOR_ID.equals(binding.connectorId)) {
            throw new IllegalArgumentException("Unknown MQTT connector profile: "
                    + binding.connectorId);
        }
        if (!ActionBinding.OPERATION_PUBLISH.equals(binding.operation)) {
            throw new IllegalArgumentException("MQTT supports only PUBLISH actions");
        }

        String resource = binding.resourceId;
        final String topic;
        if (resource.indexOf('/') >= 0 || resource.startsWith("$")) {
            topic = resource;
        } else {
            if (!LEGACY_ACTION_ID.matcher(resource).matches()) {
                throw new IllegalArgumentException("Invalid legacy MQTT action id");
            }
            topic = (commandPrefix == null ? "" : commandPrefix) + resource;
        }
        validateTopic(topic);
        return new MqttActionRequest(topic, binding.payload);
    }

    private static void validateTopic(String topic) {
        byte[] bytes = topic.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 0xffff || topic.indexOf('\u0000') >= 0
                || topic.indexOf('#') >= 0 || topic.indexOf('+') >= 0) {
            throw new IllegalArgumentException("Invalid MQTT publish topic");
        }
    }
}
