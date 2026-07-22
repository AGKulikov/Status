/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;

public final class SprutActionTargetResolverTest {
    @Test public void legacyCurrentDoorToggleIsRedirectedToWritableTarget() {
        Fixture fixture = doorFixture(1);
        ActionBinding legacy = binding(fixture.current, ActionBinding.OPERATION_TOGGLE, "{}");

        SprutActionTargetResolver.Resolved resolved =
                SprutActionTargetResolver.resolve(fixture.catalog, legacy);

        assertSame(fixture.target, resolved.characteristic());
        assertEquals(fixture.target.path().stableId(), resolved.binding().resourceId);
        assertEquals(ActionBinding.OPERATION_SET, resolved.binding().operation);
        assertEquals("0", resolved.binding().payload);
        assertEquals(0, SprutActionValue.resolve(resolved.binding(), fixture.target));
    }

    @Test public void openDoorToggleRequestsClosedEndpoint() {
        Fixture fixture = doorFixture(0);
        ActionBinding legacy = binding(fixture.current, ActionBinding.OPERATION_TOGGLE, "{}");

        SprutActionTargetResolver.Resolved resolved =
                SprutActionTargetResolver.resolve(fixture.catalog, legacy);

        assertEquals(ActionBinding.OPERATION_SET, resolved.binding().operation);
        assertEquals("1", resolved.binding().payload);
    }

    @Test public void movingDoorToggleReversesDirection() {
        Fixture opening = doorFixture(2);
        Fixture closing = doorFixture(3);

        SprutActionTargetResolver.Resolved stopOpening = SprutActionTargetResolver.resolve(
                opening.catalog, binding(opening.current, ActionBinding.OPERATION_TOGGLE, "{}"));
        SprutActionTargetResolver.Resolved stopClosing = SprutActionTargetResolver.resolve(
                closing.catalog, binding(closing.current, ActionBinding.OPERATION_TOGGLE, "{}"));

        assertEquals("1", stopOpening.binding().payload);
        assertEquals("0", stopClosing.binding().payload);
    }

    @Test public void visualOpenAndCloseBooleansMapToDoorEnumSemantics() {
        Fixture fixture = doorFixture(1);

        SprutActionTargetResolver.Resolved open = SprutActionTargetResolver.resolve(
                fixture.catalog, binding(fixture.current, ActionBinding.OPERATION_SET, "true"));
        SprutActionTargetResolver.Resolved closed = SprutActionTargetResolver.resolve(
                fixture.catalog, binding(fixture.current, ActionBinding.OPERATION_SET, "false"));

        assertEquals("0", open.binding().payload);
        assertEquals("1", closed.binding().payload);
    }

    @Test public void localizedOpaqueDoorMetadataStillFindsWritableTarget() {
        SprutCatalog.Characteristic current = characteristic(15, "Текущий режим двери",
                "C_OPTION", 1, true, false, 0, 4, Collections.emptyList());
        SprutCatalog.Characteristic target = characteristic(16, "Целевой режим двери",
                "C_OPTION", 1, true, true, 0, 1, Arrays.asList(
                        new SprutCatalog.ValidValue(0, "open", "Открыто"),
                        new SprutCatalog.ValidValue(1, "closed", "Закрыто")));
        SprutCatalog catalog = catalog("C_SERVICE", current, target);

        SprutActionTargetResolver.Resolved resolved = SprutActionTargetResolver.resolve(catalog,
                binding(current, ActionBinding.OPERATION_TOGGLE, "{}"));

        assertSame(target, resolved.characteristic());
        assertEquals("0", resolved.binding().payload);
    }

    @Test public void targetPositionToggleUsesCurrentPositionAndExactEndpoints() {
        SprutCatalog.Characteristic current = characteristic(15, "CurrentPosition", 0,
                true, false, 0, 100, Collections.emptyList());
        SprutCatalog.Characteristic target = characteristic(16, "TargetPosition", 0,
                true, true, 0, 100, Collections.emptyList());
        SprutCatalog catalog = catalog("WindowCovering", current, target);

        SprutActionTargetResolver.Resolved resolved = SprutActionTargetResolver.resolve(catalog,
                binding(target, ActionBinding.OPERATION_TOGGLE, "{}"));

        assertEquals(ActionBinding.OPERATION_SET, resolved.binding().operation);
        assertEquals("100", resolved.binding().payload);
    }

