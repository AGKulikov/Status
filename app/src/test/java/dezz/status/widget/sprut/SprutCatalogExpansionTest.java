/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.Test;

public final class SprutCatalogExpansionTest {
    @Test public void defaultResultRetainsAtMostFortyServicesAndEightyCharacteristics() {
        List<SprutCatalog.Service> services = new ArrayList<>();
        services.add(service(7L, 1L, 81, "Показатель"));
        for (long id = 2L; id <= 41L; id++) {
            services.add(service(7L, id, 1, "Показатель"));
        }
        SprutCatalog.Accessory accessory = accessory(7L, services);

        SprutCatalogExpansion.Result result = SprutCatalogExpansion.compute(accessory, "",
                SprutCatalogIndex.Query.parse(""), 1L);

        assertEquals(40, result.services().size());
        assertTrue(result.hasMoreServices());
        SprutCatalogExpansion.ServiceResult expanded = result.services().get(0);
        assertTrue(expanded.expanded());
        assertEquals(80, expanded.boundedCharacteristicCount());
        assertEquals(80, expanded.characteristics().size());
        assertTrue(expanded.hasMoreCharacteristics());
        SprutCatalogExpansion.ServiceResult collapsed = result.services().get(1);
        assertFalse(collapsed.expanded());
        assertEquals(1, collapsed.boundedCharacteristicCount());
        assertTrue(collapsed.characteristics().isEmpty());
        assertFalse(collapsed.hasMoreCharacteristics());
    }

    @Test public void nestedQueryFindsLateServiceButRetainsOnlyMatchingReferences() {
        List<SprutCatalog.Service> services = new ArrayList<>();
        for (long id = 1L; id <= 60L; id++) {
            String name = id == 60L ? "Искомое давление" : "Обычный показатель";
            services.add(service(9L, id, 1, name));
        }
        SprutCatalog.Accessory accessory = accessory(9L, services);

        SprutCatalogExpansion.Result result = SprutCatalogExpansion.compute(accessory, "Котельная",
                SprutCatalogIndex.Query.parse("искомое давление"), 60L);

        assertFalse(result.accessoryHeaderMatches());
        assertEquals(1, result.services().size());
        assertFalse(result.hasMoreServices());
        assertEquals(60L, result.services().get(0).service().id());
        assertEquals(1, result.services().get(0).characteristics().size());
        assertEquals("Искомое давление",
                result.services().get(0).characteristics().get(0).name());
    }

    @Test public void filteredCountStopsAtLimitPlusKnowledgeThatMoreExist() {
        SprutCatalog.Service service = service(11L, 3L, 12, "Совпадение");
        SprutCatalog.Accessory accessory = accessory(11L,
                Collections.singletonList(service));

        SprutCatalogExpansion.Result result = SprutCatalogExpansion.compute(accessory, "",
                SprutCatalogIndex.Query.parse("совпадение"), 3L, 5, 7);

        SprutCatalogExpansion.ServiceResult selected = result.services().get(0);
        assertEquals(7, selected.boundedCharacteristicCount());
        assertEquals(7, selected.characteristics().size());
        assertTrue(selected.hasMoreCharacteristics());
    }

    @Test public void emptyServicesDoNotConsumeUnfilteredServiceLimit() {
        List<SprutCatalog.Service> services = new ArrayList<>();
        for (long id = 1L; id <= 50L; id++) {
            services.add(new SprutCatalog.Service(14L, id, "Пустой " + id, "empty", 0,
                    true, false, Collections.emptyList()));
        }
        SprutCatalog.Service firstNonEmpty = service(14L, 100L, 1, "Значение");
        services.add(firstNonEmpty);
        SprutCatalog.Accessory accessory = accessory(14L, services);

        SprutCatalogExpansion.Result result = SprutCatalogExpansion.compute(accessory, "",
                SprutCatalogIndex.Query.parse(""), -1L, 1, 5);

        assertEquals(1, result.services().size());
        assertSame(firstNonEmpty, result.services().get(0).service());
        assertFalse(result.hasMoreServices());
    }

    @Test public void directServiceQueryCanReportAnEmptyMatchingService() {
        SprutCatalog.Service empty = new SprutCatalog.Service(15L, 8L, "Диагностика", "system", 0,
                true, false, Collections.emptyList());
        SprutCatalog.Accessory accessory = accessory(15L, Collections.singletonList(empty));

        SprutCatalogExpansion.Result result = SprutCatalogExpansion.compute(accessory, "",
                SprutCatalogIndex.Query.parse("диагностика"), 8L);

        assertEquals(1, result.services().size());
        assertEquals(0, result.services().get(0).boundedCharacteristicCount());
        assertTrue(result.services().get(0).characteristics().isEmpty());
    }

    @Test public void interruptedWorkerCancelsBeforeTraversingCatalog() {
        SprutCatalog.Accessory accessory = accessory(18L,
                Collections.singletonList(service(18L, 1L, 1, "Значение")));
        Thread.currentThread().interrupt();
        try {
            SprutCatalogExpansion.compute(accessory, "", SprutCatalogIndex.Query.parse(""), -1L);
        } catch (CancellationException expected) {
            return;
        } finally {
            // JUnit reuses its worker; never leak the deliberate interrupt to another test.
            Thread.interrupted();
        }
        throw new AssertionError("Expected CancellationException");
    }

    private static SprutCatalog.Accessory accessory(long id,
                                                     List<SprutCatalog.Service> services) {
        return new SprutCatalog.Accessory(id, null, "Устройство " + id, "", "", "", "",
                true, false, services);
    }

    private static SprutCatalog.Service service(long accessoryId, long serviceId,
                                                int characteristicCount, String name) {
        List<SprutCatalog.Characteristic> characteristics = new ArrayList<>();
        for (int i = 0; i < characteristicCount; i++) {
            characteristics.add(new SprutCatalog.Characteristic(
                    new SprutPath(accessoryId, serviceId, i + 1L), "sensor", name,
                    "measurement", "float", "", true, false, true, true, false,
                    null, null, null, Collections.emptyList(), i,
                    SprutCatalog.ValueType.INTEGER));
        }
        return new SprutCatalog.Service(accessoryId, serviceId, "Сервис " + serviceId,
                "sensor", 0, true, false, characteristics);
    }
}
