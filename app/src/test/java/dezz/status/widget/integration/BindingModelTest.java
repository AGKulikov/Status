/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BindingModelTest {
    @Test public void legacySourceKeepsStatusWidgetAutomationId() {
        SourceBinding binding = SourceBinding.legacy("gate_tile");

        assertEquals(ConnectorType.MQTT, binding.connectorType);
        assertEquals("default", binding.connectorId);
        assertEquals("gate_tile", binding.resourceId);
        assertEquals(SourceBinding.PRESENTATION_AUTO, binding.presentation);
        assertTrue(binding.isBound());
    }

    @Test public void sprutSourceUsesCanonicalCharacteristicPath() {
        SourceBinding binding = new SourceBinding(ConnectorType.SPRUTHUB, "default",
                "42/7/12", "", "temperature", " °C ");

        assertEquals("42/7/12", binding.resourceId);
        assertEquals(SourceBinding.PRESENTATION_TEMPERATURE, binding.presentation);
        assertEquals("°C", binding.unitSuffix);
    }

    @Test public void legacyActionPublishesOriginalCommand() {
        ActionBinding binding = ActionBinding.legacy("toggle_gate", "{\"open\":true}");

        assertEquals(ConnectorType.MQTT, binding.connectorType);
        assertEquals(ActionBinding.OPERATION_PUBLISH, binding.operation);
        assertEquals("toggle_gate", binding.resourceId);
        assertEquals("{\"open\":true}", binding.payload);
        assertTrue(binding.isBound());
    }

    @Test public void emptyLegacyActionIsUnbound() {
        assertFalse(ActionBinding.unbound().isBound());
    }

    @Test public void connectorNamesAreCaseInsensitiveOnRead() {
        assertEquals(ConnectorType.HOME_ASSISTANT,
                ConnectorType.fromJsonName("home_assistant", ConnectorType.MQTT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void connectorIdRejectsTopicSeparators() {
        new SourceBinding(ConnectorType.MQTT, "bad/profile", "state/topic", "",
                SourceBinding.PRESENTATION_RAW, "");
    }
}