    @Test public void alternateCurrentDoorStateCanControlCoverWithPreferredCurrentPosition() {
        SprutCatalog.Characteristic position = characteristic(14, "CurrentPosition", 0,
                true, false, 0, 100, Collections.emptyList());
        SprutCatalog.Characteristic doorState = characteristic(15, "CurrentDoorState", 1,
                true, false, 0, 4, Collections.emptyList());
        SprutCatalog.Characteristic target = characteristic(16, "TargetPosition", 0,
                true, true, 0, 100, Collections.emptyList());
        SprutCatalog catalog = catalog("WindowCovering", position, doorState, target);

        SprutActionTargetResolver.Resolved resolved = SprutActionTargetResolver.resolve(catalog,
                binding(doorState, ActionBinding.OPERATION_TOGGLE, "{}"));

        assertSame(target, resolved.characteristic());
        assertEquals("100", resolved.binding().payload);
    }

    @Test public void unrelatedReadOnlyCharacteristicStillFailsClosed() {
        SprutCatalog.Characteristic state = characteristic(15, "Temperature", 21,
                true, false, -50, 150, Collections.emptyList());
        SprutCatalog.Characteristic setting = characteristic(16, "CalibrationMode", 0,
                true, true, 0, 1, Collections.emptyList());
        SprutCatalog catalog = catalog("TemperatureSensor", state, setting);

        try {
            SprutActionTargetResolver.resolve(catalog,
                    binding(state, ActionBinding.OPERATION_TOGGLE, "{}"));
            fail("Expected read-only sensor action to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not writable"));
        }
    }

    private static Fixture doorFixture(int currentValue) {
        SprutCatalog.Characteristic current = characteristic(15, "CurrentDoorState",
                currentValue, true, false, 0, 4, Collections.emptyList());
        SprutCatalog.Characteristic target = characteristic(16, "TargetDoorState", 1,
                true, true, 0, 1, Arrays.asList(
                        new SprutCatalog.ValidValue(0, "open", "Open"),
                        new SprutCatalog.ValidValue(1, "closed", "Closed")));
        return new Fixture(catalog("C_1275_13", current, target), current, target);
    }

    private static SprutCatalog catalog(String serviceType,
                                        SprutCatalog.Characteristic... characteristics) {
        SprutCatalog.Service service = new SprutCatalog.Service(1275, 13, "Въезд",
                serviceType, 0, true, false, Arrays.asList(characteristics));
        SprutCatalog.Accessory accessory = new SprutCatalog.Accessory(1275, 1L, "Ворота",
                "", "", "", "", true, false, Collections.singletonList(service));
        return new SprutCatalog(Collections.emptyList(), Collections.singletonList(accessory));
    }

    private static SprutCatalog.Characteristic characteristic(long id, String type, Object value,
                                                               boolean readable, boolean writable,
                                                               Number min, Number max,
                                                               java.util.List<SprutCatalog.ValidValue> valid) {
        return characteristic(id, type, type, value, readable, writable, min, max, valid);
    }

    private static SprutCatalog.Characteristic characteristic(long id, String name, String type,
                                                               Object value, boolean readable,
                                                               boolean writable, Number min,
                                                               Number max,
                                                               java.util.List<SprutCatalog.ValidValue> valid) {
        return new SprutCatalog.Characteristic(new SprutPath(1275, 13, id),
                "", name, type, "uint8", "", readable, writable, true, true, false,
                min, max, 1, valid, value, SprutCatalog.ValueType.INTEGER);
    }

    private static ActionBinding binding(SprutCatalog.Characteristic characteristic,
                                         String operation, String payload) {
        return new ActionBinding(ConnectorType.SPRUTHUB, "default",
                characteristic.path().stableId(), operation, payload);
    }

    private static final class Fixture {
        final SprutCatalog catalog;
        final SprutCatalog.Characteristic current;
        final SprutCatalog.Characteristic target;

        Fixture(SprutCatalog catalog, SprutCatalog.Characteristic current,
                SprutCatalog.Characteristic target) {
            this.catalog = catalog;
            this.current = current;
            this.target = target;
        }
    }
}
