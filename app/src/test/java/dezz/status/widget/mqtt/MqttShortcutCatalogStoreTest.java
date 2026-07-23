/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;

public final class MqttShortcutCatalogStoreTest {
    @Test public void codecPersistsOnlyBoundedSemanticMetadataAndMakesItStale() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("device_class", "garage_door");
        attributes.put("friendly_name", "Ворота");
        attributes.put("private_token", "attribute-secret");
        ConnectorValue observed = new ConnectorValue(ConnectorType.MQTT,
                SourceBinding.DEFAULT_CONNECTOR_ID, "main/gate",
                "state-payload-secret", true, true, true, true,
                "boolean", "", attributes, 1234L);

        JSONObject encoded = MqttShortcutCatalogStore.encode(observed);
        String raw = encoded.toString();
        assertFalse(raw.contains("state-payload-secret"));
        assertFalse(raw.contains("attribute-secret"));
        assertTrue(raw.contains("garage_door"));

        ConnectorValue restored = MqttShortcutCatalogStore.decode(raw);
        assertNotNull(restored);
        assertEquals("main/gate", restored.resourceId);
        assertNull(restored.rawValue);
        assertFalse(restored.fresh);
        assertFalse(restored.available);
        assertFalse(restored.readable);
        assertTrue(restored.writable);
        assertEquals("garage_door", restored.attributes.get("device_class"));
        assertFalse(restored.attributes.containsKey("private_token"));
    }

    @Test public void onlyCanonicalObservedScopeIdCanEnterTheCatalog() {
        assertTrue(MqttShortcutCatalogStore.isLogicalResource("main/gate"));
        assertTrue(MqttShortcutCatalogStore.isLogicalResource("popup/door"));
        assertFalse(MqttShortcutCatalogStore.isLogicalResource("status/state/main/gate"));
        assertFalse(MqttShortcutCatalogStore.isLogicalResource("main/+"));
        assertFalse(MqttShortcutCatalogStore.isLogicalResource("main/"));
        assertNull(MqttShortcutCatalogStore.decode(
                "{\"schema\":1,\"resourceId\":\"broker/topic/that/was/not/observed\"}"));
    }

    @Test public void cacheHasAnExplicitFiniteEntryLimitAndRejectsUnknownSchema() {
        assertEquals(512, MqttShortcutCatalogStore.MAX_ENTRIES);
        assertNull(MqttShortcutCatalogStore.decode(
                "{\"schema\":99,\"resourceId\":\"main/gate\"}"));
    }
}
