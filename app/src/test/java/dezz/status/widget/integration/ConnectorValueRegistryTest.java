/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.Operator;
import dezz.status.widget.scenario.Output;
import dezz.status.widget.scenario.Rule;

public final class ConnectorValueRegistryTest {
    @Test public void invalidSnapshotCannotPartiallyReplaceCurrentValues() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        ConnectorValue previous = value(ConnectorType.HOME_ASSISTANT, "default", "sensor.old", 1);
        registry.upsert(previous);

        ConnectorValue valid = value(ConnectorType.HOME_ASSISTANT, "default", "sensor.new", 2);
        ConnectorValue foreign = value(ConnectorType.SPRUTHUB, "default", "1/2/3", true);
        try {
            registry.replaceSnapshot(ConnectorType.HOME_ASSISTANT, "default",
                    Arrays.asList(valid, foreign));
            fail("A foreign snapshot value must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        assertSame(previous, registry.get(ConnectorType.HOME_ASSISTANT, "default", "sensor.old"));
        assertNull(registry.get(ConnectorType.HOME_ASSISTANT, "default", "sensor.new"));
        assertEquals(1, registry.snapshot().size());
    }

    @Test public void duplicateSnapshotResourceCannotMutateRegistry() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        ConnectorValue previous = value(ConnectorType.MQTT, "default", "main/old", "old");
        registry.upsert(previous);
        ConnectorValue first = value(ConnectorType.MQTT, "default", "main/new", "one");
        ConnectorValue duplicate = value(ConnectorType.MQTT, "default", "main/new", "two");

        try {
            registry.replaceSnapshot(ConnectorType.MQTT, "default",
                    Arrays.asList(first, duplicate));
            fail("Duplicate resources must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        assertSame(previous, registry.get(ConnectorType.MQTT, "default", "main/old"));
        assertNull(registry.get(ConnectorType.MQTT, "default", "main/new"));
    }

    @Test public void listenerFailureIsIsolatedAndRemovedValuesAreReportedStale() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        ConnectorValue removed = value(ConnectorType.SPRUTHUB, "default", "1/2/3", false);
        registry.upsert(removed);
        AtomicInteger survivingCalls = new AtomicInteger();
        AtomicReference<Collection<ConnectorValue>> observed = new AtomicReference<>();
        registry.addListener(changed -> { throw new IllegalStateException("broken listener"); });
        registry.addListener(changed -> {
            survivingCalls.incrementAndGet();
            observed.set(changed);
        });

        ConnectorValue replacement = value(ConnectorType.SPRUTHUB, "default", "1/2/4", true);
        registry.replaceSnapshot(ConnectorType.SPRUTHUB, "default",
                Collections.singletonList(replacement));

        assertEquals(1, survivingCalls.get());
        assertEquals(2, observed.get().size());
        ConnectorValue staleRemoval = observed.get().iterator().next();
        assertEquals("1/2/3", staleRemoval.resourceId);
        assertFalse(staleRemoval.fresh);
        assertNull(registry.get(ConnectorType.SPRUTHUB, "default", "1/2/3"));
        assertSame(replacement, registry.get(ConnectorType.SPRUTHUB, "default", "1/2/4"));
    }

    @Test public void duplicateListenerRegistrationIsIdempotent() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        AtomicInteger calls = new AtomicInteger();
        ConnectorValueRegistry.Listener listener = ignored -> calls.incrementAndGet();
        registry.addListener(listener);
        registry.addListener(listener);

        registry.upsert(value(ConnectorType.HOME_ASSISTANT, "default", "sensor.one", 1));

        assertEquals(1, calls.get());
    }

    @Test public void lookupKeysUseTheSameBoundsAsConnectorValues() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        try {
            registry.get(ConnectorType.MQTT, "x".repeat(257), "main/value");
            fail("Oversized connector IDs must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("connectorId"));
        }
    }

    @Test public void missingValuePathIsUnavailableButPresentNullRemainsReadable() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("present_null", null);
        ConnectorValue value = ConnectorValue.current(ConnectorType.HOME_ASSISTANT, "default",
                "sensor.example", "ok", true, true, false, "sensor", "", attributes);

        Input presentNull = value.toInput("attributes.present_null");
        Input missing = value.toInput("attributes.typo");
        Rule empty = new Rule("empty", Input.FIELD_VALUE, Operator.EMPTY, "", "",
                Output.none());

        assertTrue(presentNull.available);
        assertTrue(presentNull.readable);
        assertTrue(empty.matches(presentNull));
        assertFalse(missing.available);
        assertFalse(missing.readable);
        assertFalse(empty.matches(missing));
    }

    private static ConnectorValue value(ConnectorType type, String connectorId, String resourceId,
                                        Object raw) {
        return ConnectorValue.current(type, connectorId, resourceId, raw, true, true, false,
                raw == null ? "" : raw.getClass().getSimpleName(), "", Collections.emptyMap());
    }
}
