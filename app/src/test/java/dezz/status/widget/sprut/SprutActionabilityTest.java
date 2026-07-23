/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class SprutActionabilityTest {
    @Test public void readOnlyCatalogEntriesRemainVisibleButCannotBecomeActions() {
        SprutCatalog.Characteristic readOnly = characteristic(1L, false);
        SprutCatalog.Service readOnlyService = service(1L, readOnly);
        SprutCatalog.Accessory readOnlyAccessory = accessory(1L, readOnlyService);

        assertFalse(SprutActionability.canControl(readOnly));
        assertFalse(SprutActionability.canControl(readOnlyService));
        assertFalse(SprutActionability.canControl(readOnlyAccessory));
    }

    @Test public void oneWritableCharacteristicMakesItsParentsActionable() {
        SprutCatalog.Characteristic readOnly = characteristic(1L, false);
        SprutCatalog.Characteristic writable = characteristic(2L, true);
        SprutCatalog.Service mixed = new SprutCatalog.Service(7L, 8L, "Климат",
                "thermostat", 0, true, false, Arrays.asList(readOnly, writable));
        SprutCatalog.Accessory accessory = accessory(7L, mixed);

        assertTrue(SprutActionability.canControl(writable));
        assertTrue(SprutActionability.canControl(mixed));
        assertTrue(SprutActionability.canControl(accessory));
    }

    private static SprutCatalog.Accessory accessory(
            long id, SprutCatalog.Service service) {
        return new SprutCatalog.Accessory(id, null, "Устройство", "", "", "", "",
                true, false, Collections.singletonList(service));
    }

    private static SprutCatalog.Service service(
            long id, SprutCatalog.Characteristic characteristic) {
        return new SprutCatalog.Service(id, 8L, "Датчик", "sensor", 0,
                true, false, Collections.singletonList(characteristic));
    }

    private static SprutCatalog.Characteristic characteristic(long id, boolean writable) {
        return new SprutCatalog.Characteristic(new SprutPath(7L, 8L, id),
                "sensor", "Значение", "value", "bool", "",
                true, writable, true, true, false,
                null, null, null, Collections.emptyList(), false,
                SprutCatalog.ValueType.BOOLEAN);
    }
}
